package nl.atlas.gallery.featurealbums

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Fictional albums only: Samsung-style hold-and-drag custom order (#515). */
@RunWith(AndroidJUnit4::class)
class AlbumReorderScreenTest {
    @get:Rule val rule = createComposeRule()

    private val albums = listOf("DCIM/Camera/" to "Camera", "Pictures/A/" to "A",
        "Pictures/B/" to "B", "Pictures/C/" to "C", "Pictures/D/" to "D")
        .mapIndexed { index, (path, title) ->
            AlbumSummary(path, title, index + 1, index + 1, 0, null, false, 10L - index)
        }

    private var reordered: List<String>? = null
    private var opened: AlbumRoute? = null
    private var coverFor: AlbumRoute? = null

    private fun show() {
        rule.setContent {
            var shown by remember { mutableStateOf(albums) }
            var sort by remember { mutableStateOf(AlbumSort.NAME) }
            MaterialTheme {
                AtlasAlbumsScreen(
                    albums = shown, sort = sort, onSort = { sort = it },
                    mediaFilter = AlbumMediaFilter.ALL, onMediaFilterChange = {},
                    onOpenAlbum = { opened = it },
                    onChooseCover = { coverFor = it },
                    onReorderAlbums = { next ->
                        reordered = next.map { it.title }
                        shown = next
                        sort = AlbumSort.CUSTOM
                    }
                )
            }
        }
        rule.waitUntil(10_000) {
            rule.onAllNodesWithTag("atlas-album-card").fetchSemanticsNodes().size == albums.size
        }
    }

    private fun centreOfCard(index: Int): Offset {
        val grid = rule.onNodeWithTag("atlas-album-grid").fetchSemanticsNode().boundsInRoot
        val card = rule.onAllNodesWithTag("atlas-album-card")[index].fetchSemanticsNode().boundsInRoot
        return card.center - grid.topLeft
    }

    private fun hold(at: Offset, path: List<Offset> = emptyList()) {
        rule.onNodeWithTag("atlas-album-grid").performTouchInput {
            down(at)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 200)
            path.forEach { moveTo(it); advanceEventTime(16) }
            up()
        }
    }

    private fun line(from: Offset, to: Offset, steps: Int) =
        (1..steps).map { from + (to - from) * (it / steps.toFloat()) }

    /** One short journey (ATLAS emulator budget): every touch rule of #515's custom order. */
    @Test fun holdToDragReordersAndHoldToReleaseOffersCover() {
        show()
        // Pinned Camera neither moves nor opens anything when dragged.
        hold(centreOfCard(0), line(centreOfCard(0), centreOfCard(3), 4))
        // Away to A's slot and back to D's own slot is no reorder.
        val d = centreOfCard(4)
        val a = centreOfCard(1)
        hold(d, line(d, a, 8) + line(a, d, 8))
        rule.runOnIdle {
            assertNull("Pinned or same-order drags must not reorder", reordered)
            assertNull(opened)
        }
        // Hold and release offers the cover menu, not the album.
        hold(centreOfCard(2))
        rule.onNodeWithTag("atlas-cover-picker-open").performClick()
        rule.runOnIdle {
            assertEquals(AlbumRoute("Pictures/B/", AlbumMediaFilter.ALL), coverFor)
            assertNull(opened)
        }
        // Hold and drag D onto A's slot: D moves there and the sort becomes Custom.
        hold(d, line(d, a, 12))
        rule.runOnIdle {
            assertEquals(listOf("Camera", "D", "A", "B", "C"), reordered)
            assertNull("A drag must not open the album", opened)
        }
        rule.onNodeWithTag("atlas-album-sort").performClick()
        rule.onNodeWithTag("atlas-album-sort-custom").assertIsSelected()
        rule.onNodeWithTag("atlas-album-sort-custom").performClick()
        // A plain tap still opens an album.
        rule.onAllNodesWithTag("atlas-album-card")[3].performClick()
        rule.runOnIdle { assertEquals(AlbumRoute("Pictures/B/", AlbumMediaFilter.ALL), opened) }
    }
}
