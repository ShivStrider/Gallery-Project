package com.facealbum.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "faces",
    foreignKeys = [
        ForeignKey(
            entity = PhotoEntity::class,
            parentColumns = ["id"],
            childColumns = ["photoId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ClusterEntity::class,
            parentColumns = ["id"],
            childColumns = ["clusterId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index("photoId"),
        Index("clusterId")
    ]
)
data class FaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val photoId: Long,
    val clusterId: Long?,
    val bboxLeft: Int,
    val bboxTop: Int,
    val bboxRight: Int,
    val bboxBottom: Int,
    /** Serialized FloatArray of EMBEDDING_SIZE floats (4 bytes each, little-endian). */
    val embedding: ByteArray,
    /** Larger = better face: relative size of bounding box vs photo area. 0..1. */
    val quality: Float
) {
    override fun equals(other: Any?): Boolean =
        other is FaceEntity && other.id == id

    override fun hashCode(): Int = id.hashCode()
}
