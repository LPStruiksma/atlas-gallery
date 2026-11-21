package nl.atlas.gallery

/**
 * #45: the only representation permitted across the diagnostics export boundary.
 * Strings arriving from Android, SQLite or media code are UNTRUSTED. This formatter
 * emits only explicit labels, nonnegative numbers, booleans and fixed vocabularies.
 * It must never append a URI, path, media name, error message or identity.
 */
internal data class AtlasScanDiagnostic(
    val durationMs: Long,
    val rows: Int?,
    val reused: Int?,
    val heapUsedBytes: Long,
    val heapLimitBytes: Long,
    val outcome: String
)

/** No exception text or Android media identifiers enter the ring. */
internal fun atlasScanFailureOutcome(error: Throwable): String = when (error) {
    is GalleryProviderSnapshotChanged -> "SNAPSHOT_CHANGED"
    is OutOfMemoryError, is GalleryScanOutOfMemory -> "OUT_OF_MEMORY"
    is java.util.concurrent.CancellationException -> "CANCELLED"
    is SecurityException -> "PERMISSION_DENIED"
    else -> "FAILED"
}

internal data class AtlasWorkDiagnostic(
    val state: String,
    val attempts: Int?,
    val stopReason: Int?
)

internal data class AtlasProcessExitDiagnostic(
    val reason: Int,
    val pssKb: Long,
    val rssKb: Long
)

internal data class AtlasDiagnosticsSnapshot(
    val version: String = "unknown",
    val build: Long = 0,
    val androidApi: Int = 0,
    val deviceModel: String = "unknown",
    val memoryClassMb: Int = 0,
    val heapUsedBytes: Long = 0,
    val heapLimitBytes: Long = 0,
    val freeStorageBytes: Long = 0,
    val images: Int = 0,
    val videos: Int = 0,
    val volumes: Int = 0,
    val folders: Int = 0,
    val photoAccess: String = "NONE",
    val peoplePhase: String = "UNAVAILABLE",
    val peopleEligible: Int = 0,
    val peopleProcessed: Int = 0,
    val peopleFaces: Int? = null,
    val peopleClusters: Int? = null,
    val peopleDirty: Boolean? = null,
    val peoplePending: Int? = null,
    val peopleOptIn: Boolean? = null,
    val lastPublicationMs: Long? = null,
    val publicationDurationMs: Long? = null,
    val visualOptIn: Boolean? = null,
    val visualDone: Int? = null,
    val visualTotal: Int? = null,
    val visualSkipped: Int? = null,
    val visualModelPresent: Boolean? = null,
    val visualModelVerified: Boolean? = null,
    val batteryPercent: Int? = null,
    val charging: Boolean? = null,
    val thermalStatus: Int? = null,
    val powerSave: Boolean? = null,
    val batteryExempt: Boolean? = null,
    val fileBytes: Map<String, Long?> = emptyMap(),
    val work: Map<String, AtlasWorkDiagnostic> = emptyMap(),
    val lastCrash: String? = null,
    val exits: List<AtlasProcessExitDiagnostic> = emptyList(),
    val scans: List<AtlasScanDiagnostic> = emptyList(),
    val logLines: List<String> = emptyList()
)

private val VERSION = Regex("[0-9]+(?:\\.[0-9]+){1,5}(?:-[0-9]+)?")
private val DEVICE = Regex("(?:SM-[A-Z0-9]{3,12}|Pixel [0-9]{1,2}[a-z]?(?: Pro| XL)?|sdk_gphone[0-9]{1,2}(?:_[a-z0-9]+)*)")
private val PHASES = setOf("UNAVAILABLE", "READY", "QUEUED", "SCANNING", "PAUSED",
    "FINISHED", "FAILED", "PERMISSION_REQUIRED", "BATTERY_LOW", "STORAGE_LOW",
    "REVIEW_AVAILABLE")
private val ACCESS = setOf("NONE", "FULL", "SELECTED", "PARTIAL")
private val OUTCOMES = setOf("SUCCESS", "FAILED", "SNAPSHOT_CHANGED", "OUT_OF_MEMORY",
    "CANCELLED", "SUPERSEDED", "PROVIDER_ERROR", "PERMISSION_DENIED")
