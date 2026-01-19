package com.facealbum.config

/**
 * Centralized configuration for face recognition parameters.
 */
object FaceRecognitionConfig {
    /** Input size expected by the face embedding model (pixels) */
    const val MODEL_INPUT_SIZE = 112

    /** Output embedding vector size from MobileFaceNet */
    const val EMBEDDING_SIZE = 512

    /** Default similarity threshold for face matching (0.0 to 1.0) */
    const val DEFAULT_SIMILARITY_THRESHOLD = 0.6f

    /** Default maximum number of photos to scan */
    const val DEFAULT_MAX_PHOTOS = 500

    /** Maximum dimension for scaled bitmaps (for memory efficiency) */
    const val MAX_BITMAP_DIMENSION = 1024

    /** Padding ratio around detected face for better recognition */
    const val FACE_MARGIN_RATIO = 0.2f

    /** Minimum face size as fraction of image width (for ML Kit) */
    const val MIN_FACE_SIZE = 0.15f

    /** Number of threads for TFLite interpreter */
    const val TFLITE_NUM_THREADS = 4

    /** Model file name in assets folder */
    const val MODEL_FILE_NAME = "mobile_face_net.tflite"
}
