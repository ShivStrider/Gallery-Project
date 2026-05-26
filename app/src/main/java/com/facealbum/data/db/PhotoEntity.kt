package com.facealbum.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per photo we've ever inspected (whether or not faces were found).
 *
 * `mediaStoreId` is the stable MediaStore row id, used to detect re-inspection.
 * `dateModified` lets the indexer skip photos whose contents have not changed
 * since the last pass.
 */
@Entity(
    tableName = "photos",
    indices = [Index(value = ["mediaStoreId"], unique = true)]
)
data class PhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaStoreId: Long,
    val uri: String,
    val displayName: String,
    val dateTaken: Long,
    val dateModified: Long,
    val processedAt: Long,
    val faceCount: Int
)
