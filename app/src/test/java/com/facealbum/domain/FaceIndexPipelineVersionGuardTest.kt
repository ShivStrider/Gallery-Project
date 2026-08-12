package com.facealbum.domain

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.facealbum.config.FaceRecognitionConfig
import com.facealbum.data.FaceDetectorWrapper
import com.facealbum.data.FaceEmbedder
import com.facealbum.data.PhotoRepository
import com.facealbum.data.db.ClusterEntity
import com.facealbum.data.db.Embeddings
import com.facealbum.data.db.FaceAlbumDatabase
import com.facealbum.data.db.FaceEntity
import com.facealbum.data.db.PhotoEntity
import com.facealbum.data.prefs.UserPreferences
import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Faces are now warped onto the ArcFace template and normalized differently
 * before embedding, so embeddings produced before and after are not
 * comparable. A stored pipeline version older than
 * [FaceRecognitionConfig.EMBEDDING_PIPELINE_VERSION] must wipe every derived
 * face/cluster and force a full re-index; a matching version must leave
 * everything alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FaceIndexPipelineVersionGuardTest {

    private lateinit var db: FaceAlbumDatabase
    private lateinit var photoRepository: PhotoRepository
    private lateinit var prefs: UserPreferences
    private lateinit var useCase: FaceIndexUseCase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            FaceAlbumDatabase::class.java
        ).allowMainThreadQueries().build()
        photoRepository = mockk()
        prefs = mockk()
        useCase = FaceIndexUseCase(
            context = ApplicationProvider.getApplicationContext(),
            db = db,
            photoRepository = photoRepository,
            detectorFactory = { mockk<FaceDetectorWrapper>(relaxed = true) },
            embedderFactory = { mockk<FaceEmbedder>(relaxed = true) },
            prefs = prefs
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `stale stored version wipes faces and clusters and resets the reprocessing watermark`() = runTest {
        every { prefs.embeddingPipelineVersion } returns
            flowOf(FaceRecognitionConfig.LEGACY_EMBEDDING_PIPELINE_VERSION)
        coEvery { prefs.setEmbeddingPipelineVersion(any()) } just Runs

        val photoId = insertPhoto(mediaStoreId = 1, dateModified = 999_000L)
        val faceId = insertFace(photoId, seed = 1f)
        val clusterId = db.clusterDao().insert(
            ClusterEntity(
                displayName = "Alice",
                coverFaceId = faceId,
                faceCount = 1,
                centroid = Embeddings.toBytes(FloatArray(4) { 0.1f }),
                createdAt = 0L,
                updatedAt = 0L
            )
        )
        db.faceDao().assignToCluster(faceId, clusterId)

        val changed = useCase.invalidateIfPipelineVersionChanged()

        assertThat(changed).isTrue()
        assertThat(db.faceDao().findById(faceId)).isNull()
        assertThat(db.clusterDao().byId(clusterId)).isNull()

        val photo = db.photoDao().findByMediaStoreId(1)
        assertThat(photo).isNotNull()
        // Same row (id preserved) so any export-log photoId reference stays valid;
        // only the reprocessing watermark moves.
        assertThat(photo!!.id).isEqualTo(photoId)
        assertThat(photo.dateModified).isEqualTo(0L)
        assertThat(photo.displayName).isEqualTo("test_1.jpg")
        assertThat(photo.faceCount).isEqualTo(1)

        coVerify { prefs.setEmbeddingPipelineVersion(FaceRecognitionConfig.EMBEDDING_PIPELINE_VERSION) }
    }

    @Test
    fun `matching stored version leaves faces, clusters and watermarks untouched`() = runTest {
        every { prefs.embeddingPipelineVersion } returns
            flowOf(FaceRecognitionConfig.EMBEDDING_PIPELINE_VERSION)

        val photoId = insertPhoto(mediaStoreId = 1, dateModified = 999_000L)
        val faceId = insertFace(photoId, seed = 1f)
        val clusterId = db.clusterDao().insert(
            ClusterEntity(
                displayName = "Alice",
                coverFaceId = faceId,
                faceCount = 1,
                centroid = Embeddings.toBytes(FloatArray(4) { 0.1f }),
                createdAt = 0L,
                updatedAt = 0L
            )
        )
        db.faceDao().assignToCluster(faceId, clusterId)

        val changed = useCase.invalidateIfPipelineVersionChanged()

        assertThat(changed).isFalse()
        assertThat(db.faceDao().findById(faceId)).isNotNull()
        assertThat(db.clusterDao().byId(clusterId)).isNotNull()
        assertThat(db.photoDao().findByMediaStoreId(1)!!.dateModified).isEqualTo(999_000L)

        coVerify(exactly = 0) { prefs.setEmbeddingPipelineVersion(any()) }
    }

    private suspend fun insertPhoto(mediaStoreId: Long, dateModified: Long): Long =
        db.photoDao().insert(
            PhotoEntity(
                mediaStoreId = mediaStoreId,
                uri = "content://media/$mediaStoreId",
                displayName = "test_$mediaStoreId.jpg",
                dateTaken = 0L,
                dateModified = dateModified,
                processedAt = 0L,
                faceCount = 1
            )
        )

    private suspend fun insertFace(photoId: Long, seed: Float): Long =
        db.faceDao().insert(
            FaceEntity(
                photoId = photoId,
                clusterId = null,
                bboxLeft = 0, bboxTop = 0, bboxRight = 100, bboxBottom = 100,
                embedding = Embeddings.toBytes(FloatArray(4) { seed }),
                quality = 0.5f
            )
        )
}
