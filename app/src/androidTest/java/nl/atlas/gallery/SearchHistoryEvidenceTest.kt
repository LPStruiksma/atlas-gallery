package nl.atlas.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.atlas.gallery.featuredesign.AtlasDestination
import nl.atlas.gallery.featuredesign.AtlasGalleryNavigation
import nl.atlas.gallery.featuredesign.AtlasGalleryTheme
import nl.atlas.gallery.featuredesign.AtlasTabPillHeight
import nl.atlas.gallery.featuresearch.AtlasSearch
import nl.atlas.gallery.featuresearch.AtlasSearchScope
import nl.atlas.gallery.featuresearch.EphemeralSearchHistory
import nl.atlas.gallery.featuresearch.LocalSearchControls
import nl.atlas.gallery.featuresearch.LocalSearchFacets
import nl.atlas.gallery.featuresearch.LocalSearchHistory
import nl.atlas.gallery.featuresearch.SearchGrantLevel
import nl.atlas.gallery.featuresearch.SearchHistoryController
import nl.atlas.gallery.featuresearch.SearchHistoryEntry
import nl.atlas.gallery.featuresearch.SearchHistoryFile
import nl.atlas.gallery.featuresearch.SearchHistoryScope
import nl.atlas.gallery.featuresearch.SearchHistorySnackbarHost
import nl.atlas.gallery.featuresearch.SearchHistoryStore
import nl.atlas.gallery.featuresearch.SearchPerson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * FIND-3a (#182): persistent recent searches at Galaxy S24+ size, light and
 * dark. Every name and query is invented; the store writes to memory only.
 * Renders are declared in scripts/ui-evidence.d/182-find3a-history.txt.
 */
@RunWith(AndroidJUnit4::class)
class SearchHistoryEvidenceTest {
    private val compose = createComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(compose)

    private val people = listOf(SearchPerson("p1", "Susanne de Vries"), SearchPerson("p2", "Daan"))

    private class MemoryFile : SearchHistoryFile {
        @Volatile var text: String? = null
        override fun read(): String? = text
        override fun write(text: String) { this.text = text }
    }

    private fun seededStore(): SearchHistoryStore = SearchHistoryStore(MemoryFile()).apply {
        load()
        update { state ->
            var s = state.onGrant(SearchGrantLevel.FULL)
            listOf(SearchHistoryEntry("2019"), SearchHistoryEntry("beach"),
                SearchHistoryEntry("snow"), SearchHistoryEntry("2019", listOf("p1")),
                SearchHistoryEntry("family dinner"), SearchHistoryEntry("Amsterdam"),
                SearchHistoryEntry("", listOf("p2")),
                // A person with no name any more: never shown.
                SearchHistoryEntry("old", listOf("gone"))
            ).forEach { s = s.remember(SearchHistoryScope.GLOBAL, it) }
            s.remember(SearchHistoryScope.ALBUM, SearchHistoryEntry("cat"))
                .remember(SearchHistoryScope.ALBUM, SearchHistoryEntry("garden"))
        }
    }

    @Test fun globalRecentsRemoveUndoAndLargeFont() {
        val store = seededStore()
        var darkTheme by mutableStateOf(false)
        var fontScale by mutableStateOf(1f)
        var reset by mutableStateOf(0)
        var clearInputFocus: (() -> Unit)? = null
        compose.setContent {
            val focus = LocalFocusManager.current
            val keyboard = LocalSoftwareKeyboardController.current
            clearInputFocus = { focus.clearFocus(force = true); keyboard?.hide() }
            val state = store.state.collectAsState()
            val scope = rememberCoroutineScope()
            val snackbar = remember { SnackbarHostState() }
            val history = remember {
                SearchHistoryController(store, state, scope, snackbar) { setOf("p1", "p2") }
            }
            var filters by remember(reset) { mutableStateOf(LocalSearchFacets()) }
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                HistoryFrame(darkTheme, history) {
                    Scaffold(containerColor = MaterialTheme.colorScheme.background,
                        bottomBar = { AtlasGalleryNavigation(AtlasDestination.SEARCH, onSelect = {}) }
                    ) { padding ->
                        Column(Modifier.fillMaxSize().padding(padding).padding(top = 12.dp)
                            .imePadding()) {
                            LocalSearchControls(
                                filters = filters, people = emptyList(),
                                history = EphemeralSearchHistory(permissionEpoch = "synthetic"),
                                onlyTheseFacesAvailable = false,
                                onFiltersChange = { filters = it },
                                onSubmittedQuery = {},
                                onClearHistory = { history.clear(SearchHistoryScope.GLOBAL) },
                                currentPermissionEpoch = "synthetic",
                                namedPeople = people,
                                recentSearches = history.visible(SearchHistoryScope.GLOBAL),
                                onRemoveRecent = { history.remove(SearchHistoryScope.GLOBAL, it) },
                                onSubmittedSearch = { history.remember(SearchHistoryScope.GLOBAL, it) }
                            )
                        }
                    }
                }
            }
        }
        listOf(false, true).forEach { dark ->
            val theme = if (dark) "dark" else "light"
            compose.runOnIdle { darkTheme = dark; fontScale = 1f; reset++ }
            waitFor("atlas-search-recent")
            compose.runOnIdle { clearInputFocus?.invoke() }
            compose.waitForIdle()
            // 7 visible: the entry for an unnamed person stays hidden.
            compose.onAllNodesWithTag("atlas-search-recent").assertCountEquals(7)
            compose.onAllNodesWithText("Susanne de Vries 2019").assertCountEquals(1)
            compose.onAllNodesWithText("old").assertCountEquals(0)
            saveUiEvidence("find-3a-global-recents-$theme.png")

            // Keyboard open, remove "snow": the Undo snackbar sits above the IME.
            compose.onNodeWithTag("atlas-search-field").performClick()
            compose.waitForIdle()
            val snowRemove = compose.onAllNodes(hasTestTag("atlas-search-recent-remove"),
                useUnmergedTree = true).fetchSemanticsNodes()
                .indexOfFirst { node -> node.config.toString().contains("snow") }
            assertTrue("snow ✕ not found", snowRemove >= 0)
            compose.onAllNodes(hasTestTag("atlas-search-recent-remove"), useUnmergedTree = true)[snowRemove]
                .performClick()
            waitForText("Removed")
            compose.onAllNodesWithText("snow").assertCountEquals(0)
            saveUiEvidence("find-3a-global-removed-ime-$theme.png")
            compose.onNodeWithText("Undo").performClick()
            waitForText("snow")
            // Undo puts it back in its old place (behind the hidden entry at 0).
            assertEquals(5, store.state.value.entries(SearchHistoryScope.GLOBAL)
                .indexOfFirst { it.text == "snow" })

            // 200% font: pills wrap and stay whole.
            compose.runOnIdle { clearInputFocus?.invoke(); fontScale = 2f; reset++ }
            waitFor("atlas-search-recent")
            compose.waitForIdle()
            val rootRight = compose.onRoot().fetchSemanticsNode().boundsInRoot.right
            compose.onAllNodesWithTag("atlas-search-recent").fetchSemanticsNodes().forEach {
                assertTrue("pill clipped at 200%: ${it.boundsInRoot}",
                    it.boundsInRoot.left >= 0f && it.boundsInRoot.right <= rootRight + 1f)
            }
            saveUiEvidence("find-3a-global-font200-$theme.png")
        }
    }

    @Test fun emptyHistoryShowsNoBlock() {
        val store = SearchHistoryStore(MemoryFile()).apply { load() }
        var darkTheme by mutableStateOf(false)
        compose.setContent {
            val state = store.state.collectAsState()
            val scope = rememberCoroutineScope()
            val history = remember {
                SearchHistoryController(store, state, scope, SnackbarHostState()) { emptySet() }
            }
            HistoryFrame(darkTheme, history) {
                Scaffold(containerColor = MaterialTheme.colorScheme.background,
                    bottomBar = { AtlasGalleryNavigation(AtlasDestination.SEARCH, onSelect = {}) }
                ) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).padding(top = 12.dp)) {
                        LocalSearchControls(
                            filters = LocalSearchFacets(), people = emptyList(),
                            history = EphemeralSearchHistory(permissionEpoch = "synthetic"),
                            onlyTheseFacesAvailable = false,
                            onFiltersChange = {}, onSubmittedQuery = {}, onClearHistory = {},
                            currentPermissionEpoch = "synthetic",
                            recentSearches = history.visible(SearchHistoryScope.GLOBAL)
                        )
                    }
                }
            }
        }
        listOf(false, true).forEach { dark ->
            compose.runOnIdle { darkTheme = dark }
            waitFor("atlas-search-field")
            compose.onAllNodesWithTag("atlas-search-recents").assertCountEquals(0)
            saveUiEvidence("find-3a-empty-${if (dark) "dark" else "light"}.png")
        }
    }

    @Test fun scopedAlbumHasItsOwnRecents() {
        val store = seededStore()
        var darkTheme by mutableStateOf(false)
        var query by mutableStateOf("")
        compose.setContent {
            val state = store.state.collectAsState()
            val scope = rememberCoroutineScope()
            val snackbar = remember { SnackbarHostState() }
            val history = remember {
                SearchHistoryController(store, state, scope, snackbar) { emptySet() }
            }
            CompositionLocalProvider(LocalSearchHistory provides history) {
                HistoryFrame(darkTheme, history) {
                    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
                        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            AtlasSearch(query = query, onQueryChange = { query = it },
                                persistentInline = true, initialExpanded = true,
                                historyScope = SearchHistoryScope.ALBUM,
                                initialScope = AtlasSearchScope.THIS_SCREEN)
                            // The album's own synthetic grid stays visible below.
                            LazyVerticalGrid(GridCells.Fixed(4),
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                items(16) { i ->
                                    Box(Modifier.aspectRatio(1f).background(
                                        Color(0xFF4E7D6B + (i * 0x0A0B07L) % 0x404040L)))
                                }
                            }
                        }
                    }
                }
            }
        }
        listOf(false, true).forEach { dark ->
            compose.runOnIdle { darkTheme = dark; query = "" }
            waitFor("atlas-search-recent")
            compose.waitForIdle()
            // Album recents only: nothing from global.
            compose.onAllNodesWithTag("atlas-search-recent").assertCountEquals(2)
            compose.onAllNodesWithText("beach").assertCountEquals(0)
            saveUiEvidence("find-3a-scoped-album-${if (dark) "dark" else "light"}.png")
        }
        compose.onNodeWithText("garden").performClick()
        compose.runOnIdle { assertEquals("garden", query) }
    }

    @Test fun settingsSwitchClearsEveryScopeAndUndoRestoresIt() {
        val store = seededStore()
        val before = store.state.value
        var darkTheme by mutableStateOf(false)
        compose.setContent {
            val state = store.state.collectAsState()
            val scope = rememberCoroutineScope()
            val snackbar = remember { SnackbarHostState() }
            val history = remember {
                SearchHistoryController(store, state, scope, snackbar) { emptySet() }
            }
            HistoryFrame(darkTheme, history) {
                Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
                    Box(Modifier.padding(padding)) {
                        AtlasSettingsScreen(
                            scanPhase = null, onPauseScan = null, onResumeScan = null,
                            visualIndexOn = null, showPhotoDetails = false, versionName = "test",
                            onVisualIndex = {}, onPeopleNumbers = {}, onPhotoDetails = {},
                            onImportV4 = {}, onLinkV4 = {}, onDiagnostics = {},
                            searchHistoryOn = state.value.enabled,
                            onSearchHistoryOn = { history.setEnabled(it) },
                            onClearSearchHistory = { history.clearAll() }
                        )
                    }
                }
            }
        }
        listOf(false, true).forEach { dark ->
            val theme = if (dark) "dark" else "light"
            compose.runOnIdle { darkTheme = dark }
            waitFor("atlas-settings-search-history")
            // The Search group sits below Advanced: bring it on screen first.
            compose.onNodeWithTag("atlas-settings-search-history-clear").performScrollTo()
            compose.waitForIdle()
            compose.onNodeWithText("Recent searches stay on this phone.").assertIsDisplayed()
            saveUiEvidence("find-3a-settings-on-$theme.png")
            compose.onNodeWithTag("atlas-settings-search-history").performClick()
            waitForText("Search history off")
            compose.runOnIdle {
                assertFalse(store.state.value.enabled)
                assertTrue(store.state.value.isEmpty)
            }
            saveUiEvidence("find-3a-settings-off-$theme.png")
            compose.onNodeWithText("Undo").performClick()
            compose.waitUntil(10_000L) { store.state.value.enabled }
            compose.runOnIdle { assertEquals(before, store.state.value) }
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

    private fun waitForText(text: String) {
        compose.waitUntil(30_000L) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }
}

/** As in MainActivity: the Undo snackbar is drawn over the app, above the pill or the IME. */
@Composable
private fun HistoryFrame(dark: Boolean, history: SearchHistoryController,
                         content: @Composable () -> Unit) {
    AtlasGalleryTheme(dark = dark) {
        Box(Modifier.fillMaxSize()) {
            content()
            SearchHistorySnackbarHost(history, Modifier.align(Alignment.BottomCenter),
                bottomClearance = AtlasTabPillHeight + 22.dp)
        }
    }
}
