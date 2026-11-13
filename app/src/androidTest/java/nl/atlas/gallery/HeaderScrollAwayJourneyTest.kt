package nl.atlas.gallery

import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.height
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * #506 / #641 rendered proof at the owner's S24+ size: on Photos and on
 * Albums the slim header bar stays pinned while the grid scrolls under it,
 * with sort and ⋮ always reachable (#509 §2 "a compact bar (search, ⋮) that
 * stays"); Photos' count line folds away once the grid leaves its top and
 * returns at the top ("no permanent second line"). 120 invented, locally
 * drawn images in 24 folders give both grids enough rows to scroll.
 */
@RunWith(AndroidJUnit4::class)
class HeaderScrollAwayJourneyTest {
    private val fixtures = SyntheticHeaderFixtures(
        sizes = List(120) { 120 to 90 },
        folderOf = { step -> "ATLAS-Scroll-Fixture-" + ((step - 1) / 5 + 1) })
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule val chain: RuleChain =
        RuleChain.outerRule(S24DisplayRule()).around(fixtures).around(compose)

    @Test fun compactBarStaysAndCountLineFoldsAwayOnScroll() {
        waitFor(hasText("photos & videos", substring = true))
        waitFor(hasTestTag("atlas-timeline-grid"))
        hideThenReveal("atlas-timeline-grid", "photos", "atlas-photos-sort", hasCountLine = true)

        compose.onNodeWithTag("atlas-nav-albums").performClick()
        waitFor(hasTestTag("atlas-album-card") and
            hasText("ATLAS-Scroll-Fixture", substring = true))
        hideThenReveal("atlas-album-grid", "albums", "atlas-album-sort", hasCountLine = false)
    }

    /** Scroll position of a lazy grid as its semantics report it (an estimate; compare only with itself). */
    private fun scrollOf(gridTag: String) = compose.onNodeWithTag(gridTag).fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange]

    private fun hideThenReveal(gridTag: String, name: String, sortTag: String,
                               hasCountLine: Boolean) {
        // The header and grid appear before the whole inventory publishes; on
        // the CI emulator other tests' media arrive in pages for a while. Each
        // publish re-plans the grid and re-anchors it on its focal photo
        // without a scroll (it jumped from item 0 to 6740 mid-drag), so wait
        // until the grid size and the header's count line have both stayed
        // the same for eight seconds.
        var seen: Pair<Float, String>? = null
        var since = 0L
        compose.waitUntil(180_000L) {
            val now = SystemClock.uptimeMillis()
            val max = compose.onAllNodes(hasTestTag(gridTag)).fetchSemanticsNodes().firstOrNull()
                ?.config?.getOrElseNullable(SemanticsProperties.VerticalScrollAxisRange) { null }
                ?.maxValue?.invoke() ?: -1f
            val counts = compose.onAllNodes(hasText("photos & videos", substring = true) or
                hasText("Ordering library", substring = true))
                .fetchSemanticsNodes().joinToString { node ->
                    node.config.getOrElseNullable(SemanticsProperties.Text) { null }
                        ?.joinToString { it.text }.orEmpty()
                }
            val state = max to counts
            if (state != seen) { seen = state; since = now }
            max > 12f && !counts.contains("Ordering") && now - since >= 8_000L
        }
        compose.waitForIdle()
        // The header is drawn over the grid's top edge, which sits below the
        // status bar; "hidden" and "shown" are measured from that edge.
        val gridTop = compose.onNodeWithTag(gridTag).getUnclippedBoundsInRoot().top
        assertTrue("$name header starts on screen",
            compose.onNodeWithTag("atlas-scrolling-header").getUnclippedBoundsInRoot().top >= gridTop - 1.dp)
        // Drag the content up step by step, as a finger would, with no fling.
        // The trace (header top dp / first visible item per step) explains
        // any failure.
        // The grid may open anywhere (on CI near the end of a large library),
        // so first drag it back towards the top to leave room below.
        val room = drag(gridTag, steps = 20, dyPx = 60f)
        val down = room + " | " + drag(gridTag, steps = 40, dyPx = -25f)
        val scrolled = scrollOf(gridTag).value()
        assertTrue("$name grid is scrolled (first visible item $scrolled; $down)", scrolled >= 1f)
        val header = compose.onNodeWithTag("atlas-scrolling-header").getUnclippedBoundsInRoot()
        assertTrue("$name compact bar stays pinned (grid top $gridTop, header $header; $down)",
            header.top >= gridTop - 1.dp)
        assertTrue("$name compact bar stays slim (${header.height})", header.height <= 64.dp)
        compose.onNodeWithTag(sortTag).assertIsDisplayed()
        compose.onNodeWithTag("atlas-header-more").assertIsDisplayed()
        if (hasCountLine) compose.waitUntil(5_000L) {
            compose.onAllNodesWithTag("atlas-header-subtitle").fetchSemanticsNodes().isEmpty()
        }
        compose.waitForIdle()
        saveUiEvidence("header-506-$name-hidden.png")

        // Back at the top, the count line returns; the bar never moved.
        compose.onNodeWithTag(gridTag).performScrollToIndex(0)
        compose.waitUntil(10_000L) { scrollOf(gridTag).value() == 0f }
        assertTrue("$name compact bar still pinned at the top",
            compose.onNodeWithTag("atlas-scrolling-header").getUnclippedBoundsInRoot().top >= gridTop - 1.dp)
        if (hasCountLine) compose.waitUntil(5_000L) {
            compose.onAllNodesWithTag("atlas-header-subtitle").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        saveUiEvidence("header-506-$name-revealed.png")
    }

    /** One slow drag in [steps] moves of [dyPx]; returns a per-step trace. */
    private fun drag(gridTag: String, steps: Int, dyPx: Float): String {
        val trace = StringBuilder("grid ${scrollOf(gridTag).value()}:")
        compose.onNodeWithTag(gridTag).performTouchInput {
            down(Offset(centerX, if (dyPx < 0) height * .75f else height * .3f))
        }
        repeat(steps) {
            compose.onNodeWithTag(gridTag).performTouchInput { moveBy(Offset(0f, dyPx)) }
            compose.waitForIdle()
            val top = compose.onNodeWithTag("atlas-scrolling-header")
                .getUnclippedBoundsInRoot().top.value.toInt()
            trace.append(" $top/${scrollOf(gridTag).value()}")
        }
        compose.onNodeWithTag(gridTag).performTouchInput { up() }
        compose.waitForIdle()
        return trace.append(" -> ${scrollOf(gridTag).value()}").toString()
    }

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitForNode(matcher)
}
