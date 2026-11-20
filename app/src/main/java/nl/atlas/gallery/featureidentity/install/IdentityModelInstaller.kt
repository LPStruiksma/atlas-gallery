package nl.atlas.gallery.featureidentity.install

import android.content.Context
import android.os.StatFs
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.atlas.gallery.featureaiedit.network.HttpsModelFetcher
import nl.atlas.gallery.featureidentity.local.IdentityModelManifest
import nl.atlas.gallery.featureidentity.local.IdentityModelStore
import nl.atlas.gallery.featureidentity.local.ModelEntry

/**
 * IDS-19 (#619): the studio's handle on the one-time "Swap person" setup. [start] runs only
 * from the user's tap on the setup sheet (#477); nothing here downloads on its own, and the
 * engine's `load()`/`generate()` never download.
 *
 * [state] combines the unique install work (WorkManager, so it survives the app being closed
 * or killed and resumes from the partial file) with what is on disk.
 */
class IdentityModelInstaller private constructor(
    private val context: Context,
    private val entries: List<ModelEntry>,
    private val scope: CoroutineScope
) {
    companion object {
        const val PROGRESS_BYTES = "bytes"
        const val PROGRESS_VERIFYING = "verifying"
        const val OUTPUT_FAILURE = "failure"
        private const val PREFS = "identity_model_installer"
        private const val DISMISSED = "dismissed_work"

        @Volatile private var instance: IdentityModelInstaller? = null

        /** The installer for the OD-1 model set. */
        fun get(context: Context): IdentityModelInstaller = instance ?: synchronized(this) {
            instance ?: IdentityModelInstaller(
                context.applicationContext,
                IdentityModelManifest.entries,
                CoroutineScope(SupervisorJob() + Dispatchers.Default)
            ).also { instance = it }
        }

        /** Unique per model set, so a later set (another engine) installs beside this one. */
        fun workName(entries: List<ModelEntry>): String = "identity-models:" + entries.joinToString("+") { it.id }

        internal fun store(context: Context) = IdentityModelStore(context.noBackupFilesDir, HttpsModelFetcher())

        internal fun install(context: Context, entries: List<ModelEntry>) =
            ModelSetInstall(store(context), entries, context.noBackupFilesDir) { StatFs(context.noBackupFilesDir.path).availableBytes }
    }

    private val workName = workName(entries)
    private val workManager get() = WorkManager.getInstance(context)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** A start refused before any work (not enough space), shown until the next start or remove. */
    private val refused = MutableStateFlow<InstallFailure?>(null)
    /** Bumped after [remove] so the disk is read again. */
    private val diskTick = MutableStateFlow(0)

    val state: StateFlow<InstallState> = combine(
        workManager.getWorkInfosForUniqueWorkFlow(workName),
        refused,
        diskTick
    ) { infos, refusedReason, _ ->
        val set = install(context, entries)
        if (refusedReason != null && !set.blocked()) return@combine InstallState.Failed(refusedReason)
        val dismissed = prefs.getString(DISMISSED, null)
        val work = infos.lastOrNull()?.takeIf { it.id.toString() != dismissed }?.let(::snapshot)
        InstallStateMapper.map(work, set.blocked(), set.installed(), set.doneBytes(), set.totalBytes)
    }.flowOn(Dispatchers.IO).stateIn(scope, SharingStarted.Eagerly, InstallState.NotInstalled)

    /** Bytes setup downloads, for the sheet's one-time size line (563 MB for OD-1). */
    val downloadBytes: Long = entries.sumOf { ModelSetInstall.downloadBytes(it) }

    /**
     * Starts (or keeps) the install after the user's tap. Checks free space first: if the peak
     * (about 738 MB from scratch) does not fit, [state] becomes `Failed(NOT_ENOUGH_SPACE)` and
     * nothing is fetched. Runs on any connection, as the sheet states the size before the tap.
     * Returns at once; the disk checks run on an IO thread.
     */
    fun start() {
        scope.launch(Dispatchers.IO) { startNow() }
    }

    private fun startNow() {
        val set = install(context, entries)
        if (set.blocked() || set.installed()) { refused.value = null; diskTick.value++; return }
        if (!set.enoughSpace()) { refused.value = InstallFailure.NOT_ENOUGH_SPACE; return }
        refused.value = null
        prefs.edit().remove(DISMISSED).apply()
        val request = OneTimeWorkRequestBuilder<IdentityModelInstallWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(workName)
            .build()
        workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * The user's Cancel, whether the work is queued (waiting for a connection or a retry), running
     * or verifying: cancels the work, waits until WorkManager recorded that, then deletes the
     * unfinished set. The delete takes the folder lock, so it runs only after a running worker
     * has left its install loop (it checks for cancellation every 64 KB and between files).
     */
    fun cancel() {
        refused.value = null
        scope.launch(Dispatchers.IO) {
            workManager.cancelUniqueWork(workName).result.get()
            install(context, entries).discardUnfinished()
            diskTick.value++
        }
    }

    /** "Remove": stops any install and deletes every model file, marker and partial download. */
    fun remove() {
        scope.launch(Dispatchers.IO) { removeNow() }
    }

    private fun removeNow() {
        refused.value = null
        // Wait for the cancel to be recorded; removeAll() then waits for a running worker to stop
        // (shared folder lock), so nothing it writes can come back after Remove.
        workManager.cancelUniqueWork(workName).result.get()
        install(context, entries).removeAll()
        // Forget a finished or failed run so the sheet shows "not installed" again.
        workManager.getWorkInfosForUniqueWork(workName).get().lastOrNull()?.let {
            prefs.edit().putString(DISMISSED, it.id.toString()).apply()
        }
        diskTick.value++
    }

    private fun snapshot(info: WorkInfo): InstallWork {
        val phase = when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> InstallWork.Phase.QUEUED
            WorkInfo.State.RUNNING -> InstallWork.Phase.RUNNING
            WorkInfo.State.SUCCEEDED -> InstallWork.Phase.SUCCEEDED
            WorkInfo.State.FAILED -> InstallWork.Phase.FAILED
            WorkInfo.State.CANCELLED -> InstallWork.Phase.CANCELLED
        }
        val failure = info.outputData.getString(OUTPUT_FAILURE)?.let { name ->
            InstallFailure.entries.firstOrNull { it.name == name }
        }
        return InstallWork(
            phase = phase,
            bytes = info.progress.getLong(PROGRESS_BYTES, 0L),
            verifying = info.progress.getBoolean(PROGRESS_VERIFYING, false),
            failure = failure
        )
    }
}
