package nl.atlas.gallery

import android.os.ParcelFileDescriptor
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * #24: real MainActivity, paired system-theme captures over disposable synthetic
 * media. Do not infer a captured row from a test name: the aggregate verifier
 * requires actual PNG bytes at the owner's 1080x2340 resolution.
 */
@RunWith(AndroidJUnit4::class)
class ParityAdditionalLightJourneyTest : ParityAdditionalThemeJourney(false)

@RunWith(AndroidJUnit4::class)
class ParityAdditionalDarkJourneyTest : ParityAdditionalThemeJourney(true)

abstract class ParityAdditionalThemeJourney(private val dark: Boolean) {
    private val mode = if (dark) "dark" else "light"
    private val fixtures = SyntheticHeaderFixtures(
        sizes = List(120) { 120 to 90 },
        folderOf = { step -> "ATLAS-Parity-Scroll-" + ((step - 1) / 5 + 1) })
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule())
        .around(ParityNightModeRule(dark)).around(fixtures).around(compose)

    @Test fun photosHeaderAwayPaired() {
        compose.waitForNode(hasText("photos & videos", substring = true))
        compose.waitForNode(hasTestTag("atlas-timeline-grid"))
        compose.waitUntil(60_000L) {
            compose.onNodeWithTag("atlas-timeline-grid").fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange].maxValue() > 12f
        }
        val grid = compose.onNodeWithTag("atlas-timeline-grid")
        repeat(3) { grid.performTouchInput { swipeUp() }; compose.waitForIdle() }
        compose.waitUntil(10_000L) {
            compose.onAllNodesWithTag("atlas-header-subtitle").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("atlas-photos-sort").assertExists()
        compose.onNodeWithTag("atlas-header-more").assertExists()
        compose.waitForIdle()
        saveUiEvidence("parity-24-header-away-$mode.png")
    }

    @Test fun photosCompactPinchPaired() {
        compose.waitForNode(hasText("photos & videos", substring = true))
        compose.waitForNode(hasTestTag("atlas-timeline-grid"))
        val grid = compose.onNodeWithTag("atlas-timeline-grid")
        compose.waitUntil(60_000L) {
            grid.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
                .maxValue() > 12f
        }
        grid.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "4 columns"))
        grid.performTouchInput {
            pinch(
                start0 = center - Offset(150f, 0f),
                end0 = center - Offset(30f, 0f),
                start1 = center + Offset(150f, 0f),
                end1 = center + Offset(30f, 0f),
                durationMillis = 400L
            )
        }
        compose.waitForIdle()
        grid.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "5 columns"))
        saveUiEvidence("parity-24-pinch-compact-$mode.png")
    }
}

/** Restore the original emulator system night mode even when assertions fail. */
private class ParityNightModeRule(private val dark: Boolean) : TestRule {
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
