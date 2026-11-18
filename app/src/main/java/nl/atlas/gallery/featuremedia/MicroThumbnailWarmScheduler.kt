package nl.atlas.gallery.featuremedia

import android.content.Context
import android.os.CancellationSignal
import android.os.PowerManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import nl.atlas.gallery.readGalleryMediaGrant

/** Deferred first-fill work carries no photo IDs or grants across processes. */
internal object MicroThumbnailWarmScheduler {
    private const val UNIQUE_NAME = "atlas-micro-warm-charging-v1"

    fun request(): OneTimeWorkRequest = OneTimeWorkRequest.Builder(MicroThumbnailWarmWorker::class.java)
        .setConstraints(Constraints.Builder().setRequiresCharging(true).build())
        .build()

    fun enqueue(context: Context) {
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request())
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_NAME)
    }
}

/** Unknown thermal state is unsafe for optional background thumbnail decoding. */
private fun microWarmThermalSafe(context: Context): Boolean {
    val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return power.currentThermalStatus < PowerManager.THERMAL_STATUS_MODERATE
}

internal class MicroThumbnailWarmWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        try {
            val grant = readGalleryMediaGrant(context)
            if (!microThumbnailsAllowed(grant.images, grant.videos, grant.selected) ||
                !MicroThumbnails.active || !ThumbnailPipeline.autoWarm)
                return@withContext Result.success()
            // An environmental pause is not a completed first fill.
            if (!microWarmCharging(context) || !microWarmThermalSafe(context))
                return@withContext Result.retry()

            val coroutine = currentCoroutineContext()
            val providerSignal = CancellationSignal()
            // CoroutineWorker.onStopped() is final: WorkManager cancels its
            // work future, which cancels doWork's parent Job. This child
            // resumes on Default even if IO is blocked in query/loadThumbnail.
            val cancelProvider = CoroutineScope(coroutine)
                .watchMicroWarmCancellation { providerSignal.cancel() }
            try {
                var gateChecks = 0
                var pausedForPower = false
                val gate = {
                    coroutine.ensureActive()
                    // Permission and cancellation are checked on every row,
                    // before eviction and before decoding. WorkManager owns
                    // the charging constraint; poll battery/thermal every 64
                    // checks as a second defense against constraint races.
                    val powerReady = if (gateChecks++ % 64 != 0) true else
                        microWarmCharging(context) && microWarmThermalSafe(context)
                    if (!powerReady) pausedForPower = true
                    !isStopped && readGalleryMediaGrant(context) == grant &&
                        MicroThumbnails.active && ThumbnailPipeline.autoWarm && powerReady
                }
                MicroThumbnails.store?.let { store ->
                    MicroWarmMediaSource(context, grant, store).use { source ->
                        if (!source.scan(providerSignal, gate))
                            return@withContext if (pausedForPower) Result.retry() else Result.success()
                        coroutine.ensureActive()
                        if (!gate())
                            return@withContext if (pausedForPower) Result.retry() else Result.success()
                        MicroThumbnailWarmer.warmPaged(
                            context.contentResolver, source, providerSignal, gate = gate
                        )
                    }
                }
                if (pausedForPower) Result.retry() else Result.success()
            } finally {
                cancelProvider.cancel()
                providerSignal.cancel()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            Result.success() // grant revoked; never retry stale media
        } catch (_: Exception) {
            Result.retry() // transient provider, staging or IO failure
        }
    }
}
