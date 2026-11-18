package nl.atlas.gallery.testkit

import android.view.View
import android.view.WindowInsets
import androidx.compose.ui.test.junit4.ComposeTestRule

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
            frames.distinct().size == 1 && frames.last().any { it.focused }
        }
    } catch (timeout: AssertionError) {
        throw AssertionError("${timeout.message}; last window state: $last", timeout)
    }
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
