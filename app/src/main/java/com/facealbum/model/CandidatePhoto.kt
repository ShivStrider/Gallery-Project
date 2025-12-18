package com.facealbum.model

import android.graphics.Rect

/**
 * Represents a photo that matched the seed photos during scanning.
 *
 * @property photo The photo information
 * @property similarity Cosine similarity score (0.0 to 1.0)
 * @property faceRect Bounding box of the detected face (optional, for debug/display)
 * @property isApproved Whether user has approved this match (starts as true, user can reject)
 */
data class CandidatePhoto(
    val photo: PhotoInfo,
    val similarity: Float,
    val faceRect: Rect? = null,
    var isApproved: Boolean = true
)
