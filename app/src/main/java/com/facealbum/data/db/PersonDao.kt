package com.facealbum.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PersonDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(person: PersonEntity): Long

    @Update
    suspend fun update(person: PersonEntity)

    @Query("SELECT * FROM persons WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): PersonEntity?

    @Query("SELECT * FROM persons ORDER BY displayName ASC")
    fun all(): Flow<List<PersonEntity>>

    @Query("DELETE FROM persons WHERE id = :id")
    suspend fun delete(id: Long)
}
