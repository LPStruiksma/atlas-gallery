package nl.atlas.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.atlas.gallery.featuredesign.AtlasGalleryTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * PERF-0 (#121, design brief revision 2): Settings → Advanced → Performance in
 * the brief's state matrix, at the S24+ size. The report reads the aggregates
 * passed in, so every capture is deterministic: synthetic numbers only, no
 * media, no People data. Light and dark × populated and no data, plus 200% font.
 */
@RunWith(AndroidJUnit4::class)
class PerformanceSettingsJourneyTest {
    private val compose = createComposeRule()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(compose)

    private val overviewTags = listOf("cold_start_ms", "viewer_ms", "grid_frame_ms",
        "face_photos_min", "visual_photos_min", "heap_fraction").map { "atlas-perf-$it" }

    @Test fun performanceReportStateMatrix() {
        var dark by mutableStateOf(false)
        var fontScale by mutableStateOf(1f)
        var data by mutableStateOf(PerformanceAggregates())
        var generation by mutableIntStateOf(0)
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                AtlasGalleryTheme(dark = dark) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background,
                        contentColor = MaterialTheme.colorScheme.onBackground) {
                        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                            // A new generation starts collapsed, like a fresh Settings visit.
                            key(generation) {
                                AtlasSettingsScreen(
                                    scanPhase = null, onPauseScan = null, onResumeScan = null,
                                    visualIndexOn = null, showPhotoDetails = false,
                                    versionName = "0.0.0-test", onVisualIndex = {},
                                    onPeopleNumbers = {}, onPhotoDetails = {}, onImportV4 = {},
                                    onLinkV4 = {}, onDiagnostics = {}, performance = data)
                            }
                        }
                    }
                }
            }
        }

        for (populated in listOf(true, false)) {
            for (isDark in listOf(false, true)) {
                compose.runOnIdle {
                    dark = isDark; fontScale = 1f
                    data = if (populated) syntheticAggregates() else PerformanceAggregates()
                    generation++
                }
                openReport()
                val theme = if (isDark) "dark" else "light"
                val state = if (populated) "populated" else "empty"

                // One viewport from the Performance heading shows all six headline rows.
                scrollToTop("atlas-settings-performance")
                val viewportBottom = compose.onNodeWithTag("atlas-settings-screen")
                    .fetchSemanticsNode().boundsInRoot.bottom
                overviewTags.forEach { tag ->
                    compose.onNodeWithTag(tag).assertIsDisplayed()
                    val bottom = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.bottom
                    assertTrue("$tag below the first viewport ($bottom > $viewportBottom)",
                        bottom <= viewportBottom + 1f)
                }
                if (!populated) {
                    assertEquals(7 + PerformanceReport.INSTRUMENTED_ACTIONS,
                        compose.onAllNodesWithText(PerformanceReport.NO_DATA)
                            .fetchSemanticsNodes().size)
                }
                saveUiEvidence("perf-0-overview-$theme-$state.png")

                scrollToTop("atlas-perf-actions-heading")
                compose.onNodeWithTag("atlas-perf-action_a22_ms").performScrollTo()
                    .assertIsDisplayed()
                scrollToTop("atlas-perf-actions-heading")
                saveUiEvidence("perf-0-actions-$theme-$state.png")
            }
        }

        // 200% font: labels stay whole and values wrap under them; the overview may scroll.
        compose.runOnIdle {
            dark = false; fontScale = 2f; data = syntheticAggregates(); generation++
        }
        openReport()
        scrollToTop("atlas-settings-performance")
        val rootRight = compose.onRoot().fetchSemanticsNode().boundsInRoot.right
        overviewTags.forEach { tag ->
            val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag clipped at 200%: $bounds", bounds.right <= rootRight + 1f)
        }
        saveUiEvidence("perf-0-overview-light-populated-font200.png")
    }

    private fun openReport() {
        compose.waitForNode(hasTestTag("atlas-settings-advanced"))
        compose.onNodeWithTag("atlas-settings-advanced").performScrollTo().performClick()
        compose.waitForNode(hasTestTag("atlas-settings-performance"))
        compose.onNodeWithTag("atlas-settings-performance").performScrollTo().performClick()
        compose.waitForNode(hasTestTag("atlas-perf-cold_start_ms"))
    }

    /** Scrolls the Settings list so [tag]'s top sits at the top of the viewport. */
    private fun scrollToTop(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo()
        compose.waitForIdle()
        val screenTop = compose.onNodeWithTag("atlas-settings-screen")
            .fetchSemanticsNode().boundsInRoot.top
        val nodeTop = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("atlas-settings-screen")
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, nodeTop - screenTop) }
        compose.waitForIdle()
    }

    /** Fixed synthetic numbers: not a measurement of any device. */
    private fun syntheticAggregates() = PerformanceAggregates().apply {
        listOf(380.0, 412.0, 455.0, 690.0).forEach { record("cold_start_ms", it) }
        listOf(64.0, 80.0, 120.0).forEach { record("viewer_ms", it) }
        listOf(8.3, 9.0, 12.0, 18.0, 33.0).forEach { record("grid_frame_ms", it) }
        listOf(0.0, 0.0, 0.0, 0.0, 1.0).forEach { record("grid_frame_over_budget", it) }
        listOf(41.0, 45.0, 52.0).forEach { record("face_photos_min", it) }
        listOf(58.0, 63.0, 70.0).forEach { record("visual_photos_min", it) }
        listOf(0.30, 0.38, 0.41).forEach { record("heap_fraction", it) }
        listOf(380.0, 412.0, 690.0).forEach { record("action_a1_ms", it) }
        listOf(64.0, 80.0).forEach { record("action_a3_ms", it) }
        listOf(140.0, 210.0).forEach { record("action_a9_ms", it) }
    }
}
