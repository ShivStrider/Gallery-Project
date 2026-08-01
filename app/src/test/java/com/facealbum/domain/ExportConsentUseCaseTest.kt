package com.facealbum.domain

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.facealbum.data.PhotoRepository
import com.facealbum.data.db.ExportItemEntity
import com.facealbum.data.db.ClusterEntity
import com.facealbum.data.db.Embeddings
import com.facealbum.data.db.ExportOperationEntity
import com.facealbum.data.db.FaceAlbumDatabase
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Consent is the moment a move becomes destructive, so these tests are about
 * what the app is allowed to conclude from the user's answer — and what it
 * must verify for itself regardless.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ExportConsentUseCaseTest {

    private lateinit var db: FaceAlbumDatabase
    private lateinit var photoRepository: PhotoRepository
    private lateinit var useCase: ExportConsentUseCase

    /** Real row id: `export_operations` and `albums` both FK to `clusters`. */
    private var clusterId: Long = 0

    @Before
    fun setUp() {
        // Block body: an expression body would inherit the last
        // statement's type, and JUnit requires @Before to return void.
        runBlocking {
            db = Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                FaceAlbumDatabase::class.java
            ).allowMainThreadQueries().build()
            clusterId = db.clusterDao().insert(
                ClusterEntity(
                    displayName = "Ada",
                    coverFaceId = null,
                    faceCount = 1,
                    centroid = Embeddings.toBytes(FloatArray(512)),
                    createdAt = 0L,
                    updatedAt = 0L
                )
            )
            photoRepository = mockk(relaxed = true)
            useCase = ExportConsentUseCase(
                context = ApplicationProvider.getApplicationContext(),
                db = db,
                photoRepository = photoRepository,
                now = { 5L }
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** Only verified copies may ever be offered to the delete prompt. */
    @Test
    fun `only verified items are offered for deletion`() = runTest {
        val opId = insertOperation()
        insertItem(opId, 1, ExportItemEntity.STATE_VERIFIED)
        insertItem(opId, 2, ExportItemEntity.STATE_COPIED)
        insertItem(opId, 3, ExportItemEntity.STATE_VERIFY_FAILED)
        insertItem(opId, 4, ExportItemEntity.STATE_COPY_FAILED)
        insertItem(opId, 5, ExportItemEntity.STATE_SKIPPED_DUPLICATE)

        val uris = useCase.deletableSourceUris(opId)

        assertThat(uris.map { it.toString() }).containsExactly(
            "content://media/external/images/media/1",
            "content://media/external/images/media/5"
        )
    }

    /**
     * The dialog result says the user accepted — it does not say the files
     * went. Anything MediaStore still returns is recorded as kept.
     */
    @Test
    fun `deletion is confirmed against MediaStore, not assumed from the dialog`() = runTest {
        val opId = insertOperation()
        insertItem(opId, 1, ExportItemEntity.STATE_VERIFIED)
        insertItem(opId, 2, ExportItemEntity.STATE_VERIFIED)
        // The system removed 1 but kept 2 (e.g. the user excluded it).
        coEvery { photoRepository.sourceStillExists(1L) } returns false
        coEvery { photoRepository.sourceStillExists(2L) } returns true

        val outcome = useCase.finalizeAfterConsent(opId, granted = true)

        assertThat(outcome.deletedCount).isEqualTo(1)
        assertThat(outcome.keptCount).isEqualTo(1)
        val byId = db.exportDao().itemsForOperation(opId).associateBy { it.sourceMediaStoreId }
        assertThat(byId[1L]!!.state).isEqualTo(ExportItemEntity.STATE_SOURCE_DELETED)
        assertThat(byId[2L]!!.state).isEqualTo(ExportItemEntity.STATE_DELETE_DENIED)
        assertThat(outcome.operationState)
            .isEqualTo(ExportOperationEntity.STATE_COMPLETED_WITH_ERRORS)
    }

    @Test
    fun `all sources deleted completes the operation cleanly`() = runTest {
        val opId = insertOperation()
        insertItem(opId, 1, ExportItemEntity.STATE_VERIFIED)
        insertItem(opId, 2, ExportItemEntity.STATE_VERIFIED)
        coEvery { photoRepository.sourceStillExists(any()) } returns false

        val outcome = useCase.finalizeAfterConsent(opId, granted = true)

        assertThat(outcome.deletedCount).isEqualTo(2)
        assertThat(outcome.keptCount).isEqualTo(0)
        assertThat(outcome.operationState).isEqualTo(ExportOperationEntity.STATE_COMPLETED)
    }

    /**
     * Declining must be completely safe: every original survives, the
     * verified copies remain, and nothing is queried for deletion.
     */
    @Test
    fun `declining consent keeps every original and every copy`() = runTest {
        val opId = insertOperation()
        insertItem(opId, 1, ExportItemEntity.STATE_VERIFIED, destUri = "content://dest/1")
        insertItem(opId, 2, ExportItemEntity.STATE_VERIFIED, destUri = "content://dest/2")

        val outcome = useCase.finalizeAfterConsent(opId, granted = false)

        assertThat(outcome.deletedCount).isEqualTo(0)
        assertThat(outcome.keptCount).isEqualTo(2)
        val items = db.exportDao().itemsForOperation(opId)
        assertThat(items.map { it.state }.toSet())
            .containsExactly(ExportItemEntity.STATE_DELETE_DENIED)
        // The copies are still recorded, so the album is still usable.
        assertThat(items.all { it.destUri != null }).isTrue()
        assertThat(db.albumDao().latestForCluster(clusterId)).isNotNull()
    }

    @Test
    fun `album history is written even when the user declines`() = runTest {
        val opId = insertOperation()
        insertItem(opId, 1, ExportItemEntity.STATE_VERIFIED)

        useCase.finalizeAfterConsent(opId, granted = false)

        val album = db.albumDao().latestForCluster(clusterId)
        assertThat(album).isNotNull()
        assertThat(album!!.albumName).isEqualTo("Ada")
        assertThat(album.photoCount).isEqualTo(1)
        // Trailing slash trimmed so history matches the copy-mode format.
        assertThat(album.exportedRelativePath).isEqualTo("Pictures/FaceAlbums/Ada")
    }

    @Test
    fun `consent batches are chunked for the system dialog`() = runTest {
        val opId = insertOperation()
        repeat(600) { i -> insertItem(opId, (i + 1).toLong(), ExportItemEntity.STATE_VERIFIED) }

        val chunks = useCase.chunkForConsent(useCase.deletableSourceUris(opId))

        assertThat(chunks.map { it.size }).containsExactly(250, 250, 100).inOrder()
        assertThat(chunks.sumOf { it.size }).isEqualTo(600)
    }

    @Test
    fun `empty selection produces no prompt`() = runTest {
        assertThat(useCase.chunkForConsent(emptyList())).isEmpty()
        assertThat(useCase.createDeleteRequest(emptyList())).isNull()
    }

    @Test
    fun `operations stranded before the prompt are recoverable`() = runTest {
        val stranded = insertOperation(state = ExportOperationEntity.STATE_AWAITING_DELETE_CONSENT)
        insertOperation(state = ExportOperationEntity.STATE_COMPLETED)

        val found = useCase.operationsAwaitingConsent()

        assertThat(found.map { it.id }).containsExactly(stranded)
    }

    /** A failed copy elsewhere must not be reported as a clean success. */
    @Test
    fun `pre-existing failures keep the operation flagged`() = runTest {
        val opId = insertOperation()
        insertItem(opId, 1, ExportItemEntity.STATE_VERIFIED)
        insertItem(opId, 2, ExportItemEntity.STATE_COPY_FAILED)
        coEvery { photoRepository.sourceStillExists(any()) } returns false

        val outcome = useCase.finalizeAfterConsent(opId, granted = true)

        assertThat(outcome.deletedCount).isEqualTo(1)
        assertThat(outcome.operationState)
            .isEqualTo(ExportOperationEntity.STATE_COMPLETED_WITH_ERRORS)
    }

    // --- helpers ---

    private suspend fun insertOperation(
        state: String = ExportOperationEntity.STATE_AWAITING_DELETE_CONSENT
    ): Long = db.exportDao().insertOperation(
        ExportOperationEntity(
            clusterId = clusterId,
            albumName = "Ada",
            destRelativePath = "Pictures/FaceAlbums/Ada/",
            mode = ExportOperationEntity.MODE_MOVE,
            state = state,
            totalCount = 1,
            createdAt = 0L,
            updatedAt = 0L
        )
    )

    private suspend fun insertItem(
        operationId: Long,
        mediaStoreId: Long,
        state: String,
        destUri: String? = null
    ) {
        db.exportDao().insertItems(
            listOf(
                ExportItemEntity(
                    operationId = operationId,
                    photoId = mediaStoreId,
                    sourceMediaStoreId = mediaStoreId,
                    sourceUri = "content://media/external/images/media/$mediaStoreId",
                    sourceDisplayName = "IMG_$mediaStoreId.jpg",
                    sourceRelativePath = "DCIM/Camera/",
                    sourceSizeBytes = 10L,
                    sourceSha256 = "hash",
                    destDisplayName = "IMG_$mediaStoreId.jpg",
                    destUri = destUri,
                    state = state,
                    errorCode = null,
                    updatedAt = 0L
                )
            )
        )
    }
}
