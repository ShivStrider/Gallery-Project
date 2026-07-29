package com.facealbum.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.facealbum.model.PhotoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.MessageDigest

/**
 * Repository for accessing device photos via MediaStore.
 */
class PhotoRepository(private val context: Context) {

    sealed class CopyToAlbumResult {
        data class Success(val uri: Uri) : CopyToAlbumResult()
        data class Failure(val error: CopyToAlbumError) : CopyToAlbumResult()
    }

    enum class CopyToAlbumError {
        INSERT_FAILED,
        SOURCE_OPEN_FAILED,
        DESTINATION_OPEN_FAILED,
        COPY_FAILED,
        FINALIZE_FAILED
    }

    /**
     * Outcome of a checked copy — carries the evidence a later verification
     * pass needs. [dedupHit] means the destination already existed and no
     * bytes were written, which is *not* by itself proof the file matches:
     * move mode must still verify it before deleting the source.
     */
    sealed class CheckedCopyResult {
        data class Success(
            val uri: Uri,
            val bytesCopied: Long,
            val sha256: String?,
            val dedupHit: Boolean
        ) : CheckedCopyResult()

        data class Failure(val error: CopyToAlbumError) : CheckedCopyResult()
    }

    /** Result of independently re-reading a destination copy. */
    sealed class VerifyResult {
        object Verified : VerifyResult()
        data class Failed(val error: VerifyError) : VerifyResult()
    }

    enum class VerifyError {
        DEST_MISSING,
        DEST_UNREADABLE,
        SIZE_MISMATCH,
        CHECKSUM_MISMATCH
    }

    /** Source facts captured at plan time, before anything is written. */
    data class SourceMetadata(
        val mediaStoreId: Long,
        val displayName: String,
        val relativePath: String?,
        val sizeBytes: Long
    )

