package nl.atlas.gallery.featureidentity.install

import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CancellationException
import nl.atlas.gallery.featureidentity.local.IdentityModelStore
import nl.atlas.gallery.featureidentity.local.ModelEntry
import nl.atlas.gallery.featureidentity.local.ModelFetchException
import nl.atlas.gallery.featureidentity.local.ModelFetchFailure
import nl.atlas.gallery.featureidentity.local.ModelGate
import nl.atlas.gallery.featureidentity.local.ModelGateException
import nl.atlas.gallery.featureidentity.local.ModelGatePolicy
import nl.atlas.gallery.featureidentity.local.ModelState
import nl.atlas.gallery.featureidentity.local.ModelVerificationException

/*
 * IDS-19 (#619): installing a model set, with no Android dependency so the JVM tests run the
 * exact code the WorkManager worker runs. It holds no network code (the #191 fence): the store
 * was built with the HTTPS fetcher from featureaiedit/network/.
 */

/** Why setup failed. A plain code: no URL, header or exception text can reach the UI through it. */
enum class InstallFailure { NO_NETWORK, NOT_ENOUGH_SPACE, FILE_CHECK_FAILED, HOST_REFUSED }

/** What the "Swap person" setup sheet (#477) shows. */
sealed interface InstallState {
    data object NotInstalled : InstallState
    data class Downloading(val bytes: Long, val total: Long) : InstallState
    data object Verifying : InstallState
    data object Installed : InstallState
    data class Failed(val reason: InstallFailure) : InstallState
    /** A manifest entry's licence or host gate is closed; setup is not offered. */
    data object Blocked : InstallState
}

