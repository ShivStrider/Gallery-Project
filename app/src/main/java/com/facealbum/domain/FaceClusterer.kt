package com.facealbum.domain

import com.facealbum.config.FaceRecognitionConfig
import com.facealbum.data.db.ClusterDao
import com.facealbum.data.db.ClusterEntity
import com.facealbum.data.db.Embeddings
import com.facealbum.data.db.FaceDao
import timber.log.Timber
import kotlin.math.sqrt

/**
 * Incremental, online face clustering.
 *
 * For each new face embedding we compare against existing cluster centroids
 * (cosine similarity). If the best cluster scores at or above
 * [FaceRecognitionConfig.CLUSTER_ASSIGN_THRESHOLD] we assign the face to it
 * and update the running-mean centroid. Otherwise we open a new singleton
 * cluster.
 *
 * A separate [mergeClose] pass walks all clusters and merges pairs whose
 * centroid similarity exceeds [FaceRecognitionConfig.CLUSTER_MERGE_THRESHOLD]
 * — cheap to run periodically and corrects ordering effects.
 *
 * ## Centroid cache
 * Centroids live in an in-memory write-through cache, loaded lazily on first
 * use. Without it, every [assign] re-read and re-deserialized every centroid
 * BLOB from Room — millions of conversions over a large scan. Consequences:
 *
 *  - Construct one instance per logical operation (a scan, a recluster, one
 *    user action) and let it go. A long-lived instance would go stale against
 *    writes made through other instances.
 *  - The clusterer persists via targeted stat updates ([ClusterDao.updateStats]),
 *    never whole-entity writes, so a concurrent rename is never clobbered by
 *    stale cached fields.
 *  - If a cached cluster was deleted externally mid-operation (user merge in
 *    parallel with a scan), [assign]'s zero-row update detects it, evicts the
 *    ghost, and retries once.
 */
