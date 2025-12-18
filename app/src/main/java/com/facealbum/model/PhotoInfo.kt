package com.facealbum.model

import android.net.Uri

/**
 * Represents a photo from the device's MediaStore.
 *
 * @property id MediaStore ID
 * @property uri Content URI
 * @property dateTaken Timestamp when photo was taken (for sorting)
 * @property displayName Original filename
 */
data class PhotoInfo(
    val id: Long,
    val uri: Uri,
    val dateTaken: Long,
    val displayName: String
)
