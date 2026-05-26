package com.facealbum.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AlbumDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(album: AlbumEntity): Long

    @Query("SELECT * FROM albums WHERE clusterId = :clusterId ORDER BY exportedAt DESC")
    suspend fun albumsForCluster(clusterId: Long): List<AlbumEntity>

    @Query("SELECT * FROM albums WHERE clusterId = :clusterId ORDER BY exportedAt DESC LIMIT 1")
    suspend fun latestForCluster(clusterId: Long): AlbumEntity?
}
