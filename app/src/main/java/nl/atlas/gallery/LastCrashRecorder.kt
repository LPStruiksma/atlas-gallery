package nl.atlas.gallery

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Looper
import android.os.Process
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import kotlin.system.exitProcess

/**
 * #33: a deliberately lossy crash summary. Never write Throwable.toString(),
 * message, source file, thread name, URI, user input or Android's raw stack trace.
 * Only known framework/ATLAS class and method identifiers and line numbers survive.
 */
private val REPORT_NAME = Regex("[A-Za-z0-9_.\u0024]{1,180}")
private val METHOD_NAME = Regex("[A-Za-z0-9_\u0024<>-]{1,80}")
private val VERSION_NAME = Regex("[A-Za-z0-9_.-]{1,64}")
private val SAFE_PACKAGES = listOf(
    "nl.atlas.gallery.", "android.", "androidx.", "java.", "javax.", "kotlin.",
    "kotlinx.", "com.microsoft.onnxruntime."
)

private val HEAP_FIELDS = Regex("heap_used_mb=[0-9]{1,7} heap_max_mb=[0-9]{1,7}")

private fun safeClass(name: String): String? =
    name.takeIf { REPORT_NAME.matches(it) && SAFE_PACKAGES.any { name.startsWith(it) } }

/**
 * Java heap in use and its limit, in MiB. Numbers only, so safe in a log line
 * or a crash report; they tell an out-of-memory crash apart from other ones.
 */
internal fun heapUseLogFields(
    usedBytes: Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() },
    maxBytes: Long = Runtime.getRuntime().maxMemory()
): String {
    val mib = 1024L * 1024L
    return "heap_used_mb=${usedBytes.coerceAtLeast(0L) / mib} " +
        "heap_max_mb=${maxBytes.coerceAtLeast(0L) / mib}"
}

/**
 * One earlier end of the app's process, as Android recorded it. A fixed word
 * for the reason and plain numbers; never Android's free-text description.
 */
internal data class ProcessExitFact(
    val timestampMillis: Long,
    val reason: String,
    val pssKb: Long,
    val rssKb: Long
)

/** Fixed names for [ApplicationExitInfo] reason codes; anything else is "other". */
internal fun processExitReasonName(code: Int): String = when (code) {
    1 -> "exit_self"            // REASON_EXIT_SELF
    2 -> "signaled"             // REASON_SIGNALED
    3 -> "low_memory"           // REASON_LOW_MEMORY
    4 -> "crash"                // REASON_CRASH
    5 -> "crash_native"         // REASON_CRASH_NATIVE
    6 -> "not_responding"       // REASON_ANR
    7 -> "initialization_failure"
    8 -> "permission_change"
    9 -> "excessive_resource_usage"
    10 -> "user_requested"
    11 -> "user_stopped"
    12 -> "dependency_died"
    13 -> "other_kill"          // REASON_OTHER
    14 -> "freezer"
    15 -> "package_state_change"
    16 -> "package_updated"
    else -> "other"
}

/**
 * #33: the ends of the last few app processes. The uncaught-exception trace
 * above only sees Java crashes; a native crash, Android reclaiming memory or
 * an unresponsive app end the process without one, and show up only here.
 */
internal fun formatProcessExits(exits: List<ProcessExitFact>): String {
    if (exits.isEmpty()) return ""
    val result = StringBuilder(64 + exits.size * 80)
    result.append("Recent app exits (newest first)\n")
    for (exit in exits.take(MAX_REPORTED_EXITS)) {
        result.append("  ").append(Instant.ofEpochMilli(exit.timestampMillis))
            .append(' ').append(exit.reason.takeIf(REPORT_NAME::matches) ?: "other")
            .append(" pss_mb=").append(exit.pssKb.coerceAtLeast(0L) / 1024L)
            .append(" rss_mb=").append(exit.rssKb.coerceAtLeast(0L) / 1024L)
            .append('\n')
    }
    return result.toString()
}

private const val MAX_REPORTED_EXITS = 8

