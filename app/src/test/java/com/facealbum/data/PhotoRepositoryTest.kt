package com.facealbum.data

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.MatrixCursor
import android.net.Uri
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
