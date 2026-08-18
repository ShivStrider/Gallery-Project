package com.facealbum.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ExportDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOperation(operation: ExportOperationEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertItems(items: List<ExportItemEntity>)

    @Query("SELECT * FROM export_operations WHERE id = :id LIMIT 1")
    suspend fun operationById(id: Long): ExportOperationEntity?

    @Query("SELECT * FROM export_operations WHERE id = :id LIMIT 1")
    fun observeOperation(id: Long): Flow<ExportOperationEntity?>

    @Query("SELECT * FROM export_operations WHERE state = :state ORDER BY createdAt DESC")
    suspend fun operationsInState(state: String): List<ExportOperationEntity>

    /**
     * Drives the "finish your move" banner: operations killed between the
     * verify phase and the system delete dialog.
     * State literal mirrors [ExportOperationEntity.STATE_AWAITING_DELETE_CONSENT].
     */
    @Query(
        """
        SELECT * FROM export_operations
        WHERE state = 'AWAITING_DELETE_CONSENT'
        ORDER BY createdAt DESC
        """
    )
    fun observeAwaitingConsent(): Flow<List<ExportOperationEntity>>

    @Query("UPDATE export_operations SET state = :state, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateOperationState(id: Long, state: String, updatedAt: Long)

    @Query("SELECT * FROM export_items WHERE operationId = :operationId ORDER BY id")
    suspend fun itemsForOperation(operationId: Long): List<ExportItemEntity>

    @Query("SELECT * FROM export_items WHERE operationId = :operationId ORDER BY id")
    fun observeItems(operationId: Long): Flow<List<ExportItemEntity>>

    @Query("SELECT * FROM export_items WHERE operationId = :operationId AND state = :state ORDER BY id")
    suspend fun itemsInState(operationId: Long, state: String): List<ExportItemEntity>

    /**
     * Work remaining for the export worker after a resume.
     * State literals mirror [ExportItemEntity.STATE_PENDING] / `STATE_COPIED`.
     */
    @Query(
        """
        SELECT * FROM export_items
        WHERE operationId = :operationId
          AND state IN ('PENDING', 'COPIED')
        ORDER BY id
        """
    )
    suspend fun unfinishedItems(operationId: Long): List<ExportItemEntity>

    /**
     * Sources eligible for deletion. Encodes invariant 1 of the safe-export
     * design in SQL: only verified copies (or checksum-matched duplicates)
     * can ever reach a delete request. State literals mirror
     * [ExportItemEntity.DELETABLE_SOURCE_STATES] — kept in sync by
     * `ExportDaoTest`.
     */
    @Query(
        """
        SELECT * FROM export_items
        WHERE operationId = :operationId
          AND state IN ('VERIFIED', 'SKIPPED_DUPLICATE')
        ORDER BY id
        """
    )
    suspend fun deletableItems(operationId: Long): List<ExportItemEntity>

    @Query("SELECT COUNT(*) FROM export_items WHERE operationId = :operationId AND state = :state")
    suspend fun countInState(operationId: Long, state: String): Int

    /** Per-state tallies behind the export report. */
    @Query(
        """
        SELECT state AS state, COUNT(*) AS count
        FROM export_items
        WHERE operationId = :operationId
        GROUP BY state
        """
    )
    suspend fun stateCounts(operationId: Long): List<ExportStateCount>

    @Query(
        """
        UPDATE export_items
        SET state = :state,
            destUri = :destUri,
            sourceSha256 = :sourceSha256,
            errorCode = :errorCode,
            updatedAt = :updatedAt
        WHERE id = :id
        """
    )
    suspend fun updateItemState(
        id: Long,
        state: String,
        destUri: String?,
        sourceSha256: String?,
        errorCode: String?,
        updatedAt: Long
    )

    /**
     * Every exported file that actually landed on disk and still has a
     * destination row, across all operations — the working set for the
     * date-repair pass.
     *
     * The four states are exactly those where a destination file exists and
     * has not since been removed: a copy that verified, a duplicate that was
     * skipped because the file was already there, and both post-consent
     * outcomes of a move. COPY_FAILED and VERIFY_FAILED are excluded because
     * their destination was deleted by the failure path; UNDONE and RESTORED
     * because undo removed the destination.
     */
    @Query(
        """
        SELECT * FROM export_items
        WHERE destUri IS NOT NULL
          AND state IN ('VERIFIED', 'SKIPPED_DUPLICATE', 'SOURCE_DELETED', 'DELETE_DENIED')
        ORDER BY id
        """
    )
    suspend fun itemsWithLandedDestination(): List<ExportItemEntity>

    @Query("DELETE FROM export_operations")
    suspend fun clearOperations()
}

/** Projection row for [ExportDao.stateCounts]. */
data class ExportStateCount(
    val state: String,
    val count: Int
)
