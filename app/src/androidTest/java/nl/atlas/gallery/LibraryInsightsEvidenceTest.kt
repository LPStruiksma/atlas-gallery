package nl.atlas.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.atlas.gallery.featuredesign.AtlasGalleryHeader
import nl.atlas.gallery.testkit.awaitScreenStill
import nl.atlas.gallery.featuredesign.AtlasGalleryTheme
import nl.atlas.gallery.featureinsights.InsightsRow
import nl.atlas.gallery.featureinsights.InsightsScope
import nl.atlas.gallery.featureinsights.InsightsType
import nl.atlas.gallery.featureinsights.InsightsUiState
import nl.atlas.gallery.featureinsights.LibraryInsights
import nl.atlas.gallery.featureinsights.LibraryInsightsScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.util.Locale

/**
 * INSIGHT-1 (#587): the real Library insights composables on a 1080x2340 S24+ display, in
 * both themes, from synthetic numbers only (no MediaStore, no personal media).
 */
@RunWith(AndroidJUnit4::class)
class LibraryInsightsEvidenceTest {
    private val compose = createComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(compose)

    private val gb = 1_000_000_000L

    private fun folder(name: String, bytes: Long, count: Int, volume: String = "external_primary") =
        InsightsRow(InsightsScope.Folder(volume, "Pictures/$name/"), name, "Pictures/$name/", bytes, count)

    private fun type(type: InsightsType, bytes: Long, count: Int) =
        InsightsRow(InsightsScope.Type(type), type.label, null, bytes, count)

    private fun year(year: Int?, bytes: Long, count: Int) =
        InsightsRow(InsightsScope.Year(year), year?.toString() ?: "Unknown date", null, bytes, count)

    private val sample = LibraryInsights(
        totalBytes = 48_200_000_000L, totalCount = 12_305,
        videoDurationMillis = (19 * 60 + 24) * 60_000L, videoDurationExact = true,
        folders = listOf(
            folder("Camera", 25_400_000_000L, 6_920), folder("Downloads", 8_900_000_000L, 2_100),
            folder("WhatsApp", 4_300_000_000L, 1_510), folder("Screenshots", 2_100_000_000L, 980),
            folder("Edited", 1_600_000_000L, 400), folder("Café Ünï", 900_000_000L, 395)),
        types = listOf(type(InsightsType.PHOTOS, 34_800_000_000L, 11_800),
            type(InsightsType.VIDEOS, 13_400_000_000L, 505),
            type(InsightsType.SCREENSHOTS, 2_100_000_000L, 980), type(InsightsType.RAW, 4_700_000_000L, 210)),
        years = listOf(year(2026, 12 * gb, 3_100), year(2025, 15 * gb, 4_000), year(2024, 9 * gb, 2_900),
            year(2023, 7 * gb, 1_900), year(2022, 3_200_000_000L, 300), year(null, 600_000_000L, 105))
    )

    private var dark by mutableStateOf(false)
    private var fontScale by mutableStateOf(1f)
    private var state by mutableStateOf<InsightsUiState>(InsightsUiState.Ready(sample))
    private var duplicateBytes by mutableStateOf<Long?>(null)
    private val opened = mutableListOf<InsightsRow>()
    private var retries = 0

