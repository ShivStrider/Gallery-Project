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
