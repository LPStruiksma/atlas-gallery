package nl.atlas.gallery

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.atlas.gallery.featurealbums.AlbumLoadIssue
import nl.atlas.gallery.featurealbums.AlbumMediaFilter
import nl.atlas.gallery.featurealbums.AlbumRoute
import nl.atlas.gallery.featurealbums.AlbumSort
import nl.atlas.gallery.featurealbums.AlbumSummary
import nl.atlas.gallery.featurealbums.AtlasAlbumsScreen
import nl.atlas.gallery.featureorganization.AtlasBatchSelectionBar
import nl.atlas.gallery.featureorganization.GalleryBatchSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Purely synthetic metadata, with an in-memory Albums-to-selection host.
 * Exercises the real Albums screen and shared batch bar/policy, not MediaStore
 * operations, OS permission requests or an installed device. The app host owns
 * publication checks and Android consent in production.
 */
@RunWith(AndroidJUnit4::class)
class AlbumBatchJourneyTest {
    @get:Rule val rule = createComposeRule()

    private val albums = listOf(
        AlbumSummary("Fiction/A/", "Fiction A", 2, 2, 0, null, false, 10L),
        AlbumSummary("Fiction/B/", "Fiction B", 1, 1, 0, null, false, 20L)
    )
    private val a = listOf(
        MediaRecord(1L, "content://atlas.synthetic/a1", "a1.jpg",
            "Fiction/A/", 10L, 1L, false),
        MediaRecord(2L, "content://atlas.synthetic/a2", "a2.jpg",
            "Fiction/A/", 10L, 2L, false)
    )
    private val b = listOf(
        MediaRecord(3L, "content://atlas.synthetic/b1", "b1.jpg",
            "Fiction/B/", 10L, 3L, false)
    )