class ModelSetInstall(
    private val store: IdentityModelStore,
    private val entries: List<ModelEntry>,
    /**
     * The store's root folder. Every install, cancel clean-up and removal of this folder takes
     * one process-wide lock keyed by it, so a Cancel or Remove from the studio (its own store
     * instance) waits until the worker's run has stopped and never races its writes.
     */
    root: File,
    /** Free bytes on the volume that holds the store (StatFs on the phone). */
    private val freeBytes: () -> Long
) {
    sealed interface Outcome {
        data object Installed : Outcome
        data object Blocked : Outcome
        data object Cancelled : Outcome
        /** [retryable] failures (a dropped connection) resume from the partial file on the next run. */
        data class Failed(val reason: InstallFailure, val retryable: Boolean) : Outcome
    }

    companion object {
        /** Headroom left on the volume after the peak, so setup never fills the phone to the last byte. */
        const val SPACE_MARGIN_BYTES = 32L * 1024 * 1024
        private const val PROGRESS_STEP_BYTES = 512L * 1024

        fun downloadBytes(entry: ModelEntry): Long = entry.archive?.rangeBytes ?: entry.sizeBytes

        private val locks = ConcurrentHashMap<String, Any>()
        private fun lockFor(root: File): Any = locks.getOrPut(root.absoluteFile.normalize().path) { Any() }
    }

    private val lock = lockFor(root)

    /** Bytes the whole set downloads (563.0 MB for OD-1). */
    val totalBytes: Long = entries.sumOf { downloadBytes(it) }

    fun blocked(): Boolean = entries.any { ModelGatePolicy.gate(it) != ModelGate.Allowed }

    fun installed(): Boolean = entries.all { store.installedFile(it) != null }

    /** Downloaded bytes so far, counting installed entries in full and partial files as far as they go. */
    fun doneBytes(): Long = entries.sumOf { entry ->
        when (val s = store.state(entry)) {
            is ModelState.Installed -> downloadBytes(entry)
            is ModelState.Partial -> minOf(s.bytes, downloadBytes(entry))
            else -> 0L
        }
    }

    /**
     * Peak extra bytes the rest of setup writes: what is left to download, plus, for an archive
     * member, the extracted file that briefly sits next to its verified range (LOCAL_ENGINE.md:
     * about 738 MB for OD-1 from scratch).
     */
    fun peakBytesNeeded(): Long = entries.sumOf { entry ->
        when (val s = store.state(entry)) {
            is ModelState.Installed -> 0L
            is ModelState.Partial -> (downloadBytes(entry) - s.bytes).coerceAtLeast(0) + (entry.archive?.let { entry.sizeBytes } ?: 0L)
            else -> downloadBytes(entry) + (entry.archive?.let { entry.sizeBytes } ?: 0L)
        }
    }

    fun enoughSpace(): Boolean = freeBytes() >= peakBytesNeeded() + SPACE_MARGIN_BYTES

    /**
     * Installs every entry in order, resuming partial files. Checks space before any fetch.
     * [onState] gets [InstallState.Downloading] about every 512 KB and [InstallState.Verifying]
     * while an entry's hash is checked. Blocking: run on an IO thread.
     */
    fun run(isCancelled: () -> Boolean, onState: (InstallState) -> Unit): Outcome = synchronized(lock) {
        // Cancelled before this run got the lock (for example Remove ran first): touch nothing.
        if (isCancelled()) return Outcome.Cancelled
        if (blocked()) return Outcome.Blocked
        if (installed()) return Outcome.Installed
        if (!enoughSpace()) return Outcome.Failed(InstallFailure.NOT_ENOUGH_SPACE, retryable = false)
        var before = 0L
        for (entry in entries) {
            if (isCancelled()) return Outcome.Cancelled
            val length = downloadBytes(entry)
            if (store.installedFile(entry) != null) { before += length; continue }
            var lastSent = -PROGRESS_STEP_BYTES
            val start = before
            val partial = partialBytes(entry)
            // A complete partial file is only hashed: no bytes, so no progress callback.
            onState(if (partial >= length) InstallState.Verifying else InstallState.Downloading(start + partial, totalBytes))
            try {
                store.install(entry, isCancelled) { offset ->
                    if (offset >= length) {
                        onState(InstallState.Verifying)
                    } else if (offset - lastSent >= PROGRESS_STEP_BYTES) {
                        lastSent = offset
                        onState(InstallState.Downloading(start + offset, totalBytes))
                    }
                }
            } catch (_: CancellationException) {
                return Outcome.Cancelled
            } catch (e: ModelFetchException) {
                return when (e.failure) {
                    ModelFetchFailure.NETWORK -> Outcome.Failed(InstallFailure.NO_NETWORK, retryable = true)
                    ModelFetchFailure.HOST_REFUSED -> Outcome.Failed(InstallFailure.HOST_REFUSED, retryable = false)
                }
            } catch (_: ModelGateException) {
                return Outcome.Blocked
            } catch (_: ModelVerificationException) {
                // The store keeps a partial file only when the stream ended early (resume it next
                // time); a wrong hash or size deletes it, and that is not retried.
                return if (store.state(entry) is ModelState.Partial) {
                    Outcome.Failed(InstallFailure.NO_NETWORK, retryable = true)
                } else {
                    Outcome.Failed(InstallFailure.FILE_CHECK_FAILED, retryable = false)
                }
            } catch (_: IOException) {
                // Network reads surface as ModelFetchException, so this is the disk.
                return if (!enoughSpace()) {
                    Outcome.Failed(InstallFailure.NOT_ENOUGH_SPACE, retryable = false)
                } else {
                    Outcome.Failed(InstallFailure.FILE_CHECK_FAILED, retryable = false)
                }
            } catch (_: IllegalStateException) {
                return Outcome.Failed(InstallFailure.FILE_CHECK_FAILED, retryable = false)
            }
            before += length
        }
        // A cancel that landed while the last hash was checked still counts as a cancel.
        if (isCancelled()) return Outcome.Cancelled
        return if (installed()) Outcome.Installed else Outcome.Failed(InstallFailure.FILE_CHECK_FAILED, retryable = false)
    }

    /**
     * After the user's Cancel, in any state (queued, downloading, verifying): unless the whole set
     * is installed, delete every file of the set, partial or verified. A half-installed set cannot
     * be used and the sheet shows it as not installed, so it must not hold hundreds of MB out of
     * sight. Waits for a running install of the same folder to stop first.
     */
    fun discardUnfinished(): Boolean = synchronized(lock) { if (installed()) false else { removeAll(); true } }

    /** "Remove": deletes every model file, marker and partial download of the set, after any running install stopped. */
    fun removeAll(): Boolean = synchronized(lock) { entries.map { store.remove(it) }.all { it } }

    private fun partialBytes(entry: ModelEntry): Long =
        (store.state(entry) as? ModelState.Partial)?.bytes?.coerceAtMost(downloadBytes(entry)) ?: 0L
}

/** What the installer knows about the unique install work, without WorkManager types. */
data class InstallWork(
    val phase: Phase,
    val bytes: Long = 0,
    val verifying: Boolean = false,
    val failure: InstallFailure? = null
) {
    enum class Phase { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }
}

/** Pure mapping from work + disk to the sheet's state; the JVM tests cover every branch. */
object InstallStateMapper {
    fun map(work: InstallWork?, blocked: Boolean, installed: Boolean, doneBytes: Long, total: Long): InstallState = when {
        blocked -> InstallState.Blocked
        work != null && (work.phase == InstallWork.Phase.QUEUED || work.phase == InstallWork.Phase.RUNNING) ->
            if (work.verifying) InstallState.Verifying else InstallState.Downloading(maxOf(work.bytes, doneBytes).coerceAtMost(total), total)
        installed -> InstallState.Installed
        work?.phase == InstallWork.Phase.FAILED -> InstallState.Failed(work.failure ?: InstallFailure.FILE_CHECK_FAILED)
        else -> InstallState.NotInstalled
    }
}
