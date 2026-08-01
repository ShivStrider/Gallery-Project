package com.facealbum.domain

import android.content.Context
import com.facealbum.data.db.ExportItemEntity
import com.facealbum.data.db.ExportOperationEntity
import com.facealbum.data.db.FaceAlbumDatabase

/**
 * What actually happened, read back from the transaction log rather than
 * from in-memory counters — so the report survives process death and tells
 * the same story the log does.
 */
data class ExportReport(
    val operationId: Long,
    val albumName: String,
    val destRelativePath: String,
    val isMove: Boolean,
    val operationState: String,
    /** Copies written and verified (includes checksum-matched duplicates). */
    val exportedCount: Int,
    /** Originals confirmed removed from the device. */
    val sourcesDeletedCount: Int,
    /** Originals deliberately kept: consent declined or excluded in the prompt. */
    val sourcesKeptCount: Int,
    val failedCount: Int,
    val restoredCount: Int,
    val undoneCount: Int
) {
    /** Undo is only meaningful while there is something to reverse. */
    val canUndo: Boolean
        get() = operationState != ExportOperationEntity.STATE_UNDONE &&
            (exportedCount > 0 || sourcesDeletedCount > 0)

    val hasProblems: Boolean get() = failedCount > 0

    /** A move where some originals stayed behind is not a completed move. */
    val isPartialMove: Boolean get() = isMove && sourcesKeptCount > 0

    companion object {
        suspend fun load(context: Context, operationId: Long): ExportReport? =
            load(FaceAlbumDatabase.get(context), operationId)

        suspend fun load(db: FaceAlbumDatabase, operationId: Long): ExportReport? {
            val operation = db.exportDao().operationById(operationId) ?: return null
            val counts = db.exportDao().stateCounts(operationId)
                .associate { it.state to it.count }
            fun count(state: String) = counts[state] ?: 0

            return ExportReport(
                operationId = operationId,
                albumName = operation.albumName,
                destRelativePath = operation.destRelativePath,
                isMove = operation.mode == ExportOperationEntity.MODE_MOVE,
                operationState = operation.state,
                exportedCount = count(ExportItemEntity.STATE_VERIFIED) +
                    count(ExportItemEntity.STATE_SKIPPED_DUPLICATE) +
                    count(ExportItemEntity.STATE_SOURCE_DELETED) +
                    count(ExportItemEntity.STATE_DELETE_DENIED),
                sourcesDeletedCount = count(ExportItemEntity.STATE_SOURCE_DELETED),
                sourcesKeptCount = count(ExportItemEntity.STATE_DELETE_DENIED),
                failedCount = count(ExportItemEntity.STATE_COPY_FAILED) +
                    count(ExportItemEntity.STATE_VERIFY_FAILED),
                restoredCount = count(ExportItemEntity.STATE_RESTORED),
                undoneCount = count(ExportItemEntity.STATE_UNDONE)
            )
        }
    }
}
