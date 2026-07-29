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
     */
    suspend fun copyToAlbumWithResult(
        sourceUri: Uri,
        albumName: String,
        originalFileName: String
    ): CopyToAlbumResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val relativePath = "${Environment.DIRECTORY_PICTURES}/FaceAlbums/$albumName"
        val mimeType = resolver.getType(sourceUri) ?: deriveMimeTypeFromFileName(originalFileName)
        val stableName = makeStableDisplayName(originalFileName, sourceUri)

        val existing = findExistingInAlbum(relativePath, stableName)
        if (existing != null) {
            return@withContext CopyToAlbumResult.Success(existing)
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, stableName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val destUri = resolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ) ?: return@withContext CopyToAlbumResult.Failure(CopyToAlbumError.INSERT_FAILED)

        try {
            val input = resolver.openInputStream(sourceUri)
                ?: throw CopyFailureException(CopyToAlbumError.SOURCE_OPEN_FAILED)
            input.use { source ->
                val output = resolver.openOutputStream(destUri)
                    ?: throw CopyFailureException(CopyToAlbumError.DESTINATION_OPEN_FAILED)
                output.use { sink ->
                    source.copyTo(sink)
                }
            }

            val complete = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            val rowsUpdated = resolver.update(destUri, complete, null, null)
            if (rowsUpdated <= 0) {
                throw CopyFailureException(CopyToAlbumError.FINALIZE_FAILED)
            }

            CopyToAlbumResult.Success(destUri)
        } catch (e: CopyFailureException) {
            resolver.delete(destUri, null, null)
            CopyToAlbumResult.Failure(e.error)
        } catch (e: IOException) {
            resolver.delete(destUri, null, null)
            CopyToAlbumResult.Failure(CopyToAlbumError.COPY_FAILED)
        } catch (e: Exception) {
            resolver.delete(destUri, null, null)
            CopyToAlbumResult.Failure(CopyToAlbumError.COPY_FAILED)
        }
    }

    private fun makeStableDisplayName(originalFileName: String, sourceUri: Uri): String {
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
}
