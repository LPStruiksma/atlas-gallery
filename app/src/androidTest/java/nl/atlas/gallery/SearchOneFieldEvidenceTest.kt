package nl.atlas.gallery

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.atlas.gallery.featuredesign.AtlasDestination
import nl.atlas.gallery.featuredesign.AtlasGalleryNavigation
import nl.atlas.gallery.featuredesign.AtlasGalleryTheme
import nl.atlas.gallery.featuresearch.EphemeralSearchHistory
import nl.atlas.gallery.featuresearch.LocalSearchControls
import nl.atlas.gallery.featuresearch.LocalSearchFacets
import nl.atlas.gallery.featuresearch.SearchPerson
import nl.atlas.gallery.featuresearch.searchWithFacets
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * #644 (spec §4): Search is one field with recent searches and suggestion
 * chips. At rest there is one ⋮, Search by face and Find similar sit among
 * the suggestions, and the All · Photos · Videos filter appears only once a
 * query has results. Dark renders at Galaxy S24+ size (1080×2340). Names,
 * faces and photos are invented and drawn locally; results come from the
 * real searchWithFacets.
 */
@RunWith(AndroidJUnit4::class)
class SearchOneFieldEvidenceTest {
    private val compose = createComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(compose)

    private val people = listOf(SearchPerson("p1", "Susanne de Vries"),
        SearchPerson("p2", "Sam"), SearchPerson("p3", "Daan"))
    private val photos = (1L..18L).map { id ->
        MediaRecord(id, "content://synthetic/$id", "IMG_$id.jpg", "DCIM/Camera/", 100,
            1_500_000_000_000L + id * 86_400_000L, id % 5 == 0L)
    }
    private val uris = mapOf("p1" to photos.filter { it.id % 2 == 0L }.map { it.uri }.toSet())

