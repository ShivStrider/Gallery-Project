package com.facealbum.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SeedFaceDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(seed: SeedFaceEntity): Long

    @Query("SELECT * FROM seed_faces WHERE personId = :personId")
    suspend fun forPerson(personId: Long): List<SeedFaceEntity>

    @Query("DELETE FROM seed_faces WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM seed_faces WHERE personId = :personId")
    suspend fun deleteForPerson(personId: Long)
}
