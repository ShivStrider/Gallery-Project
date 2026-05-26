package com.facealbum.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A group of faces believed to belong to the same person.
 *
 * `displayName` is null until the user names the cluster.
 * `centroid` is the running mean of all assigned face embeddings — re-used as
 * the comparison anchor when classifying new faces.
 * `personId` (optional) ties this cluster to a named [PersonEntity], so several
 * clusters can share one identity without being destructively merged.
 */
@Entity(
    tableName = "clusters",
    foreignKeys = [
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["id"],
            childColumns = ["personId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("personId")]
)
data class ClusterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String?,
    val coverFaceId: Long?,
    val faceCount: Int,
    /** Serialized FloatArray of EMBEDDING_SIZE floats (L2-normalized running mean). */
    val centroid: ByteArray,
    val createdAt: Long,
    val updatedAt: Long,
    val personId: Long? = null
) {
    override fun equals(other: Any?): Boolean =
        other is ClusterEntity && other.id == id

    override fun hashCode(): Int = id.hashCode()
}
