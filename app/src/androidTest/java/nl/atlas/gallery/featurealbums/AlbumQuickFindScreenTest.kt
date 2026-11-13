package nl.atlas.gallery.featurealbums

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Fictional albums only; no permissions, actual MediaStore reads or writes. */
@RunWith(AndroidJUnit4::class)
class AlbumQuickFindScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun gridKeepsExactAlbumRoutesWithoutSearchChrome() {
        val albums = listOf(
            AlbumSummary("DCIM/Camera/", "Camera", 4, 4, 0,
                null, false, 10L, locationLabel = "DCIM"),
            AlbumSummary("Pictures/Camera/", "Camera", 2, 2, 0,
                null, false, 20L, locationLabel = "Pictures"),
            AlbumSummary("HiddenParent/Weekend/", "Weekend", 1, 1, 0,
                null, false, 30L)
        )
        var opened: AlbumRoute? = null
        rule.setContent {
            MaterialTheme {
                AtlasAlbumsScreen(
                    albums = albums, sort = AlbumSort.NAME, onSort = {},
                    mediaFilter = AlbumMediaFilter.ALL, onMediaFilterChange = {},
                    onOpenAlbum = { opened = it }
                )
            }
        }
        rule.onNodeWithTag("atlas-album-quick-find").assertDoesNotExist()
        rule.onNodeWithTag("atlas-album-sort").assertExists()
        rule.onNodeWithTag("atlas-header-more").assertDoesNotExist()
        rule.onAllNodesWithTag("atlas-album-card").assertCountEquals(3)
        rule.onAllNodesWithTag("atlas-album-card")[1].performClick()
        rule.runOnIdle {
            assertEquals(AlbumRoute("Pictures/Camera/", AlbumMediaFilter.ALL), opened)
        }
    }

    @Test fun unavailableCoverDoesNotCreateAnEditAction() {
        val albums = listOf(
            AlbumSummary("Fiction/Café/", "Café", 1, 1, 0, null, false, 10L)
        )
        rule.setContent {
            MaterialTheme {
                AtlasAlbumsScreen(
                    albums = albums, sort = AlbumSort.NAME, onSort = {},
                    mediaFilter = AlbumMediaFilter.ALL, onMediaFilterChange = {},
                    onOpenAlbum = {}, onChooseCover = null
                )
            }
        }
        rule.onNodeWithTag("atlas-cover-picker-open").assertDoesNotExist()
        rule.onNodeWithTag("atlas-album-quick-find").assertDoesNotExist()
    }
}
