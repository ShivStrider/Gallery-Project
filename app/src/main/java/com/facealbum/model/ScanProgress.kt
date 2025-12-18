package com.facealbum.model

import android.net.Uri

/**
 * Represents the progress of a library scan operation.
 *
 * @property current Number of photos processed so far
 * @property total Total number of photos to scan
 * @property currentPhotoUri URI of the photo currently being processed (optional)
 * @property matchesFound Number of matches found so far
 */
data class ScanProgress(
    val current: Int,
    val total: Int,
    val currentPhotoUri: Uri? = null,
    val matchesFound: Int = 0
)
