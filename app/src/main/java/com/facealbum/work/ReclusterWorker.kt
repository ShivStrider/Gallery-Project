package com.facealbum.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.facealbum.R
import com.facealbum.data.db.FaceAlbumDatabase
import com.facealbum.data.prefs.UserPreferences
import com.facealbum.domain.ReclusterUseCase
import com.facealbum.telemetry.CrashReporter
import timber.log.Timber

/**
 * Re-groups every persisted face under the user's current threshold preferences.
 * Runs as foreground work so it survives the user navigating away from
 * Settings (and so the system doesn't kill it mid-pass).
 */
class ReclusterWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val useCase = ReclusterUseCase(
            FaceAlbumDatabase.get(applicationContext),
            UserPreferences.get(applicationContext)
        )
        return try {
            setForeground(buildForegroundInfo(0, 0))
            useCase.run { progress ->
                setProgress(
                    workDataOf(
                        KEY_PROGRESS_DONE to progress.processed,
                        KEY_PROGRESS_TOTAL to progress.total,
                        KEY_PROGRESS_CLUSTERS to progress.clusters
                    )
                )
                setForeground(buildForegroundInfo(progress.processed, progress.total))
            }
            Result.success()
        } catch (t: Throwable) {
            Timber.e(t, "Recluster worker failed")
            CrashReporter.recordNonFatal(throwable = t, source = "recluster_worker")
            Result.failure(workDataOf(KEY_ERROR_MESSAGE to (t.message ?: "Recluster failed")))
        }
    }

    private fun buildForegroundInfo(done: Int, total: Int): ForegroundInfo {
        val ctx = applicationContext
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            nm.getNotificationChannel(CHANNEL_ID) == null
        ) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    ctx.getString(R.string.notif_channel_reclustering),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val text = if (total > 0) {
            ctx.getString(R.string.notif_text_recluster_progress, done, total)
        } else {
            ctx.getString(R.string.notif_text_starting)
        }
        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(ctx.getString(R.string.notif_title_reclustering))
            .setContentText(text)
            .setProgress(total.coerceAtLeast(1), done, total == 0)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, notif)
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "recluster"
        private const val CHANNEL_ID = "recluster_channel"
        private const val NOTIF_ID = 4243

        const val KEY_PROGRESS_DONE = "recluster_done"
        const val KEY_PROGRESS_TOTAL = "recluster_total"
        const val KEY_PROGRESS_CLUSTERS = "recluster_clusters"
        const val KEY_ERROR_MESSAGE = "recluster_error"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<ReclusterWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }
}
