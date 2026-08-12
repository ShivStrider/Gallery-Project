package com.facealbum.domain

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import androidx.room.withTransaction
import com.facealbum.config.FaceRecognitionConfig
import com.facealbum.data.FaceDetectorWrapper
import com.facealbum.data.FaceEmbedder
import com.facealbum.data.ModelState
import com.facealbum.data.PhotoRepository
import com.facealbum.data.db.Embeddings
import com.facealbum.data.db.FaceAlbumDatabase
import com.facealbum.data.db.FaceEntity
import com.facealbum.data.db.PhotoEntity
import com.facealbum.data.db.ScanSessionEntity
import com.facealbum.data.db.findByIdsChunked
import com.facealbum.data.prefs.UserPreferences
import com.facealbum.model.PhotoInfo
import com.facealbum.telemetry.CrashReporter
import com.facealbum.util.BitmapLoader
import com.facealbum.util.FaceAligner
import com.facealbum.util.FacePreprocessor
import com.google.mlkit.vision.face.Face
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.math.abs

/**
 * Walks every (modified-since) photo in MediaStore, detects every face,
 * persists face embeddings, assigns each one to a cluster via [FaceClusterer],
 * and finally runs a merge pass to settle ordering effects.
 *
 * Pipelined for throughput:
 *
 *  - **Stage A** runs in parallel (concurrency = [DETECT_CONCURRENCY]):
 *    bitmap decode + ML Kit face detection. ML Kit's detector has its own
 *    internal thread pool and is safe to invoke from multiple coroutines;
 *    bitmap decode is I/O-bound.
 *  - **Stage B** runs strictly sequentially on a single-thread dispatcher:
 *    TFLite embedding + Room transactions + cluster assignment. TFLite's
 *    `Interpreter` is not thread-safe, and `FaceClusterer.assign` reads
 *    every cluster centroid and updates one in place — concurrent assigns
 *    would race on centroid updates.
 *
 *  Back-pressure is bounded by `.buffer(BUFFER_BETWEEN_STAGES)`, capping
 *  in-flight bitmaps so memory stays predictable.
 */
