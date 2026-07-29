package com.facealbum.domain

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.facealbum.data.FaceDetectorWrapper
import com.facealbum.data.FaceEmbedder
import com.facealbum.data.PhotoRepository
import com.facealbum.data.db.Embeddings
import com.facealbum.data.db.FaceAlbumDatabase
import com.facealbum.data.db.FaceEntity
import com.facealbum.data.db.PhotoEntity
import com.facealbum.data.prefs.UserPreferences
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.sqrt

/**
 * Photos deleted (or hidden by a narrowed Android 14 selection) outside the
 * app must disappear from the index, and the clusters they fed must be
 * repaired or removed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FaceIndexReconcileTest {

    private lateinit var db: FaceAlbumDatabase
    private lateinit var clusterer: FaceClusterer
    private lateinit var photoRepository: PhotoRepository
    private lateinit var useCase: FaceIndexUseCase

    @Before
    fun setUp() {
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
        photoRepository = mockk()
        useCase = FaceIndexUseCase(
            context = ApplicationProvider.getApplicationContext(),
            db = db,
            photoRepository = photoRepository,
            detector = mockk<FaceDetectorWrapper>(relaxed = true),
            embedder = mockk<FaceEmbedder>(relaxed = true),
            prefs = mockk<UserPreferences>(relaxed = true)
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `vanished photo is removed and its cluster shrinks`() = runTest {
        val keep = insertPhotoWithFace(mediaStoreId = 1, seed = 1)
        val gone = insertPhotoWithFace(mediaStoreId = 2, seed = 1, jitter = 0.01f)
        val clusterId = clusterer.assign(keep.faceId, keep.embedding, 0.1f)
        val sameCluster = clusterer.assign(gone.faceId, gone.embedding, 0.1f)
        assertThat(sameCluster).isEqualTo(clusterId)
        assertThat(db.clusterDao().byId(clusterId)!!.faceCount).isEqualTo(2)

        coEvery { photoRepository.queryAllMediaStoreIds() } returns setOf(1L)
        useCase.reconcileDeletedPhotos(clusterer)

        assertThat(db.photoDao().findByMediaStoreId(2)).isNull()
        assertThat(db.photoDao().findByMediaStoreId(1)).isNotNull()
        assertThat(db.faceDao().facesInCluster(clusterId)).hasSize(1)
        assertThat(db.clusterDao().byId(clusterId)!!.faceCount).isEqualTo(1)
    }

    @Test
    fun `cluster whose only photo vanished is deleted`() = runTest {
        val only = insertPhotoWithFace(mediaStoreId = 5, seed = 3)
        val clusterId = clusterer.assign(only.faceId, only.embedding, 0.1f)

        coEvery { photoRepository.queryAllMediaStoreIds() } returns emptySet()
        useCase.reconcileDeletedPhotos(clusterer)

        assertThat(db.photoDao().findByMediaStoreId(5)).isNull()
        assertThat(db.clusterDao().byId(clusterId)).isNull()
    }

    @Test
    fun `nothing changes when every photo is still visible`() = runTest {
        val a = insertPhotoWithFace(mediaStoreId = 1, seed = 1)
        val b = insertPhotoWithFace(mediaStoreId = 2, seed = 2)
        val cA = clusterer.assign(a.faceId, a.embedding, 0.1f)
        val cB = clusterer.assign(b.faceId, b.embedding, 0.1f)

        coEvery { photoRepository.queryAllMediaStoreIds() } returns setOf(1L, 2L)
        useCase.reconcileDeletedPhotos(clusterer)

        assertThat(db.photoDao().findByMediaStoreId(1)).isNotNull()
        assertThat(db.photoDao().findByMediaStoreId(2)).isNotNull()
        assertThat(db.clusterDao().byId(cA)).isNotNull()
        assertThat(db.clusterDao().byId(cB)).isNotNull()
    }

    private data class Inserted(val photoId: Long, val faceId: Long, val embedding: FloatArray)

    private suspend fun insertPhotoWithFace(
        mediaStoreId: Long,
        seed: Int,
        jitter: Float = 0f
    ): Inserted {
        val embedding = unitVec(seed, jitter)
        val photoId = db.photoDao().insert(
            PhotoEntity(
                mediaStoreId = mediaStoreId,
                uri = "content://media/$mediaStoreId",
                displayName = "test_$mediaStoreId.jpg",
                dateTaken = 0L,
                dateModified = 0L,
                processedAt = 0L,
                faceCount = 1
            )
        )
        val faceId = db.faceDao().insert(
            FaceEntity(
                photoId = photoId,
                clusterId = null,
                bboxLeft = 0, bboxTop = 0, bboxRight = 100, bboxBottom = 100,
                embedding = Embeddings.toBytes(embedding),
                quality = 0.1f
            )
        )
        return Inserted(photoId, faceId, embedding)
    }

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
