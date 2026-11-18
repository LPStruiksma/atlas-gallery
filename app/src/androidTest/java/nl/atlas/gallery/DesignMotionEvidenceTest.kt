package nl.atlas.gallery

import android.os.ParcelFileDescriptor
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
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

/**
 * DESIGN-7 (#137) partial: gallery chrome motion on the real MainActivity over
 * invented MediaStore PNGs. The Compose clock is frozen so each transition is
 * captured at a fixed start, middle and end frame. Reduced motion is checked by
 * setting the system animator scale to 0 while the app runs; the original
 * scales are restored afterward. Sheets, press feedback and pill visibility are
 * #137 follow-ups and are not covered here.
 */
@RunWith(AndroidJUnit4::class)
class DesignMotionEvidenceTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule())
        .around(MotionScaleRule())
        .around(SyntheticHeaderFixtures(
            sizes = List(60) { 120 to 90 },
            folderOf = { "ATLAS-Motion-137" }))
        .around(compose)

    private val tile = SemanticsMatcher("visible timeline media") {
        it.config.getOrNull(SemanticsProperties.TestTag)
            ?.startsWith("atlas-timeline-media-") == true
    }

    private fun awaitPhotos(): List<String> {
        compose.waitForNode(hasTestTag("atlas-nav-photos"))
        compose.waitUntil(60_000L) { compose.onAllNodes(tile).fetchSemanticsNodes().size >= 2 }
        return compose.onAllNodes(tile).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }.distinct()
    }

    /** Start, middle and end of a transition, with the clock frozen. */
    private fun strip(name: String, durationMillis: Long, action: () -> Unit) {
        compose.mainClock.autoAdvance = false
        try {
            action()
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            saveUiEvidence("motion-137-$name-start.png")
            compose.mainClock.advanceTimeBy(durationMillis / 2)
            compose.waitForIdle()
            saveUiEvidence("motion-137-$name-mid.png")
            compose.mainClock.advanceTimeBy(durationMillis * 2)
            compose.waitForIdle()
            saveUiEvidence("motion-137-$name-end.png")
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun tabHeaderAndSelectionStripsThenReducedMotion() {
        val tags = awaitPhotos()

        // Tab emphasis: 180 ms (AtlasMotionKind.TAB).
        strip("tab", 180L) { compose.onNodeWithTag("atlas-nav-albums").performClick() }
        compose.onNodeWithTag("atlas-nav-photos").performClick()
        awaitPhotos()

        // Header subtitle collapse: 200 ms (AtlasMotionKind.HEADER). The
        // count line folds away once the grid leaves its top. Jump there with
        // the scroll-to-index semantics action, which moves the grid without
        // advancing the frozen clock, so the fold itself is what gets captured.
        compose.waitForNode(hasTestTag("atlas-header-subtitle") and
            hasText("photos & videos", substring = true))
        compose.waitUntil(60_000L) {
            compose.onNodeWithTag("atlas-timeline-grid").fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange].maxValue() > 12f
        }
        // Scroll off the top and back while the clock runs, establishing the
        // observed collapse trigger before recording the frozen-clock strip.
        compose.onNodeWithTag("atlas-timeline-grid").performScrollToIndex(12)
        compose.waitUntil(10_000L) {
            compose.onAllNodesWithTag("atlas-header-subtitle").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("atlas-timeline-grid").performScrollToIndex(0)
        compose.waitForNode(hasTestTag("atlas-header-subtitle"))
        strip("header", 200L) {
            compose.onNodeWithTag("atlas-timeline-grid").performScrollToIndex(12)
        }
        compose.waitUntil(10_000L) {
            compose.onAllNodesWithTag("atlas-header-subtitle").fetchSemanticsNodes().isEmpty()
        }

        // Selection mode swap of tab bar and action bar: 200 ms (SELECTION).
        val visible = compose.onAllNodes(tile).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }
            .firstOrNull() ?: tags.first()
        strip("selection", 200L) {
            compose.onNodeWithTag(visible).performTouchInput { longClick() }
        }
        compose.waitForNode(hasTestTag("atlas-selection-count") and hasText("1 selected"))
        compose.onNodeWithTag("atlas-nav-photos").assertDoesNotExist()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForNode(hasTestTag("atlas-nav-photos"))

        // Reduced motion, switched on live: one frame after the tap the
        // transition must already be at its end state.
        shell137("settings put global animator_duration_scale 0")
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("atlas-nav-albums").performClick()
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            saveUiEvidence("motion-137-reduced-tab.png")
            compose.onNodeWithTag("atlas-nav-photos").performClick()
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        } finally {
            compose.mainClock.autoAdvance = true
        }
        val again = awaitPhotos()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag(again.first()).performTouchInput { longClick() }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            assertTrue("reduced motion: tab bar must be gone one frame after selection starts",
                compose.onAllNodesWithTag("atlas-nav-photos").fetchSemanticsNodes().isEmpty())
            saveUiEvidence("motion-137-reduced-selection.png")
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }
}

/**
 * The CI emulator starts with animations disabled. Turn the animator and
 * transition scales on for this journey and restore their exact previous
 * values, including a missing setting, afterward.
 */
private class MotionScaleRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                val keys = listOf("animator_duration_scale", "transition_animation_scale")
                val before = keys.associateWith { shell137("settings get global $it").trim() }
                keys.forEach { shell137("settings put global $it 1") }
                try {
                    base.evaluate()
                } finally {
                    before.forEach { (key, old) ->
                        if (old == "null" || old.isBlank()) shell137("settings delete global $key")
                        else shell137("settings put global $key $old")
                    }
                }
            }
        }
}

private fun shell137(command: String): String {
    val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
        .executeShellCommand(command)
    return ParcelFileDescriptor.AutoCloseInputStream(pfd)
        .use { it.readBytes().decodeToString() }
}
