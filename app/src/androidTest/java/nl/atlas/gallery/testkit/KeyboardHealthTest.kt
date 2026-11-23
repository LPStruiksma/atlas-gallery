package nl.atlas.gallery.testkit

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Log tag of the keyboard health check's result line. */
const val KEYBOARD_HEALTH_LOG_TAG = "ATLAS_KEYBOARD_HEALTH"

/**
 * The shard script runs this once per shard, before the suite, with the
 * runner argument `atlas_keyboard_health=true`: the emulator's own keyboard
 * (no [KeyboardRule]) must open for a focused field and close again. If it
 * does not, every test that types would go red one by one; the script stops
 * the shard instead with `::error::IME unavailable on this emulator` and
 * this test's device line (docs/testing/EMULATOR_TESTS.md, "Reading a red shard").
 *
 * Inside the normal suite the argument is absent and the test is skipped.
 */
@RunWith(AndroidJUnit4::class)
class KeyboardHealthTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun theEmulatorKeyboardOpensAndCloses() {
        assumeTrue("keyboard health check runs only from the shard script",
            InstrumentationRegistry.getArguments().getString(HEALTH_ARGUMENT) == "true")
        val focus = FocusRequester()
        var text by mutableStateOf("")
        compose.setContent {
            Box(Modifier.fillMaxSize().statusBarsPadding()) {
                BasicTextField(text, { text = it },
                    Modifier.fillMaxWidth().focusRequester(focus).testTag("atlas-keyboard-health-field"))
            }
        }
        compose.waitForIdle()
        try {
            // The first open after boot starts LatinIME's process; give it
            // one more request before calling the keyboard dead.
            val height = runCatching { open(focus, firstTimeoutMs = 20_000L) }
                .recoverCatching { open(focus, firstTimeoutMs = 10_000L) }
                .getOrThrow()
            compose.hideKeyboard()
            Log.i(KEYBOARD_HEALTH_LOG_TAG, "PASS keyboard opened at $height px and closed; " +
                "input method ${selectedInputMethod()}")
        } catch (failure: AssertionError) {
            Log.e(KEYBOARD_HEALTH_LOG_TAG, "FAIL ${failure.message} | ${describeDevice()}")
            throw failure
        }
    }

    private fun open(focus: FocusRequester, firstTimeoutMs: Long): Int {
        compose.runOnUiThread { focus.requestFocus() }
        return compose.awaitKeyboardShown(timeoutMs = firstTimeoutMs)
    }

    companion object {
        /** Runner argument that turns the check on. */
        const val HEALTH_ARGUMENT = "atlas_keyboard_health"
    }
}
