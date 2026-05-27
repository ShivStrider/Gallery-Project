package com.facealbum.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A group of faces believed to belong to the same person.
 *
 * `displayName` is null until the user names the cluster.
 * `centroid` is the running mean of all assigned face embeddings — re-used as
 * the comparison anchor when classifying new faces.
 */
@Entity(tableName = "clusters")
data class ClusterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String?,
    val coverFaceId: Long?,
    val faceCount: Int,
    /** Serialized FloatArray of EMBEDDING_SIZE floats (L2-normalized running mean). */
    val centroid: ByteArray,
    val createdAt: Long,
    val updatedAt: Long
) {
    override fun equals(other: Any?): Boolean =
        other is ClusterEntity && other.id == id

    override fun hashCode(): Int = id.hashCode()
}
