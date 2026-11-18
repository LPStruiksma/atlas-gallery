package nl.atlas.gallery

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import org.junit.Assert.assertTrue
import nl.atlas.gallery.testkit.saveClockedEvidence
import nl.atlas.gallery.testkit.MotionScaleRule
import nl.atlas.gallery.testkit.setAnimatorScale
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

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

    /** Capture the resting start before the event; clock time then measures motion. */
    private fun strip(
        name: String,
        durationMillis: Long,
        action: () -> Unit,
        atMid: () -> Unit = {}
    ) {
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            // A post-action "start" can already be the destination state when
            // the gesture settles asynchronously. Capture the actual source.
            compose.saveClockedEvidence("motion-137-$name-start.png").recycle()
            action()
            // First frame applies the state change and starts animations.
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(durationMillis / 2)
            compose.waitForIdle()
            atMid()
            compose.saveClockedEvidence("motion-137-$name-mid.png").recycle()
            compose.mainClock.advanceTimeBy(durationMillis * 2)
            compose.waitForIdle()
            compose.saveClockedEvidence("motion-137-$name-end.png").recycle()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun tabHeaderAndSelectionStripsThenReducedMotion() {
        val tags = awaitPhotos()

        // Load Albums once with the live clock, so the subsequent transition
        // strip demonstrates the tab pill instead of the album-loading state.
        compose.onNodeWithTag("atlas-nav-albums").performClick()
        compose.waitUntil(60_000L) {
            compose.onAllNodesWithTag("atlas-album-card").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("atlas-nav-photos").performClick()
        awaitPhotos()
        // Tab emphasis: 180 ms (AtlasMotionKind.TAB). The start is Photos,
        // mid is a moving pill, end is the already loaded Albums route.
        strip("tab", 180L, {
            compose.onNodeWithTag("atlas-nav-albums").performClick()
        })
        compose.waitUntil(60_000L) {
            compose.onAllNodesWithTag("atlas-album-card").fetchSemanticsNodes().isNotEmpty()
        }
        val settledTab = compose.saveClockedEvidence("motion-137-tab-end.png")
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
        val expandedHeight = compose.onNodeWithTag("atlas-header-motion")
            .getUnclippedBoundsInRoot().height
        strip("header", 200L, {
            compose.onNodeWithTag("atlas-timeline-grid").performScrollToIndex(12)
        }, atMid = {
            val halfwayHeight = compose.onNodeWithTag("atlas-header-motion")
                .getUnclippedBoundsInRoot().height
            assertTrue(
                "header at 100 ms must be partially folded: start=$expandedHeight mid=$halfwayHeight",
                halfwayHeight > 0.dp && halfwayHeight < expandedHeight
            )
        })
        compose.waitUntil(10_000L) {
            compose.onAllNodesWithTag("atlas-header-subtitle").fetchSemanticsNodes().isEmpty()
        }
        // Selection evidence must include a visible checked tile, not a
        // long-pressed item in an offscreen row.
        compose.onNodeWithTag("atlas-timeline-grid").performScrollToIndex(0)
        compose.waitForNode(hasTestTag("atlas-header-subtitle"))

        // Selection mode swap of tab bar and action bar: 200 ms (SELECTION).
        val visible = compose.onAllNodes(tile).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }
            .firstOrNull() ?: tags.first()
        strip("selection", 200L, {
            compose.onNodeWithTag(visible).performTouchInput { longClick() }
        }, atMid = {
            // Both outgoing tabs and incoming selection actions must still be
            // composed midway; this is a crossfade, not an instantaneous swap.
            compose.onNodeWithTag("atlas-nav-photos").assertExists()
            compose.onNodeWithTag("atlas-selection-bottom-bar").assertExists()
        })
        compose.waitForNode(hasTestTag("atlas-selection-count") and hasText("1 selected"))
        compose.onNodeWithTag("atlas-nav-photos").assertDoesNotExist()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForNode(hasTestTag("atlas-nav-photos"))

        // Reduced motion, switched on live: one frame after the tap the
        // transition must already be at its end state.
        setAnimatorScale(0)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("atlas-nav-albums").performClick()
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            val reducedTab = compose.saveClockedEvidence("motion-137-reduced-tab.png")
            try {
                assertTrue(
                    "animator scale 0: rendered Albums tab must match the settled " +
                        "normal-motion Albums frame, not a clipped intermediate frame",
                    reducedTab.sameAs(settledTab)
                )
            } finally {
                reducedTab.recycle()
                settledTab.recycle()
            }
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
            compose.saveClockedEvidence("motion-137-reduced-selection.png").recycle()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }
}

