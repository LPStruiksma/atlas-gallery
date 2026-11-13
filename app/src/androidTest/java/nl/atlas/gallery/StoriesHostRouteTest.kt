package nl.atlas.gallery

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.atlas.gallery.featurestories.AtlasStoriesScreen
import nl.atlas.gallery.featurestories.StoryPlaybackState
import nl.atlas.gallery.featurestories.rememberStoryPlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/**
 * E2a (#439): the Albums Stories entry and Stories screen as MainActivity wires
 * them, driven with invented records. A replaced or revoked grant must hide
 * the already-planned deck, including an open slideshow.
 */
@RunWith(AndroidJUnit4::class)
class StoriesHostRouteTest {
    @get:Rule val rule = createComposeRule()

    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.now(zone)
    private val full = GalleryMediaGrant(images = true, videos = true, selected = false)
    private val records = (1L..4L).map { id ->
        MediaRecord(
            id = 9_100_000L + id,
            uri = "content://media/external_primary/images/media/${9_100_000L + id}",
            name = "IMG_story_$id.png", relativePath = "Pictures/ATLAS-Stories-Fixture/",
            sizeBytes = 1000L,
            takenAtMillis = today.minusYears(4L * id).atTime(10, 0)
                .atZone(zone).toInstant().toEpochMilli(),
            isVideo = false
        )
    }

    @Test fun entryOpensStoryAndAReplacedOrRevokedGrantHidesTheStaleDeck() {
        val first = requireNotNull(storyPlanTokensOrNull(full, 3L, true,
            catalogRevision = 1L, epochDay = today.toEpochDay()))
        val deck = planStoryDeck(records, first, System.currentTimeMillis(), zone)
        var tokens by mutableStateOf<StoryPlanTokens?>(first)
        val opened = mutableListOf<String?>()
        rule.setContent {
            MaterialTheme {
                var open by remember { mutableStateOf(false) }
                if (open) {
                    AtlasStoriesScreen(
                        deck = deck,
                        currentGrantEpoch = tokens?.grantEpoch,
                        currentCatalogRevision = tokens?.catalogRevision,
                        onBack = { open = false },
                        onOpenMedia = {
                            opened += authorizedStoryViewerUriOrNull(it, deck, tokens, records)
                        }
                    )
                } else {
                    StoriesAlbumsEntry(currentStoryCards(deck, tokens),
                        planning = storyDeckNeedsPlan(deck, tokens), onOpen = { open = true })
                }
            }
        }
        rule.onNodeWithTag("atlas-albums-stories-entry")
            .assertTextContains("1 story from your library")
            .performClick()
        rule.onNodeWithTag("atlas-stories-grid").assertExists()
        rule.onNodeWithTag("atlas-story-on_this_day").performClick()
        rule.onNodeWithTag("atlas-story-player").assertExists()
        rule.onNodeWithTag("atlas-story-open-gallery").performClick()
        rule.runOnIdle {
            assertEquals(listOf(deck.cards.single().items.first().uri), opened.toList())
        }

        // Replace the grant while the slideshow is open: the stale deck is gone.
        tokens = storyPlanTokensOrNull(
            GalleryMediaGrant(images = false, videos = false, selected = true), 4L, true,
            catalogRevision = 2L, epochDay = today.toEpochDay())
        rule.onNodeWithTag("atlas-story-player").assertDoesNotExist()
        rule.onNodeWithTag("atlas-stories-grid").assertDoesNotExist()
        rule.onNodeWithTag("atlas-stories-empty").assertExists()
        rule.runOnIdle {
            assertNull(authorizedStoryViewerUriOrNull(
                deck.cards.single().items.first().uri, deck, tokens, records))
        }

        rule.onNodeWithTag("atlas-stories-back").performClick()
        rule.onNodeWithTag("atlas-albums-stories-entry")
            .assertTextContains("Looking for moments…")
        // Revoked: no tokens at all, nothing to plan or show.
        tokens = null
        rule.onNodeWithTag("atlas-albums-stories-entry")
            .assertTextContains("Recaps of your moments appear here")
    }

