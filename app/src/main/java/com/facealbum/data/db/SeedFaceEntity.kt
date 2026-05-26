package com.facealbum.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A user-curated exemplar face for a [PersonEntity].
 *
 * Persisting these embeddings lets a future "find more photos of this person"
 * flow match against hand-picked seeds rather than relying purely on automatic
 * clustering. Schema-only foundation for now — no producer/consumer code yet.
 */
@Entity(
    tableName = "seed_faces",
    foreignKeys = [
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["id"],
            childColumns = ["personId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("personId")]
)
data class SeedFaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val personId: Long,
    val sourcePhotoUri: String,
    /** Serialized FloatArray of EMBEDDING_SIZE floats (L2-normalized). */
    val embedding: ByteArray,
    val addedAt: Long
) {
    override fun equals(other: Any?): Boolean =
        other is SeedFaceEntity && other.id == id

    override fun hashCode(): Int = id.hashCode()
}
