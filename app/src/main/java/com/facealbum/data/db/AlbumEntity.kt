package com.facealbum.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Record of an album the user exported from a cluster. `clusterId` is
 * nullable + `SET_NULL` on delete so that re-clustering (which rebuilds
 * the `clusters` table) preserves export history rather than cascading
 * those rows away. An orphaned album row still has a useful `albumName`
 * and `exportedRelativePath`.
 */
@Entity(
    tableName = "albums",
    foreignKeys = [
        ForeignKey(
            entity = ClusterEntity::class,
            parentColumns = ["id"],
            childColumns = ["clusterId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("clusterId")]
)
data class AlbumEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clusterId: Long?,
    val albumName: String,
    val exportedRelativePath: String,
    val exportedAt: Long,
    val photoCount: Int
)
