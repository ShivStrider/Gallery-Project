package com.facealbum.util

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Random

/**
 * Pins down the FaceNet preprocessing pipeline: cropping/margin/clamping in
 * [FacePreprocessor.cropAndPreprocess] and the `(x - 127.5) * 0.0078125`
 * normalization in [FacePreprocessor.bitmapToFloatArray], whose true range is
 * [-0.99609375, 0.99609375] (128, not 127.5, is the divisor - see the
 * bitmapToFloatArray tests below for why that boundary matters). A silent bug
 * here corrupts every embedding, so the properties that matter for
 * recognition are checked directly rather than just "doesn't throw".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FacePreprocessorTest {

    private fun solidBitmap(width: Int, height: Int, color: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    // --- cropAndPreprocess: cropping, margin expansion, edge clamping ---

    @Test
    fun `cropAndPreprocess always outputs the model input size`() {
        val bitmap = solidBitmap(200, 150, Color.GRAY)
        val result = FacePreprocessor.cropAndPreprocess(bitmap, Rect(40, 40, 100, 100))

        assertThat(result.width).isEqualTo(112)
        assertThat(result.height).isEqualTo(112)
    }

    @Test
    fun `cropAndPreprocess clamps a bounding box touching the top-left edge`() {
        val bitmap = solidBitmap(200, 150, Color.GRAY)
        // left/top are 0, so margin expansion would go negative without clamping.
        val faceRect = Rect(0, 0, 50, 50)

        val result = FacePreprocessor.cropAndPreprocess(bitmap, faceRect)

        assertThat(result.width).isEqualTo(112)
        assertThat(result.height).isEqualTo(112)
    }

    @Test
    fun `cropAndPreprocess clamps a bounding box touching the bottom-right edge`() {
        val bitmap = solidBitmap(200, 150, Color.GRAY)
        // right/bottom equal the bitmap dimensions, so margin expansion would
        // overshoot the bitmap bounds without clamping.
        val faceRect = Rect(150, 100, 200, 150)

        val result = FacePreprocessor.cropAndPreprocess(bitmap, faceRect)

        assertThat(result.width).isEqualTo(112)
        assertThat(result.height).isEqualTo(112)
    }

    @Test
    fun `cropAndPreprocess handles a bounding box spanning the whole bitmap`() {
        val bitmap = solidBitmap(200, 150, Color.GRAY)
        // Margin on all four sides would extend past every edge simultaneously.
        val faceRect = Rect(0, 0, 200, 150)

        val result = FacePreprocessor.cropAndPreprocess(bitmap, faceRect)

        assertThat(result.width).isEqualTo(112)
        assertThat(result.height).isEqualTo(112)
    }

    @Test
    fun `cropAndPreprocess handles a tiny 1x1 bounding box in the interior`() {
        val bitmap = solidBitmap(200, 150, Color.GRAY)
        val faceRect = Rect(75, 75, 76, 76)

        val result = FacePreprocessor.cropAndPreprocess(bitmap, faceRect)

        assertThat(result.width).isEqualTo(112)
        assertThat(result.height).isEqualTo(112)
    }

    @Test
    fun `cropAndPreprocess handles a tiny 1x1 bounding box at the top-left corner`() {
        val bitmap = solidBitmap(200, 150, Color.GRAY)
        // width=1 so margin = (1 * FACE_MARGIN_RATIO).toInt() == 0: no expansion room,
        // and the corner position exercises clamping at the same time.
        val faceRect = Rect(0, 0, 1, 1)

        val result = FacePreprocessor.cropAndPreprocess(bitmap, faceRect)

        assertThat(result.width).isEqualTo(112)
        assertThat(result.height).isEqualTo(112)
    }

    // --- bitmapToFloatArray: length and normalization ---

    @Test
    fun `bitmapToFloatArray output length is exactly 112x112x3`() {
        val bitmap = solidBitmap(112, 112, Color.rgb(10, 20, 30))

        val result = FacePreprocessor.bitmapToFloatArray(bitmap)

        assertThat(result).hasLength(112 * 112 * 3)
    }

    @Test
    fun `bitmapToFloatArray keeps every value within the exact -0,99609375 to 0,99609375 range`() {
        // Formula from source: (channel - 127.5f) * 0.0078125f (i.e. / 128, not / 127.5),
        // so the true range is +-(127.5 * 0.0078125) = +-0.99609375, strictly inside
        // +-1. Asserting the tight bound (rather than the old +-1 bound, which the new
        // formula also happens to satisfy) means a regression back to the old
        // `x / 127.5f - 1f` formula -- which does hit exactly -1f/1f -- fails this test.
        val bitmap = Bitmap.createBitmap(112, 112, Bitmap.Config.ARGB_8888)
        val rand = Random(7L)
        for (y in 0 until 112) {
            for (x in 0 until 112) {
                bitmap.setPixel(x, y, Color.rgb(rand.nextInt(256), rand.nextInt(256), rand.nextInt(256)))
            }
        }

        val result = FacePreprocessor.bitmapToFloatArray(bitmap)

        for (value in result) {
            assertThat(value).isAtLeast(-0.99609375f)
            assertThat(value).isAtMost(0.99609375f)
        }
    }

    @Test
    fun `bitmapToFloatArray maps black to exactly -0,99609375 and white to exactly 0,99609375`() {
        // Formula from source: (channel - 127.5f) * 0.0078125f
        // channel 0   -> (0 - 127.5) * 0.0078125   = -0.99609375
        // channel 255 -> (255 - 127.5) * 0.0078125 =  0.99609375
        val black = solidBitmap(112, 112, Color.BLACK)
        val white = solidBitmap(112, 112, Color.WHITE)

        val blackResult = FacePreprocessor.bitmapToFloatArray(black)
        val whiteResult = FacePreprocessor.bitmapToFloatArray(white)

        for (value in blackResult) {
            assertThat(value).isWithin(1e-5f).of(-0.99609375f)
        }
        for (value in whiteResult) {
            assertThat(value).isWithin(1e-5f).of(0.99609375f)
        }
    }

    @Test
    fun `bitmapToFloatArray normalizes a solid colour to the exact formula value`() {
        // Formula from source: ((channel and 0xFF) - 127.5f) * 0.0078125f, applied per R,G,B.
        val (r, g, b) = Triple(200, 100, 50)
        val bitmap = solidBitmap(112, 112, Color.rgb(r, g, b))
        val expectedR = (r - 127.5f) * 0.0078125f
        val expectedG = (g - 127.5f) * 0.0078125f
        val expectedB = (b - 127.5f) * 0.0078125f

        val result = FacePreprocessor.bitmapToFloatArray(bitmap)

        // First pixel triplet.
        assertThat(result[0]).isWithin(1e-5f).of(expectedR)
        assertThat(result[1]).isWithin(1e-5f).of(expectedG)
        assertThat(result[2]).isWithin(1e-5f).of(expectedB)

        // Last pixel triplet, to confirm the whole buffer is filled consistently.
        val lastTriplet = result.size - 3
        assertThat(result[lastTriplet]).isWithin(1e-5f).of(expectedR)
        assertThat(result[lastTriplet + 1]).isWithin(1e-5f).of(expectedG)
        assertThat(result[lastTriplet + 2]).isWithin(1e-5f).of(expectedB)
    }

    @Test
    fun `cropAndPreprocess output feeds cleanly into bitmapToFloatArray`() {
        val bitmap = solidBitmap(200, 150, Color.rgb(10, 20, 30))
        val faceRect = Rect(40, 40, 100, 100)

        val cropped = FacePreprocessor.cropAndPreprocess(bitmap, faceRect)
        val floatArray = FacePreprocessor.bitmapToFloatArray(cropped)

        assertThat(floatArray).hasLength(112 * 112 * 3)
        for (value in floatArray) {
            assertThat(value).isAtLeast(-1f)
            assertThat(value).isAtMost(1f)
        }
    }
}
