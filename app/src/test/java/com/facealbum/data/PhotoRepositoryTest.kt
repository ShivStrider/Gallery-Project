package com.facealbum.data

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
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
        setupSuccessFlow("image/jpeg", ByteArrayInputStream(byteArrayOf(1)), CapturingOutputStream())

        val repo = PhotoRepository(context)
        repo.copyToAlbumWithResult(sourceUri, "Person", "same.jpg")
        repo.copyToAlbumWithResult(sourceUri, "Person", "same.jpg")

        val insertedNames = mutableListOf<String>()
        verify(exactly = 2) {
            resolver.insert(any(), withArg {
                insertedNames += it.getAsString("_display_name")
            })
        }
        assertTrue(insertedNames[0] != insertedNames[1])
    }

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
