package nl.atlas.gallery.testkit

import android.accessibilityservice.AccessibilityService
import android.app.UiModeManager
import android.content.Context
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.platform.app.InstrumentationRegistry
import nl.atlas.gallery.interactiveUiAutomation
import org.junit.runner.Description
import org.junit.runner.Result
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener

/** Log tag of the drift lines and the per-class drift summary. */
const val BASELINE_LOG_TAG = "ATLAS_BASELINE"

/** Log tag of the one device-state line per failing test. */
const val DIAG_LOG_TAG = "ATLAS_DIAG"

/**
 * Makes every test start from the same device state (docs/testing/EMULATOR_TESTS.md,
 * rule 7 and "Reading a red shard"). The shard script registers it on the
 * whole suite with the runner argument
 * `listener=nl.atlas.gallery.testkit.AtlasRunListener`; no Gradle change.
 *
 * - At the start of the run it records the baseline: keyboard hidden, display
 *   size and density, animator scale, night mode and the app on top.
 * - Before each test it compares the device with that baseline. Anything
 *   different is logged as one `ATLAS_BASELINE drift <what> left by <previous test>`
 *   line and put back: the keyboard is hidden (Back), a non-ATLAS app on top
 *   gets Back, settings and display size are restored.
 * - For each failing test it logs one `ATLAS_DIAG <test> <describeDevice()>` line.
 * - At the end it logs how often each test class left drift behind.
 *
 * Reads are in-process or one accessibility call, so a test that leaves
 * nothing behind costs a few milliseconds. Shell commands run only to put
 * something back. A listener must never fail a test: every callback catches
 * everything and logs it.
 */
class AtlasRunListener : RunListener() {
    private var baseline: DeviceBaseline? = null
    private var previousTest = "(run start)"
    private val driftByClass = linkedMapOf<String, Int>()

    override fun testRunStarted(description: Description?) = guarded("testRunStarted") {
        baseline = DeviceBaseline.baseline()
        Log.i(BASELINE_LOG_TAG, "baseline ${baseline?.describe()}")
    }

    override fun testStarted(description: Description) = guarded("testStarted") {
        val base = baseline ?: DeviceBaseline.baseline().also { baseline = it }
        val drift = settleAndCompare(base)
        if (drift.isNotEmpty()) {
            Log.w(BASELINE_LOG_TAG, "drift ${drift.joinToString(", ") { it.what }} " +
                "left by $previousTest")
            val owner = previousTest.substringBefore('#')
            driftByClass[owner] = (driftByClass[owner] ?: 0) + 1
            restore(base, drift)
        }
    }

    override fun testFinished(description: Description) {
        previousTest = description.name()
    }

    override fun testFailure(failure: Failure) = guarded("testFailure") {
        Log.e(DIAG_LOG_TAG, "${failure.description.name()} ${describeDevice()}")
    }

    override fun testRunFinished(result: Result?) = guarded("testRunFinished") {
        if (driftByClass.isEmpty()) {
            Log.i(BASELINE_LOG_TAG, "summary no drift")
        } else {
            driftByClass.forEach { (owner, count) ->
                Log.w(BASELINE_LOG_TAG, "summary $owner left drift $count time(s)")
            }
        }
    }

    /**
     * Reads the device and returns what differs from [base]. A test's window
     * and its keyboard go away a moment after the test ends, so a difference
     * counts only when it is still there after [SETTLE_MS] of real time.
     */
    private fun settleAndCompare(base: DeviceBaseline): List<Drift> {
        val until = SystemClock.uptimeMillis() + SETTLE_MS
        var drift = base.compare(DeviceBaseline.now())
        while (drift.isNotEmpty() && SystemClock.uptimeMillis() < until) {
            SystemClock.sleep(POLL_MS)
            drift = base.compare(DeviceBaseline.now())
        }
        return drift
    }

    private fun restore(base: DeviceBaseline, drift: List<Drift>) {
        val kinds = drift.map { it.kind }.toSet()
        // The keyboard goes first: a display reset with the keyboard up can
        // leave LatinIME unavailable for the rest of the shard (#437).
        if (DriftKind.Keyboard in kinds) {
            back()
            if (!waitFor { DeviceBaseline.keyboardInsetPx() == 0 }) {
                Log.w(BASELINE_LOG_TAG, "restore keyboard still up after Back " +
                    "(${DeviceBaseline.keyboardInsetPx()} px)")
            }
        }
        if (DriftKind.TopApp in kinds) {
            val top = DeviceBaseline.topPackage()
            if (top != null && !top.startsWith(ATLAS_PACKAGE)) {
                back()
                waitFor { DeviceBaseline.topPackage() == base.topPackage }
            }
        }
        if (DriftKind.Animator in kinds) {
            testShell(base.animatorScale?.let { "settings put global animator_duration_scale $it" }
                ?: "settings delete global animator_duration_scale")
        }
        if (DriftKind.Night in kinds) {
            testShell("cmd uimode night ${nightArgument(base.nightMode)}")
            waitFor { DeviceBaseline.nightMode() == base.nightMode }
        }
        if (DriftKind.Display in kinds) {
            testShell(base.sizeOverride?.let { "wm size $it" } ?: "wm size reset")
            testShell(base.densityOverride?.let { "wm density $it" } ?: "wm density reset")
            waitFor { DeviceBaseline.display() == base.display }
        }
        val left = base.compare(DeviceBaseline.now())
        if (left.isNotEmpty()) {
            Log.w(BASELINE_LOG_TAG, "restore incomplete: ${left.joinToString(", ") { it.what }}")
        }
    }

