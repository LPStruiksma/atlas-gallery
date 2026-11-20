package nl.atlas.gallery.testkit

import android.util.Log
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Sets the system night mode and font scale for one test and puts both back afterwards, also
 * when the test fails (rule 7). Make it an outer rule, around the Activity rule, so the Activity
 * starts with these values instead of being recreated mid-test:
 *
 *     @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule())
 *         .around(DisplayPrefsRule(dark = true, fontScale = 2f)).around(compose)
 */
class DisplayPrefsRule(private val dark: Boolean, private val fontScale: Float = 1f) : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val night = testShell("cmd uimode night").substringAfter(":").trim()
            val font = testShell("settings get system font_scale")
            testShell("cmd uimode night " + if (dark) "yes" else "no")
            testShell("settings put system font_scale $fontScale")
            Log.i(TEST_KIT_LOG_TAG, "night ${if (dark) "yes" else "no"}, font scale $fontScale (was $night, $font)")
            try {
                base.evaluate()
            } finally {
                if (night in setOf("yes", "no", "auto")) testShell("cmd uimode night $night")
                if (font.toFloatOrNull() == null) testShell("settings delete system font_scale")
                else testShell("settings put system font_scale $font")
            }
        }
    }
}
