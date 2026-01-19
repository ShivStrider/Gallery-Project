package com.facealbum.model

import android.net.Uri
import com.facealbum.config.FaceRecognitionConfig

/**
 * Represents the complete UI state of the app.
 */
data class AppUiState(
    val seedUris: List<Uri> = emptyList(),
    val seedEmbeddings: List<FloatArray> = emptyList(),
    val scanState: ScanState = ScanState.Idle,
    val candidates: List<CandidatePhoto> = emptyList(),
    val albumName: String = "",
    val similarityThreshold: Float = FaceRecognitionConfig.DEFAULT_SIMILARITY_THRESHOLD,
    val maxPhotosToScan: Int = FaceRecognitionConfig.DEFAULT_MAX_PHOTOS
) {
    init {
        require(similarityThreshold in 0f..1f) {
            "similarityThreshold must be between 0.0 and 1.0, was $similarityThreshold"
        }
        require(maxPhotosToScan > 0) {
            "maxPhotosToScan must be positive, was $maxPhotosToScan"
        }
    }
}
