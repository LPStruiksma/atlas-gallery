package nl.atlas.gallery

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import android.util.Log
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * #652 in the real Activity at the owner's S24+ size: the Photos grid, an
 * album and the Albums grid draw under the floating tab pill (#640), and at
 * the end of a scroll their last row still ends above it. 40 invented PNGs
 * in their own album; emulator only, deleted afterwards.
 */
@RunWith(AndroidJUnit4::class)
class FloatingTabBarJourneyTest {
    private val fixtures = SyntheticHeaderFixtures(
        sizes = List(FIXTURES) { 120 to 90 },
        folderOf = { FOLDER })
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule val chain: RuleChain =
        RuleChain.outerRule(S24DisplayRule()).around(fixtures).around(compose)

    @Test fun gridsDrawUnderThePillAndTheirLastRowStaysReachable() {
        waitFor(hasTestTag("atlas-nav-pill"))
        // Timeline or plain grid: an earlier test may have left either view on.
        assertDrawsUnderPill("Photos", waitForPhotoGrid())
        saveUiEvidence("nav-652-photos-under.png")

        // An album: scroll to its end; the last row must end above the pill.
        compose.onNodeWithTag("atlas-nav-albums").performClick()
        // Assert the destination before looking for the fixture. A stale Photos
        // tree must fail at the navigation boundary, not look like missing media.
        waitFor(hasTestTag("atlas-album-grid"))
        val card = hasTestTag("atlas-album-card") and hasText(FOLDER, substring = true)
        waitFor(card)
        compose.onNode(card).performClick()
        waitFor(hasTestTag("atlas-header-title") and hasText(FOLDER))
        val albumGrid = waitForPhotoGrid()
        assertDrawsUnderPill("album", albumGrid)
        scrollToEnd(albumGrid)
        val lastTile = compose.onAllNodes(mediaTile, useUnmergedTree = true)
            .fetchSemanticsNodes().maxOf { it.boundsInRoot.bottom }
        val pillTop = pillTopPx()
        assertTrue("album: last row ends at $lastTile px, under the pill (top $pillTop px)",
            lastTile <= pillTop + 1f)
        saveUiEvidence("nav-652-album-end.png")

        // Albums grid: draws under the pill too. Back to the album's top
        // first, so the header (with Back) shows whatever its scroll rule.
        compose.onNodeWithTag(albumGrid).performScrollToIndex(0)
        compose.waitForIdle()
        compose.onNodeWithTag("atlas-header-back").performClick()
        waitFor(card)
        compose.waitForIdle()
        assertDrawsUnderPill("Albums", "atlas-album-grid")
        saveUiEvidence("nav-652-albums-under.png")
    }

    private val mediaTile = SemanticsMatcher("media tile") { node ->
        val tag = node.config.getOrNull(SemanticsProperties.TestTag).orEmpty()
        tag.startsWith("atlas-timeline-media-") || tag.startsWith("atlas-grid-media-")
    }

    private fun pillTopPx(): Float =
        compose.onNodeWithTag("atlas-nav-pill").fetchSemanticsNode().boundsInRoot.top

    /** The grid's own bounds reach below the pill's top edge: content scrolls under it. */
    private fun assertDrawsUnderPill(name: String, gridTag: String) {
        val pill = compose.onNodeWithTag("atlas-nav-pill").getUnclippedBoundsInRoot()
        val grid = compose.onNodeWithTag(gridTag).getUnclippedBoundsInRoot()
        assertTrue("$name grid (bottom ${grid.bottom}) must extend under the pill (top ${pill.top})",
            grid.bottom >= pill.bottom - 1.dp)
    }

    private fun scrollToEnd(gridTag: String) {
        fun node(): SemanticsNode = compose.onNodeWithTag(gridTag).fetchSemanticsNode()
        repeat(40) {
            val range = node().config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
                ?: return
            if (range.value() >= range.maxValue()) return
            compose.onNodeWithTag(gridTag).performTouchInput { swipeUp() }
            compose.waitForIdle()
        }
    }

    /** The tag of whichever photo grid is showing (timeline or plain). */
    private fun waitForPhotoGrid(): String {
        waitFor(hasTestTag("atlas-timeline-grid") or hasTestTag("atlas-feature-grid"))
        compose.waitForIdle()
        return if (compose.onAllNodes(hasTestTag("atlas-timeline-grid"))
                .fetchSemanticsNodes().isNotEmpty()) "atlas-timeline-grid" else "atlas-feature-grid"
    }

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitForNode(matcher)

    private companion object {
        const val FIXTURES = 40
        const val FOLDER = "ATLAS-Pill-652"
    }
}