class FaceIndexUseCase(
    private val context: Context,
    private val db: FaceAlbumDatabase = FaceAlbumDatabase.get(context),
    private val photoRepository: PhotoRepository = PhotoRepository(context),
    detectorFactory: () -> FaceDetectorWrapper = { FaceDetectorWrapper(context) },
    embedderFactory: () -> FaceEmbedder = { FaceEmbedder(context) },
    private val prefs: UserPreferences = UserPreferences.get(context)
) {

    /**
     * ML Kit's detector and the TFLite interpreter both claim native resources
     * on construction, so neither is built until a scan actually reaches for
     * it. Constructing this use case — which a worker may do only to discover
     * the model is missing — costs nothing.
     *
     * Held as [Lazy] rather than `by lazy` so [close] can skip anything that
     * was never touched instead of allocating it just to release it.
     */
    private val detectorLazy: Lazy<FaceDetectorWrapper> = lazy(detectorFactory)
    private val embedderLazy: Lazy<FaceEmbedder> = lazy(embedderFactory)

    private val detector: FaceDetectorWrapper get() = detectorLazy.value
    private val embedder: FaceEmbedder get() = embedderLazy.value

    /**
     * Constructed at the start of every [run] from the user's current
     * threshold preferences. Re-created (rather than reused across calls) so
     * threshold changes between scans take effect immediately.
     */
    private lateinit var clusterer: FaceClusterer

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
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    suspend fun run(
        forceFullRescan: Boolean = false,
        onProgress: suspend (Progress) -> Unit = {}
    ): Int {
        // Checked before anything else touches the DB or the model: if the
        // embedding pipeline changed since the last successful index, every
        // stored face/cluster is stale and must not be served (or scanned
        // incrementally over) even if this particular run goes on to fail for
        // an unrelated reason below.
        val pipelineVersionChanged = invalidateIfPipelineVersionChanged()

        when (val state = embedder.modelState) {
            is ModelState.Failed -> throw ModelNotReadyException(state.reason)
            is ModelState.Ready -> Unit
        }

        // Mop up any "running" rows orphaned by a previous process death so
        // the session-history view stays honest. Cheap one-shot UPDATE.
        db.scanSessionDao().markOrphansCancelled(System.currentTimeMillis())

        val assignT = prefs.assignThreshold.first()
        val mergeT = prefs.mergeThreshold.first()
        clusterer = FaceClusterer(db.clusterDao(), db.faceDao(), assignT, mergeT)

        // Photos deleted (or hidden by a narrowed Android 14 selection) outside
        // the app must leave the index, or their faces haunt the groups forever.
        // Changed photos need no special pass: DATE_MODIFIED advances, so the
        // incremental query below re-indexes them.
        reconcileDeletedPhotos(clusterer)

        // A pipeline-version invalidation forces a full re-index this pass
        // regardless of what the caller asked for — an incremental scan would
        // otherwise skip every unmodified photo and leave its (already wiped)
        // faces unindexed indefinitely.
        val effectiveForceFullRescan = forceFullRescan || pipelineVersionChanged
        val lastIndexed = if (effectiveForceFullRescan) 0L else db.photoDao().lastIndexedDateModified() ?: 0L
        Timber.i(
            "Index pass start, lastIndexedDateModified=$lastIndexed, forceFullRescan=$forceFullRescan, " +
                "pipelineVersionChanged=$pipelineVersionChanged"
        )

        val sessionId = db.scanSessionDao().insert(
            ScanSessionEntity(
                startedAt = System.currentTimeMillis(),
                endedAt = null,
                status = ScanSessionEntity.STATUS_RUNNING,
                photosScanned = 0,
                facesAdded = 0,
                errorMessage = null,
                forceFullRescan = effectiveForceFullRescan
            )
        )

        val photos = photoRepository.queryPhotosModifiedSince(lastIndexed)
        Timber.i("Found ${photos.size} photo(s) to index")
        val total = photos.size

        // Stage B runs on a dispatcher with parallelism=1 so TFLite + clusterer
        // see strictly sequential calls. Use Default (CPU) rather than IO since
        // the work is CPU-bound (model inference + cosine math).
        val embedDispatcher = Dispatchers.Default.limitedParallelism(1)

        // On low-RAM devices keep stage A serial; even two in-flight 1024-px
        // bitmaps can push older phones into GC pressure.
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val detectConcurrency = if (activityManager?.isLowRamDevice == true) 1 else DETECT_CONCURRENCY
        Timber.d("Scan pipeline: detectConcurrency=$detectConcurrency")

        var totalFacesAdded = 0
        var processed = 0
        var finalStatus = ScanSessionEntity.STATUS_SUCCESS
        var finalError: String? = null

        try {
            photos.asFlow()
                .flatMapMerge(concurrency = detectConcurrency) { photo ->
                    flow {
                        currentCoroutineContext().ensureActive()
                        try {
                            val bitmap = BitmapLoader.loadScaled(context, photo.uri)
                            val faces = bitmap?.let { detector.detectAllFaces(it) } ?: emptyList()
                            emit(DetectResult(photo, bitmap, faces, error = null))
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (t: Throwable) {
                            emit(DetectResult(photo, bitmap = null, faces = emptyList(), error = t))
                        }
                    }
                }
                .flowOn(Dispatchers.IO)
                .buffer(capacity = BUFFER_BETWEEN_STAGES)
                .collect { result ->
                    currentCoroutineContext().ensureActive()
                    if (result.error != null) {
                        Timber.e(result.error, "Failed to decode/detect photo id=${result.photo.id}")
                        CrashReporter.recordNonFatal(
                            throwable = result.error,
                            source = "detect_photo",
                            context = mapOf("photo_id" to result.photo.id.toString())
                        )
                    } else {
                        try {
                            withContext(embedDispatcher) {
                                totalFacesAdded += indexFromDetection(result)
                            }
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (t: Throwable) {
                            Timber.e(t, "Failed to embed/persist photo id=${result.photo.id}")
                            CrashReporter.recordNonFatal(
                                throwable = t,
                                source = "embed_photo",
                                context = mapOf("photo_id" to result.photo.id.toString())
                            )
                        }
                    }
                    processed += 1
                    if (processed % 5 == 0 || processed == total) {
                        val clusters = db.clusterDao().all().size
                        onProgress(Progress(processed, total, totalFacesAdded, clusters))
                    }
                }
        } catch (ce: CancellationException) {
            finalStatus = ScanSessionEntity.STATUS_CANCELLED
            // Record what we finished before bubbling, then re-throw so WorkManager
            // sees the cancellation.
            db.scanSessionDao().finish(
                id = sessionId,
                endedAt = System.currentTimeMillis(),
                status = finalStatus,
                photosScanned = processed,
                facesAdded = totalFacesAdded,
                errorMessage = null
            )
            throw ce
        } catch (t: Throwable) {
            finalStatus = ScanSessionEntity.STATUS_FAILED
            finalError = t.message
            db.scanSessionDao().finish(
                id = sessionId,
                endedAt = System.currentTimeMillis(),
                status = finalStatus,
                photosScanned = processed,
                facesAdded = totalFacesAdded,
                errorMessage = finalError
            )
            throw t
        }

        Timber.i("Index pass done. Cleaning up empty clusters + merge pass.")
        clusterer.deleteEmpty()
        clusterer.mergeClose()

        db.scanSessionDao().finish(
            id = sessionId,
            endedAt = System.currentTimeMillis(),
            status = finalStatus,
            photosScanned = total,
            facesAdded = totalFacesAdded,
            errorMessage = null
        )

        val finalProgress = Progress(
            processed = total,
            total = total,
            facesFound = totalFacesAdded,
            clustersTotal = db.clusterDao().all().size
        )
        onProgress(finalProgress)
        return totalFacesAdded
    }

    /**
     * Removes index rows whose MediaStore records no longer exist, then
     * repairs every cluster that lost faces. Runs inside one transaction so a
     * mid-pass kill leaves the previous consistent state.
     */
    internal suspend fun reconcileDeletedPhotos(clusterer: FaceClusterer) {
        val known = db.photoDao().idAndMediaStoreIdRows()
        if (known.isEmpty()) return
        val visible = photoRepository.queryAllMediaStoreIds()
        val vanished = known.filter { it.mediaStoreId !in visible }
        if (vanished.isEmpty()) return

        Timber.i("Reconcile: ${vanished.size} indexed photo(s) no longer in MediaStore")
        db.withTransaction {
            val affectedClusters = mutableSetOf<Long>()
            for (row in vanished) {
                affectedClusters += db.faceDao().facesForPhoto(row.id).mapNotNull { it.clusterId }
            }
            vanished.map { it.id }.chunked(SQL_VARIABLE_CHUNK).forEach { chunk ->
                db.photoDao().deleteByIds(chunk) // faces cascade via FK
            }
            for (cid in affectedClusters) {
                clusterer.recomputeFromFaces(cid)
            }
            db.clusterDao().deleteEmpty()
        }
    }

    /**
     * Compares the pipeline version stored at the last successful index
     * against [FaceRecognitionConfig.EMBEDDING_PIPELINE_VERSION]. A mismatch
     * — including never having stored one, which is deliberately treated as
     * stale rather than as already current — means every stored face
     * embedding was produced by a different, incomparable pipeline (model,
     * input size, alignment, or normalization). Cosine similarity between an
     * old and a new embedding is meaningless, so clustering across a mixture
     * of the two is *worse* than either generation alone, and fails
     * silently: nothing about a stale BLOB looks wrong, so the symptom is
     * just "clustering got worse", not a crash or an error.
     *
     * On a mismatch this:
     *  - Deletes every face and cluster: both are entirely derived from the
     *    embedding, so nothing about them is salvageable across a pipeline
     *    change. User-assigned cluster names are lost here — unavoidably.
     *    Clusters are rebuilt from scratch in a vector space with no sound
     *    correspondence to the old one, so there is no reliable way to match
     *    an old cluster to its replacement and carry the name across;
     *    pretending otherwise would just be a different silent-corruption bug.
     *  - Does **not** touch `albums` or the `export_operations`/`export_items`
     *    transaction log — those record what was already exported to the
     *    user's filesystem, not derived clustering state, and deleting them
     *    would lose real work.
     *  - Resets every existing photo row's `dateModified` watermark to 0,
     *    *without* deleting/reinserting the row (so its id is stable and the
     *    export log's informational `photoId` references stay valid). This is
     *    necessary, not cosmetic: the outer incremental query in [run] is
     *    satisfied by forcing a full rescan, but [indexFromDetection] has its
     *    own per-photo short-circuit — `preExisting.dateModified >=
     *    photo.dateModified` — that would otherwise skip every photo whose
     *    underlying file never changed, silently leaving it unindexed even
     *    though its face row was just deleted above. The sentinel is only
     *    ever visible transiently: the moment a photo is actually
     *    reprocessed, [indexFromDetection] overwrites it with the real
     *    MediaStore value again.
     *
     * @return true if a version mismatch was found — the caller must treat
     * this run as a full re-index regardless of `forceFullRescan`.
     */
    internal suspend fun invalidateIfPipelineVersionChanged(): Boolean {
        val storedVersion = prefs.embeddingPipelineVersion.first()
        val currentVersion = FaceRecognitionConfig.EMBEDDING_PIPELINE_VERSION
        if (storedVersion == currentVersion) return false

        var clustersWiped = 0
        var photosReset = 0
        db.withTransaction {
            clustersWiped = db.clusterDao().count()
            db.faceDao().clear()
            db.clusterDao().clear()
            photosReset = db.photoDao().resetReprocessWatermark()
        }

        prefs.setEmbeddingPipelineVersion(currentVersion)
        Timber.i(
            "Embedding pipeline version changed (stored=$storedVersion, current=$currentVersion); " +
                "invalidated clusters=$clustersWiped, photos=$photosReset; forcing full re-index"
        )
        return true
    }

    /**
     * Stage B: take an already-decoded bitmap + detected faces, embed each
     * face, then persist face rows and assign them to clusters in a single
     * Room transaction so a cancellation can't leave partial state.
     *
     * TFLite inference deliberately happens BEFORE the transaction opens: a
     * many-face photo would otherwise hold SQLite's write lock across N model
     * invocations, starving the UI's reactive queries. Stage B is strictly
     * serial (single-thread dispatcher), so the existence pre-check cannot
     * race with the write that follows it.
     */
    private suspend fun indexFromDetection(result: DetectResult): Int {
        val photo = result.photo
        val bitmap = result.bitmap
        val faces = result.faces

        val preExisting = db.photoDao().findByMediaStoreId(photo.id)
        if (preExisting != null && preExisting.dateModified >= photo.dateModified) {
            // Up-to-date already; nothing to do. Defends against duplicate
            // photos sharing the same DATE_MODIFIED that slip through the
            // incremental query.
            return 0
        }

        // Model inference, outside any transaction.
        val embedded: List<EmbeddedFace> = if (bitmap == null) {
            emptyList()
        } else {
            faces.mapNotNull { face ->
                if (!isPoseUsable(face)) {
                    // A steep profile or heavily rolled face embeds poorly and
                    // then acts as a bridge between otherwise distinct people —
                    // one bad face pulls two clusters together and the damage
                    // cascades through the running centroid. Cheaper to skip it
                    // than to un-merge later.
                    Timber.d("Skipping face on pose grounds")
                    null
                } else {
                    computeEmbedding(bitmap, face)?.let { EmbeddedFace(face, it) }
                }
            }
        }
        val photoArea = bitmap?.let { (it.width * it.height).coerceAtLeast(1).toFloat() }

        return db.withTransaction {
            val existing = db.photoDao().findByMediaStoreId(photo.id)
            val now = System.currentTimeMillis()

            if (bitmap == null) {
                // Persist a faceCount=0 row anyway so `lastIndexedDateModified` advances.
                Timber.w("Could not load bitmap for photo id=${photo.id}; recording empty entry")
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
                // Photo contents changed: drop the old face rows and rebuild the
                // centroid / cover face on every cluster they belonged to.
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

            if (embedded.isEmpty() || photoArea == null) return@withTransaction 0

            var added = 0
            for ((face, embedding) in embedded) {
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
    }

    /**
     * Aligns to the model's canonical layout where possible, falling back to a
     * plain bounding-box crop when the detector withheld landmarks. The
     * fallback is deliberately kept rather than skipping the face: an
     * unaligned embedding is poor, but dropping the face loses the person
     * entirely from photos where only one shot exists.
     */
    private fun computeEmbedding(bitmap: Bitmap, face: Face): FloatArray? {
        val preprocessed = FaceAligner.align(bitmap, face)
            ?: FacePreprocessor.cropAndPreprocess(bitmap, face.boundingBox)
        return embedder.getEmbedding(preprocessed)
    }

    /**
     * Rejects faces whose head pose is too far from frontal for the model to
     * embed reliably. The thresholds are deliberately generous — this is meant
     * to drop clear profiles and sideways heads, not to demand passport
     * photos.
     */
    private fun isPoseUsable(face: Face): Boolean =
        abs(face.headEulerAngleY) <= MAX_YAW_DEGREES &&
            abs(face.headEulerAngleZ) <= MAX_ROLL_DEGREES

    fun close() {
        if (detectorLazy.isInitialized()) detectorLazy.value.close()
        if (embedderLazy.isInitialized()) embedderLazy.value.close()
    }

    private data class DetectResult(
        val photo: PhotoInfo,
        val bitmap: Bitmap?,
        val faces: List<Face>,
        val error: Throwable?
    )

    /** A detected face paired with its embedding, computed pre-transaction. */
    private data class EmbeddedFace(
        val face: Face,
        val embedding: FloatArray
    )

    class ModelNotReadyException(message: String) : RuntimeException(message)

    companion object {
        /** Parallelism for stage A (decode + ML Kit detect). */
        private const val DETECT_CONCURRENCY = 2

        /**
         * Yaw beyond this is a profile shot; the aligner can still place the
         * points but the model never saw such poses in training, so the
         * embedding is unreliable.
         */
        private const val MAX_YAW_DEGREES = 40f

        /**
         * Roll beyond this also breaks the left/right ordering the aligner
         * uses to map landmarks onto the template.
         */
        private const val MAX_ROLL_DEGREES = 35f

        /**
         * Capacity of the channel between stage A and stage B. Combined with
         * [DETECT_CONCURRENCY] this caps in-flight bitmaps at roughly
         * `DETECT_CONCURRENCY + BUFFER_BETWEEN_STAGES`, which at the project's
         * 1024-px max dimension stays well under typical heap budgets.
         */
        private const val BUFFER_BETWEEN_STAGES = 2

        /** Stay under SQLite's 999-bind-variable limit for IN() clauses. */
        private const val SQL_VARIABLE_CHUNK = 900
    }
}
