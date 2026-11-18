package nl.atlas.gallery.uitest

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves, on the emulator, each fact docs/testing/EMULATOR_UI_TESTS.md builds
 * on and each helper it tells tests to use. If one of these fails, the rules
 * in that document need another look before any test is "fixed" around it.
 */
@RunWith(AndroidJUnit4::class)
class UiTestFoundationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var focus: FocusManager? = null

    /** The app draws edge to edge, so a keyboard covers content instead of resizing the window. */
    private fun drawEdgeToEdge() = compose.runOnUiThread {
        compose.activity.window.setDecorFitsSystemWindows(false)
    }

    private fun windowHeight(): Int {
        var height = 0
        compose.runOnUiThread { height = compose.activity.window.decorView.height }
        return height
    }

    // Rule 2: the fixed test keyboard opens, at one known height, every time.
    @Test fun fixedKeyboardOpensAtTheSameKnownHeightEveryTime() {
        withTestKeyboard(TestKeyboard.Fixed) {
            drawEdgeToEdge()
            var text by mutableStateOf("")
            compose.setContent {
                focus = LocalFocusManager.current
                Box(Modifier.fillMaxSize().statusBarsPadding()) {
                    BasicTextField(text, { text = it }, Modifier.fillMaxWidth().testTag("field"))
                }
            }
            val window = windowHeight()
            val heights = (1..5).map { round ->
                val started = SystemClock.uptimeMillis()
                compose.onNodeWithTag("field").performTextInput("a")
                val inset = compose.awaitKeyboardOpen()
                Log.i(UI_TEST_LOG_TAG, "fixed keyboard round $round: inset=$inset px of $window " +
                    "after ${SystemClock.uptimeMillis() - started} ms")
                compose.runOnUiThread { focus!!.clearFocus() }
                compose.awaitKeyboardClosed()
                inset
            }
            assertEquals("the keyboard has one height on every open: $heights",
                1, heights.toSet().size)
            val share = heights.first().toFloat() / window
            assertTrue("the keyboard takes about 38% of the display, measured " +
                "${heights.first()} of $window px", share in 0.36f..0.50f)
        }
    }

    // Rule 2: with TestKeyboard.None typing works and the window is never inset.
    @Test fun noKeyboardTakesTextAndNeverInsetsTheWindow() {
        withTestKeyboard(TestKeyboard.None) {
            drawEdgeToEdge()
            var text by mutableStateOf("")
            compose.setContent {
                Box(Modifier.fillMaxSize().statusBarsPadding()) {
                    BasicTextField(text, { text = it }, Modifier.fillMaxWidth().testTag("field"))
                }
            }
            compose.typeText(compose.onNodeWithTag("field"), "Synthetic Ada", TestKeyboard.None)
            var largest = 0
            val until = SystemClock.uptimeMillis() + 2_000L
            compose.waitUntil(10_000L) {
                largest = maxOf(largest, compose.keyboardInsetPx())
                SystemClock.uptimeMillis() >= until
            }
            assertEquals("no keyboard inset in 2 s after typing", 0, largest)
            compose.onNodeWithTag("field").assertTextEquals("Synthetic Ada")
        }
    }

    // Rule 1: performClick on a cut-off node does nothing and says nothing;
    // tap() scrolls to what it can reach and refuses what it cannot.
    @Test fun tapScrollsToItsTargetAndRefusesOneItCannotReach() {
        var clipped by mutableIntStateOf(0)
        var far by mutableIntStateOf(0)
        compose.setContent {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
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
        // The hazard: both raw clicks "succeed" and reach nothing.
        compose.onNodeWithTag("far").performClick()
        compose.onNodeWithTag("clipped").performClick()
        compose.waitForIdle()
        assertEquals("a raw click on a node scrolled out of view is lost", 0, far)
        assertEquals("a raw click on a clipped node is lost", 0, clipped)

        compose.onNodeWithTag("far").tap()
        compose.waitForIdle()
        assertEquals("tap() scrolls the node into view and clicks it once", 1, far)
        compose.onNodeWithTag("far").assertFullyVisible()

        try {
            compose.onNodeWithTag("clipped").tap()
            fail("tap() must refuse a node it cannot bring into view")
        } catch (refused: AssertionError) {
            assertTrue(refused.message, refused.message!!.contains("cut off"))
        }
        assertEquals(0, clipped)
    }

    // Rule 1: a node under the keyboard is not tappable either.
    @Test fun tapRefusesATargetUnderTheKeyboard() {
        withTestKeyboard(TestKeyboard.Fixed) {
            drawEdgeToEdge()
            var text by mutableStateOf("")
            var pressed by mutableIntStateOf(0)
            compose.setContent {
                Box(Modifier.fillMaxSize().statusBarsPadding()) {
                    BasicTextField(text, { text = it }, Modifier.fillMaxWidth().testTag("field"))
                    Button({ pressed++ },
                        Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                            .testTag("bottom")) { Text("Bottom") }
                }
            }
            compose.onNodeWithTag("bottom").tap()
            compose.waitForIdle()
            assertEquals("tappable while the keyboard is closed", 1, pressed)

            compose.typeText(compose.onNodeWithTag("field"), "a", TestKeyboard.Fixed)
            try {
                compose.onNodeWithTag("bottom").tap()
                fail("tap() must refuse a node the keyboard covers")
            } catch (refused: AssertionError) {
                assertTrue(refused.message, refused.message!!.contains("under the keyboard"))
            }
            assertEquals(1, pressed)
        }
    }

    // Rule 3: delay() in composition runs on the virtual clock. Real time
    // passing does not run it; advancing the test clock does.
    @Test fun productDelaysRunOnTheVirtualClockNotOnRealTime() {
        var fired by mutableStateOf(false)
        compose.setContent {
            val label = remember { "waiting" }
            LaunchedEffect(Unit) {
                delay(2_000L)
                fired = true
            }
            Text(label)
        }
        compose.waitForIdle()
        SystemClock.sleep(2_500L)
        compose.waitForIdle()
        assertFalse("2.5 s of real time must not run a 2 s delay()", fired)
        compose.advanceVirtualTime(2_000L)
        assertTrue("advancing the virtual clock runs it", fired)
    }
}
