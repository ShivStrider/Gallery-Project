package com.facealbum.config

/**
 * Centralized configuration for face recognition + clustering parameters.
 */
object FaceRecognitionConfig {
    /** Input size expected by the face embedding model (pixels) */
    const val MODEL_INPUT_SIZE = 112

    /** Output embedding vector size from MobileFaceNet */
    /**
     * Output dimensionality of the bundled MobileFaceNet model.
     *
     * 128, not 512: the sirius-ai MobileFaceNet graph this repo ships emits a
     * 1 x 128 `embeddings` tensor. Earlier docs here claimed 512, which was
     * simply wrong — TFLite would have thrown on the output-shape mismatch.
     * Swapping in a model with a different output width means changing this
     * constant to match it; nothing persists a fixed width (embeddings are
     * stored as length-agnostic BLOBs), but all embeddings in one database
     * must share a width, so a change requires re-scanning the library.
     */
    const val EMBEDDING_SIZE = 128

    /** Default similarity threshold for face matching (0.0 to 1.0) */
    const val DEFAULT_SIMILARITY_THRESHOLD = 0.6f

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

    /** Similarity above which a new face is assigned to an existing cluster */
    const val CLUSTER_ASSIGN_THRESHOLD = 0.6f

    /** Centroid similarity above which two clusters are merged in a periodic pass */
    const val CLUSTER_MERGE_THRESHOLD = 0.75f

    /** Minimum cluster size before it shows up in the People grid */
    const val DEFAULT_MIN_CLUSTER_SIZE = 3

    /** Hard cap on photos processed per scan batch (prevents runaway memory use) */
    const val SCAN_BATCH_SIZE = 200
}
