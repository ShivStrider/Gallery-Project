package com.facealbum.domain

import android.content.Context
import android.graphics.Rect
import androidx.room.withTransaction
import com.facealbum.data.FaceDetectorWrapper
import com.facealbum.data.FaceEmbedder
import com.facealbum.data.ModelState
import com.facealbum.data.PhotoRepository
import com.facealbum.data.db.Embeddings
import com.facealbum.data.db.FaceAlbumDatabase
import com.facealbum.data.db.FaceEntity
import com.facealbum.data.db.PhotoEntity
import com.facealbum.model.PhotoInfo
import com.facealbum.telemetry.CrashReporter
import com.facealbum.util.BitmapLoader
import com.facealbum.util.FacePreprocessor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import timber.log.Timber

/**
 * Walks every (modified-since) photo in MediaStore, detects every face,
 * persists face embeddings, assigns each one to a cluster via [FaceClusterer],
 * and finally runs a merge pass to settle ordering effects.
 *
 * Designed to be called from a WorkManager worker. Emits progress through
 * [onProgress] so the foreground notification + UI can stay live.
 */
class FaceIndexUseCase(
    private val context: Context,
    private val db: FaceAlbumDatabase = FaceAlbumDatabase.get(context),
    private val photoRepository: PhotoRepository = PhotoRepository(context),
    private val detector: FaceDetectorWrapper = FaceDetectorWrapper(context),
    private val embedder: FaceEmbedder = FaceEmbedder(context),
    private val clusterer: FaceClusterer = FaceClusterer(db.clusterDao(), db.faceDao())
) {

    data class Progress(
        val processed: Int,
        val total: Int,
        val facesFound: Int,
        val clustersTotal: Int
    )

    /**
     * @return total number of new faces persisted.
     * @throws ModelNotReadyException when the TFLite model is missing/corrupt.
     */
    suspend fun run(
        forceFullRescan: Boolean = false,
        onProgress: suspend (Progress) -> Unit = {}
    ): Int {
        when (val state = embedder.modelState) {
            is ModelState.Failed -> throw ModelNotReadyException(state.reason)
            is ModelState.Ready -> Unit
        }

        val lastIndexed = if (forceFullRescan) 0L else db.photoDao().lastIndexedDateModified() ?: 0L
        Timber.i("Index pass start, lastIndexedDateModified=$lastIndexed, forceFullRescan=$forceFullRescan")

        val photos = photoRepository.queryPhotosModifiedSince(lastIndexed)
        Timber.i("Found ${photos.size} photo(s) to index")

        var totalFacesAdded = 0
        var processed = 0

        for (photo in photos) {
            currentCoroutineContext().ensureActive()
            try {
                val faceCount = indexPhoto(photo)
                totalFacesAdded += faceCount
            } catch (t: Throwable) {
                Timber.e(t, "Failed to index photo ${photo.uri}")
                CrashReporter.recordNonFatal(
                    throwable = t,
                    source = "index_photo",
                    context = mapOf("photo_id" to photo.id.toString())
                )
            }
            processed += 1
            if (processed % 5 == 0 || processed == photos.size) {
                val clusters = db.clusterDao().all().size
                onProgress(Progress(processed, photos.size, totalFacesAdded, clusters))
            }
        }

        Timber.i("Index pass done. Cleaning up empty clusters + merge pass.")
        db.clusterDao().deleteEmpty()
        clusterer.mergeClose()

        val finalProgress = Progress(
            processed = photos.size,
            total = photos.size,
            facesFound = totalFacesAdded,
            clustersTotal = db.clusterDao().all().size
        )
        onProgress(finalProgress)
        return totalFacesAdded
    }

    /**
     * The whole photo's work runs inside one Room transaction so a crash or
     * cancellation between face insert and cluster assignment cannot leave
     * inflated cluster counts or unassigned faces.
     */
    private suspend fun indexPhoto(photo: PhotoInfo): Int = db.withTransaction {
        // Skip if MediaStore id already present AND its modification date didn't change.
        val existing = db.photoDao().findByMediaStoreId(photo.id)
        if (existing != null && existing.dateModified >= photo.dateModified) {
            return@withTransaction 0
        }

        val now = System.currentTimeMillis()
        val bitmap = BitmapLoader.loadScaled(context, photo.uri)

        if (bitmap == null) {
            // Persist a faceCount=0 row anyway so `lastIndexedDateModified` advances
            // past this photo and we don't re-attempt every pass. Decode failure is
            // usually permanent (corrupt file, unsupported format).
            Timber.w("Could not load bitmap for photo ${photo.uri}; recording empty entry")
            if (existing == null) {
                db.photoDao().insert(
                    PhotoEntity(
                        mediaStoreId = photo.id,
                        uri = photo.uri.toString(),
                        displayName = photo.displayName,
                        dateTaken = photo.dateTaken,
                        dateModified = photo.dateModified,
                        processedAt = now,
                        faceCount = 0
                    )
                )
            } else {
                db.photoDao().updateMetadata(
                    id = existing.id,
                    uri = photo.uri.toString(),
                    displayName = photo.displayName,
                    dateTaken = photo.dateTaken,
                    dateModified = photo.dateModified,
                    processedAt = now,
                    faceCount = 0
                )
            }
            return@withTransaction 0
        }

        val faces = detector.detectAllFaces(bitmap)

        val photoRowId = if (existing == null) {
            db.photoDao().insert(
                PhotoEntity(
                    mediaStoreId = photo.id,
                    uri = photo.uri.toString(),
                    displayName = photo.displayName,
                    dateTaken = photo.dateTaken,
                    dateModified = photo.dateModified,
                    processedAt = now,
                    faceCount = faces.size
                )
            )
        } else {
            // Photo contents changed: drop the old face rows and rebuild the centroid
            // / cover face on every cluster they belonged to. Empty clusters are pruned
            // at the end of the batch.
            val oldClusterIds = db.faceDao().facesForPhoto(existing.id)
                .mapNotNull { it.clusterId }
                .toSet()
            db.faceDao().deleteFacesForPhoto(existing.id)
            for (cid in oldClusterIds) {
                clusterer.recomputeFromFaces(cid)
            }
            db.photoDao().updateMetadata(
                id = existing.id,
                uri = photo.uri.toString(),
                displayName = photo.displayName,
                dateTaken = photo.dateTaken,
                dateModified = photo.dateModified,
                processedAt = now,
                faceCount = faces.size
            )
            existing.id
        }

        if (faces.isEmpty()) return@withTransaction 0

        val photoArea = (bitmap.width * bitmap.height).coerceAtLeast(1).toFloat()
        var added = 0
        for (face in faces) {
            val embedding = computeEmbedding(bitmap, face.boundingBox) ?: continue
            val quality = ((face.boundingBox.width().toFloat() * face.boundingBox.height()) / photoArea)
                .coerceIn(0f, 1f)
            val faceRowId = db.faceDao().insert(
                FaceEntity(
                    photoId = photoRowId,
                    clusterId = null,
                    bboxLeft = face.boundingBox.left,
                    bboxTop = face.boundingBox.top,
                    bboxRight = face.boundingBox.right,
                    bboxBottom = face.boundingBox.bottom,
                    embedding = Embeddings.toBytes(embedding),
                    quality = quality
                )
            )
            clusterer.assign(faceRowId, embedding, quality)
            added += 1
        }
        added
    }

    private fun computeEmbedding(bitmap: android.graphics.Bitmap, bbox: Rect): FloatArray? {
        val preprocessed = FacePreprocessor.cropAndPreprocess(bitmap, bbox)
        return embedder.getEmbedding(preprocessed)
    }

    fun close() {
        detector.close()
        embedder.close()
    }

    class ModelNotReadyException(message: String) : RuntimeException(message)
}
