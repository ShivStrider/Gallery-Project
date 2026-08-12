package com.facealbum.util

import android.graphics.Bitmap
import android.graphics.Rect
import com.facealbum.config.FaceRecognitionConfig

/**
 * Utility for preprocessing face images for embedding extraction.
 */
object FacePreprocessor {

    private val MODEL_INPUT_SIZE = FaceRecognitionConfig.MODEL_INPUT_SIZE

    /** 1/128, the scale used by the model's training preprocessing. */
    private const val INV_128 = 0.0078125f
    private val FACE_MARGIN_RATIO = FaceRecognitionConfig.FACE_MARGIN_RATIO

    /**
     * Crop face from bitmap and resize to model input size.
     *
     * @param bitmap Source bitmap containing the face
     * @param faceRect Bounding box of the detected face
     * @return Cropped and resized bitmap ready for model input
     */
    fun cropAndPreprocess(bitmap: Bitmap, faceRect: Rect): Bitmap {
        // Expand rect by margin (faces need context for better recognition)
        val margin = (faceRect.width() * FACE_MARGIN_RATIO).toInt()
        val expandedRect = Rect(
            maxOf(0, faceRect.left - margin),
            maxOf(0, faceRect.top - margin),
            minOf(bitmap.width, faceRect.right + margin),
            minOf(bitmap.height, faceRect.bottom + margin)
        )

        // Crop face region
        val cropped = Bitmap.createBitmap(
            bitmap,
            expandedRect.left,
            expandedRect.top,
            expandedRect.width(),
            expandedRect.height()
        )

        // Resize to model input size
        return Bitmap.createScaledBitmap(
            cropped,
            MODEL_INPUT_SIZE,
            MODEL_INPUT_SIZE,
            true
        )
    }

    /**
     * Convert bitmap to float array normalized for FaceNet model.
     * Normalizes pixel values to [-1, 1] range.
     *
     * @param bitmap Preprocessed face bitmap (should be MODEL_INPUT_SIZE x MODEL_INPUT_SIZE)
     * @return Float array with normalized RGB values
     */
    fun bitmapToFloatArray(bitmap: Bitmap): FloatArray {
        val pixels = IntArray(MODEL_INPUT_SIZE * MODEL_INPUT_SIZE)
        bitmap.getPixels(
            pixels,
            0,
            MODEL_INPUT_SIZE,
            0,
            0,
            MODEL_INPUT_SIZE,
            MODEL_INPUT_SIZE
        )

        val floatArray = FloatArray(MODEL_INPUT_SIZE * MODEL_INPUT_SIZE * 3)
        var idx = 0

        for (pixel in pixels) {
            // (x - 127.5) / 128, matching the bundled model's training and
            // evaluation preprocessing exactly:
            //   img = img - 127.5; img = img * 0.0078125
            // (sirius-ai/MobileFaceNet_TF, utils/data_process.py). The
            // previous `x / 127.5 - 1` divided by 127.5 instead of 128 — a
            // small scale mismatch, but free to get right. Channel order is
            // RGB, which matches upstream: its BGR conversions are commented
            // out and mxnet's imdecode already yields RGB.
            floatArray[idx++] = ((pixel shr 16 and 0xFF) - 127.5f) * INV_128  // R
            floatArray[idx++] = ((pixel shr 8 and 0xFF) - 127.5f) * INV_128   // G
            floatArray[idx++] = ((pixel and 0xFF) - 127.5f) * INV_128         // B
        }
        return floatArray
    }
}
