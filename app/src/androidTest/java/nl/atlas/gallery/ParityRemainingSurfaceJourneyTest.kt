package nl.atlas.gallery

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * #24: isolated, disposable synthetic MainActivity light/dark screenshots
 * for parity states not covered by the existing theme journeys.
 */
@RunWith(AndroidJUnit4::class)
class ParityRemainingSurfaceLightJourneyTest : ParityRemainingSurfaceJourney(false)

@RunWith(AndroidJUnit4::class)
class ParityRemainingSurfaceDarkJourneyTest : ParityRemainingSurfaceJourney(true)

abstract class ParityRemainingSurfaceJourney(private val dark: Boolean) {
    private val mode = if (dark) "dark" else "light"
    private val fixtures = SyntheticHeaderFixtures(
        sizes = listOf(90 to 160, 160 to 90, 240 to 80, 100 to 100, 90 to 120, 160 to 100),
        beforeLaunch = ::resetRemainingDensity)
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule())
        .around(RemainingNightModeRule(dark)).around(fixtures).around(compose)

    @After fun restoreDensity() =
        resetRemainingDensity(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test fun photosLargeZoomPaired() {
        compose.waitForNode(hasText("photos & videos", substring = true))
        // #375: the header text can render before the tagged grid is composed.
        compose.waitForNode(hasTestTag("atlas-timeline-grid"))
        val grid = compose.onNodeWithTag("atlas-timeline-grid")
        grid.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "4 columns"))
        grid.performTouchInput {
            pinch(
                start0 = center - Offset(75f, 0f),
                end0 = center - Offset(112.5f, 0f),
                start1 = center + Offset(75f, 0f),
                end1 = center + Offset(112.5f, 0f),
                durationMillis = 400L
            )
        }
        compose.waitUntil(30_000L) {
            compose.onAllNodesWithTag("atlas-timeline-grid").fetchSemanticsNodes().any {
                it.config.getOrNull(SemanticsProperties.StateDescription) == "3 columns"
            }
        }
        grid.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "3 columns"))
        compose.waitForIdle()
        saveUiEvidence("parity-24-pinch-large-$mode.png")
    }

    @Test fun photosOriginalRatioPaired() {
        compose.waitForNode(hasText("photos & videos", substring = true))
        // #375: the header text can render before the tagged grid is composed.
        compose.waitForNode(hasTestTag("atlas-timeline-grid"))
        val grid = compose.onNodeWithTag("atlas-timeline-grid")
        grid.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "4 columns"))
        grid.performTouchInput {
            val reach = width * 0.4f
            pinch(
                start0 = center - Offset(40f, 0f),
                end0 = center - Offset(reach, 0f),
                start1 = center + Offset(40f, 0f),
                end1 = center + Offset(reach, 0f),
                durationMillis = 400L
            )
        }
        compose.waitForNode(hasTestTag("atlas-ratio-gallery"))
        compose.onNodeWithTag("atlas-ratio-gallery").assertExists()
        compose.waitForIdle()
        saveUiEvidence("parity-24-original-ratio-$mode.png")
    }
}

private class RemainingNightModeRule(private val dark: Boolean) : TestRule {
    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                val before = shell("cmd uimode night").substringAfter(":").trim()
                shell("cmd uimode night " + if (dark) "yes" else "no")
                try {
                    base.evaluate()
                } finally {
                    if (before in setOf("yes", "no", "auto")) shell("cmd uimode night $before")
                }
            }
        }

    private fun shell(command: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd)
            .use { it.readBytes().decodeToString() }
    }
}

private fun resetRemainingDensity(context: Context) {
    check(context.getSharedPreferences("atlas_featuregrid_density", Context.MODE_PRIVATE)
        .edit().clear().commit()) { "Cannot reset grid density for parity" }
}
