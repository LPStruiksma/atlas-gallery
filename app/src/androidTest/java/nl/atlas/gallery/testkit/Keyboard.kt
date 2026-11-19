package nl.atlas.gallery.testkit

import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.WindowInsets
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.toSize
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** The state a test leaves the keyboard in after typing (rule 2). */
enum class KeyboardAfter {
    /** The default: the test is not about the keyboard. */
    Hidden,

    /** The test is about the keyboard: wait until it is up and still. */
    Shown
}

internal const val TEST_KIT_LOG_TAG = "ATLAS_TESTKIT"

/**
 * Compose roots are ordered with the Activity first, but a Dialog or Popup
 * may own window focus (and therefore the IME). Use that focused root when
 * issuing keyboard requests and reading its insets.
 */
private fun ComposeTestRule.keyboardWindow(): View? {
    val roots = rootViews()
    return roots.firstOrNull { view -> onMainThread { view.hasWindowFocus() } }
        ?: roots.firstOrNull()
}

/**
 * Types [text] into the node (semantics, not key presses) and leaves the
 * keyboard in the named state. Typing focuses the field, and focus asks the
 * system for the keyboard, which arrives 0.3 to 2 s later on real time.
 */
fun ComposeTestRule.typeText(
    matcher: SemanticsMatcher, text: String, keyboard: KeyboardAfter = KeyboardAfter.Hidden
) {
    awaitThat("${matcher.description} to exist before typing") {
        exists(matcher)
    }
    onNode(matcher).performTextInput(text)
    when (keyboard) {
        KeyboardAfter.Hidden -> hideKeyboard(showExpected = true)
        KeyboardAfter.Shown -> {
            // A field that already had focus does not ask for the keyboard again.
            keyboardWindow()?.let { view ->
                onMainThread { view.windowInsetsController?.show(WindowInsets.Type.ime()) }
            }
            awaitKeyboardShown()
        }
    }
}

/** The keyboard's bottom inset in px, as this rule's window sees it now. */
fun ComposeTestRule.keyboardInsetPx(): Int =
    keyboardWindow()?.let { view -> onMainThread { view.keyboardInset() } } ?: 0

/**
 * Hides the keyboard and returns when its inset has been 0 on every frame
 * for [stillMs] of real time. Asks through the window's insets controller and
 * asks again once a second.
 *
 * [showExpected] is for the moment right after typing: the field has asked
 * for the keyboard, but the request reaches the system later than a hide sent
 * now, and the keyboard would then come up after this returned (seen on the
 * CI emulator with LatinIME, 1 run in 12). So first wait, up to
 * [arrivalMs], until the keyboard is on its way, then hide it. With
 * [TestKeyboard.None] selected there is nothing to wait for.
 */
fun ComposeTestRule.hideKeyboard(
    timeoutMs: Long = 10_000L,
    stillMs: Long = 300L,
    showExpected: Boolean = false,
    arrivalMs: Long = 3_000L
) {
    val view = keyboardWindow() ?: return
    val noKeyboard = selectedInputMethod().endsWith(TestKeyboard.None.service)
    if (showExpected && !noKeyboard) {
        val until = SystemClock.uptimeMillis() + arrivalMs
        waitUntil(arrivalMs + 5_000L) {
            val arriving = onMainThread { view.keyboardInset() > 0 || view.keyboardRequested() }
            arriving || SystemClock.uptimeMillis() >= until
        }
    }
    var asked = 0L
    var zeroSince = 0L
    var last = -1
    try {
        awaitThat("the keyboard to be hidden", timeoutMs) {
            val now = SystemClock.uptimeMillis()
            if (now - asked >= 1_000L) {
                asked = now
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    view.windowInsetsController?.hide(WindowInsets.Type.ime())
                }
            }
            val frames = sampleOnFrames { view.keyboardInset() }
            last = frames.last()
            if (frames.any { it != 0 }) {
                zeroSince = 0L
                false
            } else {
                if (zeroSince == 0L) zeroSince = now
                now - zeroSince >= stillMs
            }
        }
    } catch (timeout: AssertionError) {
        throw AssertionError("${timeout.message}; keyboard inset still $last px", timeout)
    }
    waitForIdle()
}

/**
 * Waits until the keyboard is up and its inset has held one value on two
 * frames in a row and for [stillMs] of real time, then advances the Compose
 * clock by [productSettleMs] so product code that reacts to the keyboard has
 * run (the keyboard opens on real time, Compose runs on virtual time: rule on
 * two clocks). Returns the keyboard height in px.
 */
fun ComposeTestRule.awaitKeyboardShown(
    timeoutMs: Long = 10_000L, stillMs: Long = 300L, productSettleMs: Long = 500L
): Int {
    val view = keyboardWindow()
        ?: throw AssertionError("awaitKeyboardShown(): no Compose window")
    val start = SystemClock.uptimeMillis()
    var last = Int.MIN_VALUE
    var since = start
    var everUp = false
    var asked = start
    try {
        awaitThat("the keyboard to be shown and still", timeoutMs) {
            val frames = sampleOnFrames { view.keyboardInset() }
            val now = SystemClock.uptimeMillis()
            if (!everUp && frames.last() == 0 && now - asked >= 1_000L) {
                asked = now
                onMainThread { view.windowInsetsController?.show(WindowInsets.Type.ime()) }
            }
            if (frames.distinct().size != 1 || frames.last() != last) {
                last = frames.last()
                since = now
            }
            if (last > 0) everUp = true
            else if (onMainThread { !view.hasWindowFocus() }) dismissSystemAnrDialog()
            last > 0 && now - since >= stillMs
        }
    } catch (timeout: AssertionError) {
        val seen = if (everUp) "the keyboard came up but its height kept changing (last $last px)"
            else "the keyboard never came up (requested by the window: ${view.keyboardRequested()})"
        throw AssertionError("${timeout.message}; $seen", timeout)
    }
    Log.i(TEST_KIT_LOG_TAG, "keyboard shown inset=$last px after " +
        "${SystemClock.uptimeMillis() - start} ms")
    advanceVirtualTime(productSettleMs)
    return last
}

