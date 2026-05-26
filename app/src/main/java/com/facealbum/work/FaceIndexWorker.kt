package com.facealbum.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.facealbum.MainActivity
import com.facealbum.R
import com.facealbum.domain.FaceIndexUseCase
import com.facealbum.telemetry.CrashReporter
import timber.log.Timber

/**
 * Background worker that runs [FaceIndexUseCase] under a foreground notification.
 *
 * Triggered manually from the UI ("Scan now") or whenever the app starts and the
 * index is empty.
 */
class FaceIndexWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val forceFull = inputData.getBoolean(KEY_FORCE_FULL_RESCAN, false)
        val useCase = FaceIndexUseCase(applicationContext)
        return try {
            setForeground(buildForegroundInfo(0, 0))
            useCase.run(forceFullRescan = forceFull) { progress ->
                setProgress(
                    workDataOf(
                        KEY_PROGRESS_DONE to progress.processed,
                        KEY_PROGRESS_TOTAL to progress.total,
                        KEY_PROGRESS_FACES to progress.facesFound,
                        KEY_PROGRESS_CLUSTERS to progress.clustersTotal
                    )
                )
                setForeground(buildForegroundInfo(progress.processed, progress.total))
            }
            Result.success()
        } catch (e: FaceIndexUseCase.ModelNotReadyException) {
            Timber.w(e, "Face index aborted: model not ready")
            Result.failure(workDataOf(KEY_ERROR_MESSAGE to e.message))
        } catch (t: Throwable) {
            Timber.e(t, "Face index worker failed")
            CrashReporter.recordNonFatal(throwable = t, source = "face_index_worker")
            Result.retry()
        } finally {
            useCase.close()
        }
    }

    private fun buildForegroundInfo(done: Int, total: Int): ForegroundInfo {
        val ctx = applicationContext
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        ctx.getString(R.string.notif_channel_indexing),
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }

        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            ctx,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = ctx.getString(R.string.notif_title_indexing)
        val text = if (total > 0) {
            ctx.getString(R.string.notif_text_progress, done, total)
        } else {
            ctx.getString(R.string.notif_text_starting)
        }

        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(total.coerceAtLeast(1), done, total == 0)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, notif)
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "face_index"
        private const val CHANNEL_ID = "face_index_channel"
        private const val NOTIF_ID = 4242

        const val KEY_FORCE_FULL_RESCAN = "forceFullRescan"
        const val KEY_PROGRESS_DONE = "progress_done"
        const val KEY_PROGRESS_TOTAL = "progress_total"
        const val KEY_PROGRESS_FACES = "progress_faces"
        const val KEY_PROGRESS_CLUSTERS = "progress_clusters"
        const val KEY_ERROR_MESSAGE = "error_message"

        fun enqueue(context: Context, forceFullRescan: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<FaceIndexWorker>()
                .setInputData(Data.Builder().putBoolean(KEY_FORCE_FULL_RESCAN, forceFullRescan).build())
                .build()
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
