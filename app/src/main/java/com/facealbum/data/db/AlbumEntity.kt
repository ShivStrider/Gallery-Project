package com.facealbum.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "albums",
    foreignKeys = [
        ForeignKey(
            entity = ClusterEntity::class,
            parentColumns = ["id"],
            childColumns = ["clusterId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("clusterId")]
)
data class AlbumEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clusterId: Long,
    val albumName: String,
    val exportedRelativePath: String,
    val exportedAt: Long,
    val photoCount: Int
)
