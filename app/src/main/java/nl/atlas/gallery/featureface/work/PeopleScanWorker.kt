package nl.atlas.gallery.featureface.work

import android.content.Context
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import nl.atlas.gallery.featureface.FaceBatchBudget
import nl.atlas.gallery.featureface.FaceBatchResult
import nl.atlas.gallery.featureface.FaceScanBatchPolicy
import nl.atlas.gallery.featureface.FaceScanStepResult
import nl.atlas.gallery.featureface.FaceScanStep
import nl.atlas.gallery.featurevisual.VisualScanResult

/**
 * Bounded WorkManager bridge. SQLite alone checkpoints scan progress.
 * Each invocation independently acquires a *current* in-process opt-in/grant,
 * never reconstructs one from WorkManager inputs or a previous process.
 */
class PeopleScanWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // Never spin or spend WorkManager retry on a transient hold: the
        // session decides in afterBatch whether a next bounded batch follows.
        // #552: "now" runs here, in this execution, for up to MAX_RUN_MS.
        when (runPeopleScanRun(applicationContext) { isStopped }) {
            PeopleScanRunPlan.Decision.ENQUEUE_NOW ->
                PeopleScanWorkScheduler.enqueueNextBatch(applicationContext)
            PeopleScanRunPlan.Decision.ENQUEUE_LATER ->
                PeopleScanWorkScheduler.enqueueNextBatch(applicationContext, delayed = true)
            else -> Unit
        }
        Result.success()
    }
}

/**
 * #552: consecutive bounded batches in one execution while the host keeps
 * asking for the next one now. Returns how the run ended; the caller hands
 * over to WorkManager (after the plan is no longer active) when asked to.
 */
suspend fun runPeopleScanRun(
    context: Context,
    plan: PeopleScanRunPlan = PeopleScanRunPlan(clockMs = { SystemClock.elapsedRealtime() }),
    thermalAllowed: () -> Boolean = { thermalAllowsScan(context.applicationContext) },
    isStopped: () -> Boolean
): PeopleScanRunPlan.Decision {
    PeopleScanWorkScheduler.beginRun(plan)
    val startMs = SystemClock.elapsedRealtime()
    var photos = 0
    var batches = 0
    var decision = PeopleScanRunPlan.Decision.STOP
    try {
        while (true) {
            // Null: no granted host session; nothing asked for a next batch.
            val batch = runPeopleScanBatch(context, thermalAllowed = thermalAllowed, isStopped = isStopped) ?: break
            photos += batch.photosIndexed
            batches++
            decision = plan.afterBatch(isStopped())
            if (decision != PeopleScanRunPlan.Decision.CONTINUE) break
            currentCoroutineContext().ensureActive()
        }
        return decision
    } finally {
        PeopleScanWorkScheduler.endRun(plan)
        logScanRate(photos, batches, SystemClock.elapsedRealtime() - startMs, decision)
    }
}

/**
 * #552: the real device's throughput, one line per worker run. Counts and
 * durations only: never a URI, ID, name or face.
 */
private fun logScanRate(
    photos: Int, batches: Int, elapsedMs: Long, end: PeopleScanRunPlan.Decision
) {
    if (photos == 0) return
    val rate = photos * 1000.0 / elapsedMs.coerceAtLeast(1)
    android.util.Log.i("ATLAS_FACE", "scan_rate photos=$photos batches=$batches ms=$elapsedMs " +
        "photos_per_s=${"%.2f".format(java.util.Locale.US, rate)} end=$end")
}

/**
 * One bounded batch for the installed host, or nothing when no host is
 * installed: faces (at most 8 photos or 2 s), then, when the user opted in to
 * the visual search index, the visual stage (at most 8 images or 2 s) under
 * the same charging, battery, storage, thermal, grant and epoch guards. Shared by [PeopleScanWorker] and the
 * API35 host test, which cannot run WorkManager's test harness (adding
 * work-testing is a protected Gradle change). Returns null when no host session
 * was granted, and a HELD result when the session refused to step.
 */
suspend fun runPeopleScanBatch(
    context: Context,
    /** Injectable for the API35 thermal test; production reads PowerManager. */
    thermalAllowed: () -> Boolean = { thermalAllowsScan(context.applicationContext) },
    manualForeground: Boolean = false,
    isStopped: () -> Boolean
): FaceBatchResult? {
    val appContext = context.applicationContext
    val session = try {
        PeopleScanWorkRuntime.acquire(appContext)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SecurityException) {
        null
    } catch (_: java.io.IOException) {
        null
    } ?: return null
    try {
        if (!safeToStep(session, thermalAllowed(), isStopped(), manualForeground)) {
            // Refused at its start (thermal, power, pause, stale scope): the
            // host decides whether a delayed retry keeps the run alive.
            val held = FaceBatchResult(0, 0, 0, nl.atlas.gallery.featureface.FaceBatchStop.HELD)
            if (!isStopped()) session.afterBatch(held, null)
            return held
        }
        val jobContext = currentCoroutineContext()
        val budget = FaceBatchBudget(maxPhotos = 8, maxElapsedMs = 2_000)
        val scan = FaceScanBatchPolicy(
            clockElapsedMs = { SystemClock.elapsedRealtime() },
            step = {
                jobContext.ensureActive()
                if (!safeToStep(session, thermalAllowed(), isStopped(), manualForeground)) {
                    FaceScanStepResult(FaceScanStep.HELD)
                } else {
                    session.engine.scanOne(session.epoch)
                }
            }
        ).run(budget)
        jobContext.ensureActive()
        // Second stage: its own bounded step, rechecking every guard per item,
        // so a thermal or grant change mid-batch stops it before the next image.
        val guard = {
            jobContext.isActive && safeToStep(session, thermalAllowed(), isStopped(), manualForeground)
        }
        // The step checks the guard before any work and reports a hold, so a
        // run whose faces are done still comes back for its visual queue.
        val visual: VisualScanResult? = session.visualStage?.run(guard)
        jobContext.ensureActive()
        // Cluster publication and any next batch are the host's decision,
        // made against the same live session checks.
        session.afterBatch(scan, visual)
        return scan
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        return null
    } catch (_: SecurityException) {
        return null // treat permission loss as a stop, never retry media
    } catch (_: java.io.IOException) {
        return null // provider disappeared; no stale retry
    } finally {
        session.close()
    }
}
