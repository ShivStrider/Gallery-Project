package com.facealbum.domain

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.room.withTransaction
import com.facealbum.data.PhotoRepository
import com.facealbum.data.db.ExportItemEntity
import com.facealbum.data.db.ExportOperationEntity
import com.facealbum.data.db.FaceAlbumDatabase
import com.facealbum.data.db.findByIdsChunked
import com.facealbum.work.ExportWorker
import timber.log.Timber

/**
 * Builds and commits export plans.
 *
 * A plan is a complete, inspectable list of exactly which files an export
 * will touch — produced *before* anything is written, so the confirmation UI
 * can show the truth rather than a promise. Committing a plan writes the
 * export transaction log and hands execution to the worker.
 *
 * This is the single entry point for both whole-cluster and subset exports,
 * replacing the two near-identical methods that previously drifted apart.
 */
class ExportPlanner(
    private val context: Context,
    private val db: FaceAlbumDatabase = FaceAlbumDatabase.get(context),
    private val photoRepository: PhotoRepository = PhotoRepository(context),
    private val now: () -> Long = { System.currentTimeMillis() },
    /** Injected so tests can commit a plan without a WorkManager instance. */
    private val enqueueWorker: (Long) -> Unit = { ExportWorker.enqueue(context, it) }
) {

    enum class Mode { COPY, MOVE }

    /** One file as it will appear in the confirmation sheet. */
    data class PlannedItem(
        val photoRowId: Long,
        val sourceMediaStoreId: Long,
        val sourceUri: String,
        val sourceDisplayName: String,
        val sourceRelativePath: String?,
        val sizeBytes: Long,
        val destDisplayName: String,
        /** This photo also contains a face assigned to another person. */
        val containsOtherPeople: Boolean
    )

    data class Plan(
        val clusterId: Long,
        val albumName: String,
        val destRelativePath: String,
        val mode: Mode,
        val items: List<PlannedItem>,
        /** Requested photos that aren't in this cluster, or whose source is gone. */
        val rejectedCount: Int
    ) {
        val fileCount: Int get() = items.size
        val totalBytes: Long get() = items.sumOf { it.sizeBytes }
        val sourceFolders: List<String>
            get() = items.mapNotNull { it.sourceRelativePath }.distinct().sorted()
    }

    sealed class CommitResult {
        data class Started(val operationId: Long) : CommitResult()
        object NothingToDo : CommitResult()
        object MoveUnsupported : CommitResult()
    }

    /**
     * @param photoRowIds explicit subset, or null for the whole cluster.
     */
    suspend fun plan(
        clusterId: Long,
        requestedAlbumName: String,
        photoRowIds: List<Long>? = null,
        mode: Mode = Mode.COPY
    ): Plan {
        val cluster = db.clusterDao().byId(clusterId)
        val albumName = requestedAlbumName
            .trim()
            .ifBlank { cluster?.displayName?.takeIf { it.isNotBlank() } ?: "Person_$clusterId" }
            .let(::sanitizeAlbumName)
        val destRelativePath = "Pictures/FaceAlbums/$albumName/"

        if (cluster == null) {
            return Plan(clusterId, albumName, destRelativePath, mode, emptyList(), 0)
        }

        // Defense in depth: a stale selection (carried over from another
        // cluster by the activity-scoped ViewModel) must never land in this
        // album. Intersect the request with actual membership.
        val membership = db.faceDao().photoIdsInCluster(clusterId)
        val eligibleIds = if (photoRowIds == null) {
            membership
        } else {
            val allowed = membership.toSet()
            photoRowIds.filter { it in allowed }
        }
        // Only an explicit selection can have entries rejected for membership;
        // a whole-cluster export starts from the membership list itself, so
        // nothing is dropped here. (Sources that vanished are counted below.)
        var rejected = if (photoRowIds == null) 0 else photoRowIds.size - eligibleIds.size
        if (rejected > 0) {
            Timber.w("Export plan: rejected $rejected photo id(s) outside cluster $clusterId")
        }
        if (eligibleIds.isEmpty()) {
            return Plan(clusterId, albumName, destRelativePath, mode, emptyList(), rejected)
        }

        val photoRows = db.photoDao().findByIdsChunked(eligibleIds).associateBy { it.id }
        val metadata = photoRepository.querySourceMetadata(
            photoRows.values.map { it.mediaStoreId }
        )

        // Names are resolved here, up front — a collision is never discovered
        // mid-copy, and the same plan always produces the same destinations.
        val usedNames = HashSet<String>()
        val items = ArrayList<PlannedItem>(eligibleIds.size)
        for (photoRowId in eligibleIds) {
            val row = photoRows[photoRowId]
            if (row == null) {
                rejected += 1
                continue
            }
            val meta = metadata[row.mediaStoreId]
            if (meta == null) {
                // Source vanished between indexing and export.
                rejected += 1
                continue
            }
            val sourceUri = Uri.parse(row.uri)
            val baseName = photoRepository.makeStableDisplayName(meta.displayName, sourceUri)
            val destName = uniqueName(baseName, usedNames)
            val otherPeople = db.faceDao().facesForPhoto(photoRowId)
                .any { it.clusterId != null && it.clusterId != clusterId }

            items += PlannedItem(
                photoRowId = photoRowId,
                sourceMediaStoreId = row.mediaStoreId,
                sourceUri = row.uri,
                sourceDisplayName = meta.displayName,
                sourceRelativePath = meta.relativePath,
                sizeBytes = meta.sizeBytes,
                destDisplayName = destName,
                containsOtherPeople = otherPeople
            )
        }

        return Plan(clusterId, albumName, destRelativePath, mode, items, rejected)
    }

    /**
     * Persist [plan] as an operation plus its per-file log. Nothing is copied
     * here — the worker does that, reading its work list from the log so a
     * kill at any point resumes rather than restarts.
     */
    suspend fun commit(plan: Plan): CommitResult {
        if (plan.items.isEmpty()) return CommitResult.NothingToDo
        if (plan.mode == Mode.MOVE && !isMoveSupported()) {
            // Below API 30 deleting media this app doesn't own needs either a
            // per-file consent dialog or WRITE_EXTERNAL_STORAGE, which the app
            // deliberately does not hold.
            Timber.w("Move mode requested on API ${Build.VERSION.SDK_INT}; unsupported")
            return CommitResult.MoveUnsupported
        }

        val timestamp = now()
        val operationId = db.withTransaction {
            val opId = db.exportDao().insertOperation(
                ExportOperationEntity(
                    clusterId = plan.clusterId,
                    albumName = plan.albumName,
                    destRelativePath = plan.destRelativePath,
                    mode = when (plan.mode) {
                        Mode.COPY -> ExportOperationEntity.MODE_COPY
                        Mode.MOVE -> ExportOperationEntity.MODE_MOVE
                    },
                    state = ExportOperationEntity.STATE_PENDING,
                    totalCount = plan.items.size,
                    createdAt = timestamp,
                    updatedAt = timestamp
                )
            )
            db.exportDao().insertItems(
                plan.items.map { item ->
                    ExportItemEntity(
                        operationId = opId,
                        photoId = item.photoRowId,
                        sourceMediaStoreId = item.sourceMediaStoreId,
                        sourceUri = item.sourceUri,
                        sourceDisplayName = item.sourceDisplayName,
                        sourceRelativePath = item.sourceRelativePath,
                        sourceSizeBytes = item.sizeBytes,
                        sourceSha256 = null,
                        destDisplayName = item.destDisplayName,
                        destUri = null,
                        state = ExportItemEntity.STATE_PENDING,
                        errorCode = null,
                        updatedAt = timestamp
                    )
                }
            )
            opId
        }
        Timber.i("Export operation $operationId committed with ${plan.items.size} item(s)")
        enqueueWorker(operationId)
        return CommitResult.Started(operationId)
    }

    /**
     * Two files in one album can legitimately share a name (same filename in
     * different source folders). The stable-name token usually separates them;
     * this is the backstop so a plan never maps two sources onto one file.
     */
    private fun uniqueName(baseName: String, used: MutableSet<String>): String {
        if (used.add(baseName)) return baseName
        val dot = baseName.lastIndexOf('.')
        val stem = if (dot > 0) baseName.substring(0, dot) else baseName
        val ext = if (dot > 0) baseName.substring(dot) else ""
        var suffix = 1
        while (true) {
            val candidate = "${stem}_$suffix$ext"
            if (used.add(candidate)) return candidate
            suffix += 1
        }
    }

    /**
     * Allow letters, numbers, spaces, dashes, underscores. Strip everything else
     * to keep the MediaStore RELATIVE_PATH simple and predictable.
     */
    private fun sanitizeAlbumName(raw: String): String {
        val cleaned = raw.replace(Regex("[^A-Za-z0-9 _-]"), "_").trim()
        return cleaned.ifBlank { "Person" }.take(60)
    }

    companion object {
        /**
         * Move requires `MediaStore.createDeleteRequest`, which arrived in
         * API 30. See docs/plan/05-safe-export-design.md for why earlier
         * versions are copy-only.
         */
        fun isMoveSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    }
}