    private fun back() {
        runCatching { interactiveUiAutomation().performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }
    }

    private fun waitFor(timeoutMs: Long = RESTORE_MS, condition: () -> Boolean): Boolean {
        val until = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            if (runCatching(condition).getOrDefault(false)) return true
            if (SystemClock.uptimeMillis() >= until) return false
            SystemClock.sleep(POLL_MS)
        }
    }

    private inline fun guarded(callback: String, body: () -> Unit) {
        try {
            body()
        } catch (t: Throwable) {
            Log.w(BASELINE_LOG_TAG, "listener $callback skipped: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private companion object {
        const val SETTLE_MS = 1_500L
        const val RESTORE_MS = 3_000L
        const val POLL_MS = 100L
        const val ATLAS_PACKAGE = "nl.atlas.gallery"

        fun Description.name(): String =
            if (methodName == null) className else "$className#$methodName"

        fun nightArgument(mode: Int) = when (mode) {
            UiModeManager.MODE_NIGHT_YES -> "yes"
            UiModeManager.MODE_NIGHT_NO -> "no"
            else -> "auto"
        }
    }
}

internal enum class DriftKind { Keyboard, Display, Animator, Night, TopApp }

internal data class Drift(val kind: DriftKind, val what: String)

/** Display size, density and rotation as the system reports them now. */
internal data class DisplayState(val width: Int, val height: Int, val densityDpi: Int, val rotation: Int) {
    override fun toString() = "${width}x$height ${densityDpi}dpi rotation $rotation"
}

/**
 * The device state a test must leave behind, read without the shell except
 * for the one-time `wm size`/`wm density` overrides at the start of the run.
 */
internal data class DeviceBaseline(
    val keyboardPx: Int,
    val display: DisplayState?,
    val sizeOverride: String?,
    val densityOverride: String?,
    val animatorScale: String?,
    val nightMode: Int,
    val topPackage: String?
) {
    fun describe() = "keyboard ${keyboardPx}px, display $display " +
        "(override size ${sizeOverride ?: "none"}, density ${densityOverride ?: "none"}), " +
        "animator scale ${animatorScale ?: "default"}, night ${nightMode}, top ${topPackage ?: "?"}"

    /** What [now] has that differs from this baseline. The keyboard baseline is always hidden. */
    fun compare(now: DeviceBaseline): List<Drift> = buildList {
        if (now.keyboardPx > 0) add(Drift(DriftKind.Keyboard, "keyboard ${now.keyboardPx}px"))
        if (display != null && now.display != null && now.display != display) {
            add(Drift(DriftKind.Display, "display ${now.display} (baseline $display)"))
        }
        if (now.animatorScale != animatorScale) {
            add(Drift(DriftKind.Animator, "animator scale ${now.animatorScale ?: "default"} " +
                "(baseline ${animatorScale ?: "default"})"))
        }
        if (now.nightMode != nightMode) {
            add(Drift(DriftKind.Night, "night mode ${now.nightMode} (baseline $nightMode)"))
        }
        if (topPackage != null && now.topPackage != null && now.topPackage != topPackage) {
            add(Drift(DriftKind.TopApp, "top app ${now.topPackage} (baseline $topPackage)"))
        }
    }

    companion object {
        private val context: Context
            get() = InstrumentationRegistry.getInstrumentation().targetContext

        /** The state now, without the shell overrides. */
        fun now(): DeviceBaseline = read(withOverrides = false)

        /** The baseline for a run: the state now, the keyboard counted as hidden. */
        fun baseline(): DeviceBaseline = read(withOverrides = true).copy(keyboardPx = 0)

        private fun read(withOverrides: Boolean) = DeviceBaseline(
            keyboardPx = keyboardInsetPx(),
            display = display(),
            sizeOverride = if (withOverrides) override("wm size", "Override size: ") else null,
            densityOverride = if (withOverrides) override("wm density", "Override density: ") else null,
            animatorScale = Settings.Global.getString(context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE),
            nightMode = nightMode(),
            topPackage = topPackage()
        )

        /** Height of the on-screen input method window in px, 0 when none is shown. */
        fun keyboardInsetPx(): Int = runCatching {
            interactiveUiAutomation().windows
                .filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                .maxOfOrNull { window -> Rect().also { window.getBoundsInScreen(it) }.height() } ?: 0
        }.getOrDefault(0)

        /** The package of the active application window, or null when unknown. */
        fun topPackage(): String? = runCatching {
            val windows = interactiveUiAutomation().windows
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            val top = windows.firstOrNull { it.isActive } ?: windows.firstOrNull()
            top?.root?.packageName?.toString()
        }.getOrNull()

        fun nightMode(): Int =
            (context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).nightMode

        @Suppress("DEPRECATION")
        fun display(): DisplayState? = runCatching {
            val display = (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                .getDisplay(Display.DEFAULT_DISPLAY)
            val metrics = DisplayMetrics().also { display.getRealMetrics(it) }
            DisplayState(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi, display.rotation)
        }.getOrNull()

        private fun override(command: String, prefix: String): String? =
            runCatching { testShell(command) }.getOrDefault("").lineSequence()
                .map { it.trim() }.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)
    }
}