internal fun formatCrashReport(
    timestampMillis: Long,
    version: String,
    threadCategory: String,
    failure: Throwable,
    /** [heapUseLogFields] at the time of the crash; null leaves the line out. */
    heap: String? = null,
    /** #42: caught and shown in the app instead of ending the process. */
    handled: Boolean = false
): String {
    val result = StringBuilder(512)
    result.append("ATLAS last crash\nTime: ")
        .append(Instant.ofEpochMilli(timestampMillis))
        .append("\nVersion: ")
        .append(version.takeIf(VERSION_NAME::matches) ?: "unknown")
        .append("\nThread: ")
        .append(if (threadCategory == "main") "main" else "background")
        .append(if (handled) "\nOutcome: handled, app kept running" else "")
        .append("\nException: ")
        .append(safeClass(failure.javaClass.name) ?: "OtherThrowable")
        .append('\n')
    if (heap != null && HEAP_FIELDS.matches(heap)) result.append("Heap: ").append(heap).append('\n')
    var cause = failure.cause
    var depth = 0
    while (cause != null && depth < 3) {
        result.append("Caused by: ")
            .append(safeClass(cause.javaClass.name) ?: "OtherThrowable").append('\n')
        cause = cause.cause
        depth++
    }
    var count = 0
    for (frame in failure.stackTrace) {
        if (count >= 48) break
        val clazz = safeClass(frame.className) ?: continue
        val method = frame.methodName.takeIf(METHOD_NAME::matches) ?: continue
        // A line number is data only, never a source filename or path.
        val line = "  at " + clazz + "." + method + ":" + frame.lineNumber + "\n"
        if (result.length + line.length > 8_000) break
        result.append(line)
        count++
    }
    return result.toString()
}

internal object LastCrashRecorder {
    private fun file(context: Context): File = File(context.filesDir, "crash/last.txt")

    /** The last Java crash, then how the last few app processes ended; null when neither exists. */
    fun read(context: Context): String? {
        val crash = runCatching {
            file(context).takeIf { it.isFile }?.readText(Charsets.UTF_8)
        }.getOrNull()
        val exits = formatProcessExits(recentExits(context))
        return when {
            crash == null && exits.isEmpty() -> null
            crash == null -> exits
            exits.isEmpty() -> crash
            else -> crash.trimEnd('\n') + "\n\n" + exits
        }
    }

    /** Android's own record of this app's ended processes (API 30+); empty when unavailable. */
    private fun recentExits(context: Context): List<ProcessExitFact> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return runCatching {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return emptyList()
            manager.getHistoricalProcessExitReasons(context.packageName, 0, MAX_REPORTED_EXITS)
                .map { info: ApplicationExitInfo ->
                    ProcessExitFact(info.timestamp, processExitReasonName(info.reason),
                        info.pss, info.rss)
                }
        }.getOrDefault(emptyList())
    }

    private fun version(appContext: Context): String = runCatching {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
    }.getOrNull() ?: "unknown"

    private fun write(appContext: Context, report: String) {
        val dest = file(appContext)
        if (dest.parentFile?.mkdirs() == true || dest.parentFile?.isDirectory == true) {
            val temp = File(dest.parentFile, "last.tmp")
            FileOutputStream(temp).use { output ->
                output.write(report.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            if (!temp.renameTo(dest)) temp.delete()
        }
    }

    /**
     * #42: a failure the app caught and survived (Photo DNA planning) is
     * still the Last crash Lukas can copy, marked as handled. Never throws.
     */
    fun recordHandled(context: Context, failure: Throwable) {
        try {
            val appContext = context.applicationContext
            val heap = runCatching { heapUseLogFields() }.getOrNull()
            write(appContext, formatCrashReport(System.currentTimeMillis(),
                version(appContext), "background", failure, heap, handled = true))
        } catch (_: Throwable) {
            // Out of disk space/OOM: the app already shows the failure.
        }
    }

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val appContext = context.applicationContext
        val version = version(appContext)
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val kind = if (thread === Looper.getMainLooper().thread) "main" else "background"
                val heap = runCatching { heapUseLogFields() }.getOrNull()
                write(appContext, formatCrashReport(System.currentTimeMillis(), version, kind,
                    throwable, heap))
            } catch (_: Throwable) {
                // Out of disk space/OOM must not conceal the original fatal crash.
            } finally {
                previous?.uncaughtException(thread, throwable) ?: run {
                    Process.killProcess(Process.myPid())
                    exitProcess(10)
                }
            }
        }
    }
}
