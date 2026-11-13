package nl.atlas.gallery.featurealbums

import android.accessibilityservice.AccessibilityService
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.atlas.gallery.S24DisplayRule
import nl.atlas.gallery.featuredesign.AtlasGalleryTheme
import nl.atlas.gallery.saveUiEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** #20: render the actual Albums composable with invented albums in each app theme. */
@RunWith(AndroidJUnit4::class)
class AlbumsSortLightJourneyTest : AlbumsSortSheetJourneyTest(dark = false)

@RunWith(AndroidJUnit4::class)
class AlbumsSortDarkJourneyTest : AlbumsSortSheetJourneyTest(dark = true)

/**
 * Exactly four Samsung-style radio choices, each captured at S24+ 1080x2340.
 * All album records are invented; no MediaStore permissions, photos or writes.
 */
abstract class AlbumsSortSheetJourneyTest(private val dark: Boolean) {
    private val rule = createComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(rule)

    private val albums = listOf(
        "Camera", "Screenshots", "Holidays", "Weekend", "Garden",
        "Mountains", "Pets", "Family", "Trips"
    ).mapIndexed { i, name ->
        AlbumSummary("Pictures/Fiction-$i/", name, i + 1, i + 1, 0,
            null, false, (100L - i))
    }
    private val mode = if (dark) "dark" else "light"

    @Test fun allFourSortsCloseReopenRemainSelectedAndBackDismisses() {
        val callbacks = mutableListOf<AlbumSort>()
        rule.setContent {
            var sort by remember { mutableStateOf(AlbumSort.RECENT) }
            AtlasGalleryTheme(dark = dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        AtlasAlbumsScreen(
                            albums = albums,
                            sort = sort,
                            onSort = { chosen -> callbacks += chosen; sort = chosen },
                            onOpenAlbum = {},
                            mediaFilter = AlbumMediaFilter.ALL,
                            onMediaFilterChange = {},
                            onReorderAlbums = { error("A sort choice cannot reorder albums") },
                            coverIllustration = { album ->
                                val tones = listOf(
                                    Color(0xFF618D96), Color(0xFFB2A18C),
                                    Color(0xFF5E8374), Color(0xFF7A738F)
                                )
                                Box(Modifier.fillMaxSize().background(
                                    tones[album.mediaCount % tones.size]))
                            }
                        )
                    }
                }
            }
        }

        for (choice in AlbumSort.entries) {
            open()
            AlbumSort.entries.forEach { entry ->
                rule.onNodeWithTag(entry.tag()).assertExists()
            }
            rule.onNodeWithTag(choice.tag()).performClick()
            closed()
            rule.runOnIdle { assertEquals(choice, callbacks.last()) }

            open()
            rule.onNodeWithTag(choice.tag()).assertIsSelected()
            AlbumSort.entries.filter { it != choice }.forEach { entry ->
                rule.onNodeWithTag(entry.tag()).assertIsNotSelected()
            }
            rule.waitForIdle()
            saveUiEvidence("albums-12-" + choice.name.lowercase() + "-$mode.png")
            back()
            closed()
        }

        // A dismissed sheet never resets the persistent Custom choice or invokes onSort.
        val before = callbacks.size
        open()
        rule.onNodeWithTag(AlbumSort.CUSTOM.tag()).assertIsSelected()
        back()
        closed()
        rule.runOnIdle { assertEquals(before, callbacks.size) }
        assertEquals(AlbumSort.entries.toList(), callbacks)
    }

    private fun open() {
        rule.onNodeWithTag("atlas-album-sort").performClick()
        rule.waitUntil(10_000L) {
            rule.onAllNodesWithTag("atlas-album-sort-sheet").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun closed() {
        rule.waitUntil(10_000L) {
            rule.onAllNodesWithTag("atlas-album-sort-sheet").fetchSemanticsNodes().isEmpty()
        }
    }

    private fun back() {
        assertTrue("Android Back dismisses the Albums sort sheet",
            InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
                AccessibilityService.GLOBAL_ACTION_BACK))
    }

    private fun AlbumSort.tag() = "atlas-album-sort-" + name.lowercase()
}
