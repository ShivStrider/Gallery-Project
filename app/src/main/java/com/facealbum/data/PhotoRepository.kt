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
     * Query the most recent photos from the device.
     *
     * @param limit Maximum number of photos to retrieve
     * @return List of photo information, sorted by date taken (newest first)
     */
    suspend fun queryRecentPhotos(limit: Int): List<PhotoInfo> = withContext(Dispatchers.IO) {
        queryPhotos(
            selection = "${MediaStore.Images.Media.MIME_TYPE} LIKE ?",
            selectionArgs = arrayOf("image/%"),
            sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC LIMIT $limit"
        )
    }

    /**
     * Query every image in MediaStore that was added or modified after [sinceDateModifiedSec].
     * `dateModified` is in seconds since epoch (MediaStore convention).
     *
     * Passing 0 returns the entire library — used for the first full index.
     */
    suspend fun queryPhotosModifiedSince(sinceDateModifiedSec: Long): List<PhotoInfo> =
        withContext(Dispatchers.IO) {
            queryPhotos(
                selection = "${MediaStore.Images.Media.MIME_TYPE} LIKE ? AND " +
                    "${MediaStore.Images.Media.DATE_MODIFIED} > ?",
                selectionArgs = arrayOf("image/%", sinceDateModifiedSec.toString()),
                sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} ASC"
            )
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

    suspend fun copyToAlbum(sourceUri: Uri, albumName: String, originalFileName: String): Uri? {
        return when (val result = copyToAlbumWithResult(sourceUri, albumName, originalFileName)) {
            is CopyToAlbumResult.Success -> result.uri
            is CopyToAlbumResult.Failure -> null
        }
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
        val uniqueName = makeUniqueDisplayName(originalFileName)

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, uniqueName)
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

    private fun makeUniqueDisplayName(originalFileName: String): String {
        val dot = originalFileName.lastIndexOf('.')
        val base = if (dot > 0) originalFileName.substring(0, dot) else originalFileName
        val ext = if (dot > 0) originalFileName.substring(dot) else ""
        return "${base}_${System.currentTimeMillis()}$ext"
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
