package com.facealbum.domain

import androidx.room.withTransaction
import com.facealbum.data.db.Embeddings
import com.facealbum.data.db.FaceAlbumDatabase
import com.facealbum.data.prefs.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Re-cluster every stored face embedding using the user's current threshold.
 *
 * Cheap relative to a full scan: no MediaStore I/O, no ML Kit, no TFLite —
 * only in-memory cosine comparisons. A typical library with a few thousand
 * faces completes in a few seconds.
 *
 * Crash safety: the entire rebuild (clear assignments + delete clusters +
 * re-assign every face + merge + cleanup) runs inside a single Room
 * transaction. If the worker is killed mid-pass, SQLite rolls back to the
 * pre-recluster state — the user never sees a partial cluster set.
 *
 * Albums survive cluster deletion thanks to the SET_NULL foreign key on
 * `AlbumEntity.clusterId` (see migration v1→v2).
 */
class ReclusterUseCase(
    private val db: FaceAlbumDatabase,
    private val prefs: UserPreferences
) {

    data class Progress(val processed: Int, val total: Int, val clusters: Int)

    suspend fun run(onProgress: suspend (Progress) -> Unit = {}): Int =
        withContext(Dispatchers.Default) {
            val assignT = prefs.assignThreshold.first()
            val mergeT = prefs.mergeThreshold.first()
            val clusterer = FaceClusterer(db.clusterDao(), db.faceDao(), assignT, mergeT)

            Timber.i("Recluster start (assign=$assignT, merge=$mergeT)")

            val result = db.withTransaction {
                db.faceDao().clearAllClusterAssignments()
                db.clusterDao().clear()

                val faces = db.faceDao().allOrderedByQualityDesc()
                val total = faces.size
                var processed = 0

                for (f in faces) {
                    currentCoroutineContext().ensureActive()
                    clusterer.assign(f.id, Embeddings.fromBytes(f.embedding), f.quality)
                    processed += 1
                    if (processed % 50 == 0 || processed == total) {
                        // Progress is read-only and safe to emit from inside
                        // the transaction; the UI observes WorkManager, not
                        // the in-flight DB state.
                        val clustersSoFar = db.clusterDao().all().size
                        onProgress(Progress(processed, total, clustersSoFar))
                    }
                }

                clusterer.mergeClose()
                db.clusterDao().deleteEmpty()
                Progress(processed = total, total = total, clusters = db.clusterDao().all().size)
            }

            onProgress(result)
            Timber.i("Recluster done. ${result.clusters} clusters")
            result.clusters
        }
}
