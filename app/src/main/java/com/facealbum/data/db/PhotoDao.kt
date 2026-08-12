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

    /** Prefer [findByIdsChunked]: SQLite caps bind variables at 999. */
    @Query("SELECT * FROM photos WHERE id IN (:ids)")
    suspend fun findByIds(ids: List<Long>): List<PhotoEntity>

    @Query("SELECT MAX(dateModified) FROM photos")
    suspend fun lastIndexedDateModified(): Long?

    /** Lightweight projection for MediaStore reconciliation. */
    @Query("SELECT id, mediaStoreId FROM photos")
    suspend fun idAndMediaStoreIdRows(): List<PhotoIdRow>

    /** Callers must chunk [ids] below SQLite's 999-variable limit. */
    @Query("DELETE FROM photos WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM photos")
    suspend fun count(): Int

    /**
     * Forces every photo to look un-processed to the incremental scan, in one
     * statement. The alternative — reading each row and writing it back with
     * dateModified = 0 — loads the whole table and issues one UPDATE per photo
     * inside a single transaction, which on a large library is a lot of
     * allocation and I/O for what is conceptually a one-line reset.
     */
    @Query("UPDATE photos SET dateModified = 0")
    suspend fun resetReprocessWatermark(): Int

    @Query("DELETE FROM photos")
    suspend fun clear()
}

/** Projection row for [PhotoDao.idAndMediaStoreIdRows]. */
data class PhotoIdRow(
    val id: Long,
    val mediaStoreId: Long
)

/**
 * Batched lookup that stays under SQLite's 999-bind-variable limit — a person
 * appearing in more than 999 photos is entirely realistic for family members.
 */
suspend fun PhotoDao.findByIdsChunked(ids: List<Long>): List<PhotoEntity> =
    ids.chunked(900).flatMap { findByIds(it) }