    private fun show() {
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                AtlasGalleryTheme(dark = dark) {
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                        .statusBarsPadding()) {
                        AtlasGalleryHeader("Library insights", onBack = {}, backDescription = "Back to Settings")
                        LibraryInsightsScreen(state, onOpenRow = { opened += it }, onRetry = { retries++ },
                            possibleDuplicateBytes = duplicateBytes,
                            onReviewDuplicates = { }.takeIf { duplicateBytes != null },
                            locale = Locale.US)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.awaitScreenStill(name)
        saveUiEvidence(name)
    }

    private fun both(name: String, setup: () -> Unit = {}, scroll: (() -> Unit)? = null) {
        for (isDark in listOf(false, true)) {
            compose.runOnIdle { dark = isDark; setup() }
            compose.waitForIdle()
            scroll?.invoke()
            capture("insights-587-$name-${if (isDark) "dark" else "light"}")
        }
    }

    @Test fun firstViewRowsAndNavigation() {
        show()
        // Summary and the three largest folders are on the first screen.
        compose.onNodeWithTag("atlas-insights-total-size").assertIsDisplayed()
        compose.onNodeWithTag("atlas-insights-total-count").assertIsDisplayed()
        compose.onNodeWithTag("atlas-insights-duration").assertIsDisplayed()
        val rows = compose.onAllNodesWithTag("atlas-insights-row")
        // Folders are capped at 3 before "All folders"; every row is a >= 48 dp target.
        rows[0].assertHeightIsAtLeast(48.dp)
        rows[2].assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("atlas-insights-all-folders").assertIsDisplayed()
        both("first")

        compose.onAllNodesWithTag("atlas-insights-row").onFirst().performClick()
        assertEquals(sample.folders.first(), opened.single())
        opened.clear()

        // Types and Years scroll into reach; each opens its own scope.
        compose.onNodeWithTag("atlas-insights-list").performScrollToNode(row("Unknown date"))
        both("types", scroll = {
            compose.onNodeWithTag("atlas-insights-list").performScrollToNode(row("RAW"))
        })
        both("years", scroll = {
            compose.onNodeWithTag("atlas-insights-list").performScrollToNode(row("Unknown date"))
        })
        compose.onNode(row("Unknown date")).performClick()
        assertEquals(InsightsScope.Year(null), opened.single().scope)
    }

    @Test fun allFoldersExpandsInPlace() {
        show()
        compose.onNodeWithTag("atlas-insights-all-folders").performClick()
        compose.onNodeWithTag("atlas-insights-list").performScrollToNode(row("Café Ünï"))
        compose.onNodeWithTag("atlas-insights-all-folders").assertIsDisplayed()
        both("all-folders", scroll = {
            compose.onNodeWithTag("atlas-insights-list").performScrollToNode(row("Café Ünï"))
        })
    }

    @Test fun statesEmptyCheckingUpdatingErrorAndDuplicates() {
        show()
        compose.runOnIdle { state = InsightsUiState.Checking }
        both("checking")
        compose.onNodeWithTag("atlas-insights-checking").assertIsDisplayed()

        compose.runOnIdle { state = InsightsUiState.Ready(
            LibraryInsights(0, 0, 0, true, emptyList(), emptyList(), emptyList())) }
        both("empty")
        compose.onNodeWithTag("atlas-insights-empty").assertIsDisplayed()

        compose.runOnIdle { state = InsightsUiState.Ready(sample, updating = true) }
        both("updating")
        compose.onNodeWithTag("atlas-insights-updating").assertIsDisplayed()

        compose.runOnIdle { state = InsightsUiState.Failed(sample) }
        both("error-last-known")
        compose.onNodeWithTag("atlas-insights-retry").performClick()
        assertEquals(1, retries)

        compose.runOnIdle { state = InsightsUiState.Failed(null) }
        both("error")
        compose.onNodeWithTag("atlas-insights-retry").assertIsDisplayed()

        // Photo DNA only once a valid completed scan supplies an estimate.
        compose.runOnIdle { state = InsightsUiState.Ready(sample); duplicateBytes = 1_300_000_000L }
        both("duplicates", scroll = {
            compose.onNodeWithTag("atlas-insights-list").performScrollToNode(hasTestTag("atlas-insights-duplicate-space"))
        })
        compose.onNodeWithTag("atlas-insights-duplicate-review").assertIsDisplayed()
    }

    @Test fun hugeLibraryOf30000FoldersStaysLazyAndReachable() {
        val folders = (0 until 30_000).map { folder("Folder%05d".format(it), (30_000 - it) * 1_000_000L, 8) }
        val huge = LibraryInsights(
            totalBytes = folders.sumOf { it.bytes }, totalCount = 250_000, videoDurationMillis = 0,
            videoDurationExact = true, folders = folders,
            types = listOf(type(InsightsType.PHOTOS, 600 * gb, 250_000)),
            years = listOf(year(2025, 600 * gb, 250_000)))
        state = InsightsUiState.Ready(huge)
        show()
        compose.onNodeWithTag("atlas-insights-all-folders").performClick()
        compose.onNodeWithTag("atlas-insights-list").performScrollToNode(row("Folder29999"))
        assertTrue(compose.onAllNodesWithTag("atlas-insights-row").fetchSemanticsNodes().size < 100)
        both("huge", scroll = {
            compose.onNodeWithTag("atlas-insights-list").performScrollToNode(row("Folder29999"))
        })
    }

    @Test fun largeTextReflowsWithoutLosingRows() {
        fontScale = 1.3f
        show()
        compose.onAllNodesWithTag("atlas-insights-row").onFirst().assertHeightIsAtLeast(48.dp)
        both("font130")
        compose.runOnIdle { fontScale = 2f }
        compose.onNodeWithTag("atlas-insights-total-size").assertIsDisplayed()
        both("font200")
    }
}

/** Rows speak as one TalkBack stop, so tests find them by that description. */
private fun row(text: String) = hasContentDescription(text, substring = true)
