package nl.atlas.gallery

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Calendar
import java.util.TimeZone
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
 * #554 rendered proof: typing a name suggests the person (face circle plus
 * name), picking it makes a removable chip whose photos fill the results,
 * and "2019" narrows them to that year. Names, faces and photos are invented
 * and drawn locally; results come from the real searchWithFacets.
 */
@RunWith(AndroidJUnit4::class)
class PersonNameSearchEvidenceTest {
    private val compose = createComposeRule()

    // #645: evidence at the owner's S24+ size.
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(compose)

    private val people = listOf(
        SearchPerson("p1", "Susanne de Vries"), SearchPerson("p2", "Sam"),
        SearchPerson("p3", "Sophie"), SearchPerson("p4", "Daan"))

    private fun millis(year: Int, month: Int) = Calendar.getInstance(TimeZone.getDefault()).apply {
        clear(); set(year, month - 1, 15, 12, 0, 0)
    }.timeInMillis

    // 12 invented photos over three years; Susanne is in the even ones.
    private val photos = (1L..12L).map { id ->
        MediaRecord(id, "content://synthetic/$id", "IMG_$id.jpg", "DCIM/Camera/", 100,
            millis(2018 + (id % 3).toInt(), 1 + (id % 12).toInt()), false)
    }
    private val uris = mapOf(
        "p1" to photos.filter { it.id % 2 == 0L }.map { it.uri }.toSet(),
        "p2" to setOf(photos[0].uri))

    @Test fun nameSuggestionChipAndYear() {
        var shown = -1
        compose.setContent {
            var filters by remember { mutableStateOf(LocalSearchFacets()) }
            var history by remember {
                mutableStateOf(EphemeralSearchHistory(listOf("beach"), "synthetic-grant"))
            }
            val results = remember(filters) {
                if (filters == LocalSearchFacets()) photos
                else searchWithFacets(photos, emptyList(), filters,
                    mediaVolumeOf = { it.volumeName }, nativePersonUris = uris)
            }
            shown = results.size
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
                            onSearchByFace = {}, onFindSimilar = {},
                            namedPeople = people,
                            personAvatar = { DrawnFace(it.key.hashCode().toLong()) },
                            hasResults = filters != LocalSearchFacets() && results.isNotEmpty()
                        )
                        LazyVerticalGrid(GridCells.Fixed(4),
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
        compose.onNodeWithTag("atlas-search-field").performTextInput("sus")
        waitFor("atlas-search-person-suggestion")
        compose.waitForIdle()
        saveUiEvidence("search-person-suggest.png")

        compose.onAllNodesWithTag("atlas-search-person-suggestion").onFirst().performClick()
        waitFor("atlas-search-person-chip")
        compose.runOnIdle { assertEquals(6, shown) }
        saveUiEvidence("search-person-chip.png")

        compose.onNodeWithTag("atlas-search-field").performTextInput("2019")
        // Even ids whose id % 3 == 1 (2019): 4 and 10.
        compose.waitUntil(10_000L) { shown == 2 }
        compose.waitForIdle()
        saveUiEvidence("search-person-year.png")

        // The chip's × removes the person; the year alone remains.
        compose.onNodeWithTag("atlas-search-person-chip").performClick()
        compose.waitUntil(10_000L) { shown == 4 }
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

/** An invented photo tile: a coloured scene with its year. Never a real photo. */
@androidx.compose.runtime.Composable
private fun DrawnPhoto(record: MediaRecord) {
    val year = Calendar.getInstance().apply { timeInMillis = record.takenAtMillis }.get(Calendar.YEAR)
    val tones = listOf(0xFF4E7D6B, 0xFF3F6E8C, 0xFF8A6D3B, 0xFF6D5C8A, 0xFF7A4E5A)
    Box(Modifier.aspectRatio(1f).background(Color(tones[(record.id % tones.size).toInt()])),
        contentAlignment = Alignment.BottomStart) {
        Text("$year", color = Color.White, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(4.dp))
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
