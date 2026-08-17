package com.facealbum.data

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

// Uri.parse() at field init needs Android's real implementation, which is
// only present in the JVM test JAR when the test runs under Robolectric.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PhotoRepositoryTest {
    private val context: Context = mockk()
    private val resolver: ContentResolver = mockk(relaxed = true)
    private val sourceUri: Uri = Uri.parse("content://source/photo")
    private val destUri: Uri = Uri.parse("content://media/external/images/media/123")

    @Test
    fun `copyToAlbumWithResult uses source mime type`() = runTest {
        setupSuccessFlow("image/png", ByteArrayInputStream(byteArrayOf(1, 2, 3)), CapturingOutputStream())

        val repo = PhotoRepository(context)
        val result = repo.copyToAlbumWithResult(sourceUri, "Person", "sample.png")

        assertTrue(result is PhotoRepository.CopyToAlbumResult.Success)
        verify {
            resolver.insert(any(), withArg<ContentValues> {
                assertEquals("image/png", it.getAsString("mime_type"))
            })
        }
    }

    @Test
    fun `copyToAlbumWithResult rolls back on copy failure`() = runTest {
        setupSuccessFlow(
            "image/jpeg",
            object : InputStream() { override fun read(): Int = throw IOException("boom") },
            CapturingOutputStream()
        )

        val repo = PhotoRepository(context)
        val result = repo.copyToAlbumWithResult(sourceUri, "Person", "a.jpg")

        assertEquals(PhotoRepository.CopyToAlbumResult.Failure(PhotoRepository.CopyToAlbumError.COPY_FAILED), result)
        verify { resolver.delete(destUri, null, null) }
    }

    @Test
    fun `copyToAlbumWithResult maps source open failure`() = runTest {
        every { context.contentResolver } returns resolver
        every { resolver.getType(sourceUri) } returns "image/jpeg"
        every { resolver.insert(any(), any()) } returns destUri
        every { resolver.openInputStream(sourceUri) } returns null

        val repo = PhotoRepository(context)
        val result = repo.copyToAlbumWithResult(sourceUri, "Person", "a.jpg")

        assertEquals(PhotoRepository.CopyToAlbumResult.Failure(PhotoRepository.CopyToAlbumError.SOURCE_OPEN_FAILED), result)
        verify { resolver.delete(destUri, null, null) }
    }

    @Test
    fun `copyToAlbumWithResult maps finalize failure and rolls back`() = runTest {
        setupSuccessFlow("image/heic", ByteArrayInputStream(byteArrayOf(9)), CapturingOutputStream(), 0)

        val repo = PhotoRepository(context)
        val result = repo.copyToAlbumWithResult(sourceUri, "Person", "b.heic")

        assertEquals(PhotoRepository.CopyToAlbumResult.Failure(PhotoRepository.CopyToAlbumError.FINALIZE_FAILED), result)
        verify { resolver.delete(destUri, null, null) }
    }

    @Test
    fun `copyToAlbumWithResult creates unique names for duplicates`() = runTest {
        // Same filename ("same.jpg"), two different source photos. The repo
        // must produce distinct MediaStore DISPLAY_NAME values so the two
        // exported files don't collide on disk. It does that by folding a
        // stable hash of the source URI into the stored filename.
        val sourceA = Uri.parse("content://source/photo/one")
        val sourceB = Uri.parse("content://source/photo/two")
        every { context.contentResolver } returns resolver
        every { resolver.getType(sourceA) } returns "image/jpeg"
        every { resolver.getType(sourceB) } returns "image/jpeg"
        every { resolver.insert(any(), any()) } returns destUri
        every { resolver.openInputStream(sourceA) } returns ByteArrayInputStream(byteArrayOf(1))
        every { resolver.openInputStream(sourceB) } returns ByteArrayInputStream(byteArrayOf(2))
        every { resolver.openOutputStream(destUri) } returns CapturingOutputStream()
        every { resolver.update(destUri, any(), null, null) } returns 1

        val repo = PhotoRepository(context)
        repo.copyToAlbumWithResult(sourceA, "Person", "same.jpg")
        repo.copyToAlbumWithResult(sourceB, "Person", "same.jpg")

        val insertedNames = mutableListOf<String>()
        verify(exactly = 2) {
            resolver.insert(any(), withArg {
                insertedNames += it.getAsString("_display_name")
            })
        }
        assertTrue(
            "Expected distinct display names, got $insertedNames",
            insertedNames[0] != insertedNames[1]
        )
    }

    // --- Verified-copy primitives (the gate that authorises source deletion) ---

    @Test
    fun `checked copy reports the checksum of the bytes written`() = runTest {
        val payload = byteArrayOf(9, 8, 7, 6, 5)
        setupSuccessFlow("image/jpeg", ByteArrayInputStream(payload), CapturingOutputStream())

        val result = PhotoRepository(context)
            .copyToAlbumChecked(sourceUri, "Person", "dest.jpg")

        assertTrue(result is PhotoRepository.CheckedCopyResult.Success)
        val success = result as PhotoRepository.CheckedCopyResult.Success
        assertEquals(payload.size.toLong(), success.bytesCopied)
        assertEquals(sha256Hex(payload), success.sha256)
        assertEquals(false, success.dedupHit)
    }

    // --- Source date carry-through (the export-timeline bug) ---
    //
    // DATE_TAKEN is milliseconds since epoch; DATE_MODIFIED (and DATE_ADDED,
    // never written here) is seconds since epoch. Values below are chosen so
    // a units mix-up (multiplying/dividing by 1000 on the wrong field) would
    // fail these assertions rather than passing by coincidence.

    @Test
    fun `copyToAlbumChecked carries source DATE_TAKEN ms and DATE_MODIFIED sec onto insert and finalize`() = runTest {
        val payload = byteArrayOf(1, 2, 3)
        val dateTakenMs = 1_700_000_000_123L
        val dateModifiedSec = 1_700_000_500L
        setupSuccessFlow("image/jpeg", ByteArrayInputStream(payload), CapturingOutputStream())
        every { resolver.query(sourceUri, any(), null, null, null) } returns
            datesCursor(dateTakenMs, dateModifiedSec)

        val result = PhotoRepository(context)
            .copyToAlbumChecked(sourceUri, "Person", "dest.jpg")

        assertTrue(result is PhotoRepository.CheckedCopyResult.Success)
        verify {
            resolver.insert(any(), withArg<ContentValues> {
                assertEquals(dateTakenMs, it.getAsLong(MediaStore.Images.Media.DATE_TAKEN))
                assertEquals(dateModifiedSec, it.getAsLong(MediaStore.Images.Media.DATE_MODIFIED))
            })
        }
        // Re-asserted at IS_PENDING clear too, in case an OEM rewrote it
        // while the file was being finalized.
        verify {
            resolver.update(destUri, withArg<ContentValues> {
                assertEquals(dateTakenMs, it.getAsLong(MediaStore.Images.Media.DATE_TAKEN))
                assertEquals(dateModifiedSec, it.getAsLong(MediaStore.Images.Media.DATE_MODIFIED))
            }, null, null)
        }
    }

    @Test
    fun `copyToAlbumChecked falls back to DATE_MODIFIED-derived ms when source DATE_TAKEN is null`() = runTest {
        setupSuccessFlow("image/jpeg", ByteArrayInputStream(byteArrayOf(1)), CapturingOutputStream())
        val dateModifiedSec = 1_700_000_500L
        every { resolver.query(sourceUri, any(), null, null, null) } returns
            datesCursor(dateTakenMs = null, dateModifiedSec = dateModifiedSec)

        PhotoRepository(context).copyToAlbumChecked(sourceUri, "Person", "dest.jpg")

        verify {
            resolver.insert(any(), withArg<ContentValues> {
                // Fallback must convert seconds -> milliseconds, not copy verbatim.
                assertEquals(dateModifiedSec * 1000L, it.getAsLong(MediaStore.Images.Media.DATE_TAKEN))
                assertEquals(dateModifiedSec, it.getAsLong(MediaStore.Images.Media.DATE_MODIFIED))
            })
        }
    }

    @Test
    fun `copyToAlbumChecked treats a zero DATE_TAKEN as absent rather than writing 1970`() = runTest {
        setupSuccessFlow("image/jpeg", ByteArrayInputStream(byteArrayOf(1)), CapturingOutputStream())
        val dateModifiedSec = 1_700_000_500L
        every { resolver.query(sourceUri, any(), null, null, null) } returns
            datesCursor(dateTakenMs = 0L, dateModifiedSec = dateModifiedSec)

        PhotoRepository(context).copyToAlbumChecked(sourceUri, "Person", "dest.jpg")

        verify {
            resolver.insert(any(), withArg<ContentValues> {
                assertEquals(dateModifiedSec * 1000L, it.getAsLong(MediaStore.Images.Media.DATE_TAKEN))
            })
        }
    }

    @Test
    fun `copyToAlbumChecked omits date columns entirely when the source has neither`() = runTest {
        setupSuccessFlow("image/jpeg", ByteArrayInputStream(byteArrayOf(1)), CapturingOutputStream())
        every { resolver.query(sourceUri, any(), null, null, null) } returns
            datesCursor(dateTakenMs = null, dateModifiedSec = null)

        PhotoRepository(context).copyToAlbumChecked(sourceUri, "Person", "dest.jpg")

        verify {
            resolver.insert(any(), withArg<ContentValues> {
                assertTrue(!it.containsKey(MediaStore.Images.Media.DATE_TAKEN))
                assertTrue(!it.containsKey(MediaStore.Images.Media.DATE_MODIFIED))
            })
        }
    }

    @Test
    fun `restoreFromCopy carries the exported copy's dates onto the restored row`() = runTest {
        val payload = byteArrayOf(4, 5, 6)
        val dateTakenMs = 1_699_999_000_456L
        val dateModifiedSec = 1_699_999_000L
        val copyUri = Uri.parse("content://media/external/images/media/900")
        val restoredUri = Uri.parse("content://media/external/images/media/901")

        every { context.contentResolver } returns resolver
        every { resolver.getType(copyUri) } returns "image/jpeg"
        every { resolver.query(copyUri, any(), null, null, null) } returns
            datesCursor(dateTakenMs, dateModifiedSec)
        every { resolver.insert(any(), any()) } returns restoredUri
        every { resolver.openInputStream(copyUri) } returns ByteArrayInputStream(payload)
        every { resolver.openOutputStream(restoredUri) } returns CapturingOutputStream()
        every { resolver.update(restoredUri, any(), null, null) } returns 1

        val result = PhotoRepository(context).restoreFromCopy(
            sourceCopyUri = copyUri,
            targetRelativePath = "Pictures/Camera",
            targetDisplayName = "orig.jpg",
            expectedSha256 = sha256Hex(payload)
        )

        assertTrue(result is PhotoRepository.CheckedCopyResult.Success)
        verify {
            resolver.insert(any(), withArg<ContentValues> {
                assertEquals(dateTakenMs, it.getAsLong(MediaStore.Images.Media.DATE_TAKEN))
                assertEquals(dateModifiedSec, it.getAsLong(MediaStore.Images.Media.DATE_MODIFIED))
            })
        }
        verify {
            resolver.update(restoredUri, withArg<ContentValues> {
                assertEquals(dateTakenMs, it.getAsLong(MediaStore.Images.Media.DATE_TAKEN))
                assertEquals(dateModifiedSec, it.getAsLong(MediaStore.Images.Media.DATE_MODIFIED))
            }, null, null)
        }
    }

    // --- Photo details / size reads (metadata sheet, album size) ---

    @Test
    fun `queryPhotoDetails reads size dimensions dates path and mime type`() = runTest {
        val mediaId = 42L
        every { context.contentResolver } returns resolver
        every {
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                any(),
                "${MediaStore.Images.Media._ID} = ?",
                arrayOf(mediaId.toString()),
                null
            )
        } returns photoDetailsCursor(
            id = mediaId,
            displayName = "photo.jpg",
            sizeBytes = 123_456L,
            width = 4032,
            height = 3024,
            dateTakenMs = 1_700_000_000_123L,
            dateModifiedSec = 1_700_000_500L,
            relativePath = "Pictures/Camera/",
            mimeType = "image/jpeg"
        )

        val details = PhotoRepository(context).queryPhotoDetails(mediaId)

        assertEquals(mediaId, details?.mediaStoreId)
        assertEquals("photo.jpg", details?.displayName)
        assertEquals(123_456L, details?.sizeBytes)
        assertEquals(4032, details?.width)
        assertEquals(3024, details?.height)
        // DATE_TAKEN is milliseconds; DATE_MODIFIED is seconds. A units mix-up
        // here would fail this assertion rather than passing by coincidence.
        assertEquals(1_700_000_000_123L, details?.dateTakenMs)
        assertEquals(1_700_000_500L, details?.dateModifiedSec)
        assertEquals("Pictures/Camera/", details?.relativePath)
        assertEquals("image/jpeg", details?.mimeType)
    }

    @Test
    fun `queryPhotoDetails returns null when the source row is gone`() = runTest {
        every { context.contentResolver } returns resolver
        every {
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                any(),
                "${MediaStore.Images.Media._ID} = ?",
                arrayOf("999"),
                null
            )
        } returns MatrixCursor(photoDetailsColumns())

        val details = PhotoRepository(context).queryPhotoDetails(999L)

        assertEquals(null, details)
    }

    @Test
    fun `queryTotalSizeBytes returns zero for an empty id list without querying`() = runTest {
        every { context.contentResolver } returns resolver

        val total = PhotoRepository(context).queryTotalSizeBytes(emptyList())

        assertEquals(0L, total)
        verify(exactly = 0) { resolver.query(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `queryTotalSizeBytes chunks above nine hundred ids and sums across chunks`() = runTest {
        // Nine hundred fifty ids splits into a nine-hundred-id chunk and a
        // fifty-id chunk under the SQLite bind-variable limit.
        val ids = (1L..950L).toList()
        every { context.contentResolver } returns resolver
        every {
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media.SIZE),
                any(),
                match<Array<String>> { it.size == 900 },
                null
            )
        } returns sizeCursorFor(1L..900L)
        every {
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media.SIZE),
                any(),
                match<Array<String>> { it.size == 50 },
                null
            )
        } returns sizeCursorFor(901L..950L)

        val total = PhotoRepository(context).queryTotalSizeBytes(ids)

        // Sizes are stood in for by the id itself, so the expected sum is
        // just the triangular number of 1..950.
        assertEquals(950L * 951L / 2L, total)
        verify(exactly = 2) {
            resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, any(), any(), any(), null)
        }
    }

    private fun photoDetailsColumns() = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.SIZE,
        MediaStore.Images.Media.WIDTH,
        MediaStore.Images.Media.HEIGHT,
        MediaStore.Images.Media.DATE_TAKEN,
        MediaStore.Images.Media.DATE_MODIFIED,
        MediaStore.Images.Media.RELATIVE_PATH,
        MediaStore.Images.Media.MIME_TYPE
    )

    private fun photoDetailsCursor(
        id: Long,
        displayName: String,
        sizeBytes: Long,
        width: Int,
        height: Int,
        dateTakenMs: Long,
        dateModifiedSec: Long,
        relativePath: String,
        mimeType: String
    ) = MatrixCursor(photoDetailsColumns()).apply {
        addRow(
            arrayOf<Any?>(
                id, displayName, sizeBytes, width, height,
                dateTakenMs, dateModifiedSec, relativePath, mimeType
            )
        )
    }

    private fun sizeCursorFor(ids: LongRange) =
        MatrixCursor(arrayOf(MediaStore.Images.Media.SIZE)).apply {
            ids.forEach { id -> addRow(arrayOf<Any>(id)) }
        }

    private fun datesCursor(dateTakenMs: Long?, dateModifiedSec: Long?) =
        MatrixCursor(arrayOf(MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_MODIFIED)).apply {
            addRow(arrayOf<Any?>(dateTakenMs, dateModifiedSec))
        }

    @Test
    fun `verification passes when size and checksum match`() = runTest {
        val payload = byteArrayOf(1, 2, 3, 4)
        setupVerifyFlow(payload, reportedSize = payload.size.toLong())

        val result = PhotoRepository(context)
            .verifyExportedCopy(destUri, payload.size.toLong(), sha256Hex(payload))

        assertEquals(PhotoRepository.VerifyResult.Verified, result)
    }

    /** A truncated copy must never be treated as a successful export. */
    @Test
    fun `verification fails on size mismatch`() = runTest {
        val payload = byteArrayOf(1, 2, 3)
        setupVerifyFlow(payload, reportedSize = payload.size.toLong())

        val result = PhotoRepository(context)
            .verifyExportedCopy(destUri, expectedSizeBytes = 999L, expectedSha256 = null)

        assertEquals(
            PhotoRepository.VerifyResult.Failed(PhotoRepository.VerifyError.SIZE_MISMATCH),
            result
        )
    }

    /** Same length, different bytes — only the checksum catches this. */
    @Test
    fun `verification fails on checksum mismatch`() = runTest {
        val written = byteArrayOf(1, 2, 3, 4)
        val expected = byteArrayOf(4, 3, 2, 1)
        setupVerifyFlow(written, reportedSize = written.size.toLong())

        val result = PhotoRepository(context)
            .verifyExportedCopy(destUri, written.size.toLong(), sha256Hex(expected))

        assertEquals(
            PhotoRepository.VerifyResult.Failed(PhotoRepository.VerifyError.CHECKSUM_MISMATCH),
            result
        )
    }

    @Test
    fun `verification fails when the destination row is gone`() = runTest {
        every { context.contentResolver } returns resolver
        every { resolver.query(destUri, any(), null, null, null) } returns null

        val result = PhotoRepository(context).verifyExportedCopy(destUri, 4L, null)

        assertEquals(
            PhotoRepository.VerifyResult.Failed(PhotoRepository.VerifyError.DEST_MISSING),
            result
        )
    }

    @Test
    fun `verification fails when the destination cannot be opened`() = runTest {
        every { context.contentResolver } returns resolver
        every { resolver.query(destUri, any(), null, null, null) } returns sizeCursor(4L)
        every { resolver.openInputStream(destUri) } returns null

        val result = PhotoRepository(context).verifyExportedCopy(destUri, 4L, null)

        assertEquals(
            PhotoRepository.VerifyResult.Failed(PhotoRepository.VerifyError.DEST_UNREADABLE),
            result
        )
    }

    private fun setupVerifyFlow(destBytes: ByteArray, reportedSize: Long) {
        every { context.contentResolver } returns resolver
        every { resolver.query(destUri, any(), null, null, null) } returns sizeCursor(reportedSize)
        every { resolver.openInputStream(destUri) } returns ByteArrayInputStream(destBytes)
    }

    private fun sizeCursor(size: Long) = MatrixCursor(arrayOf("_size")).apply {
        addRow(arrayOf<Any>(size))
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun setupSuccessFlow(mimeType: String, input: InputStream, output: OutputStream, updateRows: Int = 1) {
        every { context.contentResolver } returns resolver
        every { resolver.getType(sourceUri) } returns mimeType
        every { resolver.insert(any(), any()) } returns destUri
        every { resolver.openInputStream(sourceUri) } returns input
        every { resolver.openOutputStream(destUri) } returns output
        every { resolver.update(destUri, any(), null, null) } returns updateRows
    }

    private class CapturingOutputStream : OutputStream() {
        override fun write(b: Int) = Unit
    }
}
