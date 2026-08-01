package com.facealbum.domain

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.facealbum.data.PhotoRepository
import com.facealbum.data.db.ExportItemEntity
import com.facealbum.data.db.ExportOperationEntity
import com.facealbum.data.db.FaceAlbumDatabase
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Destructive-operation safety at the executor seam. The properties asserted
 * here are the ones that stand between a bug and a user losing photos.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ExportExecutorTest {

    private lateinit var db: FaceAlbumDatabase
    private lateinit var photoRepository: PhotoRepository
    private lateinit var executor: ExportExecutor

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            FaceAlbumDatabase::class.java
        ).allowMainThreadQueries().build()
        photoRepository = mockk(relaxed = true)
        executor = ExportExecutor(
            context = ApplicationProvider.getApplicationContext(),
            db = db,
            photoRepository = photoRepository,
            now = { 1L }
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `copy-mode operation completes when every file verifies`() = runTest {
        val opId = insertOperation(ExportOperationEntity.MODE_COPY, count = 2)
        stubCopySuccess()
        stubVerify(PhotoRepository.VerifyResult.Verified)

        val state = executor.run(opId)

        assertThat(state).isEqualTo(ExportOperationEntity.STATE_COMPLETED)
        assertThat(db.exportDao().itemsForOperation(opId).map { it.state }.toSet())
            .containsExactly(ExportItemEntity.STATE_VERIFIED)
    }

    /**
     * A move must never complete on its own — it parks awaiting consent,
     * because deleting the sources is the user's decision, made in the
     * foreground through the system dialog.
     */
    @Test
    fun `move-mode operation stops at awaiting-consent, never deleting sources`() = runTest {
        val opId = insertOperation(ExportOperationEntity.MODE_MOVE, count = 2)
        stubCopySuccess()
        stubVerify(PhotoRepository.VerifyResult.Verified)

        val state = executor.run(opId)

        assertThat(state).isEqualTo(ExportOperationEntity.STATE_AWAITING_DELETE_CONSENT)
        assertThat(db.exportDao().itemsForOperation(opId).map { it.state }.toSet())
            .containsExactly(ExportItemEntity.STATE_VERIFIED)
        // No item reached SOURCE_DELETED, and nothing was deleted anywhere.
        assertThat(
            db.exportDao().itemsInState(opId, ExportItemEntity.STATE_SOURCE_DELETED)
        ).isEmpty()
    }

    /**
     * The central safety property: if the copy cannot be verified, the
     * destination is removed and the item is disqualified from deletion, so
     * the source survives.
     */
    @Test
    fun `failed verification removes the copy and disqualifies the source from deletion`() = runTest {
        val opId = insertOperation(ExportOperationEntity.MODE_MOVE, count = 1)
        stubCopySuccess()
        stubVerify(
            PhotoRepository.VerifyResult.Failed(PhotoRepository.VerifyError.CHECKSUM_MISMATCH)
        )

        executor.run(opId)

        val item = db.exportDao().itemsForOperation(opId).single()
        assertThat(item.state).isEqualTo(ExportItemEntity.STATE_VERIFY_FAILED)
        assertThat(item.errorCode).isEqualTo("CHECKSUM_MISMATCH")
        assertThat(item.destUri).isNull()
        coVerify { photoRepository.deleteOwnedDest(any()) }
        // The gate that authorises deletion must not return it.
        assertThat(db.exportDao().deletableItems(opId)).isEmpty()
    }

    @Test
    fun `copy failure leaves the source untouched and out of the delete set`() = runTest {
        val opId = insertOperation(ExportOperationEntity.MODE_MOVE, count = 1)
        coEvery { photoRepository.copyToAlbumChecked(any(), any(), any(), any()) } returns
            PhotoRepository.CheckedCopyResult.Failure(PhotoRepository.CopyToAlbumError.COPY_FAILED)

        val state = executor.run(opId)

        val item = db.exportDao().itemsForOperation(opId).single()
        assertThat(item.state).isEqualTo(ExportItemEntity.STATE_COPY_FAILED)
        assertThat(db.exportDao().deletableItems(opId)).isEmpty()
        assertThat(state).isEqualTo(ExportOperationEntity.STATE_COMPLETED_WITH_ERRORS)
    }

    /**
     * Resume after process death: work already verified is not redone, and
     * the run converges without duplicating or losing anything.
     */
    @Test
    fun `resume skips finished items and completes the rest`() = runTest {
        val opId = insertOperation(ExportOperationEntity.MODE_COPY, count = 3)
        val items = db.exportDao().itemsForOperation(opId)
        // Simulate a kill after the first file verified.
        db.exportDao().updateItemState(
            id = items[0].id,
            state = ExportItemEntity.STATE_VERIFIED,
            destUri = "content://dest/1",
            sourceSha256 = "hash",
            errorCode = null,
            updatedAt = 1L
        )
        stubCopySuccess()
        stubVerify(PhotoRepository.VerifyResult.Verified)

        val state = executor.run(opId)

        assertThat(state).isEqualTo(ExportOperationEntity.STATE_COMPLETED)
        assertThat(db.exportDao().itemsForOperation(opId).map { it.state }.toSet())
            .containsExactly(ExportItemEntity.STATE_VERIFIED)
        // Only the two unfinished files were copied again.
        coVerify(exactly = 2) {
            photoRepository.copyToAlbumChecked(any(), any(), any(), any())
        }
    }

    /** An item killed between copy and verify resumes at verification. */
    @Test
    fun `an item interrupted after copying is verified, not re-copied`() = runTest {
        val opId = insertOperation(ExportOperationEntity.MODE_COPY, count = 1)
        val item = db.exportDao().itemsForOperation(opId).single()
        db.exportDao().updateItemState(
            id = item.id,
            state = ExportItemEntity.STATE_COPIED,
            destUri = "content://dest/1",
            sourceSha256 = "hash",
            errorCode = null,
            updatedAt = 1L
        )
        stubCopySuccess()
        stubVerify(PhotoRepository.VerifyResult.Verified)

        executor.run(opId)

        assertThat(db.exportDao().itemsForOperation(opId).single().state)
            .isEqualTo(ExportItemEntity.STATE_VERIFIED)
        coVerify(exactly = 0) {
            photoRepository.copyToAlbumChecked(any(), any(), any(), any())
        }
    }

    /** Re-running a finished operation must not repeat its work. */
    @Test
    fun `re-running a completed operation is a no-op`() = runTest {
        val opId = insertOperation(ExportOperationEntity.MODE_COPY, count = 1)
        db.exportDao().updateOperationState(opId, ExportOperationEntity.STATE_COMPLETED, 1L)

        val state = executor.run(opId)

        assertThat(state).isEqualTo(ExportOperationEntity.STATE_COMPLETED)
        coVerify(exactly = 0) {
            photoRepository.copyToAlbumChecked(any(), any(), any(), any())
        }
    }

    @Test
    fun `progress is reported for every file`() = runTest {
        val opId = insertOperation(ExportOperationEntity.MODE_COPY, count = 3)
        stubCopySuccess()
        stubVerify(PhotoRepository.VerifyResult.Verified)
        val seen = mutableListOf<ExportExecutor.Progress>()

        executor.run(opId) { seen += it }

        assertThat(seen.map { it.done }).containsExactly(1, 2, 3).inOrder()
        assertThat(seen.last().total).isEqualTo(3)
        assertThat(seen.last().failed).isEqualTo(0)
    }

    // --- helpers ---

    private fun stubCopySuccess() {
        coEvery { photoRepository.copyToAlbumChecked(any(), any(), any(), any()) } answers {
            PhotoRepository.CheckedCopyResult.Success(
                uri = Uri.parse("content://dest/copy"),
                bytesCopied = 10L,
                sha256 = "hash",
                dedupHit = false
            )
        }
    }

    private fun stubVerify(result: PhotoRepository.VerifyResult) {
        coEvery { photoRepository.verifyExportedCopy(any(), any(), any()) } returns result
    }

    private suspend fun insertOperation(mode: String, count: Int): Long {
        val opId = db.exportDao().insertOperation(
            ExportOperationEntity(
                clusterId = null,
                albumName = "Album",
                destRelativePath = "Pictures/FaceAlbums/Album/",
                mode = mode,
                state = ExportOperationEntity.STATE_PENDING,
                totalCount = count,
                createdAt = 0L,
                updatedAt = 0L
            )
        )
        db.exportDao().insertItems(
            (1..count).map { i ->
                ExportItemEntity(
                    operationId = opId,
                    photoId = i.toLong(),
                    sourceMediaStoreId = i.toLong(),
                    sourceUri = "content://media/external/images/media/$i",
                    sourceDisplayName = "IMG_$i.jpg",
                    sourceRelativePath = "DCIM/Camera/",
                    sourceSizeBytes = 10L,
                    sourceSha256 = null,
                    destDisplayName = "IMG_$i.jpg",
                    destUri = null,
                    state = ExportItemEntity.STATE_PENDING,
                    errorCode = null,
                    updatedAt = 0L
                )
            }
        )
        return opId
    }
}
