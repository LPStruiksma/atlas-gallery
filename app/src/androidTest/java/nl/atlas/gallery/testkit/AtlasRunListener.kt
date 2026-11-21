package nl.atlas.gallery.testkit

import android.accessibilityservice.AccessibilityService
import android.app.UiModeManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import androidx.test.platform.app.InstrumentationRegistry
import nl.atlas.gallery.clearSystemAnrDialogs
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
 * A test that leaves nothing behind costs two shell reads (`dumpsys
 * input_method`, `dumpsys window displays`) and in-process settings reads.
 * The listener never touches the accessibility window list on that path: in
 * the #870 A/B the earlier listener, which asked UiAutomation for interactive
 * windows and their node trees before every test, came with teardown failures
 * the same tests never showed without it. Interactive windows are used only
 * to answer a system dialog it found, and switched back off afterwards.
 * A listener must never fail a test: every callback catches everything and logs it.
 */
class AtlasRunListener : RunListener() {
    private var baseline: DeviceBaseline? = null
    private var previousTest = "(run start)"
    private val driftByClass = linkedMapOf<String, Int>()
    private var checkedTests = 0
    private var checkMillis = 0L

    override fun testRunStarted(description: Description?) = guarded("testRunStarted") {
        baseline = DeviceBaseline.baseline()
        Log.i(BASELINE_LOG_TAG, "baseline ${baseline?.describe()}")
    }

    override fun testStarted(description: Description) = guarded("testStarted") {
        val started = SystemClock.uptimeMillis()
        try {
            checkBaseline()
        } finally {
            checkedTests++
            checkMillis += SystemClock.uptimeMillis() - started
        }
    }

    private fun checkBaseline() {
        val base = baseline ?: DeviceBaseline.baseline().also { baseline = it }
        // A system "isn't responding" dialog (Quickstep on the CI emulator)
        // takes focus from every test after it: run 44856398928 shard 0 lost
        // ExternalPickerJourneyTest to one. Press Wait before the test starts.
        val first = DeviceBaseline.now()
        if (first.anrDialog) {
            val cleared = runCatching { withInteractiveWindows { clearSystemAnrDialogs(attempts = 2) } }
                .getOrDefault(0)
            Log.w(BASELINE_LOG_TAG, "drift system 'isn't responding' dialog (cleared $cleared) " +
                "left by $previousTest")
            val owner = previousTest.substringBefore('#')
            driftByClass[owner] = (driftByClass[owner] ?: 0) + 1
        }
        val drift = settleAndCompare(base, first)
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
        // describeDevice() lists the accessibility windows; put the flag back after.
        val device = withInteractiveWindows { describeDevice() }
        Log.e(DIAG_LOG_TAG, "${failure.description.name()} $device")
    }

