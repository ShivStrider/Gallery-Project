package com.facealbum.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One file within an export — **the per-file transaction log**. Every state
 * transition is committed immediately, before the next file is touched, so
 * the log always describes reality even if the process dies mid-operation.
 *
 * ```
 * PENDING ─copy─> COPIED ─verify─> VERIFIED ─consent─> SOURCE_DELETED
 *    │              │                 │──denied──────> DELETE_DENIED
 *    ├─> COPY_FAILED└─> VERIFY_FAILED (dest removed; source untouched)
 * SKIPPED_DUPLICATE  (pre-existing destination whose checksum matched)
 * Undo: VERIFIED/DELETE_DENIED/SKIPPED_DUPLICATE ─> UNDONE   (dest removed)
 *       SOURCE_DELETED ─restore─> RESTORED  (copied back, verified, dest removed)
 * ```
 *
 * Source identity is denormalized (not just `photoId`) because undo must be
 * able to restore a file to its original location long after the index row
 * for it has gone.
 */
@Entity(
    tableName = "export_items",
    foreignKeys = [
        ForeignKey(
            entity = ExportOperationEntity::class,
            parentColumns = ["id"],
            childColumns = ["operationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("operationId"), Index(value = ["operationId", "state"])]
)
data class ExportItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val operationId: Long,
    /** Informational: the index row this came from, if it still exists. */
    val photoId: Long?,
    val sourceMediaStoreId: Long,
    val sourceUri: String,
    val sourceDisplayName: String,
    /** Captured at plan time — required to restore the file on undo. */
    val sourceRelativePath: String?,
    val sourceSizeBytes: Long,
    /** Computed while streaming the copy; the verification reference. */
    val sourceSha256: String?,
    /** Conflict-resolved at plan time, never mid-copy. */
    val destDisplayName: String,
    val destUri: String?,
    val state: String,
    val errorCode: String?,
    val updatedAt: Long
) {
    companion object {
        const val STATE_PENDING = "PENDING"
        const val STATE_COPIED = "COPIED"
        const val STATE_VERIFIED = "VERIFIED"
        const val STATE_SKIPPED_DUPLICATE = "SKIPPED_DUPLICATE"
        const val STATE_SOURCE_DELETED = "SOURCE_DELETED"
        const val STATE_DELETE_DENIED = "DELETE_DENIED"
        const val STATE_COPY_FAILED = "COPY_FAILED"
        const val STATE_VERIFY_FAILED = "VERIFY_FAILED"
        const val STATE_UNDONE = "UNDONE"
        const val STATE_RESTORED = "RESTORED"

        /**
         * The only states whose source file may enter a delete batch.
         * Invariant 1 of the safe-export design; asserted by the
         * destructive-operation suite.
         */
        val DELETABLE_SOURCE_STATES = setOf(STATE_VERIFIED, STATE_SKIPPED_DUPLICATE)

        /** States the export worker will not revisit. */
        val WORKER_TERMINAL_STATES = setOf(
            STATE_VERIFIED,
            STATE_SKIPPED_DUPLICATE,
            STATE_SOURCE_DELETED,
            STATE_DELETE_DENIED,
            STATE_COPY_FAILED,
            STATE_VERIFY_FAILED,
            STATE_UNDONE,
            STATE_RESTORED
        )
    }
}
