package com.facealbum.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per scan attempt. Lets the UI show a history of scans and
 * distinguish "no people yet because we never scanned" from "scan ran but
 * found nothing". Written by [com.facealbum.domain.FaceIndexUseCase].
 */
@Entity(tableName = "scan_sessions")
data class ScanSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long?,
    /** "running" | "success" | "failed" | "cancelled" */
    val status: String,
    val photosScanned: Int,
    val facesAdded: Int,
    val errorMessage: String?,
    val forceFullRescan: Boolean
) {
    companion object {
        const val STATUS_RUNNING = "running"
        const val STATUS_SUCCESS = "success"
        const val STATUS_FAILED = "failed"
        const val STATUS_CANCELLED = "cancelled"
    }
}
