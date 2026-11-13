package nl.atlas.gallery.featureface.work

import android.content.Context
import android.os.PowerManager
import nl.atlas.gallery.featureface.FaceScanConditions
import nl.atlas.gallery.featureface.FaceScanEngine
import nl.atlas.gallery.featurevisual.VisualScanResult

/**
 * A process-local authorization boundary. A persisted WorkRequest is NEVER proof of
 * permission, opt-in, provider generation, a model version, or a scan lease.
 * The host may install a factory only after it can independently verify each fact.
 * No default factory exists and no app lifecycle automatically schedules scans.
 */
fun interface PeopleScanRuntimeProvider {
    fun acquire(context: Context): PeopleScanSession?
}

interface PeopleScanSession : AutoCloseable {
    /** Verified from the current Android grant and committed provider snapshot. */
    val epoch: String
    val engine: FaceScanEngine
    /** Must be checked again between photos, including after a process restart. */
    fun currentConditions(): FaceScanConditions?
    fun scopeCurrent(): Boolean
    /**
     * Called after each bounded batch, still inside the worker. The host may
     * publish clusters (ClusterPublicationCadence) and enqueue the next batch
     * only after rechecking its own live opt-in, pause and grant state.
     */
    fun afterBatch(result: nl.atlas.gallery.featureface.FaceBatchResult) {}
    /**
     * The visual index stage (VS-S1c), run after faces in the same bounded
     * batch under the same guards; null while the user has not opted in.
     */
    val visualStage: PeopleVisualScanStep? get() = null
    /**
     * As [afterBatch], with what the visual stage did in this batch (null when
     * it did not run). Faces and visual vectors progress independently.
     */
    fun afterBatch(
        result: nl.atlas.gallery.featureface.FaceBatchResult, visual: VisualScanResult?
    ) = afterBatch(result)
    /** A freshly checked model and provider/source snapshot is part of scopeCurrent. */
    override fun close()
}

/**
 * One bounded visual index step (at most 8 items or about 2 s, see
 * VisualScanBudget). [canContinue] is the worker's own guard (thermal, power,
 * grant, stop), checked before every item. Null result: nothing to do.
 */
fun interface PeopleVisualScanStep {
    fun run(canContinue: () -> Boolean): VisualScanResult?
}

object PeopleScanWorkRuntime {
    @Volatile private var provider: PeopleScanRuntimeProvider? = null

    /** Explicit in-process host registration; never restored from WorkManager Data. */
    fun installForOptedInHost(newProvider: PeopleScanRuntimeProvider) {
        provider = newProvider
    }

    /** Revocation or host teardown immediately refuses future acquisitions. */
    fun clear() { provider = null }

    internal fun acquire(context: Context): PeopleScanSession? =
        provider?.acquire(context.applicationContext)
}

/** Thermal checking is conservative; API 29+ signals MODERATE at status 2. */
internal fun thermalAllowsScan(context: Context): Boolean {
    val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return power.currentThermalStatus < PowerManager.THERMAL_STATUS_MODERATE
}

/** No WorkManager Data may hold photo keys, epochs, embeddings, or identities. */
internal fun safeToStep(
    session: PeopleScanSession,
    thermalAllowed: Boolean,
    stopped: Boolean,
    manualForeground: Boolean = false
): Boolean {
    if (stopped || !thermalAllowed || session.epoch.isBlank()) return false
    return try {
        val conditions = session.currentConditions() ?: return false
        // Manual is allowed only when the host currently vouches for foreground mode.
        // Every WorkManager call uses the default and still requires charging.
        val powerAllowed = if (manualForeground) !conditions.backgroundMaintenance
            else conditions.charging
        conditions.canRun() && powerAllowed && session.scopeCurrent()
    } catch (cancelled: java.util.concurrent.CancellationException) {
        throw cancelled
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        throw interrupted
    } catch (_: SecurityException) {
        false
    } catch (_: java.io.IOException) {
        false
    }
}
