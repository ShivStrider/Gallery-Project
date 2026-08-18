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
 * The repair pass rewrites rows describing the user's real photos, so the
 * behaviour worth pinning down is mostly what it declines to touch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ExportDateRepairUseCaseTest {

    private lateinit var db: FaceAlbumDatabase
    private lateinit var photoRepository: PhotoRepository
    private lateinit var useCase: ExportDateRepairUseCase
    private var clusterId: Long = 0

    private val takenMs = 1_600_000_000_000L
    private val modifiedSec = 1_600_000_100L
    private val exportStampMs = 1_700_000_000_000L
    private val exportStampSec = 1_700_000_000L

    @Before
    fun setUp() {
        // Block body: an expression body would inherit the last statement's
        // type, and JUnit requires @Before to return void.
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
            useCase = ExportDateRepairUseCase(db = db, photoRepository = photoRepository)
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `a copy stamped at export time is repaired from the surviving original`() = runTest {
        val opId = insertOperation()
        insertItem(opId, 1, ExportItemEntity.STATE_VERIFIED, destUri = "content://dest/1")
        // The original still has the true capture date; the copy carries the
        // moment of export, which is the bug being repaired.
        coEvery { photoRepository.queryDates(sourceUri(1)) } returns
            PhotoRepository.SourceDates(takenMs, modifiedSec)
        coEvery { photoRepository.queryDates(Uri.parse("content://dest/1")) } returns
            PhotoRepository.SourceDates(exportStampMs, exportStampSec)
        coEvery { photoRepository.updateMediaDates(any(), any()) } returns true

        val result = useCase.run()

        assertThat(result.examined).isEqualTo(1)
        assertThat(result.repaired).isEqualTo(1)
        coVerify {
            photoRepository.updateMediaDates(
                Uri.parse("content://dest/1"),
                PhotoRepository.SourceDates(takenMs, modifiedSec)
            )
        }
    }

    @Test
    fun `an already-correct copy is left untouched so the pass is idempotent`() = runTest {
        val opId = insertOperation()
        insertItem(opId, 1, ExportItemEntity.STATE_VERIFIED, destUri = "content://dest/1")
        coEvery { photoRepository.queryDates(any()) } returns
            PhotoRepository.SourceDates(takenMs, modifiedSec)

        val result = useCase.run()

        assertThat(result.alreadyCorrect).isEqualTo(1)
        assertThat(result.repaired).isEqualTo(0)
        coVerify(exactly = 0) { photoRepository.updateMediaDates(any(), any()) }
    }

    @Test
    fun `when the original is gone the date is recovered from the copy's own EXIF`() = runTest {
        val opId = insertOperation()
        insertItem(opId, 1, ExportItemEntity.STATE_SOURCE_DELETED, destUri = "content://dest/1")
        // A move deleted the source, so its row yields nothing.
        coEvery { photoRepository.queryDates(sourceUri(1)) } returns
            PhotoRepository.SourceDates(null, null)
        coEvery { photoRepository.queryDates(Uri.parse("content://dest/1")) } returns
            PhotoRepository.SourceDates(exportStampMs, exportStampSec)
        coEvery { photoRepository.readExifDateTakenMs(Uri.parse("content://dest/1")) } returns takenMs
        coEvery { photoRepository.updateMediaDates(any(), any()) } returns true

        val result = useCase.run()

        assertThat(result.repaired).isEqualTo(1)
        // DATE_TAKEN is millis, DATE_MODIFIED is seconds. Deriving one from
        // the other is the easiest way to reintroduce the original bug in
        // mirror image, so the conversion is asserted rather than assumed.
        coVerify {
            photoRepository.updateMediaDates(
                Uri.parse("content://dest/1"),
                PhotoRepository.SourceDates(takenMs, takenMs / 1000L)
            )
        }
    }

    @Test
    fun `a file with no surviving source and no EXIF is reported rather than guessed at`() = runTest {
        val opId = insertOperation()
        insertItem(opId, 1, ExportItemEntity.STATE_SOURCE_DELETED, destUri = "content://dest/1")
        coEvery { photoRepository.queryDates(any()) } returns
            PhotoRepository.SourceDates(null, null)
        coEvery { photoRepository.readExifDateTakenMs(any()) } returns null

        val result = useCase.run()

        assertThat(result.unrepairable).isEqualTo(1)
        assertThat(result.repaired).isEqualTo(0)
        // A wrong-but-plausible date is harder to notice than an obviously
        // wrong one, so writing nothing is the correct outcome here.
        coVerify(exactly = 0) { photoRepository.updateMediaDates(any(), any()) }
    }

    @Test
    fun `items whose destination was removed by a failure or undo are not examined`() = runTest {
        val opId = insertOperation()
        insertItem(opId, 1, ExportItemEntity.STATE_COPY_FAILED, destUri = null)
        insertItem(opId, 2, ExportItemEntity.STATE_VERIFY_FAILED, destUri = null)
        insertItem(opId, 3, ExportItemEntity.STATE_UNDONE, destUri = "content://dest/3")
        insertItem(opId, 4, ExportItemEntity.STATE_RESTORED, destUri = "content://dest/4")

        val result = useCase.run()

        assertThat(result.examined).isEqualTo(0)
        assertThat(result.isEmpty).isTrue()
        coVerify(exactly = 0) { photoRepository.updateMediaDates(any(), any()) }
    }

    @Test
    fun `one failure does not abandon the rest of the album`() = runTest {
        val opId = insertOperation()
        insertItem(opId, 1, ExportItemEntity.STATE_VERIFIED, destUri = "content://dest/1")
        insertItem(opId, 2, ExportItemEntity.STATE_VERIFIED, destUri = "content://dest/2")
        coEvery { photoRepository.queryDates(sourceUri(1)) } returns
            PhotoRepository.SourceDates(takenMs, modifiedSec)
        coEvery { photoRepository.queryDates(sourceUri(2)) } returns
            PhotoRepository.SourceDates(takenMs, modifiedSec)
        coEvery { photoRepository.queryDates(Uri.parse("content://dest/1")) } returns
            PhotoRepository.SourceDates(exportStampMs, exportStampSec)
        coEvery { photoRepository.queryDates(Uri.parse("content://dest/2")) } returns
            PhotoRepository.SourceDates(exportStampMs, exportStampSec)
        coEvery {
            photoRepository.updateMediaDates(Uri.parse("content://dest/1"), any())
        } throws SecurityException("denied")
        coEvery {
            photoRepository.updateMediaDates(Uri.parse("content://dest/2"), any())
        } returns true

        val result = useCase.run()

        assertThat(result.examined).isEqualTo(2)
        assertThat(result.failed).isEqualTo(1)
        assertThat(result.repaired).isEqualTo(1)
    }

    @Test
    fun `every examined item lands in exactly one tally`() = runTest {
        val opId = insertOperation()
        insertItem(opId, 1, ExportItemEntity.STATE_VERIFIED, destUri = "content://dest/1")
        insertItem(opId, 2, ExportItemEntity.STATE_SKIPPED_DUPLICATE, destUri = "content://dest/2")
        insertItem(opId, 3, ExportItemEntity.STATE_DELETE_DENIED, destUri = "content://dest/3")
        coEvery { photoRepository.queryDates(any()) } returns
            PhotoRepository.SourceDates(takenMs, modifiedSec)

        val result = useCase.run()

        assertThat(result.examined).isEqualTo(3)
        assertThat(result.repaired + result.alreadyCorrect + result.unrepairable + result.failed)
            .isEqualTo(result.examined)
    }

    private fun sourceUri(mediaStoreId: Long): Uri =
        Uri.parse("content://media/external/images/media/$mediaStoreId")

    private suspend fun insertOperation(): Long =
        db.exportDao().insertOperation(
            ExportOperationEntity(
                clusterId = clusterId,
                albumName = "Ada",
                destRelativePath = "Pictures/FaceAlbums/Ada/",
                mode = ExportOperationEntity.MODE_COPY,
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
        destUri: String?
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
