package nl.atlas.gallery

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import kotlin.math.pow

/**
 * #22: real MainActivity, real four-tab semantics, S24+ light and dark
 * screenshots over 40 synthetic colour tiles or an empty/light surface.
 * API 29-30 runs the same journey using the transparent (unblurred) fallback.
 * No personal media or photo-library network access.
 */
@RunWith(AndroidJUnit4::class)
class FloatingTabBackdropLightJourneyTest : FloatingTabBackdropJourney(dark = false)

@RunWith(AndroidJUnit4::class)
class FloatingTabBackdropDarkJourneyTest : FloatingTabBackdropJourney(dark = true)

abstract class FloatingTabBackdropJourney(private val dark: Boolean) {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val fixtures = SyntheticHeaderFixtures(
        sizes = List(40) { 120 to 90 },
        folderOf = { "ATLAS-Backdrop-22" }
    )

    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule())
        .around(BackdropNightModeRule(dark)).around(fixtures).around(compose)

    private val mode = if (dark) "dark" else "light"

    @Test fun bottomPillKeepsCrispTabsOverColourTilesAndQuietSurfaces() {
        compose.waitForNode(hasTestTag("atlas-nav-pill"))
        compose.waitForNode(hasTestTag("atlas-timeline-grid") or
            hasTestTag("atlas-feature-grid"))
        compose.onNodeWithTag("atlas-nav-photos").assertIsSelected()
        compose.onNodeWithTag("atlas-nav-albums").assertIsNotSelected()
        compose.waitForIdle()
        saveUiEvidence("pill-22-photos-$mode.png")

        compose.onNodeWithTag("atlas-nav-albums").performClick()
        compose.waitForNode(hasTestTag("atlas-album-grid"))
        compose.onNodeWithTag("atlas-nav-albums").assertIsSelected()
        compose.waitForIdle()
        saveUiEvidence("pill-22-albums-$mode.png")

        compose.onNodeWithTag("atlas-nav-search").performClick()
        compose.waitForNode(hasTestTag("atlas-search-field"))
        compose.onNodeWithTag("atlas-nav-search").assertIsSelected()
        compose.onNodeWithTag("atlas-nav-people").assertIsNotSelected()
        compose.waitForIdle()
        saveUiEvidence("pill-22-search-$mode.png")
    }

    /**
     * Worst-case compositing over pure white and pure black media. The icon
     * and 11sp label must retain at least WCAG 4.5:1 after the translucent
     * surface; the selected tab's contrast must be higher.
     */
    @Test fun inactiveTabInkMeetsTextContrastAgainstBothBackdropExtremes() {
        val pill = if (dark) floatArrayOf(38f, 43f, 48f)
            else floatArrayOf(255f, 255f, 255f)
        val ink = if (dark) floatArrayOf(247f, 250f, 248f)
            else floatArrayOf(17f, 23f, 27f)
        val opacity = if (dark) .80f else .90f
        for (background in listOf(0f, 255f)) {
            val base = FloatArray(3) { i -> opacity * pill[i] + (1f - opacity) * background }
            val inactive = FloatArray(3) { i -> .78f * ink[i] + .22f * base[i] }
            val active = ink
            val inactiveRatio = contrast(base, inactive)
            assertTrue("$mode pill over $background: inactive text contrast $inactiveRatio",
                inactiveRatio >= 4.5)
            assertTrue("$mode active tab must read more strongly",
                contrast(base, active) > inactiveRatio)
        }
    }

    private fun contrast(a: FloatArray, b: FloatArray): Double {
        fun luminance(rgb: FloatArray): Double {
            val linear = rgb.map { value ->
                val c = (value / 255f).toDouble()
                if (c <= .04045) c / 12.92 else ((c + .055) / 1.055).pow(2.4)
            }
            return .2126 * linear[0] + .7152 * linear[1] + .0722 * linear[2]
        }
        val lhs = luminance(a)
        val rhs = luminance(b)
        return (maxOf(lhs, rhs) + .05) / (minOf(lhs, rhs) + .05)
    }
}

/** Test-only system setting; restored even after assertion or emulator failure. */
private class BackdropNightModeRule(private val dark: Boolean) : TestRule {
    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                val before = shell("cmd uimode night").substringAfter(":").trim()
                shell("cmd uimode night " + if (dark) "yes" else "no")
                try {
                    base.evaluate()
                } finally {
                    if (before in setOf("yes", "no", "auto")) {
                        shell("cmd uimode night $before")
                    }
                }
            }
        }

    private fun shell(command: String): String {
        val parcel = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(parcel)
            .use { it.readBytes().decodeToString() }
    }
}
