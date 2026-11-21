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
 * 1. [a_leavesTheKeyboardUpAndADisplayOverride] ends with the emulator
 *    keyboard up and a `wm size` override in place, which no kit rule would allow.
 * 2. [b_startsWithTheBaseline] must start with the keyboard hidden and the
 *    display back at the baseline, and the log must hold one
 *    `ATLAS_BASELINE drift … left by …a_leavesTheKeyboardUpAndADisplayOverride` line.
 *
 * The runner's own ActivityFinisher closes every Activity after each test,
 * and its keyboard goes with it: in proof runs 44478342596 and 44480696103 the
 * keyboard [a_leavesTheKeyboardUpAndADisplayOverride] left up was already gone
 * when the next test started, on both shards. The display override is the
 * leak that survives, as a forgotten `wm size reset` does in a real test.
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

    @Test fun a_leavesTheKeyboardUpAndADisplayOverride() {
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
        testShell("wm size $LEAKED_SIZE")
        val leakedUntil = SystemClock.uptimeMillis() + 5_000L
        while (DeviceBaseline.display()?.height != 2340 && SystemClock.uptimeMillis() < leakedUntil) {
            SystemClock.sleep(100L)
        }
        assertEquals("the display override did not apply", 2340, DeviceBaseline.display()?.height)
        // Ends here on purpose: keyboard up, display overridden.
    }

    @Test fun b_startsWithTheBaseline() {
        try {
            assertFalse("the leaked keyboard should be gone: ${describeDevice()}",
                DeviceBaseline.keyboardShown())
            assertTrue("the listener should have reset the display: ${describeDevice()}",
                testShell("wm size").lines().none { it.trim().startsWith("Override size:") })
            assertFalse("the display should be back at the baseline", DeviceBaseline.display()?.height == 2340)
        } finally {
            leaked?.close()
            leaked = null
            if (testShell("wm size").contains("Override size:")) testShell("wm size reset")
        }
    }

    @Test fun c_failsOnPurpose() {
        fail("deliberate failure: the log must show one ATLAS_DIAG line for this test")
    }

    companion object {
        /** Runner argument that turns the proof run on. */
        const val SELF_TEST_ARGUMENT = "atlas_listener_selftest"
        private const val LEAKED_SIZE = "1080x2340"
        private var leaked: ActivityScenario<ComponentActivity>? = null
    }
}