    @Test fun restRecentsTypingAndPersonResults() {
        var library = 0
        var face = 0
        var similar = 0
        var history by mutableStateOf(EphemeralSearchHistory(permissionEpoch = "synthetic-grant"))
        compose.setContent {
            var filters by remember { mutableStateOf(LocalSearchFacets()) }
            val active = filters != LocalSearchFacets()
            val results = remember(filters) {
                if (!active) emptyList()
                else searchWithFacets(photos, emptyList(), filters,
                    mediaVolumeOf = { it.volumeName }, nativePersonUris = uris)
            }
            AtlasGalleryTheme(dark = true) {
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    bottomBar = { AtlasGalleryNavigation(AtlasDestination.SEARCH, onSelect = {}) }
                ) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).padding(top = 12.dp)) {
                        LocalSearchControls(
                            filters = filters, people = emptyList(), history = history,
                            onlyTheseFacesAvailable = false,
                            onFiltersChange = { filters = it },
                            onSubmittedQuery = {
                                history = history.rememberForGrant(it, "synthetic-grant")
                            },
                            onClearHistory = {},
                            currentPermissionEpoch = "synthetic-grant",
                            onSearchByFace = { face++ }, onFindSimilar = { similar++ },
                            namedPeople = people,
                            personAvatar = { DrawnFace(it.key.hashCode().toLong()) },
                            hasResults = active && results.isNotEmpty(),
                            onOpenLibraryStatus = { library++ }
                        )
                        // As in the app: no results grid at rest.
                        if (active) LazyVerticalGrid(GridCells.Fixed(4),
                            contentPadding = PaddingValues(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.testTag("evidence-results")) {
                            items(results, key = { it.id }) { DrawnPhoto(it) }
                        }
                    }
                }
            }
        }

        // At rest: field, one ⋮, suggestions. No type filter, no results.
        waitFor("atlas-search-field")
        compose.onAllNodes(hasContentDescription("More", substring = true))
            .fetchSemanticsNodes().let { assertEquals("one ⋮ on Search", 1, it.size) }
        compose.onNodeWithTag("atlas-search-by-face").assertExists()
        compose.onNodeWithTag("atlas-search-find-similar").assertExists()
        compose.onNodeWithTag("atlas-search-kind-ALL").assertDoesNotExist()
        compose.onNodeWithTag("evidence-results").assertDoesNotExist()
        compose.waitForIdle()
        saveUiEvidence("search-644-rest.png")

        // ⋮ holds Filters and Library status & settings.
        compose.onNodeWithTag("atlas-search-overflow").performClick()
        waitFor("atlas-library-status")
        compose.onNodeWithTag("atlas-search-more-filters").assertExists()
        compose.onNodeWithTag("atlas-library-status").performClick()
        compose.runOnIdle { assertEquals(1, library) }

        compose.onNodeWithTag("atlas-search-by-face").performClick()
        compose.onNodeWithTag("atlas-search-find-similar").performClick()
        compose.runOnIdle { assertEquals(1, face); assertEquals(1, similar) }

        // With recents.
        compose.runOnIdle {
            history = EphemeralSearchHistory(listOf("beach", "Daan", "birthday", "snow"),
                "synthetic-grant")
        }
        waitFor("atlas-search-recent")
        compose.onNodeWithTag("atlas-search-by-face").assertExists()
        compose.waitForIdle()
        saveUiEvidence("search-644-recents.png")

        // Typing a name: person suggestions; face/similar chips step aside.
        compose.onNodeWithTag("atlas-search-field").performTextInput("sus")
        waitFor("atlas-search-person-suggestion")
        compose.onNodeWithTag("atlas-search-by-face").assertDoesNotExist()
        compose.onNodeWithTag("atlas-search-recent").assertDoesNotExist()
        compose.waitForIdle()
        saveUiEvidence("search-644-typing.png")

        // Person chip plus results: now the type filter appears.
        compose.onAllNodesWithTag("atlas-search-person-suggestion").onFirst().performClick()
        waitFor("atlas-search-person-chip")
        waitFor("atlas-search-kind-ALL")
        waitFor("evidence-results")
        compose.waitForIdle()
        saveUiEvidence("search-644-results.png")

        // A query without results hides it again.
        compose.onNodeWithTag("atlas-search-person-chip").performClick()
        compose.onNodeWithTag("atlas-search-field").performTextClearance()
        compose.onNodeWithTag("atlas-search-field").performTextInput("zzzz")
        compose.waitUntil(10_000L) {
            compose.onAllNodes(hasTestTag("atlas-search-kind-ALL"))
                .fetchSemanticsNodes().isEmpty()
        }
    }

    private fun waitFor(tag: String) {
        try {
            compose.waitUntil(30_000L) {
                compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: Throwable) {
            val tree = runCatching { compose.onRoot().printToString() }.getOrDefault("?")
            throw AssertionError("Timed out waiting for $tag. Screen:\n$tree", e)
        }
    }
}

/** An invented photo tile: a flat coloured scene. Never a real photo. */
@androidx.compose.runtime.Composable
private fun DrawnPhoto(record: MediaRecord) {
    val tones = listOf(0xFF4E7D6B, 0xFF3F6E8C, 0xFF8A6D3B, 0xFF6D5C8A, 0xFF7A4E5A)
    Box(Modifier.aspectRatio(1f).background(Color(tones[(record.id % tones.size).toInt()])),
        contentAlignment = Alignment.BottomEnd) {
        if (record.isVideo) Text("▶", color = Color.White, modifier = Modifier.padding(4.dp))
    }
}

/** A flat, invented face: backdrop, skin oval, two eyes. */
@androidx.compose.runtime.Composable
private fun DrawnFace(seed: Long) {
    val backdrops = listOf(0xFF5C6B73, 0xFF8D6E63, 0xFF4E7D6B, 0xFF6D5C8A)
    val skins = listOf(0xFFF1C9A5, 0xFFD9A47E, 0xFFB57B55, 0xFF8D5B3C)
    val i = Math.floorMod(seed, 4L).toInt()
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Color(backdrops[i]))
        val w = size.width
        val h = size.height
        drawOval(Color(skins[i]), Offset(w * 0.28f, h * 0.2f), Size(w * 0.44f, h * 0.56f))
        drawOval(Color(skins[i]), Offset(w * 0.12f, h * 0.78f), Size(w * 0.76f, h * 0.5f))
        drawCircle(Color(0xFF2B2B2B), w * 0.035f, Offset(w * 0.42f, h * 0.44f))
        drawCircle(Color(0xFF2B2B2B), w * 0.035f, Offset(w * 0.58f, h * 0.44f))
    }
}
