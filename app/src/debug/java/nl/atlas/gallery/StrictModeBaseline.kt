package nl.atlas.gallery

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Process
import android.os.StrictMode
import android.os.SystemClock
import android.os.strictmode.Violation
import java.util.concurrent.Executor

/**
 * #417 PERF-7a: debug-build-only StrictMode baseline for main-thread disk I/O.
 *
 * This provider lives only in the debug source set, so release builds carry no
 * runtime policy at all. It does nothing unless the app-private marker file
 * [MARKER] exists, so the ordinary debug test suite runs unchanged (StrictMode
 * stack capture would otherwise slow its timing assertions). Turn it on for a
 * debug install with (the API 35 emulator refused `pm enable` from the shell,
 * run 44315697453, so a disabled component cannot be the switch):
 *
 *     adb shell run-as nl.atlas.gallery sh -c 'mkdir -p files && touch files/atlas-strictmode-baseline'
 *
 * Android creates providers before `Application.onCreate`, so the policy also
 * covers [AtlasApplication] start-up. Violations go to logcat (tag `StrictMode`)
 * and to [StrictModeBaseline], which `StrictModeBaselineTest` reads per phase.
 */
class DebugStrictModeInstaller : ContentProvider() {
    override fun onCreate(): Boolean {
        // One stat before the policy exists, so it is never itself recorded.
        val marker = context?.filesDir?.resolve(MARKER)
        if (marker?.exists() == true) StrictModeBaseline.install()
        return true
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?
    ): Int = 0

    companion object {
        const val MARKER = "atlas-strictmode-baseline"
    }
}

/** Main-thread StrictMode violations, labelled with the phase the test set. */
object StrictModeBaseline {
    class Record(
        val phase: String,
        val sinceProcessStartMs: Long,
        val violation: String,
        val stack: List<StackTraceElement>
    )

    /** Phase label for violations delivered from now on; the test moves it. */
    @Volatile var phase: String = "process-start"
    @Volatile var installed: Boolean = false
        private set

    private val lock = Any()
    private val records = ArrayList<Record>()
    private var dropped = 0

    fun install() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .penaltyListener(Executor { it.run() }) { record(it) }
                .build()
        )
        installed = true
    }

    private fun record(violation: Violation) {
        val since = SystemClock.uptimeMillis() - Process.getStartUptimeMillis()
        synchronized(lock) {
            if (records.size >= MAX_RECORDS) { dropped++; return }
            records += Record(phase, since, violation.javaClass.simpleName,
                violation.stackTrace.toList())
        }
    }

    fun snapshot(): List<Record> = synchronized(lock) { records.toList() }
    fun dropped(): Int = synchronized(lock) { dropped }

    private const val MAX_RECORDS = 2_000
}

/** One distinct main-thread opening: the same phase, violation, trigger and app frame. */
data class StrictModeSite(
    val phase: String,
    val violation: String,
    val opening: String,
    val trigger: String,
    val appFrame: String,
    val harness: Boolean,
    val count: Int,
    val firstMs: Long,
    val stack: List<StackTraceElement>
)

/**
 * Pure classification of violation stacks, JVM-tested in `StrictModeSiteTest`.
 * A stack is listed deepest frame first, as `Throwable.stackTrace` returns it.
 */
object StrictModeSites {
    private val guardPrefixes = listOf(
        "android.os.StrictMode", "dalvik.system.BlockGuard", "libcore.io.BlockGuardOs",
        "libcore.io.ForwardingOs", "libcore.io.Linux", "libcore.io.IoBridge"
    )

    /** What was opened: model, sqlite, shared-prefs, asset, file, network or other. */
    fun opening(violation: String, stack: List<StackTraceElement>): String {
        if (violation == "NetworkViolation") return "network"
        for (frame in stack) {
            val c = frame.className
            when {
                c.startsWith("ai.onnxruntime.") -> return "model"
                c.startsWith("android.database.sqlite.") || c.startsWith("androidx.sqlite.") ->
                    return "sqlite"
                c == "android.app.SharedPreferencesImpl" ||
                    (c == "android.app.ContextImpl" && frame.methodName == "getSharedPreferences") ->
                    return "shared-prefs"
                c.startsWith("android.content.res.AssetManager") -> return "asset"
            }
        }
        val file = stack.any {
            it.className.startsWith("java.io.") || it.className.startsWith("java.nio.") ||
                it.className.startsWith("libcore.io.") ||
                it.className == "android.os.ParcelFileDescriptor"
        }
        return if (file) "file" else "other"
    }

    /** The first frame outside StrictMode/BlockGuard plumbing, e.g. `java.io.File.exists`. */
    fun trigger(stack: List<StackTraceElement>): String =
        stack.firstOrNull { f -> guardPrefixes.none { f.className.startsWith(it) } }
            ?.let { "${it.className}.${it.methodName}" } ?: "unknown"

    /** The deepest ATLAS frame, e.g. `nl.atlas.gallery.Foo.bar(Foo.kt:12)`, or `none`. */
    fun appFrame(stack: List<StackTraceElement>): String =
        stack.firstOrNull {
            it.className.startsWith("nl.atlas.gallery.") &&
                !it.className.startsWith("nl.atlas.gallery.StrictModeBaseline") &&
                !it.className.startsWith("nl.atlas.gallery.DebugStrictModeInstaller")
        }?.let { "${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})" } ?: "none"

    /** Stacks the instrumentation itself caused (runner, test code), not the app. */
    fun harness(stack: List<StackTraceElement>): Boolean = stack.any {
        it.className.startsWith("androidx.test.") || it.className.startsWith("org.junit.") ||
            it.className.endsWith("Test") || it.className.contains("Test$")
    }

    fun group(records: List<StrictModeBaseline.Record>): List<StrictModeSite> =
        records.groupBy { r ->
            listOf(r.phase, r.violation, trigger(r.stack), appFrame(r.stack))
        }.map { (_, rs) ->
            val first = rs.minBy { it.sinceProcessStartMs }
            StrictModeSite(
                phase = first.phase, violation = first.violation,
                opening = opening(first.violation, first.stack),
                trigger = trigger(first.stack), appFrame = appFrame(first.stack),
                harness = harness(first.stack), count = rs.size,
                firstMs = first.sinceProcessStartMs, stack = first.stack
            )
        }.sortedWith(compareBy({ it.firstMs }, { it.trigger }))
}
