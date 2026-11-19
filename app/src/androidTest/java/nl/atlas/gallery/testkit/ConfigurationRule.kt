package nl.atlas.gallery.testkit

import android.app.UiAutomation
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Device configuration a test may change mid-test, put back afterwards even
 * when the test fails (docs/testing/EMULATOR_TESTS.md, rule 7): night mode,
 * font scale, animator duration scale and rotation. Only what the test
 * actually changed is restored, to the value it had before.
 *
 * A change of night mode, font scale or rotation recreates the Activity; call
 * awaitWindowSettled() (and wait for the new Activity) after it.
 */
class ConfigurationRule : TestRule {
    private val restores = LinkedHashMap<String, () -> Unit>()

    /** `cmd uimode night yes|no`. */
    fun night(dark: Boolean) {
        remember("night") {
            val before = testShell("cmd uimode night").substringAfter(":").trim();
            { if (before in setOf("yes", "no", "auto")) testShell("cmd uimode night $before") }
        }
        testShell("cmd uimode night " + if (dark) "yes" else "no")
    }

    /** `settings put system font_scale <scale>`. */
    fun fontScale(scale: Float) {
        rememberSetting("system", "font_scale")
        testShell("settings put system font_scale $scale")
    }

    /** `settings put global animator_duration_scale <scale>`, e.g. 10 to catch a frame mid-animation. */
    fun animatorScale(scale: Float) {
        rememberSetting("global", "animator_duration_scale")
        testShell("settings put global animator_duration_scale $scale")
    }

    /** Freezes the display at 0° (portrait) or 90° (landscape). */
    fun rotate(landscape: Boolean) {
        remember("rotation") {
            {
                val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                automation.setRotation(UiAutomation.ROTATION_FREEZE_0)
                automation.setRotation(UiAutomation.ROTATION_UNFREEZE)
            }
        }
        InstrumentationRegistry.getInstrumentation().uiAutomation.setRotation(
            if (landscape) UiAutomation.ROTATION_FREEZE_90 else UiAutomation.ROTATION_FREEZE_0)
    }

    private fun rememberSetting(namespace: String, key: String) {
        remember("$namespace/$key") {
            val before = testShell("settings get $namespace $key");
            {
                if (before.isEmpty() || before == "null") testShell("settings delete $namespace $key")
                else testShell("settings put $namespace $key $before")
            }
        }
    }

    private fun remember(key: String, capture: () -> (() -> Unit)) {
        if (key !in restores) restores[key] = capture()
    }

    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                try {
                    base.evaluate()
                } finally {
                    restores.entries.reversed().forEach { (key, restore) ->
                        runCatching(restore).onFailure {
                            Log.w(TEST_KIT_LOG_TAG, "could not restore $key: $it")
                        }
                    }
                    restores.clear()
                }
            }
        }
}
