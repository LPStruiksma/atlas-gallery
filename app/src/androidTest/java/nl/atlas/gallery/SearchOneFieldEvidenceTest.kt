package nl.atlas.gallery

import nl.atlas.gallery.testkit.KeyboardRule
import nl.atlas.gallery.testkit.TestKeyboard
import nl.atlas.gallery.testkit.KeyboardAfter
import nl.atlas.gallery.testkit.hideKeyboard
import nl.atlas.gallery.testkit.tap
import nl.atlas.gallery.testkit.typeText
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.Density
import nl.atlas.gallery.featuregrid.AtlasFeatureGrid
import org.junit.Assert.assertTrue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import androidx.test.platform.app.InstrumentationRegistry
import android.util.Log
import androidx.compose.ui.test.assertCountEquals
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
    @get:Rule val chain: RuleChain = RuleChain.outerRule(KeyboardRule(TestKeyboard.Fixed))
        .around(S24DisplayRule()).around(compose)

    private val people = listOf(SearchPerson("p1", "Susanne de Vries"),
        SearchPerson("p2", "Sam"), SearchPerson("p3", "Daan"))
    private val photos = (1L..18L).map { id ->
        MediaRecord(id, "content://synthetic/$id", "IMG_$id.jpg", "DCIM/Camera/", 100,
            1_500_000_000_000L + id * 86_400_000L, id % 5 == 0L)
    }
    private val uris = mapOf("p1" to photos.filter { it.id % 2 == 0L }.map { it.uri }.toSet())

    // DESIGN-4 captures both themes in one composition: createComposeRule permits
    // setContent only once per test, so switch the theme through Compose state.
    @Test fun compactSearchRestAndNoResultsInBothThemes() {
        var darkTheme by mutableStateOf(false)
        var queryReset by mutableStateOf(0)
        var clearInputFocus: (() -> Unit)? = null
        compose.setContent {
            val focusManager = LocalFocusManager.current
            val keyboardController = LocalSoftwareKeyboardController.current
            clearInputFocus = {
                focusManager.clearFocus(force = true)
                keyboardController?.hide()
            }
            var filters by remember(queryReset) { mutableStateOf(LocalSearchFacets()) }
            AtlasGalleryTheme(dark = darkTheme) {
                Scaffold(containerColor = MaterialTheme.colorScheme.background,
                    bottomBar = {
                        AtlasGalleryNavigation(AtlasDestination.SEARCH, onSelect = {})
                    }) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).padding(top = 12.dp)
                        .imePadding()) {
                        LocalSearchControls(
                            filters = filters, people = emptyList(),
                            history = EphemeralSearchHistory(permissionEpoch = "synthetic"),
                            onlyTheseFacesAvailable = false,
                            onFiltersChange = { filters = it },
                            onSubmittedQuery = {}, onClearHistory = {},
                            currentPermissionEpoch = "synthetic",
                            onSearchByFace = {}, onFindSimilar = {},
                            namedPeople = people,
                            personAvatar = { DrawnFace(it.key.hashCode().toLong()) },
                            hasResults = false
                        )
                        // #134: the app's own empty results grid and copy.
                        if (filters.text.isNotBlank()) AtlasFeatureGrid(
                            items = emptyList(), state = rememberLazyGridState(),
                            busy = false, onOpen = {}, viewKey = "search",
                            showViewAs = false,
                            emptyText = "No results for \u201C${filters.text.trim()}\u201D",
                            emptyDetail = "Try another word or name.",
                            modifier = Modifier.testTag("atlas-design-search-no-results"))
                    }
                }
            }
        }
        listOf(false, true).forEach { dark ->
            compose.runOnIdle {
                darkTheme = dark
                queryReset++
            }
            waitFor("atlas-search-field")
            compose.runOnIdle { clearInputFocus?.invoke() }
            compose.waitForIdle()
            saveUiEvidence("design-4-search-rest-${if (dark) "dark" else "light"}.png")
            compose.typeText(hasTestTag("atlas-search-field"), "unlikely-match", KeyboardAfter.Shown)
            waitFor("atlas-design-search-no-results")
            compose.waitForIdle()
            saveUiEvidence("design-4-search-ime-${if (dark) "dark" else "light"}.png")
            compose.hideKeyboard()
            compose.runOnIdle { clearInputFocus?.invoke() }
            compose.waitForIdle()
            saveUiEvidence("design-4-search-empty-${if (dark) "dark" else "light"}.png")
        }
    }

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
        compose.typeText(hasTestTag("atlas-search-field"), "sus", KeyboardAfter.Hidden)
        waitFor("atlas-search-person-suggestion")
        compose.onNodeWithTag("atlas-search-by-face").assertDoesNotExist()
        compose.onNodeWithTag("atlas-search-recent").assertDoesNotExist()
        compose.waitForIdle()
        saveUiEvidence("search-644-typing.png")

        // Person chip plus results: now the type filter appears.
        compose.tap(hasTestTag("atlas-search-person-suggestion"))
        waitFor("atlas-search-person-chip")
        waitFor("atlas-search-kind-ALL")
        waitFor("evidence-results")
        compose.waitForIdle()
        saveUiEvidence("search-644-results.png")

        // A query without results hides it again.
        compose.tap(hasTestTag("atlas-search-person-chip"))
        compose.onNodeWithTag("atlas-search-field").performTextClearance()
        compose.typeText(hasTestTag("atlas-search-field"), "zzzz", KeyboardAfter.Hidden)
        compose.waitUntil(10_000L) {
            compose.onAllNodes(hasTestTag("atlas-search-kind-ALL"))
                .fetchSemanticsNodes().isEmpty()
        }
    }

    /**
     * #134 measured rules at S24+ size (450 dpi, px ÷ 2.8125 = dp): chips stay
     * 32 dp visible (M3 adds the 48 dp touch area outside), the field is
     * 48–56 dp, suggestion rows 56–64 dp. Then typing renders and a 200% font
     * render where chips wrap without clipping, in both themes.
     */
    @Test fun measuredChipsFieldRowsTypingAndLargeFont() {
        var darkTheme by mutableStateOf(false)
        var fontScale by mutableStateOf(1f)
        var queryReset by mutableStateOf(0)
        var clearInputFocus: (() -> Unit)? = null
        val history = EphemeralSearchHistory(listOf("beach", "Daan", "birthday", "snow",
            "Amsterdam", "family dinner", "2019"), "synthetic-grant")
        compose.setContent {
            val focusManager = LocalFocusManager.current
            val keyboardController = LocalSoftwareKeyboardController.current
            clearInputFocus = {
                focusManager.clearFocus(force = true)
                keyboardController?.hide()
            }
            var filters by remember(queryReset) { mutableStateOf(LocalSearchFacets()) }
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                AtlasGalleryTheme(dark = darkTheme) {
                    Scaffold(containerColor = MaterialTheme.colorScheme.background,
                        bottomBar = {
                            AtlasGalleryNavigation(AtlasDestination.SEARCH, onSelect = {})
                        }) { padding ->
                        Column(Modifier.fillMaxSize().padding(padding).padding(top = 12.dp)
                            .imePadding()) {
                            LocalSearchControls(
                                filters = filters, people = emptyList(), history = history,
                                onlyTheseFacesAvailable = false,
                                onFiltersChange = { filters = it },
                                onSubmittedQuery = {}, onClearHistory = {},
                                currentPermissionEpoch = "synthetic-grant",
                                onSearchByFace = {}, onFindSimilar = {},
                                namedPeople = people,
                                personAvatar = { DrawnFace(it.key.hashCode().toLong()) },
                                hasResults = false
                            )
                        }
                    }
                }
            }
        }
        val density = compose.runOnIdle {
            InstrumentationRegistry.getInstrumentation().targetContext
                .resources.displayMetrics.density
        }
        listOf(false, true).forEach { dark ->
            val theme = if (dark) "dark" else "light"
            compose.runOnIdle { darkTheme = dark; fontScale = 1f; queryReset++ }
            waitFor("atlas-search-recent")
            compose.runOnIdle { clearInputFocus?.invoke() }
            compose.waitForIdle()

            val chipPx = visibleHeightPx("atlas-search-recent", density)
            val chipDp = chipPx / density
            val fieldDp = boundsDp("atlas-search-field", density)
            Log.i("ATLAS_134", "theme=$theme density=$density chip_visible_px=$chipPx " +
                "chip_visible_dp=${"%.1f".format(chipDp)} field_dp=${"%.1f".format(fieldDp)}")
            assertTrue("chip visible ${chipDp}dp, want 32 ± 2", chipDp in 30f..34f)
            assertTrue("field ${fieldDp}dp, want 48–56", fieldDp in 47.5f..56.5f)
            // Header and chips share the 16 dp screen inset (no double inset).
            val headerStart = compose.onAllNodesWithText("Recent searches").onFirst()
                .fetchSemanticsNode().boundsInRoot.left / density
            val chipStart = compose.onAllNodesWithTag("atlas-search-recent").onFirst()
                .fetchSemanticsNode().boundsInRoot.left / density
            Log.i("ATLAS_134", "theme=$theme header_start_dp=$headerStart chip_start_dp=$chipStart")
            assertTrue("header inset ${headerStart}dp, want 16", headerStart in 15f..17f)
            assertTrue("chip inset ${chipStart}dp, want 16", chipStart in 15f..17f)

            compose.typeText(hasTestTag("atlas-search-field"), "sus", KeyboardAfter.Hidden)
            waitFor("atlas-search-person-suggestion")
            compose.waitForIdle()
            val rowDp = boundsDp("atlas-search-person-suggestion", density)
            Log.i("ATLAS_134", "theme=$theme suggestion_row_dp=${"%.1f".format(rowDp)}")
            assertTrue("suggestion row ${rowDp}dp, want 56–64", rowDp in 55.5f..64.5f)
            saveUiEvidence("design-4-search-typing-$theme.png")

            compose.runOnIdle { clearInputFocus?.invoke(); fontScale = 2f; queryReset++ }
            waitFor("atlas-search-recent")
            compose.waitForIdle()
            // 200% font: every recent chip is whole on screen (wrapped, not clipped).
            val rootWidthPx = compose.onRoot().fetchSemanticsNode().boundsInRoot.right
            compose.onAllNodesWithTag("atlas-search-recent").fetchSemanticsNodes().forEach {
                assertTrue("chip clipped at 200% font: ${it.boundsInRoot}",
                    it.boundsInRoot.left >= 0f && it.boundsInRoot.right <= rootWidthPx + 1f)
            }
            compose.onAllNodesWithText("family dinner").assertCountEquals(1)
            saveUiEvidence("design-4-search-font200-$theme.png")
        }
    }

    /** Visible (drawn) height of the first [tag] node, scanned on the root image. */
    private fun visibleHeightPx(tag: String, density: Float): Int {
        val node = compose.onAllNodesWithTag(tag).onFirst().fetchSemanticsNode()
        val b = node.boundsInRoot
        val root = compose.onRoot().captureToImage().asAndroidBitmap()
        val x = b.center.x.toInt().coerceIn(0, root.width - 1)
        val margin = (6 * density).toInt()
        val top = (b.top.toInt() - margin).coerceAtLeast(0)
        val bottom = (b.bottom.toInt() + margin).coerceAtMost(root.height - 1)
        val bg = root.getPixel(x, top)
        fun differs(c: Int) = listOf(16, 8, 0).any { shift ->
            kotlin.math.abs(((c shr shift) and 0xFF) - ((bg shr shift) and 0xFF)) > 6
        }
        val first = (top..bottom).first { differs(root.getPixel(x, it)) }
        val last = (bottom downTo top).first { differs(root.getPixel(x, it)) }
        return last - first + 1
    }

    private fun boundsDp(tag: String, density: Float): Float =
        compose.onAllNodesWithTag(tag).onFirst().fetchSemanticsNode().boundsInRoot.height / density

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
