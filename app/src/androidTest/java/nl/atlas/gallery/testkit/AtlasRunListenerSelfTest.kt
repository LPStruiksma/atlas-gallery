package nl.atlas.gallery.testkit

import android.os.SystemClock
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Proof run for [AtlasRunListener], off by default. With the runner argument
 * `atlas_listener_selftest=true` (the opt-in stress run passes it):
 *
 * 1. [a_leavesTheKeyboardUp] ends with its own Activity alive and the
 *    emulator keyboard up, which no kit rule would allow.
 * 2. [b_startsWithTheKeyboardHidden] must start with keyboard inset 0, and the
 *    log must hold one `ATLAS_BASELINE drift … left by …a_leavesTheKeyboardUp` line.
 * 3. [c_failsOnPurpose] fails, and the log must hold its `ATLAS_DIAG` line.
 *
 * Without the argument all three are skipped, so the normal suite stays green.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class AtlasRunListenerSelfTest {
    @Before fun onlyInTheProofRun() {
        assumeTrue("listener self-test runs only with $SELF_TEST_ARGUMENT=true",
            InstrumentationRegistry.getArguments().getString(SELF_TEST_ARGUMENT) == "true")
    }

    @Test fun a_leavesTheKeyboardUp() {
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        leaked = scenario
        scenario.onActivity { activity ->
            activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            val field = EditText(activity)
            activity.setContentView(field)
            field.requestFocus()
        }
        // Ask again once a second until it is up: the window attaches and
        // takes focus a moment after setContentView (rule on two clocks).
        var asked = 0L
        val until = SystemClock.uptimeMillis() + 20_000L
        while (!DeviceBaseline.keyboardShown() && SystemClock.uptimeMillis() < until) {
            if (SystemClock.uptimeMillis() - asked >= 1_000L) {
                asked = SystemClock.uptimeMillis()
                scenario.onActivity { activity ->
                    val field = activity.window.decorView.findFocus()
                    if (field != null) {
                        activity.getSystemService(InputMethodManager::class.java)
                            .showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
                    }
                }
            }
            SystemClock.sleep(100L)
        }
        assertTrue("the keyboard never came up: ${describeDevice()}",
            DeviceBaseline.keyboardShown())
        // Ends here on purpose: Activity open, keyboard up.
    }

    @Test fun b_startsWithTheKeyboardHidden() {
        try {
            assertFalse("the listener should have hidden the leaked keyboard: ${describeDevice()}",
                DeviceBaseline.keyboardShown())
            assertEquals(0, DeviceBaseline.keyboardWindowPx())
        } finally {
            leaked?.close()
            leaked = null
        }
    }

    @Test fun c_failsOnPurpose() {
        fail("deliberate failure: the log must show one ATLAS_DIAG line for this test")
    }

    companion object {
        /** Runner argument that turns the proof run on. */
        const val SELF_TEST_ARGUMENT = "atlas_listener_selftest"
        private var leaked: ActivityScenario<ComponentActivity>? = null
    }
}