    /**
     * #444: the host keeps the slideshow position above the viewer route, so
     * unmounting the Stories screen for the viewer and coming Back reopens
     * the same story at the same moment; a newer catalogue revision does not.
     */
    @Test fun hoistedPlaybackSurvivesTheViewerRouteButNotANewRevision() {
        val first = requireNotNull(storyPlanTokensOrNull(full, 3L, true,
            catalogRevision = 11L, epochDay = today.toEpochDay()))
        val deck = planStoryDeck(records, first, System.currentTimeMillis(), zone)
        val size = deck.cards.single().items.size
        var tokens by mutableStateOf(first)
        var viewerOpen by mutableStateOf(false)
        val playback = StoryPlaybackState()
        rule.setContent {
            MaterialTheme {
                if (viewerOpen) {
                    Text("viewer", Modifier.testTag("fake-viewer"))
                } else {
                    AtlasStoriesScreen(
                        deck = deck,
                        currentGrantEpoch = tokens.grantEpoch,
                        currentCatalogRevision = tokens.catalogRevision,
                        onBack = {},
                        onOpenMedia = { viewerOpen = true },
                        playback = playback
                    )
                }
            }
        }
        rule.onNodeWithTag("atlas-story-on_this_day").performClick()
        rule.onNodeWithTag("atlas-story-pause").performClick()
        rule.onNodeWithTag("atlas-story-next").performClick()
        rule.onNodeWithContentDescription("Moment 2 of $size").assertExists()
        rule.onNodeWithTag("atlas-story-open-gallery").performClick()
        rule.onNodeWithTag("fake-viewer").assertExists()
        rule.onNodeWithTag("atlas-story-player").assertDoesNotExist()

        // Back from the viewer: same story, same moment, still paused.
        viewerOpen = false
        rule.onNodeWithTag("atlas-story-player").assertExists()
        rule.onNodeWithContentDescription("Moment 2 of $size").assertExists()
        rule.onNodeWithContentDescription("Resume slideshow").assertExists()

        // A swapped list (new revision, same epoch) never reopens the old position.
        rule.onNodeWithTag("atlas-story-open-gallery").performClick()
        rule.onNodeWithTag("fake-viewer").assertExists()
        tokens = requireNotNull(storyPlanTokensOrNull(full, 3L, true,
            catalogRevision = 12L, epochDay = today.toEpochDay()))
        viewerOpen = false
        rule.onNodeWithTag("atlas-story-player").assertDoesNotExist()
    }

