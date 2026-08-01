package com.facealbum.util

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * NOTE ON COVERAGE: BitmapLoader.calculateSampleSize and
 * BitmapLoader.applyExifRotation are both `private`, so per this task's
 * constraints (create-only, no edits to production files) they cannot be
 * invoked or verified directly from a test. A one-line visibility change
 * (`private fun calculateSampleSize` -> `internal fun calculateSampleSize`,
 * likewise for `applyExifRotation`) would make both directly unit-testable.
 *
 * What *is* covered here is exercised entirely through the public
 * BitmapLoader.loadScaled API, using a mocked ContentResolver in the
 * style of PhotoRepositoryTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BitmapLoaderTest {

    private val context: Context = mockk()
    private val resolver: ContentResolver = mockk(relaxed = true)
    private val uri: Uri = Uri.parse("content://media/external/images/media/42")

    @Test
    fun `loadScaled returns null when the source stream cannot be opened`() {
        every { context.contentResolver } returns resolver
        every { resolver.openInputStream(uri) } returns null

        val result = BitmapLoader.loadScaled(context, uri)

        assertThat(result).isNull()
    }

    @Test
    fun `loadScaled returns null instead of throwing when opening the stream fails`() {
        every { context.contentResolver } returns resolver
        every { resolver.openInputStream(uri) } throws IOException("boom")

        val result = BitmapLoader.loadScaled(context, uri)

        assertThat(result).isNull()
    }

    @Test
    fun `loadScaled returns null instead of throwing on an unexpected runtime exception`() {
        every { context.contentResolver } returns resolver
        every { resolver.openInputStream(uri) } throws IllegalStateException("unexpected")

        val result = BitmapLoader.loadScaled(context, uri)

        assertThat(result).isNull()
    }

    @Test
    fun `loadScaled opens the stream twice - once for bounds, once for the real decode`() {
        // Two-pass decode: a first bounds-only pass to read outWidth/outHeight,
        // then a second pass to actually decode pixels with inSampleSize set.
        every { context.contentResolver } returns resolver
        every { resolver.openInputStream(uri) } returns null

        BitmapLoader.loadScaled(context, uri)

        verify(exactly = 2) { resolver.openInputStream(uri) }
    }
}
