package com.facealbum.domain

import android.content.Context
import android.graphics.Rect
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

    private suspend fun indexPhoto(photo: PhotoInfo): Int {
        // Skip if MediaStore id already present AND its modification date didn't change.
        val existing = db.photoDao().findByMediaStoreId(photo.id)
        if (existing != null && existing.dateModified >= photo.dateModified) {
            return 0
        }

        val bitmap = BitmapLoader.loadScaled(context, photo.uri) ?: run {
            Timber.w("Could not load bitmap for photo ${photo.uri}")
            return 0
        }
        val faces = detector.detectAllFaces(bitmap)
        val now = System.currentTimeMillis()

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
            // Photo contents changed since last index: drop old faces and re-extract.
            // Capture which clusters were affected so we can re-balance their counts after.
            val oldClusterIds = db.faceDao().facesForPhoto(existing.id)
                .mapNotNull { it.clusterId }
                .toSet()
            db.faceDao().deleteFacesForPhoto(existing.id)
            for (cid in oldClusterIds) {
                db.clusterDao().recomputeFaceCount(cid, now)
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

        if (faces.isEmpty()) return 0

        val photoArea = (bitmap.width * bitmap.height).coerceAtLeast(1).toFloat()
        var added = 0
        for (face in faces) {
            val embedding = computeEmbedding(bitmap, face.boundingBox) ?: continue
            val quality = (face.boundingBox.width().toFloat() * face.boundingBox.height()) / photoArea
            val faceRowId = db.faceDao().insert(
                FaceEntity(
                    photoId = photoRowId,
                    clusterId = null,
                    bboxLeft = face.boundingBox.left,
                    bboxTop = face.boundingBox.top,
                    bboxRight = face.boundingBox.right,
                    bboxBottom = face.boundingBox.bottom,
                    embedding = Embeddings.toBytes(embedding),
                    quality = quality.coerceIn(0f, 1f)
                )
            )
            clusterer.assign(faceRowId, embedding, quality.coerceIn(0f, 1f))
            added += 1
        }
        return added
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
