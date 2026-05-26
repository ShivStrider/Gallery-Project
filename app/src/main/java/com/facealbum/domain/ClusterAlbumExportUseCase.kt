package com.facealbum.domain

import android.content.Context
import android.net.Uri
import com.facealbum.data.PhotoRepository
import com.facealbum.data.db.AlbumEntity
import com.facealbum.data.db.FaceAlbumDatabase
import com.facealbum.telemetry.CrashReporter
import timber.log.Timber

class ClusterAlbumExportUseCase(
    private val context: Context,
    private val db: FaceAlbumDatabase = FaceAlbumDatabase.get(context),
    private val photoRepository: PhotoRepository = PhotoRepository(context)
) {

    data class Result(val successCount: Int, val failedCount: Int, val albumName: String)

    suspend fun export(clusterId: Long, requestedAlbumName: String): Result {
        val cluster = db.clusterDao().byId(clusterId)
            ?: return Result(0, 0, requestedAlbumName)
        val albumName = requestedAlbumName
            .trim()
            .ifBlank { cluster.displayName?.takeIf { it.isNotBlank() } ?: "Person_${cluster.id}" }
            .let(::sanitizeAlbumName)

        val photoRowIds = db.faceDao().photoIdsInCluster(clusterId)
        Timber.i("Exporting cluster $clusterId with ${photoRowIds.size} unique photos to '$albumName'")

        var success = 0
        var failure = 0
        for (photoRowId in photoRowIds) {
            val photoRow = db.photoDao().findById(photoRowId)
            if (photoRow == null) {
                failure += 1
                continue
            }
            val result = photoRepository.copyToAlbumWithResult(
                sourceUri = Uri.parse(photoRow.uri),
                albumName = albumName,
                originalFileName = photoRow.displayName
            )
            when (result) {
                is PhotoRepository.CopyToAlbumResult.Success -> success += 1
                is PhotoRepository.CopyToAlbumResult.Failure -> {
                    failure += 1
                    CrashReporter.recordNonFatal(
                        throwable = IllegalStateException("Export failed: ${result.error}"),
                        source = "cluster_export",
                        context = mapOf("error" to result.error.name)
                    )
                }
            }
        }

        if (success > 0) {
            db.albumDao().insert(
                AlbumEntity(
                    clusterId = clusterId,
                    albumName = albumName,
                    exportedRelativePath = "Pictures/FaceAlbums/$albumName",
                    exportedAt = System.currentTimeMillis(),
                    photoCount = success
                )
            )
        }

        return Result(success, failure, albumName)
    }

    /**
     * Allow letters, numbers, spaces, dashes, underscores. Strip everything else
     * to keep the MediaStore RELATIVE_PATH simple and predictable.
     */
    private fun sanitizeAlbumName(raw: String): String {
        val cleaned = raw.replace(Regex("[^A-Za-z0-9 _-]"), "_").trim()
        return cleaned.ifBlank { "Person" }.take(60)
    }
}