    /**
     * Query every image in MediaStore that was added or modified after [sinceDateModifiedSec].
     * `dateModified` is in seconds since epoch (MediaStore convention).
     *
     * Passing 0 returns the entire library — the first full index path.
     */
    suspend fun queryPhotosModifiedSince(sinceDateModifiedSec: Long): List<PhotoInfo> =
        withContext(Dispatchers.IO) {
            if (sinceDateModifiedSec <= 0L) {
                queryPhotos(
                    selection = "${MediaStore.Images.Media.MIME_TYPE} LIKE ?",
                    selectionArgs = arrayOf("image/%"),
                    sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} ASC"
                )
            } else {
                queryPhotos(
                    selection = "${MediaStore.Images.Media.MIME_TYPE} LIKE ? AND " +
                        "${MediaStore.Images.Media.DATE_MODIFIED} > ?",
                    selectionArgs = arrayOf("image/%", sinceDateModifiedSec.toString()),
                    sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} ASC"
                )
            }
        }

    /**
     * Snapshot of every image _ID currently visible in MediaStore. Used to
     * reconcile the local index against photos deleted or moved outside the
     * app. Under Android 14 partial access this returns only the user's
     * selection — reconciliation callers must treat "not visible" as
     * "unavailable", which is still the correct grouping behaviour.
     */
    suspend fun queryAllMediaStoreIds(): Set<Long> = withContext(Dispatchers.IO) {
        val ids = HashSet<Long>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.MIME_TYPE} LIKE ?",
            arrayOf("image/%"),
            null
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext()) {
                ids.add(cursor.getLong(idCol))
            }
        }
        ids
    }

    private fun queryPhotos(
        selection: String,
        selectionArgs: Array<String>,
        sortOrder: String
    ): List<PhotoInfo> {
        val photos = mutableListOf<PhotoInfo>()
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED
        )

        context.contentResolver.query(
            collection,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val takenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val modCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                photos.add(
                    PhotoInfo(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id),
                        dateTaken = cursor.getLong(takenCol),
                        displayName = cursor.getString(nameCol),
                        dateModified = cursor.getLong(modCol)
                    )
                )
            }
        }
        return photos
    }

    /**
     * Copy a photo to the FaceAlbums folder with explicit success/failure state.
     * Destination name is derived from the source; see [copyToAlbumChecked] for
     * the export path, which resolves names up front at plan time.
     */
    suspend fun copyToAlbumWithResult(
        sourceUri: Uri,
        albumName: String,
        originalFileName: String
    ): CopyToAlbumResult {
        val destName = makeStableDisplayName(originalFileName, sourceUri)
        return when (
            val result = copyToAlbumChecked(sourceUri, albumName, destName, computeChecksum = false)
        ) {
            is CheckedCopyResult.Success -> CopyToAlbumResult.Success(result.uri)
            is CheckedCopyResult.Failure -> CopyToAlbumResult.Failure(result.error)
        }
    }

    /**
     * Copy to `Pictures/FaceAlbums/<albumName>/<destDisplayName>`, streaming
     * through a SHA-256 digest so the bytes actually written can be checked
     * later without re-reading the source.
     *
     * [destDisplayName] is resolved by the caller at plan time — filename
     * collisions are decided before any byte is written, never mid-copy.
     *
     * On any failure the partially written destination row is deleted, so a
     * failed copy never leaves a half file behind. The source is never touched.
     */
    suspend fun copyToAlbumChecked(
        sourceUri: Uri,
        albumName: String,
        destDisplayName: String,
        computeChecksum: Boolean = true
    ): CheckedCopyResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val relativePath = "${Environment.DIRECTORY_PICTURES}/FaceAlbums/$albumName"
        val mimeType = resolver.getType(sourceUri) ?: deriveMimeTypeFromFileName(destDisplayName)

        val existing = findExistingInAlbum(relativePath, destDisplayName)
        if (existing != null) {
            // Bytes already present. Deliberately reported as a dedup hit with
            // no checksum: callers that intend to delete the source must
            // verify this file first — a same-named file is not proof of
            // matching content.
            return@withContext CheckedCopyResult.Success(
                uri = existing,
                bytesCopied = 0L,
                sha256 = null,
                dedupHit = true
            )
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, destDisplayName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val destUri = resolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ) ?: return@withContext CheckedCopyResult.Failure(CopyToAlbumError.INSERT_FAILED)

        try {
            val digest = if (computeChecksum) MessageDigest.getInstance("SHA-256") else null
            var copied = 0L

            val input = resolver.openInputStream(sourceUri)
                ?: throw CopyFailureException(CopyToAlbumError.SOURCE_OPEN_FAILED)
            input.use { source ->
                val output = resolver.openOutputStream(destUri)
                    ?: throw CopyFailureException(CopyToAlbumError.DESTINATION_OPEN_FAILED)
                output.use { sink ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val read = source.read(buffer)
                        if (read == -1) break
                        sink.write(buffer, 0, read)
                        digest?.update(buffer, 0, read)
                        copied += read
                    }
                    sink.flush()
                }
            }

            val complete = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            val rowsUpdated = resolver.update(destUri, complete, null, null)
            if (rowsUpdated <= 0) {
                throw CopyFailureException(CopyToAlbumError.FINALIZE_FAILED)
            }

            CheckedCopyResult.Success(
                uri = destUri,
                bytesCopied = copied,
                sha256 = digest?.let { toHex(it.digest()) },
                dedupHit = false
            )
        } catch (e: CopyFailureException) {
            resolver.delete(destUri, null, null)
            CheckedCopyResult.Failure(e.error)
        } catch (e: IOException) {
            resolver.delete(destUri, null, null)
            CheckedCopyResult.Failure(CopyToAlbumError.COPY_FAILED)
        } catch (e: Exception) {
            resolver.delete(destUri, null, null)
            CheckedCopyResult.Failure(CopyToAlbumError.COPY_FAILED)
        }
    }

    /**
     * Independently re-read a destination copy and prove it matches the
     * source: it exists, it opens, its length matches, and its bytes hash to
     * [expectedSha256]. This is the gate a move must pass before the source
     * may be deleted — nothing else in the codebase authorises deletion.
     *
     * Reads the destination fresh rather than trusting the copy's own
     * bookkeeping, so a silently truncated write is caught.
     */
    suspend fun verifyExportedCopy(
        destUri: Uri,
        expectedSizeBytes: Long,
        expectedSha256: String?
    ): VerifyResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver

        val actualSize = try {
            resolver.query(destUri, arrayOf(MediaStore.Images.Media.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getLong(0) else null
                }
        } catch (e: Exception) {
            null
        } ?: return@withContext VerifyResult.Failed(VerifyError.DEST_MISSING)

        if (expectedSizeBytes >= 0 && actualSize != expectedSizeBytes) {
            return@withContext VerifyResult.Failed(VerifyError.SIZE_MISMATCH)
        }

        val digest = MessageDigest.getInstance("SHA-256")
        var readTotal = 0L
        try {
            val input = resolver.openInputStream(destUri)
                ?: return@withContext VerifyResult.Failed(VerifyError.DEST_UNREADABLE)
            input.use { stream ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val read = stream.read(buffer)
                    if (read == -1) break
                    digest.update(buffer, 0, read)
                    readTotal += read
                }
            }
        } catch (e: Exception) {
            return@withContext VerifyResult.Failed(VerifyError.DEST_UNREADABLE)
        }

        if (expectedSizeBytes >= 0 && readTotal != expectedSizeBytes) {
            return@withContext VerifyResult.Failed(VerifyError.SIZE_MISMATCH)
        }
        if (expectedSha256 != null && !toHex(digest.digest()).equals(expectedSha256, ignoreCase = true)) {
            return@withContext VerifyResult.Failed(VerifyError.CHECKSUM_MISMATCH)
        }
        VerifyResult.Verified
    }

    /**
     * Put a file back where it came from, streaming out of the exported copy.
     *
     * Used by undo after a move. The restored file is verified against
     * [expectedSha256] before this reports success, so a failed restore is
     * detectable rather than silent — the caller must keep the exported copy
     * in that case, since it may then be the only surviving version.
     *
     * The restored file gets a new MediaStore id; the stale index row heals
     * on the next incremental scan.
     */
    suspend fun restoreFromCopy(
        sourceCopyUri: Uri,
        targetRelativePath: String,
        targetDisplayName: String,
        expectedSha256: String?
    ): CheckedCopyResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mimeType = resolver.getType(sourceCopyUri)
            ?: deriveMimeTypeFromFileName(targetDisplayName)
        val relativePath = targetRelativePath.trimEnd('/')

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, targetDisplayName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val restoredUri = resolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ) ?: return@withContext CheckedCopyResult.Failure(CopyToAlbumError.INSERT_FAILED)

        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            val input = resolver.openInputStream(sourceCopyUri)
                ?: throw CopyFailureException(CopyToAlbumError.SOURCE_OPEN_FAILED)
            input.use { source ->
                val output = resolver.openOutputStream(restoredUri)
                    ?: throw CopyFailureException(CopyToAlbumError.DESTINATION_OPEN_FAILED)
                output.use { sink ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val read = source.read(buffer)
                        if (read == -1) break
                        sink.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        copied += read
                    }
                    sink.flush()
                }
            }

            val restoredSha = toHex(digest.digest())
            if (expectedSha256 != null && !restoredSha.equals(expectedSha256, ignoreCase = true)) {
                throw CopyFailureException(CopyToAlbumError.COPY_FAILED)
            }

            val complete = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            if (resolver.update(restoredUri, complete, null, null) <= 0) {
                throw CopyFailureException(CopyToAlbumError.FINALIZE_FAILED)
            }

            CheckedCopyResult.Success(
                uri = restoredUri,
                bytesCopied = copied,
                sha256 = restoredSha,
                dedupHit = false
            )
        } catch (e: CopyFailureException) {
            resolver.delete(restoredUri, null, null)
            CheckedCopyResult.Failure(e.error)
        } catch (e: Exception) {
            resolver.delete(restoredUri, null, null)
            CheckedCopyResult.Failure(CopyToAlbumError.COPY_FAILED)
        }
    }

    /** Hash a source file without copying it — used to check dedup hits. */
    suspend fun sha256Of(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val read = stream.read(buffer)
                    if (read == -1) break
                    digest.update(buffer, 0, read)
                }
            } ?: return@withContext null
            toHex(digest.digest())
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Source facts for the export preview and for undo. Captured before the
     * operation starts, because the source row may be gone afterwards.
     */
    suspend fun querySourceMetadata(mediaStoreIds: List<Long>): Map<Long, SourceMetadata> =
        withContext(Dispatchers.IO) {
            if (mediaStoreIds.isEmpty()) return@withContext emptyMap()
            val out = HashMap<Long, SourceMetadata>(mediaStoreIds.size)
            mediaStoreIds.chunked(SQL_VARIABLE_CHUNK).forEach { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(
                        MediaStore.Images.Media._ID,
                        MediaStore.Images.Media.DISPLAY_NAME,
                        MediaStore.Images.Media.RELATIVE_PATH,
                        MediaStore.Images.Media.SIZE
                    ),
                    "${MediaStore.Images.Media._ID} IN ($placeholders)",
                    chunk.map { it.toString() }.toTypedArray(),
                    null
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        out[id] = SourceMetadata(
                            mediaStoreId = id,
                            displayName = cursor.getString(nameCol) ?: "",
                            relativePath = cursor.getString(pathCol),
                            sizeBytes = cursor.getLong(sizeCol)
                        )
                    }
                }
            }
            out
        }

    /**
     * Delete a file this app created under `Pictures/FaceAlbums/`. App-owned
     * media needs no user consent — unlike source photos, which can only be
     * removed through `MediaStore.createDeleteRequest`. Used by undo.
     */
    suspend fun deleteOwnedDest(destUri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.delete(destUri, null, null) > 0
        } catch (e: Exception) {
            false
        }
    }

    /** True when a source row is still present — used to confirm deletions. */
    suspend fun sourceStillExists(mediaStoreId: Long): Boolean = withContext(Dispatchers.IO) {
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media._ID} = ?",
            arrayOf(mediaStoreId.toString()),
            null
        )?.use { it.moveToFirst() } ?: false
    }

    private fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
        return sb.toString()
    }

    internal fun makeStableDisplayName(originalFileName: String, sourceUri: Uri): String {
        val dot = originalFileName.lastIndexOf('.')
        val base = if (dot > 0) originalFileName.substring(0, dot) else originalFileName
        val ext = if (dot > 0) originalFileName.substring(dot) else ""
        val sourceToken = sourceUri.lastPathSegment?.takeLast(16)?.replace(Regex("[^A-Za-z0-9_-]"), "")
            ?: "src"
        return "${base}_${sourceToken}$ext"
    }

    private fun findExistingInAlbum(relativePath: String, displayName: String): Uri? {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH}=? AND ${MediaStore.Images.Media.DISPLAY_NAME}=?"
        val args = arrayOf(relativePath, displayName)
        context.contentResolver.query(collection, projection, selection, args, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                return ContentUris.withAppendedId(collection, id)
            }
        }
        return null
    }

    private fun deriveMimeTypeFromFileName(fileName: String): String {
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "heic" -> "image/heic"
            "heif" -> "image/heif"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }
    }

    private class CopyFailureException(val error: CopyToAlbumError) : RuntimeException()

    private companion object {
        const val COPY_BUFFER_BYTES = 64 * 1024

        /** Stay under SQLite's 999-bind-variable limit for IN() clauses. */
        const val SQL_VARIABLE_CHUNK = 900

        val HEX = "0123456789abcdef".toCharArray()
    }
}
