package nl.atlas.gallery.uitest

import android.os.Looper
import android.view.View
import android.view.WindowInsets
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.toSize
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.abs

/**
 * The tap for emulator tests (docs/testing/EMULATOR_UI_TESTS.md, rule 1).
 *
 * Compose's performClick() is a touch at the centre of the node's CLIPPED
 * bounds. A node that is scrolled out of its list, or that a shrinking
 * keyboard-padded list no longer shows, has empty clipped bounds: the touch
 * then lands somewhere else, nothing happens and nothing fails. The test only
 * times out a minute later on whatever the tap should have caused.
 *
 * tap() scrolls the node into its scroll parent first, then refuses to tap
 * unless the whole node is on screen and above the keyboard. A refused tap
 * says where the node is and what hides it.
 */
fun SemanticsNodeInteraction.tap(): SemanticsNodeInteraction {
    var node = fetchSemanticsNode("tap(): the node to tap does not exist")
    if (node.visibilityProblem() != null && node.hasScrollParent()) {
        performScrollTo()
        node = fetchSemanticsNode("tap(): the node disappeared while scrolling to it")
    }
    node.visibilityProblem()?.let { problem ->
        throw AssertionError("tap() refused: ${node.label()} $problem. A touch there would " +
            "land elsewhere and do nothing. Scroll it into view, or close the keyboard first.")
    }
    return performClick()
}

/** Fails unless the whole node is on screen and above the keyboard. */
fun SemanticsNodeInteraction.assertFullyVisible(): SemanticsNodeInteraction {
    val node = fetchSemanticsNode("assertFullyVisible(): the node does not exist")
    node.visibilityProblem()?.let { throw AssertionError("${node.label()} $it") }
    return this
}

/**
 * captureToImage() for pixel checks (rule 1): it copies only the clipped part
 * of a node, so a tile half under a header gives pixels of the wrong place.
 * Scrolls the node into view and refuses a node that is still not whole.
 */
fun SemanticsNodeInteraction.captureWhole(): ImageBitmap {
    val node = fetchSemanticsNode("captureWhole(): the node does not exist")
    if (node.visibilityProblem() != null && node.hasScrollParent()) performScrollTo()
    return assertFullyVisible().captureToImage()
}

/**
 * Why a person could not see or touch the whole node, or null when they can:
 * it has no size, a scroll viewport or clipping parent cuts it, or it lies
 * under the keyboard. Other content drawn on top of it is not detected.
 */
fun SemanticsNode.visibilityProblem(): String? {
    val whole = Rect(positionInRoot, size.toSize())
    val shown = boundsInRoot
    if (whole.width < 1f || whole.height < 1f) return "has no size (laid out at ${whole.px()})"
    val cut = abs(shown.left - whole.left) > 1f || abs(shown.top - whole.top) > 1f ||
        abs(shown.right - whole.right) > 1f || abs(shown.bottom - whole.bottom) > 1f
    if (cut) {
        return "is cut off by a scroll viewport or a clipping parent: laid out at " +
            "${whole.px()}, visible part ${shown.px()}"
    }
    val view = (root as? ViewRootForTest)?.view ?: return null
    val (keyboard, windowHeight) = view.keyboardInsetAndWindowHeight()
    if (keyboard > 0) {
        val bottom = positionInWindow.y + size.height
        val keyboardTop = windowHeight - keyboard
        if (bottom > keyboardTop + 1f) {
            return "is under the keyboard: its bottom is at ${bottom.toInt()} px in the " +
                "window and the keyboard starts at $keyboardTop px"
        }
    }
    return null
}

/** The keyboard (IME) bottom inset and the window height, in px, read on the main thread. */
internal fun View.keyboardInsetAndWindowHeight(): Pair<Int, Int> {
    var result = 0 to 0
    val read = Runnable {
        val inset = rootWindowInsets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0
        result = inset to rootView.height
    }
    if (Looper.myLooper() == Looper.getMainLooper()) read.run()
    else InstrumentationRegistry.getInstrumentation().runOnMainSync(read)
    return result
}

private fun SemanticsNode.hasScrollParent(): Boolean =
    generateSequence(parent) { it.parent }.any { SemanticsActions.ScrollBy in it.config }

private fun SemanticsNode.label(): String {
    val tag = config.getOrNull(SemanticsProperties.TestTag)
    val text = config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
    return when {
        tag != null -> "'$tag'"
        !text.isNullOrEmpty() -> "'$text'"
        else -> "node #$id"
    }
}

private fun Rect.px(): String =
    "${left.toInt()},${top.toInt()}..${right.toInt()},${bottom.toInt()} px"
