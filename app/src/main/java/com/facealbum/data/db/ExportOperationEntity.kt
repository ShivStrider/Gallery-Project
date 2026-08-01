package com.facealbum.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One export the user confirmed — the header row of the export transaction
 * log. Individual files live in `export_items`.
 *
 * `clusterId` is nullable + SET_NULL for the same reason as [AlbumEntity]:
 * re-clustering rebuilds the clusters table and must not erase the record of
 * what was already moved.
 *
 * A MOVE operation parks in [STATE_AWAITING_DELETE_CONSENT] between the
 * background copy/verify phase and the foreground system delete dialog. That
 * state is durable on purpose: the app can be killed there and must re-offer
 * the consent prompt on the next launch rather than stranding verified copies.
 */
@Entity(
    tableName = "export_operations",
    foreignKeys = [
        ForeignKey(
            entity = ClusterEntity::class,
            parentColumns = ["id"],
            childColumns = ["clusterId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("clusterId"), Index("state")]
)
data class ExportOperationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clusterId: Long?,
    val albumName: String,
    /** e.g. `Pictures/FaceAlbums/Alice/` */
    val destRelativePath: String,
    val mode: String,
    val state: String,
    val totalCount: Int,
    val createdAt: Long,
    val updatedAt: Long
) {
    companion object {
        const val MODE_COPY = "COPY"
        const val MODE_MOVE = "MOVE"

        const val STATE_PENDING = "PENDING"
        const val STATE_RUNNING = "RUNNING"
        const val STATE_AWAITING_DELETE_CONSENT = "AWAITING_DELETE_CONSENT"
        const val STATE_FINALIZING = "FINALIZING"
        const val STATE_COMPLETED = "COMPLETED"
        const val STATE_COMPLETED_WITH_ERRORS = "COMPLETED_WITH_ERRORS"
        const val STATE_CANCELLED = "CANCELLED"
        const val STATE_UNDONE = "UNDONE"

        /** States from which no further automated work is scheduled. */
        val TERMINAL_STATES = setOf(
            STATE_COMPLETED,
            STATE_COMPLETED_WITH_ERRORS,
            STATE_CANCELLED,
            STATE_UNDONE
        )
    }
}
