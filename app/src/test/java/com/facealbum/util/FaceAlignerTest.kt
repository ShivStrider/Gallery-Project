package com.facealbum.util

import android.graphics.Matrix
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pins the mathematical properties of [FaceAligner.similarityTransform]: the
 * closed-form least-squares fit of a uniform-scale + rotation + translation
 * (no shear, no reflection) mapping between two five-point sets. A bug here
 * silently misaligns every face fed to the embedding model, so behaviour is
 * checked directly against known transforms rather than "doesn't throw".
 *
 * Robolectric is required (not plain JUnit) because `similarityTransform`
 * returns `android.graphics.Matrix`, which does not exist on a plain JVM
 * classpath. `@Config(sdk = [33])` matches the rest of this package's tests,
 * and Robolectric's native-graphics mode (the default since 4.4, and what
 * this project's other Bitmap/Matrix-touching tests already rely on) backs
 * `Matrix` with the real transform math rather than a no-op shadow, so
 * `setValues`/`mapPoints`/`getValues` behave exactly as they would on device.
 *
 * `FaceAligner.align(Bitmap, Face)` is intentionally NOT tested here: it
 * needs a real ML Kit `Face` with populated landmarks, which cannot be
 * constructed meaningfully in a unit test (its landmark fields have no
 * public constructor / builder). A mock `Face` would only prove the mock
 * behaves as configured, not that the alignment code is correct, so it is
 * left uncovered rather than given a test that only agrees with itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FaceAlignerTest {

    // Five landmark-like points (not collinear, spread like eyes/nose/mouth)
    // used as the common source set across tests.
    private val facePoints = floatArrayOf(
        30f, 40f,
        70f, 40f,
        50f, 60f,
        35f, 80f,
        65f, 80f
    )

    private fun mapAll(matrix: Matrix, points: FloatArray): FloatArray {
        val out = FloatArray(points.size)
        matrix.mapPoints(out, points)
        return out
    }

    private fun assertPointsClose(actual: FloatArray, expected: FloatArray, tolerance: Float) {
        assertThat(actual).hasLength(expected.size)
        for (i in actual.indices) {
            assertThat(actual[i]).isWithin(tolerance).of(expected[i])
        }
    }

    private fun matrixValues(matrix: Matrix): FloatArray {
        val values = FloatArray(9)
        matrix.getValues(values)
        return values
    }

    // --- identity ---

    @Test
    fun `mapping a point set onto itself returns a transform that maps each point back to itself`() {
        val matrix = FaceAligner.similarityTransform(facePoints, facePoints)

        assertThat(matrix).isNotNull()
        val mapped = mapAll(matrix!!, facePoints)
        assertPointsClose(mapped, facePoints, tolerance = 1e-3f)
    }

    // --- pure translation ---

    @Test
    fun `pure translation recovers translation only, with no scale or rotation`() {
        val dx = 17.5f
        val dy = -9.25f
        val dst = FloatArray(facePoints.size) { i ->
            facePoints[i] + if (i % 2 == 0) dx else dy
        }

        val matrix = FaceAligner.similarityTransform(facePoints, dst)

        assertThat(matrix).isNotNull()
        val values = matrixValues(matrix!!)
        assertThat(values[Matrix.MSCALE_X]).isWithin(1e-4f).of(1f)
        assertThat(values[Matrix.MSCALE_Y]).isWithin(1e-4f).of(1f)
        assertThat(values[Matrix.MSKEW_X]).isWithin(1e-4f).of(0f)
        assertThat(values[Matrix.MSKEW_Y]).isWithin(1e-4f).of(0f)
        assertThat(values[Matrix.MTRANS_X]).isWithin(1e-3f).of(dx)
        assertThat(values[Matrix.MTRANS_Y]).isWithin(1e-3f).of(dy)

        // Behavioural cross-check: applying the matrix reproduces dst exactly.
        val mapped = mapAll(matrix, facePoints)
        assertPointsClose(mapped, dst, tolerance = 1e-3f)
    }

    // --- pure scale ---

    @Test
    fun `pure scale about the origin recovers the scale factor only`() {
        val k = 2.5f
        val dst = FloatArray(facePoints.size) { i -> facePoints[i] * k }

        val matrix = FaceAligner.similarityTransform(facePoints, dst)

        assertThat(matrix).isNotNull()
        val values = matrixValues(matrix!!)
        assertThat(values[Matrix.MSCALE_X]).isWithin(1e-4f).of(k)
        assertThat(values[Matrix.MSCALE_Y]).isWithin(1e-4f).of(k)
        assertThat(values[Matrix.MSKEW_X]).isWithin(1e-4f).of(0f)
        assertThat(values[Matrix.MSKEW_Y]).isWithin(1e-4f).of(0f)
        assertThat(values[Matrix.MTRANS_X]).isWithin(1e-3f).of(0f)
        assertThat(values[Matrix.MTRANS_Y]).isWithin(1e-3f).of(0f)

        val mapped = mapAll(matrix, facePoints)
        assertPointsClose(mapped, dst, tolerance = 1e-3f)
    }

    // --- pure rotation ---

    @Test
    fun `pure rotation by 30 degrees maps source onto destination within tolerance`() {
        val thetaRad = Math.toRadians(30.0)
        val cosT = cos(thetaRad).toFloat()
        val sinT = sin(thetaRad).toFloat()

        // Rotate every point about the origin by 30 degrees: this is the
        // ground truth the recovered transform is compared against.
        val dst = FloatArray(facePoints.size)
        for (i in facePoints.indices step 2) {
            val x = facePoints[i]
            val y = facePoints[i + 1]
            dst[i] = x * cosT - y * sinT
            dst[i + 1] = x * sinT + y * cosT
        }

        val matrix = FaceAligner.similarityTransform(facePoints, dst)

        assertThat(matrix).isNotNull()

        // Behaviour, not representation: apply the matrix and compare the
        // resulting points to the known-correct rotated destination.
        val mapped = mapAll(matrix!!, facePoints)
        assertPointsClose(mapped, dst, tolerance = 1e-3f)
    }

    // --- no shear (similarity, not a general poly-to-poly warp) ---

    @Test
    fun `recovered matrix is always a pure similarity, never a sheared warp`() {
        // Deliberately unrelated point sets (no exact similarity maps src to
        // dst) so this exercises the general least-squares fit, not just an
        // exact-fit special case. setPolyToPoly with 4+ points would happily
        // introduce shear/perspective to hit these points exactly; the
        // closed-form similarity fit must not, no matter how the points are
        // related.
        val src = floatArrayOf(
            12f, 5f,
            88f, 3f,
            40f, 55f,
            10f, 90f,
            95f, 80f
        )
        val dst = floatArrayOf(
            5f, 120f,
            130f, 60f,
            70f, 10f,
            200f, 200f,
            15f, 45f
        )

        val matrix = FaceAligner.similarityTransform(src, dst)

        assertThat(matrix).isNotNull()
        val values = matrixValues(matrix!!)
        // Layout is [a, -b, tx, b, a, ty, 0, 0, 1]: MSCALE_X and MSCALE_Y are
        // both `a`, and MSKEW_X/MSKEW_Y are +-b. A shear transform would break
        // one or both of these equalities.
        assertThat(values[Matrix.MSCALE_X]).isWithin(1e-5f).of(values[Matrix.MSCALE_Y])
        assertThat(values[Matrix.MSKEW_X]).isWithin(1e-5f).of(-values[Matrix.MSKEW_Y])
    }

    // --- degenerate input ---

    @Test
    fun `all source points identical returns null instead of dividing by zero`() {
        val degenerateSrc = floatArrayOf(
            10f, 10f,
            10f, 10f,
            10f, 10f,
            10f, 10f,
            10f, 10f
        )
        val dst = floatArrayOf(
            38.2946f, 51.6963f,
            73.5318f, 51.5014f,
            56.0252f, 71.7366f,
            41.5493f, 92.3655f,
            70.7299f, 92.2041f
        )

        val matrix = FaceAligner.similarityTransform(degenerateSrc, dst)

        assertThat(matrix).isNull()
    }

    // --- over-determined least squares with noise ---

    @Test
    fun `noisy points around an exact similarity recover a transform close to the clean one`() {
        // Ground truth: scale 1.3, rotate 12 degrees, translate by (4, -6).
        val k = 1.3f
        val thetaRad = Math.toRadians(12.0)
        val a0 = (k * cos(thetaRad)).toFloat()
        val b0 = (k * sin(thetaRad)).toFloat()
        val tx0 = 4f
        val ty0 = -6f

        val cleanDst = FloatArray(facePoints.size)
        for (i in facePoints.indices step 2) {
            val x = facePoints[i]
            val y = facePoints[i + 1]
            cleanDst[i] = a0 * x - b0 * y + tx0
            cleanDst[i + 1] = b0 * x + a0 * y + ty0
        }

        // Small, non-cancelling per-point noise (up to 0.5 units) so the 5
        // noisy points are no longer exactly related by any similarity
        // transform - an interpolating fit could not hit all 5 exactly with
        // only 4 degrees of freedom, which is exactly the point being tested.
        val noise = floatArrayOf(
            0.4f, -0.3f, -0.2f, 0.5f, 0.1f, 0.1f, -0.5f, -0.4f, 0.3f, 0.2f
        )
        val noisyDst = FloatArray(cleanDst.size) { i -> cleanDst[i] + noise[i] }

        val matrix = FaceAligner.similarityTransform(facePoints, noisyDst)

        assertThat(matrix).isNotNull()

        // The recovered parameters should sit close to the true, noise-free
        // ones - proving the fit averages out the noise rather than chasing
        // it, which is what "least squares" over 5 points into a 4-parameter
        // model buys over an interpolating method.
        val values = matrixValues(matrix!!)
        assertThat(values[Matrix.MSCALE_X]).isWithin(0.05f).of(a0)
        assertThat(values[Matrix.MSKEW_Y]).isWithin(0.05f).of(b0)
        assertThat(values[Matrix.MTRANS_X]).isWithin(1.5f).of(tx0)
        assertThat(values[Matrix.MTRANS_Y]).isWithin(1.5f).of(ty0)

        // And applying the recovered matrix should land close to the clean
        // (noise-free) destination, not merely close to the noisy inputs it
        // was fit from.
        val mapped = mapAll(matrix, facePoints)
        assertPointsClose(mapped, cleanDst, tolerance = 0.5f)
    }
}
