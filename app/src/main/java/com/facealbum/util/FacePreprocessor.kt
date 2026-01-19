package com.facealbum.util

import android.graphics.Bitmap
import android.graphics.Rect
import com.facealbum.config.FaceRecognitionConfig

/**
 * Utility for preprocessing face images for embedding extraction.
 */
object FacePreprocessor {

    private val MODEL_INPUT_SIZE = FaceRecognitionConfig.MODEL_INPUT_SIZE
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
            // Normalize to [-1, 1] (standard for FaceNet)
            floatArray[idx++] = ((pixel shr 16 and 0xFF) / 127.5f) - 1f  // R
            floatArray[idx++] = ((pixel shr 8 and 0xFF) / 127.5f) - 1f   // G
            floatArray[idx++] = ((pixel and 0xFF) / 127.5f) - 1f         // B
        }
        return floatArray
    }
}
