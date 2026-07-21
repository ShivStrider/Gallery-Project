package com.facealbum.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PhotoDao {
    /**
     * Insert a new row. Using ABORT (not REPLACE) so a stale mediaStoreId clash
     * cannot cascade-delete existing faces — callers must check existence first
     * via [findByMediaStoreId] and route to [updateMetadata] when needed.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(photo: PhotoEntity): Long

    @Query(
        """
        UPDATE photos
        SET uri = :uri,
            displayName = :displayName,
            dateTaken = :dateTaken,
            dateModified = :dateModified,
            processedAt = :processedAt,
            faceCount = :faceCount
        WHERE id = :id
        """
    )
    suspend fun updateMetadata(
        id: Long,
        uri: String,
        displayName: String,
        dateTaken: Long,
        dateModified: Long,
        processedAt: Long,
        faceCount: Int
    )

    @Query("SELECT * FROM photos WHERE mediaStoreId = :mediaStoreId LIMIT 1")
    suspend fun findByMediaStoreId(mediaStoreId: Long): PhotoEntity?

    @Query("SELECT * FROM photos WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): PhotoEntity?

    @Query("SELECT * FROM photos WHERE id IN (:ids)")
    suspend fun findByIds(ids: List<Long>): List<PhotoEntity>

    @Query("SELECT MAX(dateModified) FROM photos")
    suspend fun lastIndexedDateModified(): Long?

    @Query("SELECT COUNT(*) FROM photos")
    suspend fun count(): Int

    @Query("DELETE FROM photos")
    suspend fun clear()
}