/**
 * Fails unless the whole node, measured without clipping, ends at least
 * [clearancePx] above the keyboard. Clipped bounds cannot be used for this:
 * for a node pushed off screen they collapse onto the viewport edge and
 * would pass.
 */
fun ComposeTestRule.assertAboveKeyboard(matcher: SemanticsMatcher, clearancePx: Int = 0) {
    val node = onNode(matcher).fetchSemanticsNode("assertAboveKeyboard(): no ${matcher.description}")
    val view = keyboardWindow()
        ?: throw AssertionError("assertAboveKeyboard(): no Compose window")
    val (keyboard, windowHeight) = onMainThread { view.keyboardInset() to view.rootView.height }
    val whole = Rect(node.positionInWindow, node.size.toSize())
    val keyboardTop = windowHeight - keyboard
    if (whole.bottom + clearancePx > keyboardTop + 1f) {
        throw AssertionError("${matcher.description} is not above the keyboard: its bottom is at " +
            "${whole.bottom.toInt()} px in the window, the keyboard starts at $keyboardTop px " +
            "(keyboard $keyboard px, wanted clearance $clearancePx px)")
    }
    node.visibilityProblem()?.let { throw AssertionError("${matcher.description} $it") }
}

internal fun View.keyboardInset(): Int =
    rootWindowInsets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0

/** Whether the window currently asks for the keyboard, even if it has not arrived yet. */
internal fun View.keyboardRequested(): Boolean =
    rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true

/**
 * A keyboard that belongs to the tests instead of the emulator. Both are
 * test-only input methods inside the androidTest APK; nothing ships in ATLAS.
 * With one selected, a test no longer depends on LatinIME: its 0.3 to 2 s
 * arrival, its changing height, its own permission screen (#424).
 */
enum class TestKeyboard(internal val service: String) {
    /**
     * The default for every test: fields take focus and text, nothing slides
     * up or resizes the screen.
     */
    None("AtlasNoKeyboard"),

    /**
     * A flat panel that says "Test keyboard", for tests whose claim is about
     * layout above the keyboard. 918 px tall at 1080x2340 unless the rule
     * names another height.
     */
    Fixed("AtlasTestKeyboard")
}

/** The global setting the fixed test keyboard reads its height from, in px. */
internal const val FIXED_KEYBOARD_HEIGHT_SETTING = "atlas_test_keyboard_height_px"

/**
 * Selects [keyboard] for one test and puts the previous input method back.
 * Make it the OUTER rule, so the keyboard is chosen before the Activity starts:
 *
 *     @get:Rule val chain: RuleChain = RuleChain.outerRule(KeyboardRule(TestKeyboard.None))
 *         .around(S24DisplayRule()).around(compose)
 *
 * [fixedHeightPx] sets the height of [TestKeyboard.Fixed]; null keeps its default.
 */
class KeyboardRule(
    private val keyboard: TestKeyboard = TestKeyboard.None,
    private val fixedHeightPx: Int? = null
) : TestRule {
    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() = withTestKeyboard(keyboard, fixedHeightPx) { base.evaluate() }
        }
}

/** Runs [body] with [keyboard] selected; restores the previous input method afterwards. */
fun <T> withTestKeyboard(keyboard: TestKeyboard, fixedHeightPx: Int? = null, body: () -> T): T {
    val testPackage = InstrumentationRegistry.getInstrumentation().context.packageName
    val id = "$testPackage/nl.atlas.gallery.testkit.${keyboard.service}"
    val before = selectedInputMethod()
    if (fixedHeightPx != null) {
        testShell("settings put global $FIXED_KEYBOARD_HEIGHT_SETTING $fixedHeightPx")
    }
    try {
        val enabled = testShell("ime enable $id")
        val selected = testShell("ime set $id")
        val now = selectedInputMethod()
        check(now == id) {
            "Could not select the test keyboard $id (now '$now'). ime enable said " +
                "'$enabled', ime set said '$selected'."
        }
        Log.i(TEST_KIT_LOG_TAG, "keyboard=${keyboard.name} selected (was $before)")
        return body()
    } finally {
        if (before.isEmpty() || before == "null" || before == id) testShell("ime reset")
        else testShell("ime set $before")
        if (before != id) testShell("ime disable $id")
        if (fixedHeightPx != null) testShell("settings delete global $FIXED_KEYBOARD_HEIGHT_SETTING")
        Log.i(TEST_KIT_LOG_TAG, "keyboard restored: selected=${selectedInputMethod()} " +
            "enabled=${testShell("ime list -s").replace("\n", ",")}")
    }
}

/** The id of the input method the device has selected now. */
fun selectedInputMethod(): String = testShell("settings get secure default_input_method")
