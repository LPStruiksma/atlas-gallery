package nl.atlas.gallery.testkit

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
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.toSize
import kotlin.math.abs

/**
 * The default tap (docs/testing/EMULATOR_TESTS.md, rule 3).
 *
 * Compose's performClick() is a touch at the centre of the node's CLIPPED
 * bounds. For a node scrolled out of its list, or one a keyboard-padded list
 * no longer shows, the touch lands somewhere else: nothing happens and
 * nothing fails. The test times out later on whatever the tap should have
 * caused.
 *
 * tap() waits until the node exists, scrolls it into its scroll parent, and
 * requires it to be enabled, whole on screen, above the keyboard and at the
 * same place on two frames in a row. Then it invokes the node's click action.
 * Otherwise it fails and says where the node is and what cuts it. It never
 * retries. For a test about touch delivery itself use [touchTap].
 */
fun ComposeTestRule.tap(matcher: SemanticsMatcher, useUnmergedTree: Boolean = false) {
    val target = awaitTappable("tap", matcher, useUnmergedTree)
    val node = target.fetchSemanticsNode()
    if (SemanticsActions.OnClick !in node.config) {
        throw AssertionError("tap() refused: ${matcher.description} has no click action " +
            "(${node.place()}). Match the clickable node, or use touchTap for a gesture target.")
    }
    target.performSemanticsAction(SemanticsActions.OnClick)
}

/**
 * A tap with real pointer events, for tests about touch delivery (TOUCH-1,
 * gestures, hit targets): rule 4. Same preconditions as [tap], then DOWN and
 * UP at the node's centre, then [effect] must become true within [timeoutMs].
 * No effect is a failure that says where the pointer went and what was there.
 *
 * Compose test pointers go straight to the Compose view. They do not pass the
 * window, so the keyboard window cannot take them and a Window.Callback never
 * sees them; only UiAutomation.injectInputEvent travels that path.
 */
fun ComposeTestRule.touchTap(
    matcher: SemanticsMatcher, timeoutMs: Long = 10_000L, effect: () -> Boolean
) {
    val target = awaitTappable("touchTap", matcher, useUnmergedTree = false)
    val bounds = target.fetchSemanticsNode().boundsInRoot
    target.performTouchInput { click() }
    try {
        awaitThat("the effect of touchTap(${matcher.description})", timeoutMs, effect)
    } catch (timeout: AssertionError) {
        val point = bounds.center
        val under = runCatching {
            onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true)
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .lastOrNull { it.boundsInRoot.contains(point) }?.let { "${it.label()} ${it.place()}" }
        }.getOrNull() ?: "nothing"
        throw AssertionError("touchTap(${matcher.description}) had no effect. The pointer went " +
            "to ${point.x.toInt()},${point.y.toInt()} px; now at that point: $under. " +
            timeout.message, timeout)
    }
}

/** Fails unless the whole node is on screen and above the keyboard. */
fun SemanticsNodeInteraction.assertFullyVisible(): SemanticsNodeInteraction {
    val node = fetchSemanticsNode("assertFullyVisible(): the node does not exist")
    node.visibilityProblem()?.let { throw AssertionError("${node.label()} $it") }
    return this
}

/**
 * captureToImage() for pixel checks: it copies only the clipped part of a
 * node, so a tile half under a header gives pixels of the wrong place.
 * Scrolls the node into view and refuses a node that is still not whole.
 */
fun SemanticsNodeInteraction.captureWhole(): ImageBitmap {
    val node = fetchSemanticsNode("captureWhole(): the node does not exist")
    if (node.visibilityProblem() != null && node.hasScrollParent()) performScrollTo()
    return assertFullyVisible().captureToImage()
}

private fun ComposeTestRule.awaitTappable(
    what: String, matcher: SemanticsMatcher, useUnmergedTree: Boolean
): SemanticsNodeInteraction {
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
