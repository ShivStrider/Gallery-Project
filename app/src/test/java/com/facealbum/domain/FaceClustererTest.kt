package com.facealbum.domain

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.facealbum.data.db.Embeddings
import com.facealbum.data.db.FaceAlbumDatabase
import com.facealbum.data.db.FaceEntity
import com.facealbum.data.db.PhotoEntity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FaceClustererTest {

    private lateinit var db: FaceAlbumDatabase
    private lateinit var clusterer: FaceClusterer
    private var mediaStoreCounter: Long = 1

    @Before
    fun setUp() {
        mediaStoreCounter = 1
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            FaceAlbumDatabase::class.java
        ).allowMainThreadQueries().build()
        clusterer = FaceClusterer(
            clusterDao = db.clusterDao(),
            faceDao = db.faceDao(),
            assignThreshold = 0.6f,
            mergeThreshold = 0.75f,
            now = { 0L }
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `similar embeddings land in the same cluster`() = runTest {
        val faceA = insertFace(unitVec(seed = 1, jitter = 0f))
        val faceB = insertFace(unitVec(seed = 1, jitter = 0.01f))
        val faceC = insertFace(unitVec(seed = 1, jitter = -0.005f))

        val cA = clusterer.assign(faceA, unitVec(seed = 1, jitter = 0f), quality = 0.1f)
        val cB = clusterer.assign(faceB, unitVec(seed = 1, jitter = 0.01f), quality = 0.1f)
        val cC = clusterer.assign(faceC, unitVec(seed = 1, jitter = -0.005f), quality = 0.1f)

        assertThat(cA).isEqualTo(cB)
        assertThat(cB).isEqualTo(cC)
        assertThat(db.clusterDao().all()).hasSize(1)
        assertThat(db.clusterDao().all()[0].faceCount).isEqualTo(3)
    }

    @Test
    fun `dissimilar embeddings produce separate clusters`() = runTest {
        val faceA = insertFace(unitVec(seed = 1))
        val faceB = insertFace(unitVec(seed = 2))

        clusterer.assign(faceA, unitVec(seed = 1), quality = 0.1f)
        clusterer.assign(faceB, unitVec(seed = 2), quality = 0.1f)

        assertThat(db.clusterDao().all()).hasSize(2)
    }

    @Test
    fun `mergeClose collapses near-duplicate clusters`() = runTest {
        // Create two clusters whose centroids are very close (>0.75).
        val faceA = insertFace(unitVec(seed = 1, jitter = 0f))
        val faceB = insertFace(unitVec(seed = 1, jitter = 0.02f))
        clusterer.assign(faceA, unitVec(seed = 1, jitter = 0f), 0.1f)
        // Temporarily raise threshold so B opens a new cluster instead of joining A.
        val splitClusterer = FaceClusterer(
            clusterDao = db.clusterDao(),
            faceDao = db.faceDao(),
            assignThreshold = 0.9999f,
            mergeThreshold = 0.75f,
            now = { 0L }
        )
        splitClusterer.assign(faceB, unitVec(seed = 1, jitter = 0.02f), 0.1f)
        assertThat(db.clusterDao().all().size).isEqualTo(2)

        clusterer.mergeClose()
        assertThat(db.clusterDao().all().size).isEqualTo(1)
    }

    @Test
    fun `mergeClose lets the larger cluster absorb the smaller, keeping its id and name`() = runTest {
        // Build a 3-face cluster A from near-duplicate embeddings.
        val faceA1 = insertFace(unitVec(seed = 1, jitter = 0f))
        val faceA2 = insertFace(unitVec(seed = 1, jitter = 0.005f))
        val faceA3 = insertFace(unitVec(seed = 1, jitter = -0.005f))
        val cA = clusterer.assign(faceA1, unitVec(seed = 1, jitter = 0f), 0.1f)
        clusterer.assign(faceA2, unitVec(seed = 1, jitter = 0.005f), 0.1f)
        clusterer.assign(faceA3, unitVec(seed = 1, jitter = -0.005f), 0.1f)
        assertThat(db.clusterDao().byId(cA)!!.faceCount).isEqualTo(3)

        // A single-face cluster B, close enough to merge (>0.75) but forced into
        // its own cluster via a strict assign threshold, same idiom as the
        // existing near-duplicate merge test.
        val faceB = insertFace(unitVec(seed = 1, jitter = 0.02f))
        val splitClusterer = FaceClusterer(
            clusterDao = db.clusterDao(),
            faceDao = db.faceDao(),
            assignThreshold = 0.9999f,
            mergeThreshold = 0.75f,
            now = { 0L }
        )
        val cB = splitClusterer.assign(faceB, unitVec(seed = 1, jitter = 0.02f), 0.1f)
        assertThat(cA).isNotEqualTo(cB)
        assertThat(db.clusterDao().all()).hasSize(2)

        db.clusterDao().rename(cA, "Alice", now = 0L)

        clusterer.mergeClose()

        val remaining = db.clusterDao().all()
        assertThat(remaining).hasSize(1)
        // The larger cluster (3 faces) must survive, not the smaller (1 face) -
        // this is user-visible: it decides which id and display name survive.
        assertThat(remaining[0].id).isEqualTo(cA)
        assertThat(remaining[0].displayName).isEqualTo("Alice")
        assertThat(remaining[0].faceCount).isEqualTo(4)
        assertThat(db.clusterDao().byId(cB)).isNull()
    }

    @Test
    fun `mergeClose converges across passes when a merge newly unlocks a third cluster`() = runTest {
        // Three clusters A, B, C constructed so that neither B nor C is close
        // enough to A to merge directly (cos = 0.72 < 0.75 threshold), but B
        // and C are close enough to merge with each other (cos = 0.7592). Once
        // B absorbs C, the resulting centroid's noise component partially
        // cancels (B and C share the same 0.72 component along A's axis but
        // differ in their orthogonal "noise" direction), pushing similarity to
        // A up to ~0.768 - newly above threshold. A single pass over the
        // sorted list visits A (largest, so sorted first) before B and C ever
        // merge, so it can only discover the A-mergedBC merge on a subsequent
        // pass. This pins that `mergeClose` keeps looping until a pass yields
        // zero merges, rather than stopping after one full scan.
        val aB = 0.72f
        val aC = 0.72f
        val rB = sqrt(1f - aB * aB)
        val rC = sqrt(1f - aC * aC)
        val cosPhi = 0.5f
        val sinPhi = sqrt(3f) / 2f

        val vecA = FloatArray(512).also { it[0] = 1f }
        val vecB = FloatArray(512).also { it[0] = aB; it[1] = rB }
        val vecC = FloatArray(512).also { it[0] = aC; it[1] = rC * cosPhi; it[2] = rC * sinPhi }

        // Sanity-check the geometry this test depends on, independent of any
        // FaceClusterer behaviour, before relying on it below.
        assertThat(SimilarityMatcher.cosineSimilarity(vecA, vecB)).isLessThan(0.75f)
        assertThat(SimilarityMatcher.cosineSimilarity(vecA, vecC)).isLessThan(0.75f)
        assertThat(SimilarityMatcher.cosineSimilarity(vecB, vecC)).isAtLeast(0.75f)

        // Cluster A: 3 identical-direction faces, so it sorts first (largest)
        // and its centroid stays exactly on axis 0.
        val faceA1 = insertFace(vecA)
        val faceA2 = insertFace(vecA)
        val faceA3 = insertFace(vecA)
        val idA = clusterer.assign(faceA1, vecA, 0.1f)
        clusterer.assign(faceA2, vecA, 0.1f)
        clusterer.assign(faceA3, vecA, 0.1f)
        assertThat(db.clusterDao().byId(idA)!!.faceCount).isEqualTo(3)

        // B and C: each 0.72 similar to A, which is above the 0.6 default
        // assign threshold, so a strict split threshold is needed to keep
        // them as their own singleton clusters instead of joining A outright.
        val splitClusterer = FaceClusterer(
            clusterDao = db.clusterDao(),
            faceDao = db.faceDao(),
            assignThreshold = 0.9999f,
            mergeThreshold = 0.75f,
            now = { 0L }
        )
        val faceB = insertFace(vecB)
        val idB = splitClusterer.assign(faceB, vecB, 0.1f)
        val faceC = insertFace(vecC)
        val idC = splitClusterer.assign(faceC, vecC, 0.1f)
        assertThat(db.clusterDao().all()).hasSize(3)

        clusterer.mergeClose()

        val remaining = db.clusterDao().all()
        assertThat(remaining).hasSize(1)
        assertThat(remaining[0].id).isEqualTo(idA)
        assertThat(remaining[0].faceCount).isEqualTo(5)
        assertThat(db.clusterDao().byId(idB)).isNull()
        assertThat(db.clusterDao().byId(idC)).isNull()
    }

    @Test
    fun `mergeUserRequested moves all faces and deletes source`() = runTest {
        val faceA = insertFace(unitVec(seed = 1))
        val faceB = insertFace(unitVec(seed = 2))
        val cA = clusterer.assign(faceA, unitVec(seed = 1), 0.1f)
        val cB = clusterer.assign(faceB, unitVec(seed = 2), 0.1f)
        assertThat(cA).isNotEqualTo(cB)

        clusterer.mergeUserRequested(fromClusterId = cB, intoClusterId = cA)

        assertThat(db.clusterDao().byId(cB)).isNull()
        assertThat(db.clusterDao().byId(cA)!!.faceCount).isEqualTo(2)
        assertThat(db.faceDao().facesInCluster(cA)).hasSize(2)
    }

    @Test
    fun `write-through cache keeps Room authoritative across instances`() = runTest {
        // Build clusters through one instance…
        val ids = mutableListOf<Long>()
        for (seed in 1..5) {
            repeat(4) { i ->
                val v = unitVec(seed = seed, jitter = 0.002f * i)
                val faceId = insertFace(v)
                ids += clusterer.assign(faceId, v, quality = 0.1f)
            }
        }
        assertThat(db.clusterDao().all()).hasSize(5)

        // …then a brand-new instance (fresh cache, loaded from Room) must see
        // exactly the same state and route new faces to the same clusters.
        val fresh = FaceClusterer(
            clusterDao = db.clusterDao(),
            faceDao = db.faceDao(),
            assignThreshold = 0.6f,
            mergeThreshold = 0.75f,
            now = { 0L }
        )
        for (seed in 1..5) {
            val v = unitVec(seed = seed, jitter = 0.001f)
            val faceId = insertFace(v)
            val assigned = fresh.assign(faceId, v, quality = 0.1f)
            assertThat(assigned).isEqualTo(ids[(seed - 1) * 4])
        }
        assertThat(db.clusterDao().all()).hasSize(5)
        assertThat(db.clusterDao().all().sumOf { it.faceCount }).isEqualTo(25)
    }

    @Test
    fun `rename survives clusterer stat updates`() = runTest {
        val v = unitVec(seed = 1)
        val faceId = insertFace(v)
        val clusterId = clusterer.assign(faceId, v, quality = 0.1f)

        // Rename through the DAO (as the UI does), then keep assigning through
        // the same cached clusterer instance: the name must survive.
        db.clusterDao().rename(clusterId, "Alice", now = 1L)
        val v2 = unitVec(seed = 1, jitter = 0.01f)
        val faceId2 = insertFace(v2)
        clusterer.assign(faceId2, v2, quality = 0.2f)

        assertThat(db.clusterDao().byId(clusterId)!!.displayName).isEqualTo("Alice")
        assertThat(db.clusterDao().byId(clusterId)!!.faceCount).isEqualTo(2)
    }

    @Test
    fun `refineAssignments moves a misassigned face to the correct cluster`() = runTest {
        // Cluster A: 3 near-duplicate faces of identity 1.
        val faceA1 = insertFace(unitVec(seed = 1, jitter = 0f))
        val faceA2 = insertFace(unitVec(seed = 1, jitter = 0.005f))
        val faceA3 = insertFace(unitVec(seed = 1, jitter = -0.005f))
        val clusterA = clusterer.assign(faceA1, unitVec(seed = 1, jitter = 0f), 0.1f)
        clusterer.assign(faceA2, unitVec(seed = 1, jitter = 0.005f), 0.1f)
        clusterer.assign(faceA3, unitVec(seed = 1, jitter = -0.005f), 0.1f)

        // Cluster B: 3 near-duplicate faces of an unrelated identity 2 (seeds
        // 1 and 2 are near-orthogonal in 512-D, well under both thresholds -
        // same premise "dissimilar embeddings produce separate clusters" above
        // relies on).
        val faceB1 = insertFace(unitVec(seed = 2, jitter = 0f))
        val faceB2 = insertFace(unitVec(seed = 2, jitter = 0.005f))
        val faceB3 = insertFace(unitVec(seed = 2, jitter = -0.005f))
        val clusterB = clusterer.assign(faceB1, unitVec(seed = 2, jitter = 0f), 0.1f)
        clusterer.assign(faceB2, unitVec(seed = 2, jitter = 0.005f), 0.1f)
        clusterer.assign(faceB3, unitVec(seed = 2, jitter = -0.005f), 0.1f)
        assertThat(clusterA).isNotEqualTo(clusterB)

        // Simulate a pre-existing order-dependence bug: a genuine identity-2
        // face sitting in cluster A instead of B (as if it had arrived before
        // A/B were well separated). Inserted directly via the DAOs, bypassing
        // assign(), and cluster A's stored faceCount bumped to match - the
        // clusterer must discover and fix this from Room state alone.
        val strayEmbedding = unitVec(seed = 2, jitter = 0.01f)
        val strayFace = insertFace(strayEmbedding)
        db.faceDao().assignToCluster(strayFace, clusterA)
        val clusterAEntity = db.clusterDao().byId(clusterA)!!
        db.clusterDao().updateStats(
            id = clusterA,
            centroid = clusterAEntity.centroid,
            faceCount = clusterAEntity.faceCount + 1,
            coverFaceId = clusterAEntity.coverFaceId,
            updatedAt = 0L
        )

        clusterer.invalidate()
        val moved = clusterer.refineAssignments()

        assertThat(moved).isEqualTo(1)
        assertThat(db.faceDao().findById(strayFace)!!.clusterId).isEqualTo(clusterB)
        assertThat(db.clusterDao().byId(clusterA)!!.faceCount).isEqualTo(3)
        assertThat(db.clusterDao().byId(clusterB)!!.faceCount).isEqualTo(4)
    }

    @Test
    fun `refineAssignments converges so a second call moves zero faces`() = runTest {
        val faceA1 = insertFace(unitVec(seed = 1, jitter = 0f))
        val faceA2 = insertFace(unitVec(seed = 1, jitter = 0.005f))
        val clusterA = clusterer.assign(faceA1, unitVec(seed = 1, jitter = 0f), 0.1f)
        clusterer.assign(faceA2, unitVec(seed = 1, jitter = 0.005f), 0.1f)

        val faceB1 = insertFace(unitVec(seed = 2, jitter = 0f))
        val faceB2 = insertFace(unitVec(seed = 2, jitter = 0.005f))
        clusterer.assign(faceB1, unitVec(seed = 2, jitter = 0f), 0.1f)
        clusterer.assign(faceB2, unitVec(seed = 2, jitter = 0.005f), 0.1f)

        // A stray identity-2 face parked in cluster A, same idiom as above,
        // so the first call has real work to do.
        val strayFace = insertFace(unitVec(seed = 2, jitter = 0.01f))
        db.faceDao().assignToCluster(strayFace, clusterA)
        val clusterAEntity = db.clusterDao().byId(clusterA)!!
        db.clusterDao().updateStats(
            id = clusterA,
            centroid = clusterAEntity.centroid,
            faceCount = clusterAEntity.faceCount + 1,
            coverFaceId = clusterAEntity.coverFaceId,
            updatedAt = 0L
        )

        clusterer.invalidate()
        val firstMoved = clusterer.refineAssignments()
        assertThat(firstMoved).isGreaterThan(0)

        val secondMoved = clusterer.refineAssignments()
        assertThat(secondMoved).isEqualTo(0)
    }

    @Test
    fun `refineAssignments hysteresis margin keeps a boundary face from flapping`() = runTest {
        // Two clusters built from exactly repeated vectors, so their
        // centroids land exactly on vA / vB (no averaging noise to reason
        // about): vA = e0, vB = cos(theta) e0 + sin(theta) e1 with
        // cos(theta) = 0.5, comfortably under both thresholds so A and B
        // never merge or cross-assign on their own.
        val vA = FloatArray(512).also { it[0] = 1f }
        val cosTheta = 0.5f
        val sinTheta = sqrt(0.75f)
        val vB = FloatArray(512).also { it[0] = cosTheta; it[1] = sinTheta }

        val faceA1 = insertFace(vA)
        val faceA2 = insertFace(vA)
        val clusterA = clusterer.assign(faceA1, vA, 0.1f)
        clusterer.assign(faceA2, vA, 0.1f)

        val faceB1 = insertFace(vB)
        val faceB2 = insertFace(vB)
        val clusterB = clusterer.assign(faceB1, vB, 0.1f)
        clusterer.assign(faceB2, vB, 0.1f)
        assertThat(clusterA).isNotEqualTo(clusterB)

        // Face C sits near A, tilted just enough toward B that B is the
        // strictly-nearest centroid, but by less than the 0.02 hysteresis
        // margin: cos(C, B) - cos(C, A) = sin(phi - 30 degrees) = gap, with
        // phi chosen so gap is exactly 0.01 via the angle-subtraction
        // identity below. Both similarities are comfortably above the 0.6
        // assign threshold, so only the margin condition is being exercised.
        val gap = 0.01f
        val thirtyDegrees = (kotlin.math.PI / 6.0).toFloat()
        val phi = thirtyDegrees + asin(gap)
        val vC = FloatArray(512).also { it[0] = cos(phi); it[1] = sin(phi) }
        val simToA = SimilarityMatcher.cosineSimilarity(vC, vA)
        val simToB = SimilarityMatcher.cosineSimilarity(vC, vB)
        assertThat(simToB).isGreaterThan(simToA)
        assertThat(simToB - simToA).isLessThan(0.02f)
        assertThat(simToB).isAtLeast(0.6f)

        // C is parked in A directly, as if that's where it already sits.
        val faceC = insertFace(vC)
        db.faceDao().assignToCluster(faceC, clusterA)

        clusterer.invalidate()
        val moved = clusterer.refineAssignments()

        assertThat(moved).isEqualTo(0)
        assertThat(db.faceDao().findById(faceC)!!.clusterId).isEqualTo(clusterA)
    }

    @Test
    fun `mergeClose anti-chaining guard blocks a bridge merge a centroid-only rule would make`() = runTest {
        // Cluster A grows to the chain-guard size (8) from identical vectors,
        // so its centroid sits exactly on e0 with no averaging noise to
        // reason about.
        val vA = FloatArray(512).also { it[0] = 1f }
        var clusterA = -1L
        repeat(8) {
            val faceId = insertFace(vA)
            clusterA = clusterer.assign(faceId, vA, 0.1f)
        }
        assertThat(db.clusterDao().byId(clusterA)!!.faceCount).isEqualTo(8)

        // Cluster C: a single face whose similarity to A's centroid (0.77)
        // clears the plain merge threshold (0.75) but not the chain-guard
        // threshold (0.75 + 0.05 = 0.80) that applies once either side has
        // >= 8 faces. Forced into its own cluster via a strict assign
        // threshold, same idiom used throughout this file.
        val cosPhi = 0.77f
        val sinPhi = sqrt(1f - cosPhi * cosPhi)
        val vC = FloatArray(512).also { it[0] = cosPhi; it[1] = sinPhi }
        assertThat(SimilarityMatcher.cosineSimilarity(vA, vC)).isGreaterThan(0.75f)
        assertThat(SimilarityMatcher.cosineSimilarity(vA, vC)).isLessThan(0.80f)

        val splitClusterer = FaceClusterer(
            clusterDao = db.clusterDao(),
            faceDao = db.faceDao(),
            assignThreshold = 0.9999f,
            mergeThreshold = 0.75f,
            now = { 0L }
        )
        val faceC = insertFace(vC)
        val clusterC = splitClusterer.assign(faceC, vC, 0.1f)
        assertThat(clusterA).isNotEqualTo(clusterC)
        assertThat(db.clusterDao().all()).hasSize(2)

        clusterer.mergeClose()

        // The guard must keep them apart: with the old centroid-only rule
        // (sim 0.77 >= mergeThreshold 0.75) these would have merged.
        val remaining = db.clusterDao().all()
        assertThat(remaining).hasSize(2)
        assertThat(db.clusterDao().byId(clusterA)!!.faceCount).isEqualTo(8)
        assertThat(db.clusterDao().byId(clusterC)!!.faceCount).isEqualTo(1)
    }

    private suspend fun insertFace(embedding: FloatArray): Long {
        val mediaStoreId = mediaStoreCounter++
        val photoId = db.photoDao().insert(
            PhotoEntity(
                mediaStoreId = mediaStoreId,
                uri = "content://media/$mediaStoreId",
                displayName = "test.jpg",
                dateTaken = 0L,
                dateModified = 0L,
                processedAt = 0L,
                faceCount = 1
            )
        )
        return db.faceDao().insert(
            FaceEntity(
                photoId = photoId,
                clusterId = null,
                bboxLeft = 0, bboxTop = 0, bboxRight = 100, bboxBottom = 100,
                embedding = Embeddings.toBytes(embedding),
                quality = 0.1f
            )
        )
    }

    /**
     * Deterministic unit-length vector. `seed` picks a direction in 512-D;
     * `jitter` nudges it slightly so identity-class vectors stay close (>0.99 cos sim).
     */
    private fun unitVec(seed: Int, jitter: Float = 0f, size: Int = 512): FloatArray {
        val v = FloatArray(size)
        val rand = java.util.Random(seed.toLong())
        for (i in 0 until size) v[i] = (rand.nextFloat() - 0.5f)
        if (jitter != 0f) {
            val jr = java.util.Random((seed * 7919 + 13).toLong())
            for (i in 0 until size) v[i] += (jr.nextFloat() - 0.5f) * jitter
        }
        var norm = 0f
        for (x in v) norm += x * x
        val n = sqrt(norm)
        if (n > 0f) for (i in v.indices) v[i] /= n
        return v
    }
}