private val STATES = setOf("ENQUEUED", "RUNNING", "SUCCEEDED", "FAILED", "BLOCKED", "CANCELLED")
private val CRASH_EXCEPTION = Regex(
    "(?m)^Exception: (java\\.lang\\.(?:OutOfMemoryError|IllegalStateException|" +
        "IllegalArgumentException|NullPointerException|RuntimeException|" +
        "SecurityException|ArrayIndexOutOfBoundsException)|OtherThrowable)$")
private val LOG_KEYS = setOf("rows", "reused", "elapsed_ms", "duration_ms", "heap_used",
    "heap_limit", "processed", "eligible", "faces", "clusters", "pending", "skipped",
    "done", "total", "attempts", "outcome", "phase", "state")
private val LOG_TAGS = setOf("ATLAS_SCAN", "ATLAS_PEOPLE", "ATLAS_VISUAL", "ATLAS_WORK")
private val LOG_ENUMS = OUTCOMES + PHASES + STATES

private fun safeLog(raw: String): String? {
    val fields = raw.split(' ')
    if (fields.size !in 2..12 || fields.first() !in LOG_TAGS) return null
    for (field in fields.drop(1)) {
        val kv = field.split('=', limit = 2)
        if (kv.size != 2 || kv[0] !in LOG_KEYS) return null
        val value = kv[1]
        if (value.toLongOrNull() == null && value !in LOG_ENUMS) return null
    }
    return raw.takeIf { it.length <= 240 }
}

private fun code(value: String?, allowed: Set<String>): String =
    value?.takeIf { it in allowed } ?: "unknown"
private fun count(value: Number?): String =
    value?.toLong()?.takeIf { it >= 0L }?.toString() ?: "unavailable"
private fun yesNo(value: Boolean?): String = when (value) {
    true -> "yes"
    false -> "no"
    null -> "unavailable"
}

internal fun formatAtlasDiagnostics(s: AtlasDiagnosticsSnapshot): String = buildString(4096) {
    fun line(name: String, value: String) {
        append(name).append(": ").append(value).append('\n')
    }
    append("ATLAS diagnostics v1 (aggregate-only)\n")
    line("Version", s.version.takeIf(VERSION::matches) ?: "unknown")
    line("Build", count(s.build))
    line("Android API", count(s.androidApi))
    line("Device model", s.deviceModel.takeIf(DEVICE::matches) ?: "unknown")
    line("Heap memory class MiB", count(s.memoryClassMb))
    line("Heap used bytes", count(s.heapUsedBytes))
    line("Heap limit bytes", count(s.heapLimitBytes))
    line("Free storage bytes", count(s.freeStorageBytes))
    line("Images", count(s.images))
    line("Videos", count(s.videos))
    line("Volumes", count(s.volumes))
    line("Folders", count(s.folders))
    line("Photo access", code(s.photoAccess, ACCESS))
    line("People phase", code(s.peoplePhase, PHASES))
    line("People eligible", count(s.peopleEligible))
    line("People processed", count(s.peopleProcessed))
    line("People faces", count(s.peopleFaces))
    line("People clusters", count(s.peopleClusters))
    line("People dirty", yesNo(s.peopleDirty))
    line("People pending", count(s.peoplePending))
    line("People opted in", yesNo(s.peopleOptIn))
    line("Last People publication epoch ms", count(s.lastPublicationMs))
    line("Last People publication duration ms", count(s.publicationDurationMs))
    line("Visual opted in", yesNo(s.visualOptIn))
    line("Visual done", count(s.visualDone))
    line("Visual total", count(s.visualTotal))
    line("Visual skipped", count(s.visualSkipped))
    line("Visual model present", yesNo(s.visualModelPresent))
    line("Visual model verified", yesNo(s.visualModelVerified))
    line("Battery percent", s.batteryPercent?.takeIf { it in 0..100 }?.toString() ?: "unavailable")
    line("Charging", yesNo(s.charging))
    line("Thermal status", count(s.thermalStatus))
    line("Power save", yesNo(s.powerSave))
    line("Battery optimisation exempt", yesNo(s.batteryExempt))
    for ((key, label) in listOf(
        "people" to "People database bytes", "face" to "Face database bytes",
        "visual" to "Visual database bytes", "curation" to "Curation database bytes",
        "thumbnail" to "Thumbnail cache bytes", "inventory" to "Inventory cache bytes"
    )) line(label, count(s.fileBytes[key]))
    for (key in listOf("people", "thumbnail", "visual")) {
        val work = s.work[key]
        line("Background $key", work?.let { code(it.state, STATES) } ?: "unavailable")
        line("Background $key attempts", count(work?.attempts))
        line("Background $key stop reason code", count(work?.stopReason))
    }
    // Never copy the stored report verbatim. Even a corrupted/app-modified file
    // cannot smuggle its free-text message into an export.
    line("Last crash recorded", yesNo(s.lastCrash != null))
    line("Crash exception", s.lastCrash?.let { CRASH_EXCEPTION.find(it)?.groupValues?.get(1) }
        ?: "unavailable")
    for (exit in s.exits.takeLast(5)) {
        append("Process exit: reason=").append(count(exit.reason))
            .append(" pss_kb=").append(count(exit.pssKb))
            .append(" rss_kb=").append(count(exit.rssKb)).append('\n')
    }
    if (s.scans.isEmpty()) line("Scan history", "unavailable")
    for (scan in s.scans.takeLast(20)) {
        append("Scan: elapsed_ms=").append(count(scan.durationMs))
            .append(" rows=").append(count(scan.rows))
            .append(" reused=").append(count(scan.reused))
            .append(" heap_used=").append(count(scan.heapUsedBytes))
            .append(" heap_limit=").append(count(scan.heapLimitBytes))
            .append(" outcome=").append(code(scan.outcome, OUTCOMES))
            .append('\n')
    }
    if (s.logLines.isEmpty()) line("ATLAS log ring", "unavailable")
    s.logLines.takeLast(500).mapNotNull(::safeLog).forEach {
        line("Log", it)
    }
}


