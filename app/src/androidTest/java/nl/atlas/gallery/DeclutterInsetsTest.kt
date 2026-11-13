package nl.atlas.gallery

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.atlas.gallery.featuredesign.AtlasGalleryTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * #661: under SDK 36 edge-to-edge, Declutter's shared back-chevron header and its
 * Undo / Review footer must clear the status and navigation bars, while the
 * dark backdrop still runs behind them.
 */
@RunWith(AndroidJUnit4::class)
class DeclutterInsetsTest {
    private val rule = createComposeRule()

    // #645: evidence at the owner's S24+ size (1080x2340).
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(rule)

    @Test fun headerAndFooterClearTheSystemBars() {
        val deck = listOf(
            MediaRecord(1, "content://media/external/images/media/1", "synthetic-large",
                "DCIM/Fictional/", 10_000_000L, 12L, false),
            MediaRecord(2, "content://media/external/images/media/2", "synthetic-small",
                "DCIM/Fictional/", 2_000_000L, 11L, false)
        )
        var statusTop = -1
        var navBottom = -1
        rule.setContent {
            val density = LocalDensity.current
            statusTop = WindowInsets.statusBars.getTop(density)
            navBottom = WindowInsets.navigationBars.getBottom(density)
            AtlasGalleryTheme(dark = true) {
                DeclutterScreen(folder = "DCIM/Fictional/", deck = deck,
                    onExit = {}, onTrashRequest = {})
            }
        }
        rule.waitForIdle()
        // The test activity is edge-to-edge like the app, so the bars report
        // real insets; a zero here would make the checks below vacuous.
        assertTrue("status bar inset $statusTop", statusTop > 0)
        val windowBottom = rule.onRoot().fetchSemanticsNode().boundsInWindow.bottom
        val header = rule.onNodeWithTag("atlas-header-back")
            .fetchSemanticsNode().boundsInWindow
        assertTrue("header top ${header.top} under status bar $statusTop",
            header.top >= statusTop)
        for (tag in listOf("atlas-declutter-undo", "atlas-declutter-review")) {
            val footer = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow
            assertTrue("$tag bottom ${footer.bottom} under nav bar " +
                "(window $windowBottom, inset $navBottom)",
                footer.bottom <= windowBottom - navBottom)
        }
        saveUiEvidence("declutter-661-insets.png")

        // Review list: the Trash button at the bottom also clears the nav bar.
        rule.onNodeWithTag("atlas-declutter-discard").performClick()
        rule.onNodeWithTag("atlas-declutter-review").performClick()
        rule.waitForIdle()
        val trash = rule.onNodeWithTag("atlas-declutter-trash")
            .fetchSemanticsNode().boundsInWindow
        assertTrue("trash bottom ${trash.bottom} under nav bar",
            trash.bottom <= windowBottom - navBottom)
        saveUiEvidence("declutter-661-insets-review.png")
    }
}
