package nl.atlas.gallery

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.atlas.gallery.featureface.search.FaceMatchTier
import nl.atlas.gallery.featurepeopleui.FaceDiscoveryPhase
import nl.atlas.gallery.featurepeopleui.FaceDiscoveryUiState
import nl.atlas.gallery.featurepeopleui.PeopleHomeScreen
import nl.atlas.gallery.featurepeopleui.PeopleHomeUiState
import nl.atlas.gallery.featurepeopleui.PeopleProfileUi
import nl.atlas.gallery.featuresearch.OverviewAdvancedMode
import nl.atlas.gallery.featuresearch.PeopleOverviewSelection
import nl.atlas.gallery.featuresearch.face.FaceSearchHit
import nl.atlas.gallery.featuresearch.face.FaceSearchResultsScreen
import nl.atlas.gallery.featuresearch.face.FaceSearchState
import nl.atlas.gallery.testkit.KeyboardAfter
import nl.atlas.gallery.testkit.KeyboardRule
import nl.atlas.gallery.testkit.typeText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

private fun ComposeTestRule.awaitNode(matcher: SemanticsMatcher) {
    try {
        waitUntil(60_000L) {
            onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    } catch (e: Throwable) {
        val tree = runCatching { onRoot().printToString() }.getOrDefault("?")
        throw AssertionError("Timed out waiting for ${matcher.description}. Screen:\n$tree", e)
    }
}

private fun ComposeTestRule.assertAbsent(matcher: SemanticsMatcher) {
    assertEquals("${matcher.description} must not be shown", 0,
        onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().size)
}

/**
 * FIND-2 F (#181) in the real Activity on invented images: two albums of
 * three locally drawn PNGs. By image from a name-filtered Albums list offers
 * only that album's photos; Back returns to the same filtered list; By face
 * without face discovery shows the index-off state.
 */
@RunWith(AndroidJUnit4::class)
class Find2OverviewUnionJourneyTest {
    private val fixtures = SyntheticHeaderFixtures(
        folderOf = { step -> if (step <= 3) "FIND2-Alpha" else "FIND2-Beta" })
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(KeyboardRule())
        .around(S24DisplayRule())
        .around(fixtures).around(compose)

    @Test fun albumsListSearchesOnlyTheMediaOfTheAlbumsItShows() {
        compose.awaitNode(hasTestTag("atlas-nav-albums"))
        compose.onNodeWithTag("atlas-nav-albums").performClick()
        compose.awaitNode(hasText("FIND2-Alpha"))
        compose.awaitNode(hasText("FIND2-Beta"))

        // B/F: the subtle search expands to the tool tray over the same list.
        compose.onNodeWithTag("atlas-search-expand").performClick()
        compose.awaitNode(hasTestTag("atlas-albums-by-image"))
        compose.onNodeWithTag("atlas-albums-by-face").assertIsEnabled()
        compose.onNodeWithTag("atlas-albums-by-image").assertIsEnabled()
        compose.waitForIdle()
        saveUiEvidence("find-2-albums-tools.png")

        // The list's own name filter decides the source: only Alpha is shown.
        compose.typeText(hasTestTag("atlas-scoped-search-field"), "Alpha", KeyboardAfter.Hidden)
        compose.awaitNode(hasTestTag("atlas-albums-by-image"))
        compose.onNodeWithTag("atlas-albums-by-image").performClick()

        // D: the reference chooser offers exactly Alpha's three photos.
        compose.awaitNode(hasTestTag("atlas-find-similar"))
        compose.awaitNode(hasTestTag("atlas-face-reference-2"))
        compose.assertAbsent(hasTestTag("atlas-face-reference-3"))
        compose.waitForIdle()
        saveUiEvidence("find-2-albums-image-chooser.png")

        // Back returns to Albums with the same query and open tray.
        compose.onNodeWithTag("atlas-face-search-back").performClick()
        compose.awaitNode(hasTestTag("atlas-albums-by-image"))
        compose.awaitNode(hasText("Alpha"))
        compose.assertAbsent(hasTestTag("atlas-find-similar"))
        compose.waitForIdle()
        saveUiEvidence("find-2-albums-return.png")

        // H3 / C: By face explains index-off (a fresh emulator has no face
        // discovery) or, if an earlier test turned it on, offers only Alpha.
        compose.onNodeWithTag("atlas-albums-by-face").performClick()
        compose.awaitNode(hasTestTag("atlas-face-results-not-indexed") or
            hasTestTag("atlas-face-reference-0"))
        compose.assertAbsent(hasTestTag("atlas-face-reference-3"))
        compose.waitForIdle()
        saveUiEvidence("find-2-albums-by-face.png")
        dismissWithBack(compose, hasTestTag("atlas-face-search"))
        compose.awaitNode(hasTestTag("atlas-albums-by-face"))

        // G1: with the keyboard up, the tools stay above it.
        compose.onNodeWithTag("atlas-scoped-search-field").performTextClearance()
        compose.typeText(hasTestTag("atlas-scoped-search-field"), "Beta", KeyboardAfter.Shown)
        compose.awaitNode(hasTestTag("atlas-albums-by-image"))
        compose.waitForIdle()
        saveUiEvidence("find-2-albums-ime.png")
    }
}

/**
 * FIND-2 F/E/H (#181) states the empty emulator library cannot reach:
 * a People list with the tool tray, union results titled for People with a
 * literal "Back to People", and an empty in-scope result. Names, faces and
 * media are invented; tiles have no real photo behind them.
 */
@RunWith(AndroidJUnit4::class)
class Find2OverviewStateEvidenceTest {
    private val compose = createComposeRule()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(KeyboardRule())
        .around(S24DisplayRule()).around(compose)

    private var dark by mutableStateOf(true)
    private var fontScale by mutableStateOf(1f)

    private fun record(id: Long) = MediaRecord(
        id = id, uri = "content://media/external_primary/images/media/$id",
        name = "synthetic-$id.png", relativePath = "DCIM/Fictional/",
        sizeBytes = 1000L + id, takenAtMillis = 1000L * id, isVideo = false,
        volumeName = "external_primary", widthPixels = 480, heightPixels = 480)

    private fun themed(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                key(dark, fontScale) { AtlasEvidenceHarness(dark = dark) { content() } }
            }
        }
    }

    @Test fun peopleListOffersFaceAndImageOverItsShownPeople() {
        val picked = mutableListOf<Pair<OverviewAdvancedMode, PeopleOverviewSelection>>()
        val profiles = listOf("Ada", "Ben", "Chloé", "Daan", "Eva").mapIndexed { i, name ->
            PeopleProfileUi(i + 1L, name, accessiblePhotos = 30 - i * 4,
                confirmedPhotoReferences = 30 - i * 4)
        }
        themed {
            PeopleHomeScreen(
                state = PeopleHomeUiState(
                    discovery = FaceDiscoveryUiState(phase = FaceDiscoveryPhase.FINISHED,
                        observedPermissionEpoch = "synthetic-grant"),
                    profiles = profiles),
                onOpenProfile = {},
                currentPermissionEpoch = "synthetic-grant",
                searchExpanded = true,
                onAdvancedSearch = { mode, selection -> picked += mode to selection }
            )
        }
        compose.awaitNode(hasTestTag("atlas-people-by-face"))
        compose.onNodeWithTag("atlas-people-by-face").assertIsEnabled()
        compose.waitForIdle()
        saveUiEvidence("find-2-people-tools-dark.png")

        compose.runOnIdle { dark = false; fontScale = 1.3f }
        compose.awaitNode(hasTestTag("atlas-people-by-image"))
        compose.waitForIdle()
        saveUiEvidence("find-2-people-tools-light-large-text.png")
        compose.onNodeWithTag("atlas-people-by-image").performClick()
        compose.runOnIdle {
            assertEquals(OverviewAdvancedMode.IMAGE, picked.single().first)
            assertEquals((1L..5L).toSet(), picked.single().second.importedPersonIds)
        }
    }

    @Test fun peopleToolsAreUnavailableWhenTheListShowsNobody() {
        themed {
            PeopleHomeScreen(
                state = PeopleHomeUiState(profiles = listOf(PeopleProfileUi(1L, "Ada",
                    accessiblePhotos = 3, confirmedPhotoReferences = 3))),
                onOpenProfile = {},
                currentPermissionEpoch = "synthetic-grant",
                searchQuery = "Zed",
                searchExpanded = true,
                onAdvancedSearch = { _, _ -> error("No people are shown") }
            )
        }
        compose.awaitNode(hasTestTag("atlas-people-by-face"))
        compose.onNodeWithTag("atlas-people-by-face").assertIsNotEnabled()
        compose.onNodeWithTag("atlas-people-by-image").assertIsNotEnabled()
        compose.waitForIdle()
        saveUiEvidence("find-2-people-tools-no-match.png")
    }

    @Test fun peopleUnionResultsNameTheirOriginAndReturnToIt() {
        var returned = 0
        var backs = 0
        val a = record(1); val b = record(2); val c = record(3)
        val results = FaceSearchState.Results(epoch = "synthetic-epoch",
            hits = listOf(FaceSearchHit(a, FaceMatchTier.SAME_PERSON, 0.95f, emptyList()),
                FaceSearchHit(b, FaceMatchTier.PROBABLE, 0.72f, emptyList()),
                FaceSearchHit(c, FaceMatchTier.SAME_PERSON, 0.90f, emptyList())),
            person = null, personName = null, truncated = false)
        var screen by mutableStateOf(listOf(c, a, b))
        themed {
            FaceSearchResultsScreen(state = results, onOpen = { _, _ -> },
                onOpenAlbum = { _, _ -> }, onOpenPeople = {}, onBack = { backs++ },
                screenMedia = screen, screenLabel = "People",
                onReturnToOrigin = { returned++ })
        }
        compose.awaitNode(hasText("Matches in these people"))
        compose.awaitNode(hasText("Same person · 2"))
        compose.waitForIdle()
        saveUiEvidence("find-2-people-face-results-dark.png")

        compose.runOnIdle { dark = false; fontScale = 1.3f }
        compose.awaitNode(hasText("Same person · 2"))
        compose.waitForIdle()
        saveUiEvidence("find-2-people-face-results-light-large-text.png")

        compose.onNodeWithTag("atlas-face-search-return-origin").performClick()
        compose.onNodeWithTag("atlas-face-search-back").performClick()
        compose.runOnIdle { assertEquals(1, returned); assertEquals(1, backs) }

        // H1: none of the engine's hits is in this source.
        compose.runOnIdle { fontScale = 1f; screen = listOf(record(9)) }
        compose.awaitNode(hasText("No results in this search"))
        compose.assertAbsent(hasTestTag("atlas-face-result-0"))
        compose.waitForIdle()
        saveUiEvidence("find-2-people-face-no-matches.png")
    }
}
