package nl.atlas.gallery.uitest

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Which keyboard a test runs with (docs/testing/EMULATOR_UI_TESTS.md, rule 2).
 * Both are test-only input methods from the androidTest APK; the emulator's
 * own keyboard (LatinIME) is never the subject of an ATLAS test.
 */
enum class TestKeyboard(internal val service: String) {
    /** Text fields take focus and text; nothing slides up or resizes the screen. */
    None("AtlasNoKeyboard"),

    /** A flat panel, always 38% of the display tall. For tests about keyboard insets. */
    Fixed("AtlasTestKeyboard")
}

internal const val UI_TEST_LOG_TAG = "ATLAS_UITEST"

/**
 * Selects [keyboard] for one test and puts the previous input method back.
 * Make it the OUTER rule, so the keyboard is chosen before the Activity starts:
 *
 *     @get:Rule val chain: RuleChain = RuleChain.outerRule(KeyboardRule(TestKeyboard.None))
 *         .around(S24DisplayRule()).around(compose)
 */
class KeyboardRule(private val keyboard: TestKeyboard) : TestRule {
    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() = withTestKeyboard(keyboard) { base.evaluate() }
        }
}

/** Runs [body] with [keyboard] selected; restores the previous input method afterwards. */
fun <T> withTestKeyboard(keyboard: TestKeyboard, body: () -> T): T {
    val testPackage = InstrumentationRegistry.getInstrumentation().context.packageName
    val id = "$testPackage/nl.atlas.gallery.uitest.${keyboard.service}"
    val before = uiTestShell("settings get secure default_input_method")
    val enabled = uiTestShell("ime enable $id")
    val selected = uiTestShell("ime set $id")
    val now = uiTestShell("settings get secure default_input_method")
    check(now == id) {
        "Could not select the test keyboard $id (now '$now'). ime enable said '$enabled', " +
            "ime set said '$selected'."
    }
    Log.i(UI_TEST_LOG_TAG, "keyboard=${keyboard.name} selected (was $before)")
    try {
        return body()
    } finally {
        if (before.isEmpty() || before == "null" || before == id) uiTestShell("ime reset")
        else uiTestShell("ime set $before")
        if (before != id) uiTestShell("ime disable $id")
    }
}

/** The keyboard's bottom inset in px, as the window hosting this rule's content sees it. */
fun ComposeTestRule.keyboardInsetPx(): Int {
    val root = onAllNodes(isRoot()).fetchSemanticsNodes().firstOrNull()?.root
    val view = (root as? ViewRootForTest)?.view ?: return 0
    return view.keyboardInsetAndWindowHeight().first
}

/**
 * Waits until the keyboard is open and its inset has held one value for
 * [stillMillis] of real time, then advances the virtual clock by
 * [productSettleMillis] so that product code that reacts to the keyboard
 * (a delayed bring-into-view, for example) has run. Returns the inset in px.
 *
 * The keyboard opens on real time and Compose runs on virtual time (rule 3):
 * neither wait alone is enough.
 */
fun ComposeTestRule.awaitKeyboardOpen(
    timeoutMillis: Long = 10_000L,
    stillMillis: Long = 300L,
    productSettleMillis: Long = 500L
): Int {
    val inset = awaitKeyboardInset("open", timeoutMillis, stillMillis) { it > 0 }
    advanceVirtualTime(productSettleMillis)
    return inset
}

/** Waits until the keyboard inset has been zero for [stillMillis] of real time. */
fun ComposeTestRule.awaitKeyboardClosed(
    timeoutMillis: Long = 10_000L,
    stillMillis: Long = 300L,
    productSettleMillis: Long = 500L
) {
    awaitKeyboardInset("closed", timeoutMillis, stillMillis) { it == 0 }
    advanceVirtualTime(productSettleMillis)
}

/**
 * Types [text] the way tests do (semantics, not key presses) and then waits
 * for the keyboard state that [keyboard] implies: open and settled for
 * [TestKeyboard.Fixed], still closed for [TestKeyboard.None].
 */
fun ComposeTestRule.typeText(
    field: SemanticsNodeInteraction, text: String, keyboard: TestKeyboard
) {
    field.performTextInput(text)
    when (keyboard) {
        TestKeyboard.Fixed -> awaitKeyboardOpen()
        TestKeyboard.None -> waitForIdle()
    }
}

private fun ComposeTestRule.awaitKeyboardInset(
    what: String, timeoutMillis: Long, stillMillis: Long, wanted: (Int) -> Boolean
): Int {
    val start = SystemClock.uptimeMillis()
    var last = Int.MIN_VALUE
    var since = start
    try {
        waitUntil(timeoutMillis) {
            val inset = keyboardInsetPx()
            val now = SystemClock.uptimeMillis()
            if (inset != last) { last = inset; since = now }
            wanted(inset) && now - since >= stillMillis
        }
    } catch (timeout: ComposeTimeoutException) {
        throw AssertionError("The keyboard was not $what and still within " +
            "${timeoutMillis / 1000} s: its inset is $last px. Input method: " +
            uiTestShell("settings get secure default_input_method"), timeout)
    }
    Log.i(UI_TEST_LOG_TAG,
        "keyboard $what inset=$last px after ${SystemClock.uptimeMillis() - start} ms")
    return last
}

/** Runs a shell command as the shell user and returns its trimmed output. */
internal fun uiTestShell(command: String): String {
    val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
        .executeShellCommand(command)
    // Reading to the end waits for the command to finish.
    return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
        .use { it.readBytes().decodeToString() }.trim()
}
