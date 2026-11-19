package nl.atlas.gallery.testkit

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.toSize
import kotlin.math.abs

/**
 * The tap for emulator tests (docs/testing/EMULATOR_TESTS.md, rules 3 and 4).
 *
 * Compose's performClick() is a touch at the centre of the node's CLIPPED
 * bounds. For a node scrolled out of its list, or one a keyboard-padded list
 * no longer shows, the touch lands somewhere else: nothing happens and
 * nothing fails. The test times out later on whatever the tap should have
 * caused.
 *
 * tap() waits until the node exists, scrolls it into its scroll parent, and
 * requires it to be enabled, whole on screen, above the keyboard and at the
 * same place on two frames in a row. Then it sends a pointer DOWN and UP at
 * the node's centre, so hit testing still happens and an overlay that
 * swallows the touch is caught by the missing effect. If the node moved
 * while the pointer was down it fails with both positions. It never retries.
 *
 * Compose test pointers go straight to the Compose view. They do not pass the
 * window, so the keyboard window cannot take them and a Window.Callback never
 * sees them; only UiAutomation.injectInputEvent travels that path.
 */
fun ComposeTestRule.tap(matcher: SemanticsMatcher, useUnmergedTree: Boolean = false) {
    pointerTap(matcher, useUnmergedTree)
}

/**
 * [tap], then [effect] must become true within [timeoutMs]. For tests about
 * touch delivery (TOUCH-1, gestures, hit targets) and wherever the next step
 * depends on the tap having landed. No effect is a failure that says where
 * the pointer went and what is at that point now.
 */
fun ComposeTestRule.tap(
    matcher: SemanticsMatcher,
    useUnmergedTree: Boolean = false,
    timeoutMs: Long = 10_000L,
    effect: () -> Boolean
) {
    val point = pointerTap(matcher, useUnmergedTree)
    try {
        awaitThat("the effect of tap(${matcher.description})", timeoutMs, effect)
    } catch (timeout: AssertionError) {
        val under = runCatching {
            onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true)
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .lastOrNull { it.boundsInRoot.contains(point) }?.let { "${it.label()} ${it.place()}" }
        }.getOrNull() ?: "nothing"
        throw AssertionError("tap(${matcher.description}) had no effect. The pointer went " +
            "to ${point.x.toInt()},${point.y.toInt()} px; now at that point: $under. " +
            timeout.message, timeout)
    }
}

/** Sends the checked pointer tap and returns the point it went to, in root px. */
private fun ComposeTestRule.pointerTap(matcher: SemanticsMatcher, useUnmergedTree: Boolean): Offset {
    val target = awaitTappable(matcher, useUnmergedTree)
    val before = target.fetchSemanticsNode().wholeInRoot()
    // The click advances the Compose clock by one frame between DOWN and UP.
    // Hold the clock otherwise, so the position read after UP is the one the
    // pointer met and not yet the result of the click itself.
    val autoAdvance = mainClock.autoAdvance
    mainClock.autoAdvance = false
    val after = try {
        target.performTouchInput { click() }
        runCatching { target.fetchSemanticsNode().wholeInRoot() }.getOrNull()
    } finally {
        mainClock.autoAdvance = autoAdvance
    }
    if (after != null && after != before) {
        throw AssertionError("tap(${matcher.description}): the node moved while the pointer " +
            "was down, from ${before.px()} to ${after.px()}. The touch may have missed it. " +
            "Wait for the layout to finish before the tap.")
    }
    return before.center
}

/** Fails unless the whole node is on screen and above the keyboard. */
fun SemanticsNodeInteraction.assertFullyVisible(): SemanticsNodeInteraction {
    val node = fetchSemanticsNode("assertFullyVisible(): the node does not exist")
    node.visibilityProblem()?.let { throw AssertionError("${node.label()} $it") }
    return this
}

/**
 * captureToImage() for pixel checks (rule 9): it copies only the clipped part of a
 * node, so a tile half under a header gives pixels of the wrong place.
 * Scrolls the node into view and refuses a node that is still not whole.
 */
fun SemanticsNodeInteraction.captureWhole(): ImageBitmap {
    val node = fetchSemanticsNode("captureWhole(): the node does not exist")
    if (node.visibilityProblem() != null && node.hasScrollParent()) performScrollTo()
    return assertFullyVisible().captureToImage()
}

private fun ComposeTestRule.awaitTappable(
    matcher: SemanticsMatcher, useUnmergedTree: Boolean
): SemanticsNodeInteraction {
    val what = "tap"
    awaitThat("${matcher.description} to exist before $what") {
        exists(matcher, useUnmergedTree)
    }
    val target = onNode(matcher, useUnmergedTree)
    var node = target.fetchSemanticsNode()
    if (node.visibilityProblem() != null && node.hasScrollParent()) {
        target.performScrollTo()
        node = target.fetchSemanticsNode("$what(): the node disappeared while scrolling to it")
    }
    fun refuse(why: String): Nothing = throw AssertionError("$what() refused: " +
        "${matcher.description} $why. Device: ${describeDevice()}")
    if (SemanticsProperties.Disabled in node.config) refuse("is disabled (${node.place()})")
    node.visibilityProblem()?.let { refuse(it) }
    // Still: the same place on two display frames in a row, checked a few times.
    var before = node.wholeInRoot()
    repeat(STILL_ATTEMPTS) {
        sampleOnFrames { }
        val after = target.fetchSemanticsNode("$what(): the node disappeared").wholeInRoot()
        if (after == before) {
            target.fetchSemanticsNode().visibilityProblem()?.let { refuse(it) }
            return target
        }
        before = after
    }
    refuse("is still moving after $STILL_ATTEMPTS checks (now at ${before.px()})")
}

private const val STILL_ATTEMPTS = 30

/**
 * Why a person could not see or touch the whole node, or null when they can:
 * it has no size, a scroll viewport or clipping parent cuts it, or it lies
 * under the keyboard. Other content drawn on top of it is not detected.
 */
fun SemanticsNode.visibilityProblem(): String? {
    val whole = wholeInRoot()
    val shown = boundsInRoot
    if (whole.width < 1f || whole.height < 1f) return "has no size (laid out at ${whole.px()})"
    val cut = abs(shown.left - whole.left) > 1f || abs(shown.top - whole.top) > 1f ||
        abs(shown.right - whole.right) > 1f || abs(shown.bottom - whole.bottom) > 1f
    if (cut) {
        val clipper = generateSequence(parent) { it.parent }
            .firstOrNull { !it.boundsInRoot.containsRect(whole) }
        return "is cut off: laid out at ${whole.px()}, visible part ${shown.px()}" +
            (clipper?.let { ", clipped by ${it.label()} at ${it.boundsInRoot.px()}" } ?: "")
    }
    val view = (root as? ViewRootForTest)?.view ?: return null
    val (keyboard, windowHeight) = onMainThread { view.keyboardInset() to view.rootView.height }
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

private fun SemanticsNode.wholeInRoot(): Rect = Rect(positionInRoot, size.toSize())

private fun Rect.containsRect(other: Rect): Boolean =
    other.left >= left - 1f && other.top >= top - 1f &&
        other.right <= right + 1f && other.bottom <= bottom + 1f

private fun SemanticsNode.place(): String =
    "laid out at ${wholeInRoot().px()}, visible part ${boundsInRoot.px()}"

internal fun SemanticsNode.hasScrollParent(): Boolean =
    generateSequence(parent) { it.parent }.any { SemanticsActions.ScrollBy in it.config }

internal fun SemanticsNode.label(): String {
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
