package nl.atlas.gallery.testkit

import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.WindowInsets
import androidx.compose.ui.test.junit4.ComposeTestRule
import nl.atlas.gallery.clearSystemAnrDialogs

/**
 * Call after anything that changes the window: display size, rotation, night
 * mode, recreate, coming back from a system dialog (rule 6). The system
 * applies those on its own schedule and Compose's idle check cannot see it.
 *
 * Returns when the keyboard, system-bar and cutout insets and the window size
 * are the same on two frames in a row and an app window has focus.
 */
fun ComposeTestRule.awaitWindowSettled(timeoutMs: Long = 10_000L) {
    var last = "no Compose window yet"
    try {
        awaitThat("the window to settle", timeoutMs) {
            val views = rootViews()
            if (views.isEmpty()) return@awaitThat false
            val frames = sampleOnFrames { views.map { it.windowState() } }
            last = frames.last().toString()
            val focused = frames.last().any { it.focused }
            if (!focused) dismissSystemAnrDialog()
            frames.distinct().size == 1 && focused
        }
    } catch (timeout: AssertionError) {
        throw AssertionError("${timeout.message}; last window state: $last", timeout)
    }
}

private var lastAnrCheck = 0L

/**
 * The emulator's launcher sometimes stops responding, and its system "isn't
 * responding" dialog then takes focus from the app: no keyboard can open and
 * no window settles (run 44338113784, shard 1, all six runs). That is the
 * device, not ATLAS. Presses Wait on such a dialog, at most once every two
 * seconds, and logs it. Never touches the app's own windows.
 */
internal fun dismissSystemAnrDialog() {
    val now = SystemClock.uptimeMillis()
    if (now - lastAnrCheck < 2_000L) return
    lastAnrCheck = now
    val cleared = runCatching { clearSystemAnrDialogs(attempts = 1) }.getOrDefault(0)
    if (cleared > 0) Log.w(TEST_KIT_LOG_TAG, "dismissed a system 'isn't responding' dialog")
}

internal data class WindowState(
    val width: Int, val height: Int, val keyboard: Int,
    val bars: String, val cutout: String, val focused: Boolean
)

internal fun View.windowState(): WindowState {
    val insets = rootWindowInsets
    return WindowState(
        width = rootView.width,
        height = rootView.height,
        keyboard = insets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0,
        bars = insets?.getInsets(WindowInsets.Type.systemBars())?.toString() ?: "-",
        cutout = insets?.getInsets(WindowInsets.Type.displayCutout())?.toString() ?: "-",
        focused = hasWindowFocus()
    )
}