    @Test fun albumJourneyDeselectBackScopeChangeAndRevocationNeverMutateMedia() {
        var active by mutableStateOf<AlbumRoute?>(null)
        var selected by mutableStateOf<GalleryBatchSelection?>(null,
            referentialEqualityPolicy())
        var granted by mutableStateOf(true)
        var operating by mutableStateOf(false)
        var shareRequests = 0
        var consentRequests = 0
        var mediaWrites = 0
        var dispatcher: OnBackPressedDispatcher? = null

        rule.setContent {
            val owner = LocalOnBackPressedDispatcherOwner.current
            SideEffect { dispatcher = owner?.onBackPressedDispatcher }
            val route = active
            val current = if (route?.relativePath == "Fiction/B/") b else a
            // A source publication change or lost grant invalidates the
            // selection without invoking any media operation.
            LaunchedEffect(route, granted) {
                if (!granted || (selected != null && selected!!.source !== current)) {
                    selected = null
                }
            }
            MaterialTheme {
                if (route == null || !granted) {
                    AtlasAlbumsScreen(
                        albums = if (granted) albums else emptyList(),
                        sort = AlbumSort.NAME, onSort = {},
                        onOpenAlbum = { active = it; selected = null },
                        mediaFilter = AlbumMediaFilter.ALL,
                        onMediaFilterChange = {},
                        loadIssue = if (granted) null else AlbumLoadIssue.ACCESS_NOT_GRANTED
                    )
                } else {
                    Column {
                        Text(route.relativePath, modifier = Modifier.testTag("active-album"))
                        selected?.let { session ->
                            AtlasBatchSelectionBar(
                                state = session.uiState(current, 7L, granted, operating),
                                onExit = { selected = null },
                                onShare = { shareRequests++ },
                                // Fake OS consent request; no physical mutation.
                                onTrash = { consentRequests++ },
                                modifier = Modifier.testTag("atlas-batch-selection-bar")
                            )
                        }
                        Button(onClick = {
                            val session = selected
                            selected = if (session == null)
                                GalleryBatchSelection.start(current, 7L, current[0])
                            else session.toggled(current[0])
                        }, modifier = Modifier.testTag("toggle-first")) { Text("Toggle first") }
                        if (current.size > 1) {
                            Button(onClick = {
                                val session = selected
                                selected = if (session == null)
                                    GalleryBatchSelection.start(current, 7L, current[1])
                                else session.toggled(current[1])
                            }, modifier = Modifier.testTag("toggle-second")) { Text("Toggle second") }
                        }
                        Button(onClick = { operating = !operating },
                            modifier = Modifier.testTag("toggle-busy")) { Text("Busy state") }
                        Button(onClick = {
                            active = AlbumRoute(if (route.relativePath == "Fiction/A/")
                                "Fiction/B/" else "Fiction/A/", AlbumMediaFilter.ALL)
                        }, modifier = Modifier.testTag("switch-album")) { Text("Switch album") }
                        Button(onClick = { granted = false },
                            modifier = Modifier.testTag("revoke-access")) { Text("Revoke") }
                    }
                }
            }
        }

        rule.onAllNodesWithTag("atlas-album-card")[0].assertExists()
        rule.onAllNodesWithTag("atlas-album-card")[0].performClick()
        rule.onNodeWithTag("active-album").assertTextEquals("Fiction/A/")
        rule.onNodeWithTag("toggle-first").performClick()
        rule.onNodeWithTag("atlas-selection-count").assertTextEquals("1 selected")
        rule.onNodeWithTag("atlas-selection-share").assertIsEnabled()
        rule.onNodeWithTag("atlas-selection-trash").assertIsEnabled()
        rule.onNodeWithTag("atlas-selection-consent").assertExists()

        rule.onNodeWithTag("atlas-selection-trash").performClick()
        rule.runOnIdle {
            assertEquals(1, consentRequests)
            assertEquals(0, shareRequests)
            assertEquals(0, mediaWrites)
        }
        rule.onNodeWithTag("toggle-second").performClick()
        rule.onNodeWithTag("atlas-selection-count").assertTextEquals("2 selected")
        rule.onNodeWithTag("toggle-first").performClick()
        rule.onNodeWithTag("atlas-selection-count").assertTextEquals("1 selected")
        rule.onNodeWithTag("toggle-second").performClick()
        rule.onNodeWithTag("atlas-batch-selection-bar").assertDoesNotExist()

        rule.onNodeWithTag("toggle-first").performClick()
        rule.onNodeWithTag("toggle-busy").performClick()
        rule.onNodeWithTag("atlas-selection-share").assertIsNotEnabled()
        rule.onNodeWithTag("atlas-selection-trash").assertIsNotEnabled()
        rule.onNodeWithTag("toggle-busy").performClick()
        rule.runOnIdle { requireNotNull(dispatcher).onBackPressed() }
        rule.onNodeWithTag("atlas-batch-selection-bar").assertDoesNotExist()
        rule.onNodeWithTag("active-album").assertTextEquals("Fiction/A/")

        rule.onNodeWithTag("toggle-first").performClick()
        rule.onNodeWithTag("switch-album").performClick()
        rule.onNodeWithTag("active-album").assertTextEquals("Fiction/B/")
        rule.waitUntil(10_000) {
            rule.onAllNodesWithTag("atlas-batch-selection-bar").fetchSemanticsNodes().isEmpty()
        }
        rule.onNodeWithTag("toggle-first").performClick()
        rule.onNodeWithTag("atlas-selection-count").assertTextEquals("1 selected")
        rule.onNodeWithTag("revoke-access").performClick()
        rule.onNodeWithText("Albums need photo access").assertExists()
        rule.onNodeWithTag("atlas-batch-selection-bar").assertDoesNotExist()
        rule.runOnIdle {
            assertNull(selected)
            assertEquals(1, consentRequests)
            assertEquals(0, shareRequests)
            assertEquals(0, mediaWrites)
        }
    }
}