    override fun testRunFinished(result: Result?) = guarded("testRunFinished") {
        Log.i(BASELINE_LOG_TAG, "checked $checkedTests test(s) in $checkMillis ms")
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
    private fun settleAndCompare(base: DeviceBaseline, first: DeviceBaseline): List<Drift> {
        val until = SystemClock.uptimeMillis() + SETTLE_MS
        var drift = base.compare(first)
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
            if (!waitFor { !DeviceBaseline.keyboardShown() }) {
                Log.w(BASELINE_LOG_TAG, "restore keyboard still up after Back")
            }
        }
        if (DriftKind.TopApp in kinds) {
            val top = DeviceBaseline.focus().topPackage
            if (top != null && !top.startsWith(ATLAS_PACKAGE)) {
                back()
                waitFor { DeviceBaseline.focus().topPackage == base.topPackage }
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

    /** A global Back. Needs no interactive windows. */
    private fun back() {
        runCatching {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        }
    }

    /**
     * Runs [block] with UiAutomation's interactive windows on and puts the
     * service flags back as they were, so a found dialog does not leave the
     * flag on for the tests after it.
     */
    private fun <T> withInteractiveWindows(block: () -> T): T {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val before = automation.serviceInfo?.flags
        try {
            return block()
        } finally {
            val info = automation.serviceInfo
            if (before != null && info != null && info.flags != before) {
                info.flags = before
                automation.serviceInfo = info
            }
        }
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
    val keyboardShown: Boolean,
    val display: DisplayState?,
    val sizeOverride: String?,
    val densityOverride: String?,
    val animatorScale: String?,
    val nightMode: Int,
    val topPackage: String?,
    /** A system "isn't responding" dialog has focus. Answered, never compared. */
    val anrDialog: Boolean = false
) {
    fun describe() = "keyboard ${if (keyboardShown) "shown" else "hidden"}, display $display " +
        "(override size ${sizeOverride ?: "none"}, density ${densityOverride ?: "none"}), " +
        "animator scale ${animatorScale ?: "default"}, night ${nightMode}, top ${topPackage ?: "?"}"

    /** What [now] has that differs from this baseline. The keyboard baseline is always hidden. */
    fun compare(now: DeviceBaseline): List<Drift> = buildList {
        if (now.keyboardShown) add(Drift(DriftKind.Keyboard, "keyboard shown"))
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
        fun baseline(): DeviceBaseline = read(withOverrides = true).copy(keyboardShown = false)

        private fun read(withOverrides: Boolean): DeviceBaseline {
            val focus = focus()
            return DeviceBaseline(
                keyboardShown = keyboardShown(),
                display = display(),
                sizeOverride = if (withOverrides) override("wm size", "Override size: ") else null,
                densityOverride = if (withOverrides) override("wm density", "Override density: ") else null,
                animatorScale = Settings.Global.getString(context.contentResolver,
                    Settings.Global.ANIMATOR_DURATION_SCALE),
                nightMode = nightMode(),
                topPackage = focus.topPackage,
                anrDialog = focus.anrDialog
            )
        }

        /**
         * Whether the system shows a keyboard now: the input method service's
         * own `mInputShown` flag. In the first proof run (#440, run 44478342596)
         * the accessibility window list said no keyboard while `mInputShown=true`
         * and the keyboard was still on screen, so the flag is the signal
         * that catches a leak; the window list is not read at all (#870).
         */
        fun keyboardShown(): Boolean = runCatching {
            testShell("dumpsys input_method").lineSequence()
                .any { it.trim().startsWith("mInputShown=true") }
        }.getOrDefault(false)

        /**
         * The focused window as window manager reports it, from one
         * `dumpsys window displays` (the full `dumpsys window` when that has
         * no focus line). No accessibility call, no node trees.
         */
        fun focus(): Focus {
            val line = focusLine(focusCommand) ?: focusLine("dumpsys window")?.also {
                if (focusCommand != "dumpsys window") {
                    focusCommand = "dumpsys window"
                    Log.i(BASELINE_LOG_TAG, "focus read falls back to the full dumpsys window")
                }
            }
            return Focus.parse(line)
        }

        private var focusCommand = "dumpsys window displays"

        private fun focusLine(command: String): String? = runCatching {
            testShell(command).lineSequence().map { it.trim() }
                .firstOrNull { it.startsWith("mCurrentFocus=") }
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

/**
 * The focused window from a `mCurrentFocus=Window{4282c7e u0 <title>}` line.
 * An activity window's title is `<package>/<activity>`; the system ANR dialog's
 * title starts with "Application Not Responding".
 */
internal data class Focus(val topPackage: String?, val anrDialog: Boolean) {
    companion object {
        private val WINDOW = Regex("""Window\{\S+ u\d+ ([^}]*)}""")

        fun parse(line: String?): Focus {
            val title = line?.let { WINDOW.find(it)?.groupValues?.get(1)?.trim() }
                ?: return Focus(topPackage = null, anrDialog = false)
            val anr = title.startsWith("Application Not Responding", ignoreCase = true) ||
                title.contains("isn't responding", ignoreCase = true)
            val pkg = title.substringBefore('/', missingDelimiterValue = "")
                .takeIf { '/' in title && it.isNotBlank() && ' ' !in it }
            return Focus(topPackage = if (anr) null else pkg, anrDialog = anr)
        }
    }
}
