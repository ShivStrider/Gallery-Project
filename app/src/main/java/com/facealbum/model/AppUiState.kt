package com.facealbum.model

import android.net.Uri

/**
 * Represents the complete UI state of the app.
 */
data class AppUiState(
    val seedUris: List<Uri> = emptyList(),
    val seedEmbeddings: List<FloatArray> = emptyList(),
    val scanState: ScanState = ScanState.Idle,
    val candidates: List<CandidatePhoto> = emptyList(),
    val albumName: String = "",
    val similarityThreshold: Float = 0.6f,
    val maxPhotosToScan: Int = 500
)
