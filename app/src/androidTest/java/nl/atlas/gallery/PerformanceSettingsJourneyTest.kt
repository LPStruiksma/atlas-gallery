package nl.atlas.gallery

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.atlas.gallery.testkit.awaitThat
import nl.atlas.gallery.testkit.exists
import nl.atlas.gallery.testkit.tap
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * PERF-0: real Settings destination and rendered aggregate rows, with only
 * synthetic MediaStore fixtures and no personal photo/People information.
 */
@RunWith(AndroidJUnit4::class)
class PerformanceSettingsJourneyTest {
    private val fixtures = SyntheticHeaderFixtures(sizes = listOf(120 to 90))
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule())
        .around(fixtures).around(compose)

    @Test fun performanceSummaryAndInventoryRenderWithMissingSamples() {
        compose.waitForNode(hasTestTag("atlas-header-more"))
        compose.tap(hasTestTag("atlas-header-more"))
        // The overflow menu is a separate window.
        compose.awaitThat("the overflow menu open") {
            compose.exists(hasTestTag("atlas-library-status"))
        }
        compose.tap(hasTestTag("atlas-library-status"))
        compose.waitForNode(hasTestTag("atlas-settings-screen"))
        compose.onNodeWithTag("atlas-settings-advanced").performClick()
        compose.waitForNode(hasTestTag("atlas-settings-performance"))
        compose.onNodeWithTag("atlas-settings-performance").performScrollTo().performClick()

        compose.waitForNode(hasTestTag("atlas-perf-cold_start_ms"))
        compose.onNodeWithTag("atlas-perf-cold_start_ms").performScrollTo()
            .assertIsDisplayed()
        saveUiEvidence("perf-0-settings-top.png")

        compose.onNodeWithTag("atlas-perf-grid-jank").performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag("atlas-perf-action_a11_ms").performScrollTo()
            .assertIsDisplayed()
        saveUiEvidence("perf-0-settings-actions.png")
    }
}
