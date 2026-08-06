package com.facealbum.perf

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.facealbum.data.db.ClusterDao
import com.facealbum.data.db.ClusterEntity
import com.facealbum.data.db.ClusterSummary
import com.facealbum.data.db.Embeddings
import com.facealbum.data.db.FaceAlbumDatabase
import com.facealbum.data.db.FaceEntity
import com.facealbum.data.db.PhotoEntity
import com.facealbum.domain.FaceClusterer
import com.facealbum.domain.SimilarityMatcher
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.sqrt
import kotlin.system.measureTimeMillis
import kotlin.time.Duration.Companion.minutes

/**
 * Performance-shape regression tests for [FaceClusterer] (plan item P4.5 /
 * Phase 7). See `docs/plan/07-performance-plan.md` for the targets.
 *
 * ## Why operation counts, not wall-clock, are the hard assertions
 * CI runners are shared and noisy. A wall-clock assertion tight enough to
 * catch a real regression will also flake on a slow runner, and a flaky test
 * gets deleted rather than fixed. Instead the hard assertions here count
 * calls made to a delegating [ClusterDao] wrapper ([CountingClusterDao]) to
 * prove the *algorithmic* shape holds:
 *
 *  - [FaceClusterer.assign] loads the full centroid table at most once per
 *    clusterer instance (the in-memory cache from P4.1/P4.2), never once per
 *    face. A regression back to a per-face `ClusterDao.all()` read would make
 *    [CountingClusterDao.allCalls] grow linearly with face count; here it is
 *    asserted `== 1` regardless of scale (100 / 1 000 / 5 000 faces).
 *  - [FaceClusterer.mergeClose] reloads centroids at most once per call, no
 *    matter how many pairs it merges in that pass. A regression where the
 *    merge loop re-queries Room after absorbing each pair (instead of working
 *    off the in-memory cache) would make `allCalls` grow with the merge
 *    count; here it is asserted `== 1` even though the scenario forces dozens
 *    of merges.
 *
 * Wall-clock numbers are still measured and printed (ms total, µs/face
 * amortized) so a human can eyeball them against the documented targets, but
 * they are only guarded by a loose (~10x) sanity ceiling that a correct
 * implementation could never approach even on a slow runner — the point is
 * to catch a genuine O(n^2)-class blowup, not to enforce the target itself.
 *
 * ## NOTE on the former restart behaviour in `mergeClose` (fixed, P4.2)
 * `mergeClose`'s *comparison* loop used to restart its pairwise scan from
 * index 0 (with a fresh `sortedByDescending`) after every single merge,
 * making comparison cost scale with `merges * clusters` rather than with
 * `clusters` alone — see the historical `while (changed) { ... } outer@ for
 * ... break@outer` shape once here and in `FaceClusterer.kt`. It has been
 * replaced with a shape that sorts once per pass, then walks outer-to-inner
 * absorbing every later still-alive cluster within threshold (tracked via a
 * per-pass dead set) before repeating full passes only while the last one
 * produced a merge — cost is now `passes * clusters^2` comparisons, with
 * passes typically small (2-3) and, notably, independent of merge count.
 * The merge scenario below still deliberately uses a bounded number of
 * pre-merge clusters (~300) rather than one singleton cluster per face,
 * since this test's job is measuring/guarding shape at a fixed, known scale
 * rather than proving arbitrary scale-out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ClusteringBenchmarkTest {

    // ---- synthetic data generation -----------------------------------

    private data class Scale(val totalFaces: Int, val identities: Int)

    // 5 000/200 mirrors the "5 000 faces / 200 clusters" figure in the
    // performance targets; 100 and 1 000 give intermediate scale points.
    private val scales = listOf(
        Scale(totalFaces = 100, identities = 20),
        Scale(totalFaces = 1_000, identities = 100),
        Scale(totalFaces = 5_000, identities = 200)
    )

    private val dim = 512
    private val seedBase = 42L

    // Small relative to a unit vector's component scale (~1/sqrt(512)), so
    // variants of the same identity stay well above the 0.6 assign
    // threshold while distinct identities (independent random directions in
    // 512-D) stay well below it. Verified empirically below, not just by
    // this comment - see `synthetic generator separates identities`.
    private val noiseSigma = 0.02f

    /** Deterministic per-identity "true" direction. */
    private fun baseVector(identity: Int): FloatArray {
        val rand = java.util.Random(seedBase + 1_000_003L * identity)
        val v = FloatArray(dim) { rand.nextGaussian().toFloat() }
        return l2Normalize(v)
    }

    /** A noisy sample of [identity], e.g. one face belonging to that person. */
    private fun variantVector(identity: Int, variant: Int): FloatArray {
        val base = baseVector(identity)
        val rand = java.util.Random(seedBase + 7_919L * identity + 104_729L * variant)
        val v = FloatArray(dim) { i -> base[i] + rand.nextGaussian().toFloat() * noiseSigma }
        return l2Normalize(v)
    }

    private fun l2Normalize(v: FloatArray): FloatArray {
        var norm = 0f
        for (x in v) norm += x * x
        val n = sqrt(norm)
        if (n == 0f) return v
        val out = FloatArray(v.size)
        for (i in v.indices) out[i] = v[i] / n
        return out
    }

    @Test
    fun `synthetic generator separates identities at the assign threshold`() {
        // Guards the benchmark's premise. If this generator ever regressed to
        // producing embeddings that don't actually separate at 0.6 cosine,
        // the scale benchmarks below would silently degenerate (everything
        // in one cluster, or every face its own cluster) and stop exercising
        // the code paths they're meant to measure.
        val assignThreshold = 0.6f
        val identities = 200

        var minIntra = Float.POSITIVE_INFINITY
        var maxInter = Float.NEGATIVE_INFINITY
        for (identity in 0 until identities step 7) { // sampled, not exhaustive - keep this fast
            val a = variantVector(identity, 0)
            val b = variantVector(identity, 1)
            minIntra = minOf(minIntra, SimilarityMatcher.cosineSimilarity(a, b))

            val other = (identity + 1) % identities
            val c = variantVector(other, 0)
            maxInter = maxOf(maxInter, SimilarityMatcher.cosineSimilarity(a, c))
        }

        // Comfortable margin either side of the threshold, not a knife's edge.
        assertThat(minIntra).isGreaterThan(assignThreshold + 0.1f)
        assertThat(maxInter).isLessThan(assignThreshold - 0.1f)
        println("[generator] minIntraSim=$minIntra maxInterSim=$maxInter (assignThreshold=$assignThreshold)")
    }

    // ---- counting DAO wrapper -----------------------------------------

    /**
     * Delegates every call to [delegate] while counting invocations of the
     * one call the hard assertions above care about: [all], the full
     * centroid-table read. Everything else is pure pass-through so
     * [FaceClusterer] behaves identically to production.
     */
    private class CountingClusterDao(private val delegate: ClusterDao) : ClusterDao {
        var allCalls = 0
            private set

        override suspend fun insert(cluster: ClusterEntity): Long = delegate.insert(cluster)

        override suspend fun updateStats(
            id: Long,
            centroid: ByteArray,
            faceCount: Int,
            coverFaceId: Long?,
            updatedAt: Long
        ): Int = delegate.updateStats(id, centroid, faceCount, coverFaceId, updatedAt)

        override suspend fun rename(id: Long, name: String, now: Long) =
            delegate.rename(id, name, now)

        override suspend fun deleteEmpty() = delegate.deleteEmpty()

        override suspend fun byId(id: Long): ClusterEntity? = delegate.byId(id)

        override suspend fun all(): List<ClusterEntity> {
            allCalls++
            return delegate.all()
        }

        override fun summariesAtLeast(minSize: Int): Flow<List<ClusterSummary>> =
            delegate.summariesAtLeast(minSize)

        override suspend fun delete(id: Long) = delegate.delete(id)

        override suspend fun clear() = delegate.clear()
    }

    // ---- fixture helpers -------------------------------------------------

    private fun newDb(): FaceAlbumDatabase =
        Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            FaceAlbumDatabase::class.java
        ).allowMainThreadQueries().build()

    /**
     * One shared photo row per database: this benchmark measures clustering,
     * not the photo/face relational shape, so we don't need (or want the
     * insert overhead of) one photo per face.
     */
    private suspend fun insertSharedPhoto(db: FaceAlbumDatabase): Long =
        db.photoDao().insert(
            PhotoEntity(
                mediaStoreId = 1L,
                uri = "content://media/1",
                displayName = "bench.jpg",
                dateTaken = 0L,
                dateModified = 0L,
                processedAt = 0L,
                faceCount = 0
            )
        )

    private fun faceEntity(photoId: Long, embedding: FloatArray): FaceEntity =
        FaceEntity(
            photoId = photoId,
            clusterId = null,
            bboxLeft = 0, bboxTop = 0, bboxRight = 100, bboxBottom = 100,
            embedding = Embeddings.toBytes(embedding),
            quality = 0.1f
        )

    // ---- benchmark: assign() ------------------------------------------

    // runTest defaults to a 60s wall-clock timeout, and this body is real
    // blocking work (5 000 Robolectric Room inserts plus ~5 000 x 200 x 512
    // float comparisons), not virtual time that the scheduler can skip. On a
    // cold, contended CI runner 60s is not comfortable headroom, and blowing
    // it would look like a product regression rather than a slow machine.
    // The meaningful ceilings are the per-scale assertions inside the loop.
    @Test
    fun `assign centroid-table reads stay flat across face count scale`() = runTest(
        timeout = 5.minutes
    ) {
        val summary = mutableListOf<String>()
        for (scale in scales) {
            val db = newDb()
            try {
                val photoId = insertSharedPhoto(db)
                val countingClusterDao = CountingClusterDao(db.clusterDao())
                val clusterer = FaceClusterer(
                    clusterDao = countingClusterDao,
                    faceDao = db.faceDao(),
                    assignThreshold = 0.6f,
                    mergeThreshold = 0.75f,
                    now = { 0L }
                )

                val elapsedMs = measureTimeMillis {
                    db.withTransaction {
                        for (i in 0 until scale.totalFaces) {
                            val identity = i % scale.identities
                            val variant = i / scale.identities
                            val embedding = variantVector(identity, variant)
                            val faceId = db.faceDao().insert(faceEntity(photoId, embedding))
                            clusterer.assign(faceId, embedding, quality = 0.1f)
                        }
                    }
                }

                // Hard assertion: exactly one full centroid-table read for the
                // whole batch of `scale.totalFaces` assigns, regardless of
                // scale. Protects against the P4.1 regression where `assign`
                // re-read and re-deserialized every centroid from Room on
                // every single face (which would make this call count equal
                // to `scale.totalFaces`, not 1).
                assertThat(countingClusterDao.allCalls).isEqualTo(1)

                val clusterCount = db.clusterDao().all().size
                val amortizedUs = (elapsedMs * 1000.0) / scale.totalFaces
                val line = "faces=${scale.totalFaces} identities=${scale.identities} " +
                    "clusters=$clusterCount totalMs=$elapsedMs amortizedUsPerFace=" +
                    String.format("%.1f", amortizedUs)
                println("[assign] $line")
                summary += line

                // Loose sanity ceiling only (~10x the documented 5ms/face
                // amortized target = 50ms/face = 50_000us/face). Wall-clock is
                // NOT the regression signal here - allCalls above is. This
                // only exists to catch a genuine algorithmic blowup (e.g. a
                // return to per-face O(n) Room reads) surviving the allCalls
                // check some other way.
                assertThat(amortizedUs).isLessThan(50_000.0)
            } finally {
                db.close()
            }
        }
        println("[assign] summary:")
        summary.forEach { println("  $it") }
    }

    // ---- benchmark: mergeClose() ---------------------------------------

    @Test
    fun `mergeClose reloads centroids once regardless of merge count`() = runTest {
        val db = newDb()
        try {
            val photoId = insertSharedPhoto(db)

            // Force real splitting: an artificially strict assign threshold
            // means near-duplicate variants of the same identity each open
            // their own cluster instead of joining one, the same idiom
            // `FaceClustererTest` uses for its merge test. This guarantees
            // mergeClose() below has real work to do (many merges in one
            // pass), not zero.
            //
            // Bounded at 150 identities x 2 variants (300 pre-merge clusters)
            // rather than one cluster per face at full 5k scale: this test's
            // job is measuring/guarding merge behaviour at a fixed, known
            // scale (matching the "~300 pre-merge clusters" figure recorded
            // in docs/plan/07-performance-plan.md), not proving scale-out to
            // 5k. 300 is enough to exercise many merges in one call while
            // staying well inside the runtime budget.
            val identities = 150
            val variantsPerIdentity = 2
            val splitter = FaceClusterer(
                clusterDao = db.clusterDao(),
                faceDao = db.faceDao(),
                assignThreshold = 0.9999f,
                mergeThreshold = 0.75f,
                now = { 0L }
            )
            db.withTransaction {
                for (identity in 0 until identities) {
                    for (variant in 0 until variantsPerIdentity) {
                        val embedding = variantVector(identity, variant)
                        val faceId = db.faceDao().insert(faceEntity(photoId, embedding))
                        splitter.assign(faceId, embedding, quality = 0.1f)
                    }
                }
            }
            val preMergeClusters = db.clusterDao().all().size
            // Sanity: confirm the strict threshold actually produced real
            // splitting (more clusters than identities), otherwise this test
            // would exercise zero merges and prove nothing.
            assertThat(preMergeClusters).isGreaterThan(identities)

            val countingClusterDao = CountingClusterDao(db.clusterDao())
            val merger = FaceClusterer(
                clusterDao = countingClusterDao,
                faceDao = db.faceDao(),
                assignThreshold = 0.6f,
                mergeThreshold = 0.75f,
                now = { 0L }
            )

            val elapsedMs = measureTimeMillis { merger.mergeClose() }
            val postMergeClusters = db.clusterDao().all().size

            // Hard assertion: mergeClose reloads the centroid table exactly
            // once for the whole pass (its single invalidate()+ensureLoaded()
            // at the top), no matter how many pairs it merges inside that
            // pass. Protects against a regression where the merge loop
            // re-queries Room after absorbing each pair instead of working
            // off the in-memory cache - which would make this call count
            // grow with the merge count instead of staying at 1.
            assertThat(countingClusterDao.allCalls).isEqualTo(1)
            // Sanity: merges actually happened.
            assertThat(postMergeClusters).isLessThan(preMergeClusters)

            println(
                "[mergeClose] preMergeClusters=$preMergeClusters postMergeClusters=$postMergeClusters " +
                    "elapsedMs=$elapsedMs"
            )
            // Loose sanity ceiling only (~5x the documented "<=1s at 200
            // clusters" target, tightened from 10s post-P4.2 restart fix -
            // see class doc). Wall-clock is not the regression signal -
            // allCalls above is. Kept loose enough to absorb a slow/shared
            // CI runner rather than to enforce the target number itself.
            assertThat(elapsedMs).isLessThan(5_000L)
        } finally {
            db.close()
        }
    }
}
