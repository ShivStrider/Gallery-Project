package com.facealbum.domain

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.facealbum.data.PhotoRepository
import com.facealbum.data.db.ClusterEntity
import com.facealbum.data.db.Embeddings
import com.facealbum.data.db.ExportItemEntity
import com.facealbum.data.db.ExportOperationEntity
import com.facealbum.data.db.FaceAlbumDatabase
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
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
 * Undo is the last line of defence after a move. The behaviour that matters
 * is what it refuses to delete when something goes wrong.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ExportUndoUseCaseTest {

    private lateinit var db: FaceAlbumDatabase
    private lateinit var photoRepository: PhotoRepository
    private lateinit var useCase: ExportUndoUseCase
    private var clusterId: Long = 0

    @Before
    fun setUp() = runBlocking {
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
        useCase = ExportUndoUseCase(
            context = ApplicationProvider.getApplicationContext(),
            db = db,
            photoRepository = photoRepository,
            now = { 9L }
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `copy-mode undo removes the copies it created`() = runTest {
        val opId = insertOperation(ExportOperationEntity.MODE_COPY)
        insertItem(opId, 1, ExportItemEntity.STATE_VERIFIED, destUri = "content://dest/1")
        insertItem(opId, 2, ExportItemEntity.STATE_VERIFIED, destUri = "content://dest/2")
        coEvery { photoRepository.deleteOwnedDest(any()) } returns true

        val result = useCase.undo(opId)

        assertThat(result.removedCopies).isEqualTo(2)
        assertThat(result.restoredCount).isEqualTo(0)
        assertThat(db.exportDao().itemsForOperation(opId).map { it.state }.toSet())
            .containsExactly(ExportItemEntity.STATE_UNDONE)
        assertThat(db.exportDao().operationById(opId)!!.state)
            .isEqualTo(ExportOperationEntity.STATE_UNDONE)
    }

    @Test
    fun `deleted originals are restored and their copies removed`() = runTest {
        val opId = insertOperation(ExportOperationEntity.MODE_MOVE)
        insertItem(opId, 1, ExportItemEntity.STATE_SOURCE_DELETED, destUri = "content://dest/1")
        coEvery {
            photoRepository.restoreFromCopy(any(), any(), any(), any())
        } returns PhotoRepository.CheckedCopyResult.Success(
            uri = Uri.parse("content://media/restored/1"),
            bytesCopied = 10L,
            sha256 = "hash",
            dedupHit = false
        )
        coEvery { photoRepository.deleteOwnedDest(any()) } returns true

        val result = useCase.undo(opId)

        assertThat(result.restoredCount).isEqualTo(1)
        val item = db.exportDao().itemsForOperation(opId).single()
        assertThat(item.state).isEqualTo(ExportItemEntity.STATE_RESTORED)
        // Restored to the original folder and filename, not the album.
        coVerify {
            photoRepository.restoreFromCopy(
                sourceCopyUri = any(),
                targetRelativePath = "DCIM/Camera/",
                targetDisplayName = "IMG_1.jpg",
                expectedSha256 = "hash"
            )
        }
    }

    /**
     * The one unrecoverable mistake available to undo: deleting the exported
     * copy after failing to restore, when that copy is the only version left.
     */
    @Test
    fun `a failed restore keeps the exported copy`() = runTest {
        val opId = insertOperation(ExportOperationEntity.MODE_MOVE)
        insertItem(opId, 1, ExportItemEntity.STATE_SOURCE_DELETED, destUri = "content://dest/1")
        coEvery {
            photoRepository.restoreFromCopy(any(), any(), any(), any())
        } returns PhotoRepository.CheckedCopyResult.Failure(
            PhotoRepository.CopyToAlbumError.COPY_FAILED
        )

        val result = useCase.undo(opId)

        assertThat(result.failedCount).isEqualTo(1)
        assertThat(result.restoredCount).isEqualTo(0)
        coVerify(exactly = 0) { photoRepository.deleteOwnedDest(any()) }
        val item = db.exportDao().itemsForOperation(opId).single()
        assertThat(item.state).isEqualTo(ExportItemEntity.STATE_SOURCE_DELETED)
        assertThat(item.destUri).isEqualTo("content://dest/1")
        assertThat(item.errorCode).isEqualTo("COPY_FAILED")
    }

    /**
     * A dedup hit's destination existed before the export, so undo must not
     * remove it — that file is not ours to delete.
     */
    @Test
    fun `undo never deletes a destination that pre-dated the export`() = runTest {
        val opId = insertOperation(ExportOperationEntity.MODE_COPY)
        insertItem(
            opId, 1, ExportItemEntity.STATE_SKIPPED_DUPLICATE, destUri = "content://dest/pre-existing"
        )

        val result = useCase.undo(opId)

        coVerify(exactly = 0) { photoRepository.deleteOwnedDest(any()) }
        assertThat(result.removedCopies).isEqualTo(0)
        assertThat(db.exportDao().itemsForOperation(opId).single().state)
            .isEqualTo(ExportItemEntity.STATE_UNDONE)
    }

    @Test
    fun `an item with no original path is reported rather than silently dropped`() = runTest {
        val opId = insertOperation(ExportOperationEntity.MODE_MOVE)
        insertItem(
            opId, 1, ExportItemEntity.STATE_SOURCE_DELETED,
            destUri = "content://dest/1", relativePath = null
        )

        val result = useCase.undo(opId)

        assertThat(result.failedCount).isEqualTo(1)
        val item = db.exportDao().itemsForOperation(opId).single()
        assertThat(item.errorCode).isEqualTo("NOT_RESTORABLE")
        // The copy is preserved so the photo still exists somewhere.
        assertThat(item.destUri).isEqualTo("content://dest/1")
    }

    @Test
    fun `failed items have nothing to undo`() = runTest {
        val opId = insertOperation(ExportOperationEntity.MODE_COPY)
        insertItem(opId, 1, ExportItemEntity.STATE_COPY_FAILED)
        insertItem(opId, 2, ExportItemEntity.STATE_VERIFY_FAILED)

        val result = useCase.undo(opId)

        assertThat(result.restoredCount).isEqualTo(0)
        assertThat(result.removedCopies).isEqualTo(0)
        assertThat(result.failedCount).isEqualTo(0)
        coVerify(exactly = 0) { photoRepository.deleteOwnedDest(any()) }
    }

    // --- helpers ---

    private suspend fun insertOperation(mode: String): Long =
        db.exportDao().insertOperation(
            ExportOperationEntity(
                clusterId = clusterId,
                albumName = "Ada",
                destRelativePath = "Pictures/FaceAlbums/Ada/",
                mode = mode,
                state = ExportOperationEntity.STATE_COMPLETED,
                totalCount = 1,
                createdAt = 0L,
                updatedAt = 0L
            )
        )

    private suspend fun insertItem(
        operationId: Long,
        mediaStoreId: Long,
        state: String,
        destUri: String? = null,
        relativePath: String? = "DCIM/Camera/"
    ) {
        db.exportDao().insertItems(
            listOf(
                ExportItemEntity(
                    operationId = operationId,
                    photoId = mediaStoreId,
                    sourceMediaStoreId = mediaStoreId,
                    sourceUri = "content://media/external/images/media/$mediaStoreId",
                    sourceDisplayName = "IMG_$mediaStoreId.jpg",
                    sourceRelativePath = relativePath,
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
