package com.facealbum.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A named identity that can own one or more [ClusterEntity] rows.
 *
 * Foundation for a future multi-person flow: today, a `ClusterEntity`
 * already plays the role of "a person" in the UI. `PersonEntity` lets a
 * user group multiple clusters under one identity (e.g. the same person
 * recognised as two clusters across sunglasses / no sunglasses) without
 * destructively merging the underlying clusters.
 */
@Entity(tableName = "persons")
data class PersonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val createdAt: Long,
    val notes: String? = null
)
