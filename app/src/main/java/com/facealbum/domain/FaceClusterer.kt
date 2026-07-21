package com.facealbum.domain

import com.facealbum.config.FaceRecognitionConfig
import com.facealbum.data.db.ClusterDao
import com.facealbum.data.db.ClusterEntity
import com.facealbum.data.db.Embeddings
import com.facealbum.data.db.FaceDao
import com.facealbum.data.db.FaceEntity
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
 */
class FaceClusterer(
    private val clusterDao: ClusterDao,
    private val faceDao: FaceDao,
    private val assignThreshold: Float = FaceRecognitionConfig.CLUSTER_ASSIGN_THRESHOLD,
    private val mergeThreshold: Float = FaceRecognitionConfig.CLUSTER_MERGE_THRESHOLD,
    private val now: () -> Long = { System.currentTimeMillis() }
) {

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
        val clusters = clusterDao.all()

        var bestId: Long = -1L
        var bestSim = Float.NEGATIVE_INFINITY
        var bestEntity: ClusterEntity? = null
        for (c in clusters) {
            val centroid = Embeddings.fromBytes(c.centroid)
            val sim = SimilarityMatcher.cosineSimilarity(embedding, centroid)
            if (sim > bestSim) {
                bestSim = sim
                bestId = c.id
                bestEntity = c
            }
        }

        return if (bestEntity != null && bestSim >= assignThreshold) {
            val merged = runningMean(
                oldCentroid = Embeddings.fromBytes(bestEntity.centroid),
                oldCount = bestEntity.faceCount,
                addition = embedding
            )
            val newCount = bestEntity.faceCount + 1
            // Upgrade the cover face if the new face has noticeably higher quality.
            // Avoids thumbnail flicker for small improvements (0.05 threshold).
            val currentCoverQuality = bestEntity.coverFaceId
                ?.let { faceDao.findById(it)?.quality } ?: -1f
            val coverFaceId = if (quality > currentCoverQuality + COVER_UPGRADE_THRESHOLD) {
                faceId
            } else {
                bestEntity.coverFaceId ?: faceId
            }
            clusterDao.update(
                bestEntity.copy(
                    centroid = Embeddings.toBytes(merged),
                    faceCount = newCount,
                    coverFaceId = coverFaceId,
                    updatedAt = now()
                )
            )
            faceDao.assignToCluster(faceId, bestEntity.id)
            bestEntity.id
        } else {
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
            faceDao.assignToCluster(faceId, newId)
            newId
        }
    }

    /**
     * Merge cluster pairs whose centroids are at or above [mergeThreshold].
     * Repeats until no merges happen — typical scan produces only a handful of merges
     * so the O(n^2) loop is acceptable for the cluster counts we expect (≤200).
     */
    suspend fun mergeClose() {
        var changed = true
        while (changed) {
            changed = false
            val all = clusterDao.all().sortedByDescending { it.faceCount }
            outer@ for (i in all.indices) {
                val a = all[i]
                val aCentroid = Embeddings.fromBytes(a.centroid)
                for (j in i + 1 until all.size) {
                    val b = all[j]
                    val bCentroid = Embeddings.fromBytes(b.centroid)
                    val sim = SimilarityMatcher.cosineSimilarity(aCentroid, bCentroid)
                    if (sim >= mergeThreshold) {
                        Timber.d("Merging cluster ${b.id} into ${a.id} (sim=$sim)")
                        mergeInto(survivor = a, absorbed = b)
                        changed = true
                        break@outer
                    }
                }
            }
        }
    }

    private suspend fun mergeInto(survivor: ClusterEntity, absorbed: ClusterEntity) {
        faceDao.reassignCluster(fromCluster = absorbed.id, toCluster = survivor.id)
        val newCount = survivor.faceCount + absorbed.faceCount
        val newCentroid = weightedMean(
            Embeddings.fromBytes(survivor.centroid), survivor.faceCount,
            Embeddings.fromBytes(absorbed.centroid), absorbed.faceCount
        )
        clusterDao.update(
            survivor.copy(
                centroid = Embeddings.toBytes(newCentroid),
                faceCount = newCount,
                updatedAt = now()
            )
        )
        clusterDao.delete(absorbed.id)
    }

    /**
     * User-initiated merge: combine [fromClusterId] into [intoClusterId].
     */
    suspend fun mergeUserRequested(fromClusterId: Long, intoClusterId: Long) {
        if (fromClusterId == intoClusterId) return
        val survivor = clusterDao.byId(intoClusterId) ?: return
        val absorbed = clusterDao.byId(fromClusterId) ?: return
        mergeInto(survivor = survivor, absorbed = absorbed)
    }

    /**
     * Rebuild a cluster's centroid + cover face from its current face rows.
     * Called from the indexer when faces are deleted (e.g. photo re-indexed).
     *
     * If the cluster ends up empty, it's deleted; the caller normally also runs
     * [ClusterDao.deleteEmpty] after the batch to clean up any others.
     */
    suspend fun recomputeFromFaces(clusterId: Long) {
        val faces = faceDao.facesInCluster(clusterId)
        val cluster = clusterDao.byId(clusterId) ?: return
        if (faces.isEmpty()) {
            clusterDao.delete(clusterId)
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
        clusterDao.update(
            cluster.copy(
                centroid = Embeddings.toBytes(l2Normalized(sum)),
                faceCount = faces.size,
                coverFaceId = bestFaceId,
                updatedAt = now()
            )
        )
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
    }
}
