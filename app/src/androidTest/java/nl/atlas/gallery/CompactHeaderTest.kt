package nl.atlas.gallery

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.atlas.gallery.featuredesign.AtlasGalleryHeader
import nl.atlas.gallery.featuredesign.AtlasGalleryTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** #492: the Photos header in isolation, on a narrow 360dp phone width. */
@RunWith(AndroidJUnit4::class)
class CompactHeaderTest {
    @get:Rule val rule = createComposeRule()

    @Test fun photosHeaderFitsOneSlimRowAndEveryActionCallsItsHandler() {
        val calls = mutableListOf<String>()
        rule.setContent {
            AtlasGalleryTheme(dark = true) {
                Column(Modifier.width(360.dp)) {
                    AtlasGalleryHeader(
                        // A long album name must ellipsize, not wrap the bar taller.
                        title = "A very long camera folder name that would otherwise wrap",
                        modifier = Modifier.fillMaxWidth(),
                        subtitle = photosHeaderStatus(false, false, 123_456),
                        onBack = { calls += "back" },
                        backDescription = "All albums",
                        onOpenLibraryStatus = { calls += "status" },
                        menuItems = photosHeaderMenu(
                            timelineSelected = true,
                            canDeclutter = true,
                            onToggleTimeline = { calls += "timeline" },
                            onDeclutter = { calls += "declutter" }
                        ),
                        actions = {
                            PhotosSortAction(SortOrder.RANDOM,
                                onSort = { calls += "sort:" + it.name },
                                onShuffle = { calls += "shuffle" })
                        }
                    )
                }
            }
        }
        val height = rule.onNodeWithTag("atlas-header").getUnclippedBoundsInRoot().height
        assertTrue("Header is $height tall", height <= 64.dp)

        rule.onNodeWithTag("atlas-header-back").performClick()
        rule.onNodeWithTag("atlas-photos-sort").performClick()
        rule.onNodeWithTag("atlas-photos-sort-random").assertIsSelected()
        rule.onNodeWithTag("atlas-photos-sort-largest").performClick()
        rule.onNodeWithTag("atlas-photos-sort").performClick()
        rule.onNodeWithTag("atlas-photos-reshuffle").performClick()
        listOf("atlas-timeline-toggle",
            "atlas-open-declutter", "atlas-library-status").forEach { tag ->
            rule.onNodeWithTag("atlas-header-more").performClick()
            rule.onNodeWithTag(tag).performClick()
        }
        rule.runOnIdle {
            assertEquals(listOf("back", "sort:LARGEST", "shuffle", "timeline",
                "declutter", "status"), calls)
        }
    }

    @Test fun declutterOnlyAppearsWhenItCanRun() {
        val menu = photosHeaderMenu(true, canDeclutter = false, {}, {})
        // #494: original proportions is reached by pinching, not a menu entry.
        assertEquals(listOf("atlas-timeline-toggle"), menu.map { it.testTag })
        assertEquals("Ordering library…", photosHeaderStatus(true, false, 3))
    }
}
