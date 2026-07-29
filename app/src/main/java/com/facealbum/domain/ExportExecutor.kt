package com.facealbum.domain

import android.content.Context
import android.net.Uri
import com.facealbum.data.PhotoRepository
import com.facealbum.data.db.ExportItemEntity
import com.facealbum.data.db.ExportOperationEntity
import com.facealbum.data.db.FaceAlbumDatabase
import timber.log.Timber

/**
 * Executes a committed export operation: copy, then verify, one file at a
 * time, persisting each transition before touching the next file.
 *
 * Nothing here deletes anything. Source deletion for a move happens later,
 * in the foreground, through the system consent dialog — see
 * `docs/plan/05-safe-export-design.md`. The most this class will ever do is
 * remove a *destination* copy it just wrote and could not verify.
 *
 * Because every transition is committed immediately, re-running after a
 * process death resumes: finished items are skipped, and an item caught
 * mid-copy is re-copied over its own partial destination.
 */
class ExportExecutor(
    private val context: Context,
    private val db: FaceAlbumDatabase = FaceAlbumDatabase.get(context),
    private val photoRepository: PhotoRepository = PhotoRepository(context),
    private val now: () -> Long = { System.currentTimeMillis() }
) {

    data class Progress(val done: Int, val total: Int, val failed: Int)

    /**
     * @return the operation's state after this pass.
     */
    suspend fun run(
        operationId: Long,
        onProgress: suspend (Progress) -> Unit = {}
    ): String {
        val dao = db.exportDao()
        val operation = dao.operationById(operationId)
        if (operation == null) {
            Timber.w("Export operation $operationId not found")
            return ExportOperationEntity.STATE_CANCELLED
        }
        if (operation.state in ExportOperationEntity.TERMINAL_STATES) {
            // Idempotent: WorkManager may re-run a completed operation.
            return operation.state
        }

        dao.updateOperationState(operationId, ExportOperationEntity.STATE_RUNNING, now())

        val pending = dao.unfinishedItems(operationId)
        val alreadyDone = operation.totalCount - pending.size
        var done = alreadyDone
        var failed = 0

        for (item in pending) {
            val copied = when (item.state) {
                ExportItemEntity.STATE_COPIED -> item
                else -> copyItem(operation, item)
            }
            if (copied.state == ExportItemEntity.STATE_COPY_FAILED) {
                failed += 1
                done += 1
                onProgress(Progress(done, operation.totalCount, failed))
                continue
            }
            val verified = verifyItem(copied)
            if (verified.state == ExportItemEntity.STATE_VERIFY_FAILED) failed += 1
            done += 1
            onProgress(Progress(done, operation.totalCount, failed))
        }

        return finalize(operationId)
    }

    private suspend fun copyItem(
        operation: ExportOperationEntity,
        item: ExportItemEntity
    ): ExportItemEntity {
        val result = photoRepository.copyToAlbumChecked(
            sourceUri = Uri.parse(item.sourceUri),
            albumName = operation.albumName,
            destDisplayName = item.destDisplayName
        )
        return when (result) {
            is PhotoRepository.CheckedCopyResult.Success -> {
                // A dedup hit wrote no bytes, so it carries no checksum. It is
                // deliberately NOT trusted here: verification below hashes the
                // destination against the source before it can count as
                // exported, because a same-named file is not the same file.
                val sha = result.sha256 ?: photoRepository.sha256Of(Uri.parse(item.sourceUri))
                val updated = item.copy(
                    state = ExportItemEntity.STATE_COPIED,
                    destUri = result.uri.toString(),
                    sourceSha256 = sha,
                    errorCode = null,
                    updatedAt = now()
                )
                persist(updated)
                updated
            }
            is PhotoRepository.CheckedCopyResult.Failure -> {
                val updated = item.copy(
                    state = ExportItemEntity.STATE_COPY_FAILED,
                    errorCode = result.error.name,
                    updatedAt = now()
                )
                persist(updated)
                Timber.w("Export item ${item.id} copy failed: ${result.error}")
                updated
            }
        }
    }

    private suspend fun verifyItem(item: ExportItemEntity): ExportItemEntity {
        val destUri = item.destUri?.let(Uri::parse)
        if (destUri == null) {
            val updated = item.copy(
                state = ExportItemEntity.STATE_VERIFY_FAILED,
                errorCode = PhotoRepository.VerifyError.DEST_MISSING.name,
                updatedAt = now()
            )
            persist(updated)
            return updated
        }

        val result = photoRepository.verifyExportedCopy(
            destUri = destUri,
            expectedSizeBytes = item.sourceSizeBytes,
            expectedSha256 = item.sourceSha256
        )
        return when (result) {
            is PhotoRepository.VerifyResult.Verified -> {
                val updated = item.copy(
                    state = ExportItemEntity.STATE_VERIFIED,
                    errorCode = null,
                    updatedAt = now()
                )
                persist(updated)
                updated
            }
            is PhotoRepository.VerifyResult.Failed -> {
                // The copy is not trustworthy, so remove it. The source is
                // untouched and stays that way — this item can never enter a
                // delete batch, by construction of ExportDao.deletableItems.
                photoRepository.deleteOwnedDest(destUri)
                val updated = item.copy(
                    state = ExportItemEntity.STATE_VERIFY_FAILED,
                    destUri = null,
                    errorCode = result.error.name,
                    updatedAt = now()
                )
                persist(updated)
                Timber.w("Export item ${item.id} failed verification: ${result.error}")
                updated
            }
        }
    }

    /**
     * Decide the operation's resting state. A move stops at
     * [ExportOperationEntity.STATE_AWAITING_DELETE_CONSENT] — the background
     * job never deletes a source; the UI must ask first.
     */
    private suspend fun finalize(operationId: Long): String {
        val dao = db.exportDao()
        val operation = dao.operationById(operationId) ?: return ExportOperationEntity.STATE_CANCELLED
        val items = dao.itemsForOperation(operationId)
        val succeeded = items.count {
            it.state == ExportItemEntity.STATE_VERIFIED ||
                it.state == ExportItemEntity.STATE_SKIPPED_DUPLICATE
        }
        val failedCount = items.size - succeeded

        val nextState = when {
            succeeded == 0 -> ExportOperationEntity.STATE_COMPLETED_WITH_ERRORS
            operation.mode == ExportOperationEntity.MODE_MOVE ->
                ExportOperationEntity.STATE_AWAITING_DELETE_CONSENT
            failedCount > 0 -> ExportOperationEntity.STATE_COMPLETED_WITH_ERRORS
            else -> ExportOperationEntity.STATE_COMPLETED
        }
        dao.updateOperationState(operationId, nextState, now())
        Timber.i(
            "Export operation $operationId: $succeeded verified, $failedCount failed, state=$nextState"
        )
        return nextState
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
}