/**
 * In-process ring: only aggregate events published while this process is alive.
 * This cannot recover historical logcat or previous-process scans; those remain
 * explicitly unavailable after restart. No persisted identifiers or media data.
 */
internal object AtlasDiagnosticsRing {
    private val lock = Any()
    private val history = ArrayDeque<AtlasScanDiagnostic>()
    private val lines = ArrayDeque<String>()

    fun scan(durationMs: Long, rows: Int?, outcome: String, reused: Int? = null) {
        val runtime = Runtime.getRuntime()
        val used = (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0)
        val limit = runtime.maxMemory()
        val safeOutcome = code(outcome, OUTCOMES)
        val event = AtlasScanDiagnostic(durationMs.coerceAtLeast(0), rows?.takeIf { it >= 0 },
            reused?.takeIf { it >= 0 }, used, limit, safeOutcome)
        synchronized(lock) {
            history.addLast(event)
            while (history.size > 20) history.removeFirst()
            appendLog("ATLAS_SCAN elapsed_ms=" + event.durationMs +
                (event.rows?.let { " rows=" + it } ?: "") +
                (event.reused?.let { " reused=" + it } ?: "") + " heap_used=" + used +
                " heap_limit=" + limit + " outcome=" + safeOutcome)
        }
    }

    fun people(phase: String, processed: Int, eligible: Int) {
        synchronized(lock) {
            appendLog("ATLAS_PEOPLE phase=" + code(phase, PHASES) +
                " processed=" + processed.coerceAtLeast(0) +
                " eligible=" + eligible.coerceAtLeast(0))
        }
    }

    // Called only under lock. No raw message can pass the safeLog grammar.
    private fun appendLog(line: String) {
        safeLog(line)?.let {
            lines.addLast(it)
            while (lines.size > 500) lines.removeFirst()
        }
    }
    fun scans(): List<AtlasScanDiagnostic> = synchronized(lock) { history.toList() }
    fun logs(): List<String> = synchronized(lock) { lines.toList() }
}
