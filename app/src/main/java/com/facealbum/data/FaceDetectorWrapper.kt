package com.facealbum.data

import android.content.Context
import android.graphics.Bitmap
import com.facealbum.config.FaceRecognitionConfig
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import timber.log.Timber
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Wrapper for ML Kit Face Detection.
 */
class FaceDetectorWrapper(context: Context) {

    private val detector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(FaceRecognitionConfig.MIN_FACE_SIZE)
            .build()
    )

    /**
     * Detect all faces in a bitmap.
     */
    suspend fun detectAllFaces(bitmap: Bitmap): List<Face> = suspendCoroutine { cont ->
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        detector.process(inputImage)
            .addOnSuccessListener { faces ->
                Timber.d("Face detection completed: found ${faces.size} face(s)")
                cont.resume(faces)
            }
            .addOnFailureListener { e ->
                Timber.e(e, "Face detection failed")
                cont.resume(emptyList())
            }
    }

    fun close() {
        detector.close()
    }
}
