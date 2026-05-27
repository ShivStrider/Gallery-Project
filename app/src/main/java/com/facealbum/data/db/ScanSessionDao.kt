package com.facealbum.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ScanSessionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(session: ScanSessionEntity): Long

    @Query(
        """
        UPDATE scan_sessions
           SET endedAt = :endedAt,
               status = :status,
               photosScanned = :photosScanned,
               facesAdded = :facesAdded,
               errorMessage = :errorMessage
         WHERE id = :id
        """
    )
    suspend fun finish(
        id: Long,
        endedAt: Long,
        status: String,
        photosScanned: Int,
        facesAdded: Int,
        errorMessage: String?
    )

    /**
     * Mark any stuck "running" rows from a previous process death as cancelled
     * so the history view doesn't show phantom in-flight scans.
     */
    @Query(
        """
        UPDATE scan_sessions
           SET status = 'cancelled', endedAt = :now
         WHERE status = 'running'
        """
    )
    suspend fun markOrphansCancelled(now: Long)
}
