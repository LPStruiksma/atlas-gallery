package nl.atlas.gallery

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.work.WorkManager
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.atlas.gallery.featureface.work.PeopleScanWorkScheduler
import nl.atlas.gallery.featuremedia.MicroThumbnails
import nl.atlas.gallery.featurepeople.identity.PeopleNumbers
import nl.atlas.gallery.featurevisual.onnx.SiglipImageEmbedder

/**
 * Strictly read-only system and already-published ATLAS aggregates. The scan,
 * FaceIndexStore, GalleryRepository and review data are never opened or changed.
 * Missing instrumentation is represented as unavailable, never fabricated.
 * Runs on IO: reading WorkManager, large inventory distinct counts and file sizes
 * must not freeze Settings on a large library.
 */
internal suspend fun captureAtlasDiagnostics(
    context: Context,
    published: List<MediaRecord>,
    expectedGrant: GalleryMediaGrant,
    status: PeopleDiscoveryStatus?,
    faceHost: PeopleDiscoveryHost,
    faceGrantEpoch: String?
): String = withContext(Dispatchers.IO) {
    val grantedAtStart = runCatching { readGalleryMediaGrant(context) }.getOrNull()
    val current = grantedAtStart == expectedGrant && expectedGrant.hasAny
    // No names, paths, URIs, epochs, vectors or file contents cross to the DTO.
    val authorized = if (current) published else emptyList()
    val volumeCount = authorized.asSequence().map { it.volumeName }.distinct().count()
    val folderCount = authorized.asSequence()
        .map { it.volumeName to it.relativePath }.distinct().count()
    val people = if (current && faceGrantEpoch != null) {
        runCatching { faceHost.peopleNumbers(faceGrantEpoch) }.getOrNull()
    } else null
    val info = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }.getOrNull()
    val runtime = Runtime.getRuntime()
    val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val battery = runCatching {
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }.getOrNull()
    val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val percent = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else null
    val charging = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        ?.takeIf { it >= 0 }?.let { it != 0 }
    val work = buildMap {
        queryUniqueWork(context, PeopleScanWorkScheduler.UNIQUE_NAME)?.let { put("people", it) }
        queryUniqueWork(context, "atlas-micro-warm-charging-v1")?.let { put("thumbnail", it) }
        // Visual shares People's single worker; there is no third unique job.
    }
    fun size(file: File): Long? = if (file.isFile) runCatching { file.length() }.getOrNull()
        else 0L
    val fileBytes = mapOf(
        "people" to size(context.getDatabasePath("atlas_native_people.db")),
        "face" to size(context.getDatabasePath("atlas_face_discovery.db")),
        "visual" to size(context.getDatabasePath("atlas_visual_index.db")),
        "curation" to size(context.getDatabasePath("atlas_curation_keep.db")),
        "thumbnail" to MicroThumbnails.store?.bytesUsed?.takeIf { it >= 0L },
        "inventory" to size(File(context.noBackupFilesDir, "gallery-inventory.bin"))
    )
    val exits = if (Build.VERSION.SDK_INT >= 30) {
        runCatching {
            activity?.getHistoricalProcessExitReasons(context.packageName, 0, 5)
                ?.map {
                    AtlasProcessExitDiagnostic(it.reason, it.pss, it.rss)
                } ?: emptyList()
        }.getOrDefault(emptyList())
    } else emptyList()
    val ui = status?.ui?.takeIf { current }
    val visual = status?.visual?.takeIf { current }
    val snapshot = AtlasDiagnosticsSnapshot(
        version = info?.versionName ?: "unknown",
        build = info?.longVersionCode ?: 0L,
        androidApi = Build.VERSION.SDK_INT,
        deviceModel = Build.MODEL ?: "unknown",
        memoryClassMb = activity?.memoryClass ?: 0,
        heapUsedBytes = (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0),
        heapLimitBytes = runtime.maxMemory(),
        freeStorageBytes = context.filesDir.usableSpace,
        images = authorized.count { !it.isVideo },
        videos = authorized.count { it.isVideo },
        volumes = volumeCount,
        folders = folderCount,
        photoAccess = when {
            !current -> "NONE"
            expectedGrant.images && expectedGrant.videos -> "FULL"
            expectedGrant.selected -> "SELECTED"
            else -> "PARTIAL"
        },
        peoplePhase = ui?.phase?.name ?: "UNAVAILABLE",
        peopleEligible = ui?.eligible ?: 0,
        peopleProcessed = ui?.processed ?: 0,
        peopleFaces = people?.faces,
        peopleClusters = people?.let { it.groupsOf3 + it.pairs + it.singletons + it.namedPeople },
        // The current host does not publish dirty/pending counters, an opt-in
        // flag or publication durations as safe read-only accessors.
        visualOptIn = visual?.enabled,
        visualDone = visual?.done,
        visualTotal = visual?.total,
        visualSkipped = visual?.skipped,
        visualModelPresent = runCatching {
            File(context.noBackupFilesDir, SiglipImageEmbedder.MODEL_DIR).exists()
        }.getOrNull(),
        batteryPercent = percent,
        charging = charging,
        thermalStatus = if (Build.VERSION.SDK_INT >= 29) power?.currentThermalStatus else null,
        powerSave = power?.isPowerSaveMode,
        batteryExempt = runCatching {
            power?.isIgnoringBatteryOptimizations(context.packageName)
        }.getOrNull(),
        fileBytes = fileBytes,
        work = work,
        lastCrash = LastCrashRecorder.read(context),
        exits = exits,
        scans = AtlasDiagnosticsRing.scans(),
        logLines = AtlasDiagnosticsRing.logs()
    )
    // A grant that changed while scanning aggregate metadata must not export a
    // stale permission-scoped count. In that case restart without scoped data.
    if (runCatching { readGalleryMediaGrant(context) }.getOrNull() != grantedAtStart) {
        formatAtlasDiagnostics(snapshot.copy(
            images = 0, videos = 0, volumes = 0, folders = 0, photoAccess = "NONE",
            peoplePhase = "UNAVAILABLE", peopleEligible = 0, peopleProcessed = 0,
            peopleFaces = null, peopleClusters = null, visualOptIn = null,
            visualDone = null, visualTotal = null, visualSkipped = null
        ))
    } else formatAtlasDiagnostics(snapshot)
}

private fun queryUniqueWork(context: Context, name: String): AtlasWorkDiagnostic? =
    runCatching {
        val jobs = WorkManager.getInstance(context).getWorkInfosForUniqueWork(name)
            .get(2, TimeUnit.SECONDS)
        jobs.lastOrNull()?.let {
            AtlasWorkDiagnostic(it.state.name, it.runAttemptCount, it.stopReason)
        }
    }.getOrNull()
