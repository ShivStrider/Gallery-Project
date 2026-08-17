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

    /**
     * Minimum improvement [FaceClusterer.refineAssignments] requires before moving a
     * face from its current cluster to a better-matching one. Without this margin, a
     * face sitting almost exactly equidistant between two centroids could be pulled
     * back and forth as each move nudges both centroids by a hair. See
     * [FaceClusterer.refineAssignments] KDoc for the full reasoning.
     */
    const val REFINE_HYSTERESIS_MARGIN = 0.02f

    /**
     * Once either side of a candidate [FaceClusterer.mergeClose] pair has at least this
     * many faces, the pair must clear `mergeThreshold + [CLUSTER_MERGE_CHAIN_GUARD_MARGIN]`
     * instead of the plain merge threshold. See [FaceClusterer.mergeClose] KDoc for the
     * reasoning.
     */
    const val CLUSTER_MERGE_CHAIN_GUARD_SIZE = 8

    /**
     * Added on top of the (possibly user-configured) merge threshold once a candidate
     * pair is "large" per [CLUSTER_MERGE_CHAIN_GUARD_SIZE] — a margin rather than a
     * second absolute constant so the guard still scales if the base threshold is
     * changed via user preferences.
     */
    const val CLUSTER_MERGE_CHAIN_GUARD_MARGIN = 0.05f

    /** Minimum cluster size before it shows up in the People grid */
    const val DEFAULT_MIN_CLUSTER_SIZE = 3

    /** Hard cap on photos processed per scan batch (prevents runaway memory use) */
    const val SCAN_BATCH_SIZE = 200

    /**
     * Version of everything upstream of a stored face embedding: the model
     * file, its input size, face alignment/cropping, pixel normalization, and
     * channel order. Two embeddings produced under different versions live in
     * different, mutually incomparable vector spaces — cosine similarity
     * between them is meaningless, even though nothing about the stored BLOB
     * looks wrong.
     *
     * MUST be incremented whenever any of the above changes upstream of the
     * stored vector. [com.facealbum.domain.FaceIndexUseCase.run] compares this
     * against the version persisted in `UserPreferences` at the start of every
     * pass; a mismatch wipes every stored face and cluster and forces a full
     * re-index of every photo, because incrementally scanning only new/changed
     * photos would otherwise leave old- and new-generation vectors mixed in
     * the same table — which clusters *worse* than either generation alone,
     * and fails silently (it looks like ordinary bad clustering, not a bug).
     *
     * Bumped 1 -> 2 for: faces are now warped onto the ArcFace canonical
     * template before embedding (previously a raw bounding-box crop), and
     * input normalization changed from `x/127.5 - 1` to `(x - 127.5)/128`.
     */
    const val EMBEDDING_PIPELINE_VERSION = 2

    /**
     * The implicit pipeline version of every embedding stored before this
     * versioning scheme existed. Never itself persisted — `UserPreferences`
     * falls back to this when no version has ever been recorded, so a user
     * who already scanned before this guard shipped is correctly treated as
     * stale (not as already current) the first time the app runs with it.
     */
    const val LEGACY_EMBEDDING_PIPELINE_VERSION = 1
}
