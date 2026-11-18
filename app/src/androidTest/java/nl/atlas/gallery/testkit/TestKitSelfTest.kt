package nl.atlas.gallery.testkit

import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves, on the emulator, each helper of the test kit and each fact that
 * docs/testing/EMULATOR_TESTS.md builds on. If one of these fails, the rules
 * need another look before any test is "fixed" around it.
 *
 * Every test starts and ends with the keyboard hidden and changes no setting
 * that it does not put back.
 */
@RunWith(AndroidJUnit4::class)
class TestKitSelfTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun activityKeyboardInset(): Int =
        onMainThread { compose.activity.window.decorView.keyboardInset() }

    @Before fun startsWithTheKeyboardHidden() {
        assertEquals("the previous test left the keyboard up", 0, activityKeyboardInset())
    }

    @After fun leavesTheKeyboardHidden() {
        compose.hideKeyboard()
        assertEquals(0, activityKeyboardInset())
    }

    /** The app draws edge to edge, so a keyboard covers content instead of resizing the window. */
    private fun drawEdgeToEdge() = compose.runOnUiThread {
        compose.activity.window.setDecorFitsSystemWindows(false)
    }

    private fun expectFailure(containing: String, block: () -> Unit) {
        try {
            block()
        } catch (expected: AssertionError) {
            assertTrue("message should contain '$containing': ${expected.message}",
                expected.message.orEmpty().contains(containing))
            return
        }
        fail("expected a failure containing '$containing'")
    }

    private fun fieldAndBottomButton(onPress: () -> Unit) {
        drawEdgeToEdge()
        var text by mutableStateOf("")
        compose.setContent {
            Box(Modifier.fillMaxSize().statusBarsPadding()) {
                BasicTextField(text, { text = it }, Modifier.fillMaxWidth().testTag("field"))
                Button(onPress, Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                    .testTag("bottom")) { Text("Bottom") }
            }
        }
    }

    // Rule 3. The fact first: a raw click on a cut-off node does nothing and
    // says nothing. tap() scrolls to what it can reach, fires once, and
    // refuses what it cannot reach, with the bounds in the message.
    @Test fun tapFiresOnceScrollsToItsTargetAndRefusesAClippedOne() {
        var clipped by mutableIntStateOf(0)
        var far by mutableIntStateOf(0)
        var plain by mutableIntStateOf(0)
        compose.setContent {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Button({ plain++ }, Modifier.testTag("plain")) { Text("Plain") }
                Button({}, Modifier.testTag("disabled"), enabled = false) { Text("Disabled") }
                Box(Modifier.fillMaxWidth().height(80.dp).clipToBounds()) {
                    Button({ clipped++ }, Modifier.offset(y = 240.dp).testTag("clipped")) {
                        Text("Clipped")
                    }
                }
                Column(Modifier.fillMaxWidth().height(240.dp)
                    .verticalScroll(rememberScrollState())) {
                    Spacer(Modifier.height(1_200.dp))
                    Button({ far++ }, Modifier.testTag("far")) { Text("Far") }
                    Spacer(Modifier.height(40.dp))
                }
            }
        }
        compose.onNodeWithTag("far").performClick()
        compose.onNodeWithTag("clipped").performClick()
        compose.waitForIdle()
        assertEquals("a raw click on a node scrolled out of view is lost", 0, far)
        assertEquals("a raw click on a clipped node is lost", 0, clipped)

        compose.tap(hasTestTag("plain"))
        compose.waitForIdle()
        assertEquals("tap() on a visible button fires once", 1, plain)

        compose.tap(hasTestTag("far"))
        compose.waitForIdle()
        assertEquals("tap() scrolls the node into view and clicks it once", 1, far)
        compose.onNodeWithTag("far").assertFullyVisible()

        expectFailure("laid out at") { compose.tap(hasTestTag("clipped")) }
        assertEquals(0, clipped)
        expectFailure("is disabled") { compose.tap(hasTestTag("disabled")) }
    }

    // Rule 4: a tap can name its effect; no effect is a failure that says
    // where the pointer went. The tap is a real pointer: a scrim on top of
    // the button takes it, which a click action would not notice.
    @Test fun tapWithAnEffectFailsWhenNothingHappens() {
        var pressed by mutableIntStateOf(0)
        var covered by mutableStateOf(false)
        compose.setContent {
            Box(Modifier.fillMaxSize().statusBarsPadding()) {
                Button({ pressed++ }, Modifier.testTag("button")) { Text("Button") }
                if (covered) {
                    Box(Modifier.fillMaxSize().testTag("scrim")
                        .pointerInput(Unit) { detectTapGestures { } })
                }
            }
        }
        compose.tap(hasTestTag("button")) { pressed == 1 }
        covered = true
        compose.waitForIdle()
        expectFailure("had no effect") {
            compose.tap(hasTestTag("button"), timeoutMs = 1_000L) { pressed == 2 }
        }
        assertEquals("the scrim took the second tap", 1, pressed)
    }

    // Rule 3: a node that moves while the pointer is down is a failed tap,
    // not a silent miss. A product timer that fires inside the click's own
    // frame is the case the stillness check before the tap cannot see.
    @Test fun tapFailsWhenTheNodeMovesWhileThePointerIsDown() {
        var moved by mutableStateOf(false)
        compose.setContent {
            LaunchedEffect(Unit) {
                delay(8L)
                moved = true
            }
            Box(Modifier.fillMaxSize().statusBarsPadding()) {
                Button({}, Modifier.offset(y = if (moved) 300.dp else 0.dp).testTag("button")) {
                    Text("Button")
                }
            }
        }
        expectFailure("moved while the pointer was down") { compose.tap(hasTestTag("button")) }
    }

    // Rule 2 on the emulator's own keyboard: typing leaves it hidden by
    // default; when asked for, it comes up, has a height, and a node under it
    // is neither "above the keyboard" nor tappable.
    @Test fun deviceKeyboardIsHiddenAfterTypingAndShownOnRequest() {
        var pressed by mutableIntStateOf(0)
        fieldAndBottomButton { pressed++ }
        compose.typeText(hasTestTag("field"), "Synthetic Ada")
        assertEquals("typeText leaves the keyboard hidden", 0, compose.keyboardInsetPx())
        compose.onNodeWithTag("field").assertTextEquals("Synthetic Ada")
        compose.assertAboveKeyboard(hasTestTag("bottom"))
        compose.tap(hasTestTag("bottom"))
        compose.waitForIdle()
        assertEquals(1, pressed)

        val started = SystemClock.uptimeMillis()
        compose.typeText(hasTestTag("field"), "!", KeyboardAfter.Shown)
        val height = compose.keyboardInsetPx()
        Log.i(TEST_KIT_LOG_TAG, "device keyboard: $height px after " +
            "${SystemClock.uptimeMillis() - started} ms")
        assertTrue("the keyboard has a height", height > 0)
        compose.assertAboveKeyboard(hasTestTag("field"))
        expectFailure("not above the keyboard") { compose.assertAboveKeyboard(hasTestTag("bottom")) }
        expectFailure("under the keyboard") { compose.tap(hasTestTag("bottom")) }
        assertEquals(1, pressed)

        compose.hideKeyboard()
        assertEquals(0, compose.keyboardInsetPx())
        compose.awaitWindowSettled()
    }

    // The fixed test keyboard opens at one known height, every time: 918 px
    // of a 2340 px display by default, or the height the test names. The
    // device's own input method is selected again afterwards.
    @Test fun fixedKeyboardOpensAtTheSameKnownHeightEveryTime() {
        val deviceKeyboard = selectedInputMethod()
        Log.i(TEST_KIT_LOG_TAG, "before: selected=$deviceKeyboard " +
            "enabled=${testShell("ime list -s").replace("\n", ",")}")
        fieldAndBottomButton { }
        val window = onMainThread { compose.activity.window.decorView.height }
        withTestKeyboard(TestKeyboard.Fixed) {
            val heights = (1..5).map { round ->
                val started = SystemClock.uptimeMillis()
                compose.typeText(hasTestTag("field"), "a", KeyboardAfter.Shown)
                val inset = compose.keyboardInsetPx()
                Log.i(TEST_KIT_LOG_TAG, "fixed keyboard round $round: inset=$inset px of " +
                    "$window after ${SystemClock.uptimeMillis() - started} ms")
                expectFailure("under the keyboard") { compose.tap(hasTestTag("bottom")) }
                compose.hideKeyboard()
                inset
            }
            assertEquals("the keyboard has one height on every open: $heights",
                1, heights.toSet().size)
            assertEquals("918 px of a 2340 px display by default",
                Math.round(window * 918f / 2340f), heights.first())
        }
        withTestKeyboard(TestKeyboard.Fixed, fixedHeightPx = 600) {
            compose.typeText(hasTestTag("field"), "b", KeyboardAfter.Shown)
            assertEquals("the height the test named", 600, compose.keyboardInsetPx())
            compose.hideKeyboard()
        }
        assertEquals("the device's own input method is selected again",
            deviceKeyboard, selectedInputMethod())
        assertFalse("the test keyboards are disabled again",
            testShell("ime list -s").contains("nl.atlas.gallery.testkit"))
    }

    // With TestKeyboard.None typing works and the window is never inset.
    @Test fun noKeyboardTakesTextAndNeverInsetsTheWindow() {
        fieldAndBottomButton { }
        withTestKeyboard(TestKeyboard.None) {
            compose.onNodeWithTag("field").performClick()
            compose.typeText(hasTestTag("field"), "Synthetic Ada")
            compose.onNodeWithTag("field").performClick()
            var largest = 0
            val until = SystemClock.uptimeMillis() + 2_000L
            compose.awaitThat("2 s without a keyboard") {
                largest = maxOf(largest, compose.keyboardInsetPx())
                SystemClock.uptimeMillis() >= until
            }
            assertEquals("no keyboard inset in 2 s after focusing the field", 0, largest)
            compose.onNodeWithTag("field").assertTextEquals("Synthetic Ada")
        }
    }

    // Rule 1: a wait that times out names its outcome, the screen and the device.
    @Test fun awaitThatReportsWhatItWaitedForAndTheDevice() {
        compose.setContent { Text("Synthetic screen", Modifier.testTag("screen")) }
        expectFailure("waiting for: something that never happens") {
            compose.awaitThat("something that never happens", timeoutMs = 500L) { false }
        }
        expectFailure("animator scale") {
            compose.awaitThat("the device line", timeoutMs = 500L) { false }
        }
        compose.awaitWindowSettled()
    }

    // Two clocks: delay() in composition runs on the Compose test clock. Real
    // time passing does not run it; advancing the test clock does.
    @Test fun productDelaysRunOnTheVirtualClockNotOnRealTime() {
        var fired by mutableStateOf(false)
        compose.setContent {
            LaunchedEffect(Unit) {
                delay(2_000L)
                fired = true
            }
            Text("waiting")
        }
        compose.waitForIdle()
        SystemClock.sleep(2_500L)
        compose.waitForIdle()
        assertFalse("2.5 s of real time must not run a 2 s delay()", fired)
        compose.advanceVirtualTime(2_000L)
        assertTrue("advancing the virtual clock runs it", fired)
    }
}
