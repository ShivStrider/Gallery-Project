package com.facealbum.domain

import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.facealbum.data.PhotoRepository
import com.facealbum.data.db.AlbumEntity
import com.facealbum.data.db.ExportItemEntity
import com.facealbum.data.db.ExportOperationEntity
import com.facealbum.data.db.FaceAlbumDatabase
import timber.log.Timber

/**
 * The deletion half of a move export.
 *
 * Source photos belong to other apps, so the app cannot delete them itself.
 * The only lawful route is `MediaStore.createDeleteRequest`, which shows a
 * system confirmation the user must accept — and it must be launched from the
 * foreground, which is why this is deliberately separate from [ExportExecutor]
 * and never runs inside a worker.
 *
 * Two rules hold throughout:
 *  1. Only files whose copies verified can be offered for deletion. That is
 *     enforced in SQL by `ExportDao.deletableItems`, not by callers.
 *  2. An item is marked [ExportItemEntity.STATE_SOURCE_DELETED] only after
 *     MediaStore confirms the row is actually gone — the dialog's result code
 *     is a statement of intent, not evidence.
 */
class ExportConsentUseCase(
    private val context: Context,
    private val db: FaceAlbumDatabase = FaceAlbumDatabase.get(context),
    private val photoRepository: PhotoRepository = PhotoRepository(context),
    private val now: () -> Long = { System.currentTimeMillis() }
) {

    data class Outcome(
        val operationState: String,
        val deletedCount: Int,
        val keptCount: Int
    )

    /** Source URIs eligible for deletion — verified copies only. */
    suspend fun deletableSourceUris(operationId: Long): List<Uri> =
        db.exportDao().deletableItems(operationId).map { Uri.parse(it.sourceUri) }

    /**
     * The system dialog degrades badly with very large selections, so batches
     * are capped and the UI walks them one prompt at a time.
     */
    fun chunkForConsent(uris: List<Uri>): List<List<Uri>> =
        if (uris.isEmpty()) emptyList() else uris.chunked(CONSENT_BATCH_SIZE)

    /**
     * Build the system delete prompt. Null below API 30, where this route
     * does not exist and move mode is therefore refused at plan time.
     */
    fun createDeleteRequest(uris: List<Uri>): IntentSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || uris.isEmpty()) return null
        return MediaStore.createDeleteRequest(context.contentResolver, uris).intentSender
    }

    /**
     * Reconcile the log with reality after the user answers the prompt.
     *
     * [granted] only says the user accepted the dialog; it does not say which
     * files went. Each source is re-queried, so a photo the system kept — or
     * one the user excluded inside the prompt — is recorded as kept rather
     * than silently reported as moved.
     */
    suspend fun finalizeAfterConsent(operationId: Long, granted: Boolean): Outcome {
        val dao = db.exportDao()
        val operation = dao.operationById(operationId)
            ?: return Outcome(ExportOperationEntity.STATE_CANCELLED, 0, 0)

        dao.updateOperationState(operationId, ExportOperationEntity.STATE_FINALIZING, now())

        val candidates = dao.deletableItems(operationId)
        var deleted = 0
        var kept = 0

        for (item in candidates) {
            val stillPresent = if (granted) {
                photoRepository.sourceStillExists(item.sourceMediaStoreId)
            } else {
                true
            }
            val newState = if (stillPresent) {
                kept += 1
                ExportItemEntity.STATE_DELETE_DENIED
            } else {
                deleted += 1
                ExportItemEntity.STATE_SOURCE_DELETED
            }
            dao.updateItemState(
                id = item.id,
                state = newState,
                destUri = item.destUri,
                sourceSha256 = item.sourceSha256,
                errorCode = null,
                updatedAt = now()
            )
        }

        // The copies exist either way, so the album is real either way.
        val exported = deleted + kept
        if (exported > 0) {
            db.albumDao().insert(
                AlbumEntity(
                    clusterId = operation.clusterId,
                    albumName = operation.albumName,
                    exportedRelativePath = operation.destRelativePath.trimEnd('/'),
                    exportedAt = now(),
                    photoCount = exported
                )
            )
        }

        // Declining is a legitimate choice, not a failure of the export: the
        // user keeps verified copies and their originals.
        val allItems = dao.itemsForOperation(operationId)
        val hadFailures = allItems.any {
            it.state == ExportItemEntity.STATE_COPY_FAILED ||
                it.state == ExportItemEntity.STATE_VERIFY_FAILED
        }
        val finalState = when {
            hadFailures || kept > 0 -> ExportOperationEntity.STATE_COMPLETED_WITH_ERRORS
            else -> ExportOperationEntity.STATE_COMPLETED
        }
        dao.updateOperationState(operationId, finalState, now())
        Timber.i(
            "Export $operationId consent finalised: $deleted source(s) deleted, $kept kept, state=$finalState"
        )
        return Outcome(finalState, deleted, kept)
    }

    /** Operations stranded before the prompt, e.g. by process death. */
    suspend fun operationsAwaitingConsent(): List<ExportOperationEntity> =
        db.exportDao().operationsInState(ExportOperationEntity.STATE_AWAITING_DELETE_CONSENT)

    companion object {
        /**
         * The system delete dialog lists every file; very large batches are
         * slow to render and hostile to review, so prompts are chunked.
         */
        const val CONSENT_BATCH_SIZE = 250
    }
}
