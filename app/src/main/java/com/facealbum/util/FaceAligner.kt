package com.facealbum.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import com.facealbum.config.FaceRecognitionConfig
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark
import kotlin.math.abs

/**
 * Warps a detected face onto the canonical 112x112 layout MobileFaceNet was
 * trained on.
 *
 * ## Why this exists
 * The bundled model comes from sirius-ai/MobileFaceNet_TF, whose published
 * accuracy was measured on the standard InsightFace evaluation set — images
 * that are *already* aligned: every face warped by a 5-point similarity
 * transform so the eyes, nose and mouth corners sit at fixed pixel positions.
 * Feeding it a raw bounding-box crop instead means the network sees identity
 * mixed with head pose, framing and scale, and cosine distance then measures
 * "photographed similarly" at least as much as "same person". That is the
 * failure mode where everyone ends up in everyone else's group.
 *
 * Aligning first removes pose/scale/roll from the input, so what is left for
 * the embedding to encode is much closer to identity alone.
 */
object FaceAligner {

    /**
     * The ArcFace/InsightFace canonical five points for a 112x112 crop, in
     * **image** coordinates (x grows to the viewer's right, y downward).
     * Index order: outer-left eye, outer-right eye, nose base, left mouth
     * corner, right mouth corner.
     */
    private val TEMPLATE = floatArrayOf(
        38.2946f, 51.6963f,
        73.5318f, 51.5014f,
        56.0252f, 71.7366f,
        41.5493f, 92.3655f,
        70.7299f, 92.2041f
    )

    /**
     * @return an aligned [FaceRecognitionConfig.MODEL_INPUT_SIZE]-square
     *   bitmap, or null when the detector did not report all five landmarks —
     *   the caller is expected to fall back to a plain crop rather than skip
     *   the face entirely.
     */
    fun align(source: Bitmap, face: Face): Bitmap? {
        val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position ?: return null
        val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position ?: return null
        val nose = face.getLandmark(FaceLandmark.NOSE_BASE)?.position ?: return null
        val leftMouth = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position ?: return null
        val rightMouth = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position ?: return null

        // Order the pairs by x rather than trusting the landmark names. The
        // template is defined in image coordinates, while ML Kit's "left"/
        // "right" are the *subject's* own sides — so a naive name-to-slot
        // mapping mirrors every face and silently ruins the alignment. Sorting
        // sidesteps the convention question completely.
        val (eyeA, eyeB) = orderByX(leftEye, rightEye)
        val (mouthA, mouthB) = orderByX(leftMouth, rightMouth)

        val src = floatArrayOf(
            eyeA.x, eyeA.y,
            eyeB.x, eyeB.y,
            nose.x, nose.y,
            mouthA.x, mouthA.y,
            mouthB.x, mouthB.y
        )

        val matrix = similarityTransform(src, TEMPLATE) ?: return null
        val size = FaceRecognitionConfig.MODEL_INPUT_SIZE
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(
            source,
            matrix,
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        )
        return out
    }

    private fun orderByX(p: PointF, q: PointF): Pair<PointF, PointF> =
        if (p.x <= q.x) p to q else q to p

    /**
     * Least-squares similarity transform (uniform scale + rotation +
     * translation, no shear, no reflection) mapping [src] onto [dst].
     *
     * Deliberately not `Matrix.setPolyToPoly`: with four or more point pairs
     * that fits a perspective warp, which can stretch a face non-uniformly to
     * hit the template exactly. A similarity transform can only move, rotate
     * and scale the face, which is what the training-time alignment did.
     *
     * Solves `X = a·x − b·y + tx`, `Y = b·x + a·y + ty` in closed form — the
     * parameters are linear in (a, b, tx, ty), so this is the exact optimum
     * and needs no SVD.
     *
     * @return null if the points are degenerate (all coincident), which would
     *   otherwise divide by zero.
     */
    internal fun similarityTransform(src: FloatArray, dst: FloatArray): Matrix? {
        val n = src.size / 2
        if (n < 2 || dst.size != src.size) return null

        var sx = 0.0; var sy = 0.0; var sX = 0.0; var sY = 0.0
        var sNormSq = 0.0; var sDot = 0.0; var sCross = 0.0
        for (i in 0 until n) {
            val x = src[2 * i].toDouble()
            val y = src[2 * i + 1].toDouble()
            val bigX = dst[2 * i].toDouble()
            val bigY = dst[2 * i + 1].toDouble()
            sx += x; sy += y; sX += bigX; sY += bigY
            sNormSq += x * x + y * y
            sDot += x * bigX + y * bigY
            sCross += x * bigY - y * bigX
        }

        // n·Σ‖p−p̄‖², i.e. the spread of the source points about their mean.
        val denom = n * sNormSq - sx * sx - sy * sy
        if (abs(denom) < 1e-8) return null

        val a = (n * sDot - sx * sX - sy * sY) / denom
        val b = (n * sCross - sx * sY + sy * sX) / denom
        val tx = (sX - a * sx + b * sy) / n
        val ty = (sY - b * sx - a * sy) / n

        return Matrix().apply {
            setValues(
                floatArrayOf(
                    a.toFloat(), (-b).toFloat(), tx.toFloat(),
                    b.toFloat(), a.toFloat(), ty.toFloat(),
                    0f, 0f, 1f
                )
            )
        }
    }
}
