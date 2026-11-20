package nl.atlas.gallery.featureidentity.install

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.atlas.gallery.featureidentity.local.IdentityModelManifest

/**
 * IDS-19 (#619): downloads and verifies the OD-1 model set after the user's tap. Foreground
 * work with a progress notification and a Cancel action where the platform allows it; it
 * resumes from the partial file after the app is killed and retries with backoff when the
 * connection drops. A wrong hash or size fails at once and is not retried.
 *
 * Foreground on Android 14+: WorkManager's SystemForegroundService needs the `dataSync`
 * service type and FOREGROUND_SERVICE_DATA_SYNC in the app manifest (a protected path, its
 * own PR for Lukas). Until those exist, the worker runs as ordinary background work with the
 * same notification, still resumable; it checks the manifest at run time and never asks the
 * platform for a type the manifest does not declare.
 */
class IdentityModelInstallWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        private const val CHANNEL = "atlas-identity-models"
        private const val NOTIFICATION_ID = 26019
        /** Connection drops retried with exponential backoff (30 s doubling) before failing. */
        const val MAX_ATTEMPTS = 8
        private const val PROGRESS_INTERVAL_MS = 500L
        /** WorkManager's own foreground service (named, not imported: it is library-internal API). */
        private const val WORK_FOREGROUND_SERVICE = "androidx.work.impl.foreground.SystemForegroundService"
        private const val DATA_SYNC_PERMISSION = "android.permission.FOREGROUND_SERVICE_DATA_SYNC"
    }

    private val entries = IdentityModelManifest.entries
    private val totalLabel = String.format(Locale.US, "%.0f MB", entries.sumOf { ModelSetInstall.downloadBytes(it) } / 1_000_000.0)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val set = IdentityModelInstaller.install(applicationContext, entries)
        goForeground(InstallState.Downloading(set.doneBytes(), set.totalBytes))
        var lastSent = 0L
        val outcome = set.run(isCancelled = { isStopped }) { state ->
            val now = SystemClock.elapsedRealtime()
            if (state is InstallState.Downloading && now - lastSent < PROGRESS_INTERVAL_MS) return@run
            lastSent = now
            setProgressAsync(
                workDataOf(
                    IdentityModelInstaller.PROGRESS_BYTES to ((state as? InstallState.Downloading)?.bytes ?: set.totalBytes),
                    IdentityModelInstaller.PROGRESS_VERIFYING to (state is InstallState.Verifying)
                )
            )
            notifyProgress(state)
        }
        clearNotification()
        when (outcome) {
            ModelSetInstall.Outcome.Installed -> Result.success()
            ModelSetInstall.Outcome.Blocked -> Result.failure()
            ModelSetInstall.Outcome.Cancelled -> {
                // The user's Cancel frees the space (IdentityModelInstaller.cancel does the same after
                // the work is gone, which also covers queued work); a stop for lost connection or
                // quota keeps the partial file so the next run resumes it.
                if (stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP) set.discardUnfinished()
                Result.retry()
            }
            is ModelSetInstall.Outcome.Failed ->
                if (outcome.retryable && runAttemptCount + 1 < MAX_ATTEMPTS) Result.retry()
                else Result.failure(workDataOf(IdentityModelInstaller.OUTPUT_FAILURE to outcome.reason.name))
        }
    }

    private suspend fun goForeground(state: InstallState) {
        val info = foregroundInfo(notification(state)) ?: run { notifyProgress(state); return }
        try {
            setForeground(info)
        } catch (_: IllegalStateException) {
            // Not allowed now (for example started from the background on Android 12+): carry on
            // as background work; the partial file makes it resumable either way.
            notifyProgress(state)
        }
    }

    private fun foregroundInfo(notification: Notification): ForegroundInfo? {
        val dataSync = dataSyncDeclared()
        return when {
            Build.VERSION.SDK_INT >= 34 ->
                if (dataSync) ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else null
            dataSync -> ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else -> ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    /** True when the merged manifest gives SystemForegroundService the dataSync type (and its permission on 14+). */
    private fun dataSyncDeclared(): Boolean = try {
        val component = ComponentName(applicationContext.packageName, WORK_FOREGROUND_SERVICE)
        val pm = applicationContext.packageManager
        val service = if (Build.VERSION.SDK_INT >= 33) {
            pm.getServiceInfo(component, PackageManager.ComponentInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION") pm.getServiceInfo(component, 0)
        }
        val typed = service.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC != 0
        typed && (Build.VERSION.SDK_INT < 34 ||
            applicationContext.checkSelfPermission(DATA_SYNC_PERMISSION) == PackageManager.PERMISSION_GRANTED)
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun manager() = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun notification(state: InstallState): Notification {
        val manager = manager()
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Swap person setup", NotificationManager.IMPORTANCE_LOW))
        val builder = Notification.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading Swap person ($totalLabel)")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(
                    null, "Cancel", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
                ).build()
            )
        when (state) {
            is InstallState.Downloading -> {
                val permille = if (state.total > 0) (state.bytes * 1000 / state.total).toInt().coerceIn(0, 1000) else 0
                builder.setProgress(1000, permille, false)
                    .setContentText(String.format(Locale.US, "%d%%", permille / 10))
            }
            InstallState.Verifying -> builder.setProgress(0, 0, true).setContentText("Checking files")
            else -> Unit
        }
        return builder.build()
    }

    /** Posting needs the notification permission on Android 13+; without it this is a quiet no-op. */
    private fun notifyProgress(state: InstallState) {
        val manager = manager()
        if (!manager.areNotificationsEnabled()) return
        try { manager.notify(NOTIFICATION_ID, notification(state)) } catch (_: SecurityException) { }
    }

    private fun clearNotification() {
        manager().cancel(NOTIFICATION_ID)
    }
}
