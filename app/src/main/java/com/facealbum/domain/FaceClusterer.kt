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

    /** In-memory snapshot of a face used only within [refineAssignments]. */
    private class FaceState(
        val id: Long,
        val embedding: FloatArray,
        val quality: Float,
        val originalClusterId: Long?,
        var clusterId: Long?
    )

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
     *
     * ## Anti-chaining guard
     * Centroid linkage is prone to chaining: cluster A absorbs B, the shifted
     * A-centroid now reaches C even though A's *original* faces and C were
     * never actually close, and two genuinely different people end up in one
     * group via a bridge. Because a survivor's face count only grows within a
     * pass (see above), a large `faceCount` is a direct, already-tracked
     * signal that a cluster has been through one or more absorptions and its
     * centroid may have drifted from any single member. So once either side
     * of a candidate pair has at least
     * [FaceRecognitionConfig.CLUSTER_MERGE_CHAIN_GUARD_SIZE] faces, the pair
     * must clear `mergeThreshold + `
     * [FaceRecognitionConfig.CLUSTER_MERGE_CHAIN_GUARD_MARGIN] instead of the
     * plain [mergeThreshold]. This was picked over an average-linkage sample
     * check (re-reading member faces per candidate pair via
     * [FaceDao.facesInCluster]) because it costs nothing beyond a size
     * comparison on data already cached — the sampling approach would add
     * Room reads inside the O(n^2) pairwise loop, which is exactly the shape
     * [ClusteringBenchmarkTest] guards against ("mergeClose reloads centroids
     * once regardless of merge count").
     */
    suspend fun mergeClose() {
        // Reload rather than trust the cache: this is the end-of-scan pass and
        // other writers (user merges, reassigns) may have touched clusters
        // since the cache was built. One reload per scan is cheap.
        invalidate()
        val clusters = ensureLoaded()
        val chainGuardThreshold = mergeThreshold + FaceRecognitionConfig.CLUSTER_MERGE_CHAIN_GUARD_MARGIN
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
                    val requiredSim = if (
                        maxOf(a.faceCount, b.faceCount) >= FaceRecognitionConfig.CLUSTER_MERGE_CHAIN_GUARD_SIZE
                    ) {
                        chainGuardThreshold
                    } else {
                        mergeThreshold
                    }
                    if (sim >= requiredSim) {
                        Timber.d("Merging cluster ${b.id} into ${a.id} (sim=$sim, required=$requiredSim)")
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

    /**
     * Order-independent refinement pass over existing cluster assignments.
     *
     * [assign] is greedy and online: a face is locked into whichever cluster
     * existed at the moment it arrived, and is never revisited as later faces
     * shift that cluster's centroid. This runs up to [maxIterations]
     * Lloyd-style sweeps over every face's *current* assignment:
     *
     *  - Every face's embedding is loaded once, up front
     *    ([FaceDao.allOrderedByQualityDesc]).
     *  - Each sweep computes, for every face, the nearest cluster centroid
     *    *as centroids stood at the start of that sweep* (centroids are only
     *    recomputed once, after the whole sweep has decided its moves — a
     *    face's move never affects another face's decision within the same
     *    sweep). A face moves only if all of:
     *      1. the nearest centroid belongs to a different cluster than the
     *         face's current one,
     *      2. that similarity is at or above [assignThreshold], and
     *      3. it beats the face's similarity to its *current* centroid by at
     *         least [FaceRecognitionConfig.REFINE_HYSTERESIS_MARGIN].
     *    Condition 3 is a hysteresis margin: without it, a face sitting
     *    almost exactly equidistant between two centroids could move back
     *    toward its original cluster on the very next sweep once that move
     *    nudges both centroids by a hair, flapping instead of settling.
     *  - All centroid math for the sweep happens in memory, from the
     *    embeddings loaded up front — no per-face or per-cluster Room reads.
     *  - Stops the moment a sweep moves zero faces (converged), or after
     *    [maxIterations] sweeps, whichever comes first.
     *
     * Only the *net* effect is written through to Room once the loop ends:
     * faces whose final cluster differs from what Room has, and — for every
     * cluster whose membership actually changed — one authoritative
     * recompute of its centroid/faceCount/cover face from final membership
     * (mirroring [recomputeFromFaces]'s math, but from the already-loaded
     * embeddings rather than a fresh per-cluster query). A cluster that lost
     * every member is written with `faceCount = 0` so a subsequent
     * [deleteEmpty] call actually removes it — callers should call
     * [deleteEmpty] after this.
     *
     * @return the total number of faces that changed cluster, summed across
     *   all sweeps (0 if nothing moved — including on a converged, idempotent
     *   second call).
     */
    suspend fun refineAssignments(maxIterations: Int = 3): Int {
        val clusters = ensureLoaded()
        if (clusters.isEmpty()) return 0

        val faceRows = faceDao.allOrderedByQualityDesc()
        if (faceRows.isEmpty()) return 0

        val states = faceRows.map { f ->
            FaceState(
                id = f.id,
                embedding = Embeddings.fromBytes(f.embedding),
                quality = f.quality,
                originalClusterId = f.clusterId,
                clusterId = f.clusterId
            )
        }

        var totalMoved = 0
        var iteration = 0
        while (iteration < maxIterations) {
            iteration++
            var movedThisIteration = 0
            for (state in states) {
                val currentId = state.clusterId ?: continue
                val currentCentroid = clusters[currentId]?.centroid ?: continue

                var bestId: Long? = null
                var bestSim = Float.NEGATIVE_INFINITY
                for ((cid, c) in clusters) {
                    val sim = SimilarityMatcher.cosineSimilarity(state.embedding, c.centroid)
                    if (sim > bestSim) {
                        bestSim = sim
                        bestId = cid
                    }
                }

                if (bestId == null || bestId == currentId || bestSim < assignThreshold) continue
                val simToCurrent = SimilarityMatcher.cosineSimilarity(state.embedding, currentCentroid)
                if (bestSim < simToCurrent + FaceRecognitionConfig.REFINE_HYSTERESIS_MARGIN) continue

                state.clusterId = bestId
                movedThisIteration++
            }

            if (movedThisIteration == 0) break
            totalMoved += movedThisIteration
            recomputeCentroidsInMemory(states, clusters)
        }

        if (totalMoved == 0) return 0

        // Persist only what actually changed: faces whose final cluster
        // differs from the DB, and the clusters on either end of a move.
        val touchedClusters = HashSet<Long>()
        for (state in states) {
            if (state.clusterId != state.originalClusterId) {
                faceDao.assignToCluster(state.id, state.clusterId)
                state.originalClusterId?.let { touchedClusters += it }
                state.clusterId?.let { touchedClusters += it }
            }
        }

        val membersByCluster = HashMap<Long, MutableList<FaceState>>()
        for (state in states) {
            val cid = state.clusterId ?: continue
            membersByCluster.getOrPut(cid) { mutableListOf() } += state
        }

        for (clusterId in touchedClusters) {
            val cached = clusters[clusterId] ?: continue
            val members = membersByCluster[clusterId].orEmpty()
            if (members.isEmpty()) {
                // Every face moved out; write faceCount = 0 so deleteEmpty()
                // (the caller's responsibility, per KDoc above) picks it up.
                cached.faceCount = 0
                clusterDao.updateStats(
                    id = clusterId,
                    centroid = Embeddings.toBytes(cached.centroid),
                    faceCount = 0,
                    coverFaceId = null,
                    updatedAt = now()
                )
                continue
            }

            val dim = members.first().embedding.size
            val sum = FloatArray(dim)
            var bestFaceId = members.first().id
            var bestQuality = members.first().quality
            for (m in members) {
                for (i in 0 until dim) sum[i] += m.embedding[i]
                if (m.quality > bestQuality) {
                    bestQuality = m.quality
                    bestFaceId = m.id
                }
            }
            // Explicit `x = x / n` rather than `x /= n`: Kotlin rejects a
            // compound assignment into a primitive-array element when the
            // right operand is an Int ("No set method providing array
            // access"), even though the plain form resolves fine. Same shape
            // as recomputeFromFaces above.
            for (i in 0 until dim) sum[i] = sum[i] / members.size
            val centroid = l2Normalized(sum)

            cached.centroid = centroid
            cached.faceCount = members.size
            cached.coverFaceId = bestFaceId
            cached.coverQuality = bestQuality
            clusterDao.updateStats(
                id = clusterId,
                centroid = Embeddings.toBytes(centroid),
                faceCount = members.size,
                coverFaceId = bestFaceId,
                updatedAt = now()
            )
        }

        return totalMoved
    }

    /**
     * Recomputes every cluster's centroid in [clusters] from [states]' *current*
     * (possibly just-moved) assignments, purely in memory. Used between
     * sweeps inside [refineAssignments] so the next sweep sees this sweep's
     * moves reflected in the centroids it compares against, without a Room
     * round-trip. Does not touch [Cached.faceCount] / [Cached.coverFaceId] —
     * those are only ever made authoritative by the final write-through in
     * [refineAssignments], once the whole pass has settled.
     */
    private fun recomputeCentroidsInMemory(states: List<FaceState>, clusters: LinkedHashMap<Long, Cached>) {
        val sums = HashMap<Long, FloatArray>()
        val counts = HashMap<Long, Int>()
        for (state in states) {
            val cid = state.clusterId ?: continue
            val sum = sums.getOrPut(cid) { FloatArray(state.embedding.size) }
            for (i in state.embedding.indices) sum[i] += state.embedding[i]
            counts[cid] = (counts[cid] ?: 0) + 1
        }
        for ((cid, sum) in sums) {
            val count = counts[cid] ?: continue
            // See the note in refineAssignments: `sum[i] /= count` does not
            // compile for a FloatArray element divided by an Int.
            for (i in sum.indices) sum[i] = sum[i] / count
            clusters[cid]?.centroid = l2Normalized(sum)
        }
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