    /**
     * #451: rotation / process death. The host mints a new catalogue revision
     * after a recreate and re-plans, so the restored slideshow waits for that
     * live deck and reopens the same story at the same moment; a story that is
     * gone (or now holds other moments) after the recreate lands on the grid.
     * The source is a plain field so it can change between save and restore.
     */
    @Test fun slideshowPositionSurvivesRecreateAndAGoneStoryFallsBackToTheGrid() {
        var source: List<MediaRecord> = records
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            MaterialTheme {
                val current = source
                val revision = rememberStoryCatalogRevision(current)
                val tokens = storyPlanTokensOrNull(full, 3L, true,
                    catalogRevision = revision, epochDay = today.toEpochDay())
                val deck = rememberStoryDeck(current, tokens, enabled = true)
                AtlasStoriesScreen(
                    deck = deck,
                    currentGrantEpoch = tokens?.grantEpoch,
                    currentCatalogRevision = tokens?.catalogRevision,
                    onBack = {},
                    onOpenMedia = {},
                    playback = rememberStoryPlaybackState(deck)
                )
            }
        }
        rule.waitUntil(10_000L) {
            rule.onAllNodesWithTag("atlas-story-on_this_day").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("atlas-story-on_this_day").performClick()
        rule.onNodeWithTag("atlas-story-pause").performClick()
        rule.onNodeWithTag("atlas-story-next").performClick()
        rule.onNodeWithContentDescription("Moment 2 of 4").assertExists()

        // Same records after the recreate: same story, moment 2, still paused.
        restoration.emulateSavedInstanceStateRestore()
        rule.waitUntil(10_000L) {
            rule.onAllNodesWithTag("atlas-story-player").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithContentDescription("Moment 2 of 4").assertExists()
        rule.onNodeWithContentDescription("Resume slideshow").assertExists()
        rule.onNodeWithTag("atlas-stories-grid").assertDoesNotExist()

        // One moment left the library while the app was away: same key, other
        // items. The re-planned deck has the story, but it is not the one that
        // was open, so the user lands on the grid instead of a wrong moment.
        source = records.drop(1)
        restoration.emulateSavedInstanceStateRestore()
        rule.waitUntil(10_000L) {
            rule.onAllNodesWithTag("atlas-stories-grid").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("atlas-story-player").assertDoesNotExist()
        rule.onNodeWithTag("atlas-story-on_this_day").assertExists()

        // Open the 3-moment story; after a recreate where the story is gone
        // entirely there is nothing to reopen either.
        rule.onNodeWithTag("atlas-story-on_this_day").performClick()
        rule.onNodeWithTag("atlas-story-pause").performClick()
        rule.onNodeWithContentDescription("Moment 1 of 3").assertExists()
        source = records.take(1)
        restoration.emulateSavedInstanceStateRestore()
        rule.waitUntil(10_000L) {
            rule.onAllNodesWithTag("atlas-stories-empty").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("atlas-story-player").assertDoesNotExist()
    }

    /**
     * #457: a recreate with a story open, then Back (or any other route that
     * closes Stories) before the re-planned deck is ready. Meanwhile a planning
     * placeholder shows, not the empty state; reopening lands on the grid.
     * New catalogue revisions get no deck until the test releases them, so the
     * "deck not ready yet" window is deterministic.
     */
    @Test fun closingStoriesWhileARestoreWaitsReopensOnTheGrid() {
        var holdNewDecks by mutableStateOf(false)
        val releasedRevisions = mutableSetOf<Long>()
        var navAway by mutableStateOf(false)
        var dispatcher: OnBackPressedDispatcher? = null
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            MaterialTheme {
                val owner = LocalOnBackPressedDispatcherOwner.current
                SideEffect { dispatcher = owner?.onBackPressedDispatcher }
                var open by rememberSaveable { mutableStateOf(false) }
                // A bottom-nav style close that does not touch the playback itself.
                LaunchedEffect(navAway) {
                    if (navAway) {
                        open = false
                        navAway = false
                    }
                }
                val revision = rememberStoryCatalogRevision(records)
                if (!holdNewDecks) releasedRevisions += revision
                val tokens = storyPlanTokensOrNull(full, 3L, true,
                    catalogRevision = revision, epochDay = today.toEpochDay())
                val released = revision in releasedRevisions
                val deck = remember(tokens, released) {
                    if (tokens != null && released) planStoryDeck(records, tokens,
                        System.currentTimeMillis(), zone) else null
                }
                val playback = rememberStoryPlaybackState(deck)
                ClearStoryPlaybackWhenHidden(shown = open, playback = playback)
                if (open) {
                    AtlasStoriesScreen(
                        deck = deck,
                        currentGrantEpoch = tokens?.grantEpoch,
                        currentCatalogRevision = tokens?.catalogRevision,
                        onBack = { open = false },
                        onOpenMedia = {},
                        playback = playback
                    )
                } else {
                    StoriesAlbumsEntry(currentStoryCards(deck, tokens),
                        planning = storyDeckNeedsPlan(deck, tokens), onOpen = { open = true })
                }
            }
        }

        fun playStoryToMomentTwo() {
            rule.onNodeWithTag("atlas-story-on_this_day").performClick()
            rule.onNodeWithTag("atlas-story-pause").performClick()
            rule.onNodeWithTag("atlas-story-next").performClick()
            rule.onNodeWithContentDescription("Moment 2 of 4").assertExists()
        }

        fun recreateWhileTheDeckIsNotReady() {
            holdNewDecks = true
            rule.waitForIdle()
            restoration.emulateSavedInstanceStateRestore()
            // Waiting for the re-planned deck: placeholder, not the empty state.
            rule.onNodeWithTag("atlas-stories-planning").assertExists()
            rule.onNodeWithTag("atlas-stories-empty").assertDoesNotExist()
            rule.onNodeWithTag("atlas-story-player").assertDoesNotExist()
        }

        fun releaseDecksAndReopen() {
            holdNewDecks = false
            rule.onNodeWithTag("atlas-albums-stories-entry")
                .assertTextContains("1 story from your library")
                .performClick()
            rule.onNodeWithTag("atlas-stories-grid").assertExists()
            rule.onNodeWithTag("atlas-story-player").assertDoesNotExist()
            rule.onNodeWithTag("atlas-stories-planning").assertDoesNotExist()
        }

        // System Back while the restore waits.
        rule.onNodeWithTag("atlas-albums-stories-entry").performClick()
        playStoryToMomentTwo()
        recreateWhileTheDeckIsNotReady()
        rule.runOnIdle { requireNotNull(dispatcher).onBackPressed() }
        rule.onNodeWithTag("atlas-albums-stories-entry").assertExists()
        releaseDecksAndReopen()

        // Any other route that sets Stories closed (bottom nav) while it waits.
        playStoryToMomentTwo()
        recreateWhileTheDeckIsNotReady()
        navAway = true
        rule.onNodeWithTag("atlas-albums-stories-entry").assertExists()
        releaseDecksAndReopen()

        // Not closed: once the deck arrives the placeholder gives way to the
        // same story at moment 2, still paused.
        playStoryToMomentTwo()
        recreateWhileTheDeckIsNotReady()
        holdNewDecks = false
        rule.onNodeWithTag("atlas-story-player").assertExists()
        rule.onNodeWithContentDescription("Moment 2 of 4").assertExists()
        rule.onNodeWithContentDescription("Resume slideshow").assertExists()
        rule.onNodeWithTag("atlas-stories-planning").assertDoesNotExist()
    }
}
