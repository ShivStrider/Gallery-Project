package com.facealbum.domain

import android.content.Context
import android.net.Uri
import com.facealbum.data.PhotoRepository
import com.facealbum.data.db.ExportItemEntity
import com.facealbum.data.db.ExportOperationEntity
import com.facealbum.data.db.FaceAlbumDatabase
import timber.log.Timber

/**
 * Reverses a completed export, using the transaction log as the record of
 * what to undo.
 *
 * What undo guarantees, in priority order:
 *  1. Files whose originals were deleted are put back, verified against the
 *     checksum recorded at export time.
 *  2. Copies this app created are removed.
 *
 * It deliberately does **not** delete a destination that already existed
 * before the export ([ExportItemEntity.STATE_SKIPPED_DUPLICATE]) — the export
 * did not create that file, so removing it would destroy something the user
 * had independently.
 *
 * If a restore fails, the exported copy is kept: at that moment it may be the
 * only surviving version of the photo, so removing it to tidy up would be the
 * one unrecoverable mistake available here.
 */
class ExportUndoUseCase(
    private val context: Context,
    private val db: FaceAlbumDatabase = FaceAlbumDatabase.get(context),
    private val photoRepository: PhotoRepository = PhotoRepository(context),
    private val now: () -> Long = { System.currentTimeMillis() }
) {

    data class Result(
        val restoredCount: Int,
        val removedCopies: Int,
        val failedCount: Int
    )

    suspend fun undo(operationId: Long): Result {
        val dao = db.exportDao()
        val operation = dao.operationById(operationId)
            ?: return Result(0, 0, 0)

        var restored = 0
        var removed = 0
        var failed = 0

        for (item in dao.itemsForOperation(operationId)) {
            when (item.state) {
                ExportItemEntity.STATE_SOURCE_DELETED -> {
                    if (restoreItem(item)) restored += 1 else failed += 1
                }
                ExportItemEntity.STATE_VERIFIED,
                ExportItemEntity.STATE_DELETE_DENIED -> {
                    if (removeCopy(item)) removed += 1
                }
                ExportItemEntity.STATE_SKIPPED_DUPLICATE -> {
                    // The destination pre-dated this export; leave it alone
                    // and simply retire the log entry.
                    persist(item.copy(state = ExportItemEntity.STATE_UNDONE, updatedAt = now()))
                }
                else -> Unit // Failed items never produced anything to undo.
            }
        }

        dao.updateOperationState(operationId, ExportOperationEntity.STATE_UNDONE, now())
        Timber.i(
            "Undo of export $operationId: $restored restored, $removed copies removed, $failed failed"
        )
        return Result(restored, removed, failed)
    }

    /**
     * Put the original back, then remove the exported copy — in that order,
     * and only if the restore verified.
     */
    private suspend fun restoreItem(item: ExportItemEntity): Boolean {
        val destUri = item.destUri?.let(Uri::parse)
        val targetPath = item.sourceRelativePath
        if (destUri == null || targetPath == null) {
            // Without a copy to read or a place to put it, the file cannot be
            // recovered here; leave the log entry as evidence.
            persist(
                item.copy(
                    errorCode = ERROR_NOT_RESTORABLE,
                    updatedAt = now()
                )
            )
            Timber.w("Cannot restore export item ${item.id}: missing copy or original path")
            return false
        }

        val result = photoRepository.restoreFromCopy(
            sourceCopyUri = destUri,
            targetRelativePath = targetPath,
            targetDisplayName = item.sourceDisplayName,
            expectedSha256 = item.sourceSha256
        )
        return when (result) {
            is PhotoRepository.CheckedCopyResult.Success -> {
                photoRepository.deleteOwnedDest(destUri)
                persist(
                    item.copy(
                        state = ExportItemEntity.STATE_RESTORED,
                        destUri = null,
                        errorCode = null,
                        updatedAt = now()
                    )
                )
                true
            }
            is PhotoRepository.CheckedCopyResult.Failure -> {
                // Keep the copy: it may now be the only version that exists.
                persist(item.copy(errorCode = result.error.name, updatedAt = now()))
                Timber.w("Restore failed for export item ${item.id}: ${result.error}")
                false
            }
        }
    }

    private suspend fun removeCopy(item: ExportItemEntity): Boolean {
        val destUri = item.destUri?.let(Uri::parse)
        val deleted = destUri?.let { photoRepository.deleteOwnedDest(it) } ?: false
        persist(
            item.copy(
                state = ExportItemEntity.STATE_UNDONE,
                destUri = null,
                errorCode = null,
                updatedAt = now()
            )
        )
        return deleted
    }

    private suspend fun persist(item: ExportItemEntity) {
        db.exportDao().updateItemState(
            id = item.id,
            state = item.state,
            destUri = item.destUri,
            sourceSha256 = item.sourceSha256,
            errorCode = item.errorCode,
            updatedAt = item.updatedAt
        )
    }

    private companion object {
        const val ERROR_NOT_RESTORABLE = "NOT_RESTORABLE"
    }
}
