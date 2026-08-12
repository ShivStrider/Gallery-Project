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
            // ACCURATE, not FAST: indexing is a one-off background pass, and
            // detection quality feeds straight into embedding quality. FAST
            // also gives coarser landmarks, which the aligner depends on.
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            // Landmarks are required by FaceAligner — without them every face
            // falls back to an unaligned crop, which is what made distinct
            // people cluster together.
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
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
