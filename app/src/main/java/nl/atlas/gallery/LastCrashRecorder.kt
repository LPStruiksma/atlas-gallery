package nl.atlas.gallery

import android.content.Context
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

private fun safeClass(name: String): String? =
    name.takeIf { REPORT_NAME.matches(it) && SAFE_PACKAGES.any { name.startsWith(it) } }

internal fun formatCrashReport(
    timestampMillis: Long,
    version: String,
    threadCategory: String,
    failure: Throwable
): String {
    val result = StringBuilder(512)
    result.append("ATLAS last crash\nTime: ")
        .append(Instant.ofEpochMilli(timestampMillis))
        .append("\nVersion: ")
        .append(version.takeIf(VERSION_NAME::matches) ?: "unknown")
        .append("\nThread: ")
        .append(if (threadCategory == "main") "main" else "background")
        .append("\nException: ")
        .append(safeClass(failure.javaClass.name) ?: "OtherThrowable")
        .append('\n')
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

    fun read(context: Context): String? =
        runCatching { file(context).takeIf { it.isFile }?.readText(Charsets.UTF_8) }.getOrNull()

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val appContext = context.applicationContext
        val version = runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val kind = if (thread === Looper.getMainLooper().thread) "main" else "background"
                val report = formatCrashReport(System.currentTimeMillis(), version, kind, throwable)
                val dest = file(appContext)
                if (dest.parentFile?.mkdirs() == true || dest.parentFile?.isDirectory == true) {
                    val temp = File(dest.parentFile, "last.tmp")
                    FileOutputStream(temp).use { output ->
                        output.write(report.toByteArray(Charsets.UTF_8))
                        output.fd.sync()
                    }
                    if (!temp.renameTo(dest)) temp.delete()
                }
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
