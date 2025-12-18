package com.facealbum.model

/**
 * Represents the current state of the scanning operation.
 */
sealed class ScanState {
    /** No scan in progress */
    object Idle : ScanState()

    /** Scan is in progress */
    data class Scanning(val progress: ScanProgress) : ScanState()

    /** Scan completed successfully */
    data class Complete(val candidates: List<CandidatePhoto>) : ScanState()

    /** Scan encountered an error */
    data class Error(val message: String) : ScanState()
}