class FaceClusterer(
    private val clusterDao: ClusterDao,
    private val faceDao: FaceDao,
    private val assignThreshold: Float = FaceRecognitionConfig.CLUSTER_ASSIGN_THRESHOLD,
    private val mergeThreshold: Float = FaceRecognitionConfig.CLUSTER_MERGE_THRESHOLD,
    private val now: () -> Long = { System.currentTimeMillis() }
) {

    private class Cached(
        val id: Long,
        var centroid: FloatArray,
        var faceCount: Int,
        var coverFaceId: Long?,
        var coverQuality: Float
    )

    private var cache: LinkedHashMap<Long, Cached>? = null

    private suspend fun ensureLoaded(): LinkedHashMap<Long, Cached> {
        cache?.let { return it }
        val clusters = clusterDao.all()
        val coverQualities = HashMap<Long, Float>()
        clusters.mapNotNull { it.coverFaceId }
            .chunked(SQL_VARIABLE_CHUNK)
            .forEach { chunk ->
                faceDao.qualitiesForIds(chunk).forEach { coverQualities[it.id] = it.quality }
            }
        val loaded = LinkedHashMap<Long, Cached>(clusters.size * 2)
        for (c in clusters) {
            loaded[c.id] = Cached(
                id = c.id,
                centroid = Embeddings.fromBytes(c.centroid),
                faceCount = c.faceCount,
                coverFaceId = c.coverFaceId,
                coverQuality = c.coverFaceId?.let { coverQualities[it] } ?: -1f
            )
        }
        cache = loaded
        return loaded
    }

    /** Drops the cache; the next operation reloads from Room. */
    fun invalidate() {
        cache = null
    }

    /**
     * Assign [faceId] (already persisted with embedding [embedding] and quality
     * [quality]) to the best matching cluster, or create a new one.
     *
     * Caller is expected to wrap multi-face batches in a Room transaction.
     */
    suspend fun assign(
        faceId: Long,
        embedding: FloatArray,
        quality: Float
    ): Long {
        val clusters = ensureLoaded()

        var best: Cached? = null
        var bestSim = Float.NEGATIVE_INFINITY
        for (c in clusters.values) {
            val sim = SimilarityMatcher.cosineSimilarity(embedding, c.centroid)
            if (sim > bestSim) {
                bestSim = sim
                best = c
            }
        }

        if (best != null && bestSim >= assignThreshold) {
            val merged = runningMean(
                oldCentroid = best.centroid,
                oldCount = best.faceCount,
                addition = embedding
            )
            val newCount = best.faceCount + 1
            // Upgrade the cover face if the new face has noticeably higher quality.
            // Avoids thumbnail flicker for small improvements (0.05 threshold).
            val upgradeCover = best.coverFaceId == null ||
                quality > best.coverQuality + COVER_UPGRADE_THRESHOLD
            val coverFaceId = if (upgradeCover) faceId else best.coverFaceId
            val coverQuality = if (upgradeCover) quality else best.coverQuality

            val updated = clusterDao.updateStats(
                id = best.id,
                centroid = Embeddings.toBytes(merged),
                faceCount = newCount,
                coverFaceId = coverFaceId,
                updatedAt = now()
            )
            if (updated == 0) {
                // Cluster vanished under us (deleted through another instance).
                // Evict the ghost and re-run against the remaining clusters.
                Timber.w("Cluster ${best.id} disappeared mid-assign; evicting and retrying")
                clusters.remove(best.id)
                return assign(faceId, embedding, quality)
            }
            best.centroid = merged
            best.faceCount = newCount
            best.coverFaceId = coverFaceId
            best.coverQuality = coverQuality
            faceDao.assignToCluster(faceId, best.id)
            return best.id
        }

        val newId = clusterDao.insert(
            ClusterEntity(
                displayName = null,
                coverFaceId = faceId,
                faceCount = 1,
                centroid = Embeddings.toBytes(l2Normalized(embedding)),
                createdAt = now(),
                updatedAt = now()
            )
        )
        clusters[newId] = Cached(
            id = newId,
            centroid = l2Normalized(embedding),
            faceCount = 1,
            coverFaceId = faceId,
            coverQuality = quality
        )
        faceDao.assignToCluster(faceId, newId)
        return newId
    }

    /**
     * Merge cluster pairs whose centroids are at or above [mergeThreshold].
     * Repeats until a full pass produces no merges. The pairwise scan runs
     * over cached centroids — pure float math, no per-iteration Room reads.
     *
     * Each pass sorts survivors by [Cached.faceCount] descending once, then
     * walks outer-to-inner absorbing every later, still-alive cluster within
     * threshold — absorbed clusters are marked dead in a per-pass set rather
     * than restarting the scan. This is safe because a survivor's face count only
     * grows within a pass (via absorption), so for any not-yet-visited `j`,
     * the invariant `a.faceCount >= b.faceCount` established by the initial
     * sort holds for the rest of the pass — "larger absorbs smaller" is
     * preserved without re-sorting after each merge. A centroid shift from
     * absorption can newly bring a survivor within threshold of a cluster
     * already passed over earlier in this pass; that's picked up by the next
     * full pass, which is why passes repeat until one yields zero merges.
     */
    suspend fun mergeClose() {
        // Reload rather than trust the cache: this is the end-of-scan pass and
        // other writers (user merges, reassigns) may have touched clusters
        // since the cache was built. One reload per scan is cheap.
        invalidate()
        val clusters = ensureLoaded()
        var changed = true
        while (changed) {
            changed = false
            val all = clusters.values.sortedByDescending { it.faceCount }
            val dead = HashSet<Long>()
            for (i in all.indices) {
                val a = all[i]
                if (a.id in dead) continue
                for (j in i + 1 until all.size) {
                    val b = all[j]
                    if (b.id in dead) continue
                    val sim = SimilarityMatcher.cosineSimilarity(a.centroid, b.centroid)
                    if (sim >= mergeThreshold) {
                        Timber.d("Merging cluster ${b.id} into ${a.id} (sim=$sim)")
                        mergeInto(survivor = a, absorbed = b)
                        dead += b.id
                        changed = true
                    }
                }
            }
        }
    }

    private suspend fun mergeInto(survivor: Cached, absorbed: Cached) {
        faceDao.reassignCluster(fromCluster = absorbed.id, toCluster = survivor.id)
        val newCentroid = weightedMean(
            survivor.centroid, survivor.faceCount,
            absorbed.centroid, absorbed.faceCount
        )
        val newCount = survivor.faceCount + absorbed.faceCount
        clusterDao.updateStats(
            id = survivor.id,
            centroid = Embeddings.toBytes(newCentroid),
            faceCount = newCount,
            coverFaceId = survivor.coverFaceId,
            updatedAt = now()
        )
        clusterDao.delete(absorbed.id)
        survivor.centroid = newCentroid
        survivor.faceCount = newCount
        cache?.remove(absorbed.id)
    }

    /**
     * User-initiated merge: combine [fromClusterId] into [intoClusterId].
     */
    suspend fun mergeUserRequested(fromClusterId: Long, intoClusterId: Long) {
        if (fromClusterId == intoClusterId) return
        val clusters = ensureLoaded()
        val survivor = clusters[intoClusterId] ?: return
        val absorbed = clusters[fromClusterId] ?: return
        mergeInto(survivor = survivor, absorbed = absorbed)
    }

    /**
     * Rebuild a cluster's centroid + cover face from its current face rows.
     * Called from the indexer when faces are deleted (e.g. photo re-indexed).
     *
     * If the cluster ends up empty, it's deleted; the caller normally also runs
     * [deleteEmpty] after the batch to clean up any others.
     */
    suspend fun recomputeFromFaces(clusterId: Long) {
        val faces = faceDao.facesInCluster(clusterId)
        if (faces.isEmpty()) {
            clusterDao.delete(clusterId)
            cache?.remove(clusterId)
            return
        }
        val dim = Embeddings.fromBytes(faces.first().embedding).size
        val sum = FloatArray(dim)
        var bestFaceId = faces.first().id
        var bestQuality = faces.first().quality
        for (f in faces) {
            val v = Embeddings.fromBytes(f.embedding)
            for (i in 0 until dim) sum[i] += v[i]
            if (f.quality > bestQuality) {
                bestQuality = f.quality
                bestFaceId = f.id
            }
        }
        val faceCount = faces.size
        for (i in 0 until dim) sum[i] = sum[i] / faceCount
        val centroid = l2Normalized(sum)
        clusterDao.updateStats(
            id = clusterId,
            centroid = Embeddings.toBytes(centroid),
            faceCount = faceCount,
            coverFaceId = bestFaceId,
            updatedAt = now()
        )
        cache?.get(clusterId)?.let {
            it.centroid = centroid
            it.faceCount = faceCount
            it.coverFaceId = bestFaceId
            it.coverQuality = bestQuality
        }
    }

    /**
     * Drops zero-face clusters and keeps the cache coherent with the delete.
     * Callers should use this instead of [ClusterDao.deleteEmpty] whenever a
     * clusterer instance is alive.
     */
    suspend fun deleteEmpty() {
        clusterDao.deleteEmpty()
        cache?.values?.removeIf { it.faceCount <= 0 }
    }

    private fun runningMean(oldCentroid: FloatArray, oldCount: Int, addition: FloatArray): FloatArray {
        val total = oldCount + 1
        val out = FloatArray(oldCentroid.size)
        for (i in oldCentroid.indices) {
            out[i] = (oldCentroid[i] * oldCount + addition[i]) / total
        }
        return l2Normalized(out)
    }

    private fun weightedMean(a: FloatArray, weightA: Int, b: FloatArray, weightB: Int): FloatArray {
        val total = weightA + weightB
        val out = FloatArray(a.size)
        for (i in a.indices) {
            out[i] = (a[i] * weightA + b[i] * weightB) / total
        }
        return l2Normalized(out)
    }

    private fun l2Normalized(v: FloatArray): FloatArray {
        var norm = 0f
        for (x in v) norm += x * x
        val n = sqrt(norm)
        if (n == 0f) return v
        val out = FloatArray(v.size)
        for (i in v.indices) out[i] = v[i] / n
        return out
    }

    companion object {
        /** Only upgrade the cover face if the new face quality exceeds the current by this margin. */
        private const val COVER_UPGRADE_THRESHOLD = 0.05f

        /** Stay under SQLite's 999-bind-variable limit for IN() clauses. */
        private const val SQL_VARIABLE_CHUNK = 900
    }
}
