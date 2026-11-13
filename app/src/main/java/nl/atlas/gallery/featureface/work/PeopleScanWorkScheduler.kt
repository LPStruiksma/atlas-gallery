package nl.atlas.gallery.featureface.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Only PeopleDiscoveryHost calls this, after the user's explicit "Discover faces"
 * (or Resume) in the same process. No boot/regrant observer calls this code.
 */
object PeopleScanWorkScheduler {
    const val UNIQUE_NAME = "atlas-native-people-scan-v1"
    private const val BACKOFF_SECONDS = 30L
    /** After a thermal, power or detector hold; the constraints still gate the run. */
    const val HOLD_RETRY_MINUTES = 5L

    /** #552: the plan of the execution running now; at most one (unique work). */
    @Volatile private var activeRun: PeopleScanRunPlan? = null

    internal fun beginRun(plan: PeopleScanRunPlan) { activeRun = plan }

    internal fun endRun(plan: PeopleScanRunPlan) {
        if (activeRun === plan) activeRun = null
    }

    fun request(initialDelayMinutes: Long = 0): OneTimeWorkRequest =
        OneTimeWorkRequest.Builder(PeopleScanWorker::class.java)
            .setInitialDelay(initialDelayMinutes, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()

    /**
     * Explicit host call only; the worker independently reauthorizes regardless
     * of who enqueued it. KEEP prevents two concurrent unique chains.
     */
    fun enqueueAfterVerifiedOptIn(context: Context) {
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request())
    }

    /**
     * Next bounded batch of a user-started run, called from inside the running
     * batch: APPEND_OR_REPLACE queues it behind the current one instead of the
     * KEEP no-op. [delayed] waits [HOLD_RETRY_MINUTES] first, after a hold.
     * Each batch still reauthorizes on its own.
     */
    fun enqueueNextBatch(context: Context, delayed: Boolean = false) {
        // #552: inside a running execution the next batch runs right there.
        activeRun?.let {
            it.request(if (delayed) PeopleScanRunPlan.Request.LATER else PeopleScanRunPlan.Request.NOW)
            return
        }
        val next = request(if (delayed) HOLD_RETRY_MINUTES else 0)
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, next)
    }

    fun cancel(context: Context) {
        activeRun?.cancel()
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_NAME)
    }
}
