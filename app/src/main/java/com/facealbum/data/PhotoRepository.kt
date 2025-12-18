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

/**
 * Repository for accessing device photos via MediaStore.
 */
class PhotoRepository(private val context: Context) {

    /**
     * Query the most recent photos from the device.
     *
     * @param limit Maximum number of photos to retrieve
     * @return List of photo information, sorted by date taken (newest first)
     */
    suspend fun queryRecentPhotos(limit: Int): List<PhotoInfo> = withContext(Dispatchers.IO) {
        val photos = mutableListOf<PhotoInfo>()

        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED
        )

        val selection = "${MediaStore.Images.Media.MIME_TYPE} LIKE ?"
        val selectionArgs = arrayOf("image/%")

        // Sort by DATE_TAKEN descending
        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC LIMIT $limit"

        context.contentResolver.query(
            collection,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                photos.add(
                    PhotoInfo(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id),
                        dateTaken = cursor.getLong(dateCol),
                        displayName = cursor.getString(nameCol)
                    )
                )
            }
        }

        photos
    }

    /**
     * Copy a photo to the FaceAlbums folder.
     *
     * @param sourceUri URI of the source photo
     * @param albumName Name of the album (subfolder)
     * @param originalFileName Original filename
     * @return URI of the copied file, or null if copy failed
     */
    suspend fun copyToAlbum(
        sourceUri: Uri,
        albumName: String,
        originalFileName: String
    ): Uri? = withContext(Dispatchers.IO) {
        val relativePath = "${Environment.DIRECTORY_PICTURES}/FaceAlbums/$albumName"

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, originalFileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val destUri = resolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ) ?: return@withContext null

        try {
            resolver.openInputStream(sourceUri)?.use { input ->
                resolver.openOutputStream(destUri)?.use { output ->
                    input.copyTo(output)
                }
            }

            // Mark as complete
            contentValues.clear()
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(destUri, contentValues, null, null)

            destUri
        } catch (e: Exception) {
            resolver.delete(destUri, null, null)
            null
        }
    }
}
