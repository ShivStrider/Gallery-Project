package com.facealbum.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.facealbum.config.FaceRecognitionConfig

/**
 * Utility for loading bitmaps with proper scaling and rotation handling.
 */
object BitmapLoader {

    private val MAX_DIMENSION = FaceRecognitionConfig.MAX_BITMAP_DIMENSION

    /**
     * Load a bitmap from URI with automatic downscaling and EXIF rotation correction.
     *
     * @param context Android context
     * @param uri URI of the image to load
     * @return Loaded and properly oriented bitmap, or null if loading failed
     */
    fun loadScaled(context: Context, uri: Uri): Bitmap? {
        return try {
            // First pass: get dimensions without loading full bitmap
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }

            // Calculate appropriate sample size
            val sampleSize = calculateSampleSize(
                options.outWidth,
                options.outHeight,
                MAX_DIMENSION
            )

            // Second pass: load scaled bitmap
            val loadOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            val bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, loadOptions)
            } ?: return null

            // Apply EXIF rotation if needed
            applyExifRotation(context, uri, bitmap)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Calculate the appropriate sample size for downscaling.
     */
    private fun calculateSampleSize(width: Int, height: Int, maxDim: Int): Int {
        var sampleSize = 1
        while (width / sampleSize > maxDim || height / sampleSize > maxDim) {
            sampleSize *= 2
        }
        return sampleSize
    }

    /**
     * Apply EXIF rotation to correct image orientation.
     */
    private fun applyExifRotation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val rotation = context.contentResolver.openInputStream(uri)?.use { stream ->
            val exif = ExifInterface(stream)
            when (exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f

        return if (rotation != 0f) {
            val matrix = Matrix().apply { postRotate(rotation) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }
    }
}
