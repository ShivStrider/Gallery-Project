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
import com.facealbum.domain.ExportExecutor
import com.facealbum.telemetry.CrashReporter
import timber.log.Timber

/**
 * Runs the copy + verify phase of an export under a foreground notification.
 *
 * Deliberately does **not** delete anything: for a move, this worker stops at
 * `AWAITING_DELETE_CONSENT` and the UI asks the user through the system
 * delete dialog. Android does not permit silently deleting media the app
 * doesn't own, and we would not want to even if it did.
 *
 * Resumption is a property of the export log rather than of this worker: each
 * item's state is committed before the next file is touched, so a re-run
 * simply skips what's finished. That also makes a retry safe.
 */
class ExportWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val operationId = inputData.getLong(KEY_OPERATION_ID, -1L)
        if (operationId <= 0L) {
            Timber.e("ExportWorker started without a valid operation id")
            return Result.failure()
        }

        return try {
            setForeground(buildForegroundInfo(0, 0))
            val finalState = ExportExecutor(applicationContext).run(operationId) { progress ->
                setProgress(
                    workDataOf(
                        KEY_OPERATION_ID to operationId,
                        KEY_PROGRESS_DONE to progress.done,
                        KEY_PROGRESS_TOTAL to progress.total,
                        KEY_PROGRESS_FAILED to progress.failed
                    )
                )
                setForeground(buildForegroundInfo(progress.done, progress.total))
            }
            Timber.i("Export operation $operationId finished this pass in state $finalState")
            Result.success(
                workDataOf(
                    KEY_OPERATION_ID to operationId,
                    KEY_FINAL_STATE to finalState
                )
            )
        } catch (t: Throwable) {
            // Retrying is safe: finished items are skipped, and no source file
            // has been touched.
            Timber.e(t, "Export worker failed for operation $operationId")
            CrashReporter.recordNonFatal(
                throwable = t,
                source = "export_worker",
                context = mapOf("operation_id" to operationId.toString())
            )
            Result.retry()
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
                        ctx.getString(R.string.notif_channel_export),
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

        val text = if (total > 0) {
            ctx.getString(R.string.notif_text_export_progress, done, total)
        } else {
            ctx.getString(R.string.notif_text_starting)
        }

        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(ctx.getString(R.string.notif_title_export))
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
        private const val CHANNEL_ID = "face_album_export_channel"
        private const val NOTIF_ID = 4243

        const val KEY_OPERATION_ID = "operation_id"
        const val KEY_PROGRESS_DONE = "export_done"
        const val KEY_PROGRESS_TOTAL = "export_total"
        const val KEY_PROGRESS_FAILED = "export_failed"
        const val KEY_FINAL_STATE = "export_final_state"

        /** One unique work chain per operation, so exports never interleave. */
        fun uniqueWorkName(operationId: Long) = "export_op_$operationId"

        /**
         * KEEP rather than REPLACE: if this operation is already running,
         * restarting it would duplicate in-flight copy work for no benefit.
         */
        fun enqueue(context: Context, operationId: Long) {
            val request = OneTimeWorkRequestBuilder<ExportWorker>()
                .setInputData(
                    Data.Builder().putLong(KEY_OPERATION_ID, operationId).build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueWorkName(operationId),
                ExistingWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context, operationId: Long) {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkName(operationId))
        }
    }
}
