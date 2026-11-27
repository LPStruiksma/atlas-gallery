package nl.atlas.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.atlas.gallery.featuredesign.AtlasFirstScanLoading
import nl.atlas.gallery.featuredesign.AtlasGalleryLoading
import nl.atlas.gallery.testkit.captureWhole
import nl.atlas.gallery.testkit.withAnimatorScale
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import org.junit.Assert.assertTrue
import nl.atlas.gallery.featuredesign.AtlasGalleryTheme
import nl.atlas.gallery.featuredesign.AtlasLoadingArc
import nl.atlas.gallery.featureorganization.SelectionCheckCircle
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * BRAND-L4 (#657): the held sphere's selection and loading states on S24+,
 * light and dark. Selection: lapis sphere with a 2 dp white halo, a tick or
 * the pick number when ordered, over synthetic colour tiles. Loading: the
 * sphere on its arc beside a plain count (0 %, 50 %, 100 %, and the quiet
 * gallery loading line). Synthetic colours only.
 */
@RunWith(AndroidJUnit4::class)
class HeldSphereEvidenceTest {
    private val compose = createComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(compose)

    @Test fun selectionAndLoadingSpheresInBothThemes() {
        var dark by mutableStateOf(false)
        compose.setContent {
            AtlasGalleryTheme(dark = dark) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                    .padding(16.dp)) {
                    Text("Selection", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        listOf(null, 1, 2, 3).forEachIndexed { i, order ->
                            Box(Modifier.size(96.dp)
                                .background(Color.hsv((i * 70f + 20f) % 360f, .5f, .8f))) {
                                SelectionCheckCircle(true, Modifier.align(Alignment.TopStart)
                                    .padding(5.dp), order = order)
                            }
                        }
                        Box(Modifier.size(96.dp).background(Color.hsv(300f, .5f, .8f))) {
                            SelectionCheckCircle(false, Modifier.align(Alignment.TopStart).padding(5.dp))
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    Text("Loading", style = MaterialTheme.typography.titleMedium)
                    for ((progress, line) in listOf(
                        0f to "Finding faces, 0 of 12.305",
                        .58f to "Finding faces, 7.140 of 12.305",
                        1f to "Finding faces, 12.305 of 12.305"
                    )) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            AtlasLoadingArc(progress, size = 28.dp,
                                tag = "held-arc-${(progress * 100).toInt()}")
                            Spacer(Modifier.width(10.dp))
                            Text(line, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(200.dp)) {
                        AtlasGalleryLoading(bottomInset = 0.dp)
                    }
                    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        AtlasFirstScanLoading()
                    }
                }
            }
        }
        for (theme in listOf(false, true)) {
            compose.runOnIdle { dark = theme }
            compose.waitUntil(5_000L) {
                compose.onAllNodesWithTag("atlas-quiet-loading-line").fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(4, compose.onAllNodesWithTag("atlas-selection-sphere", useUnmergedTree = true)
                .fetchSemanticsNodes().size)
            compose.onNodeWithTag("atlas-quiet-loading").assertExists()
            // Progress semantics survive the custom arc: a range when known…
            for (percent in listOf(0, 58, 100)) {
                val range = compose.onNodeWithTag("held-arc-$percent", useUnmergedTree = true)
                    .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
                assertEquals(percent / 100f, range.current, 0.01f)
                assertEquals(0f..1f, range.range)
            }
            // …and indeterminate progress for the quiet loading line and the first scan.
            for (tag in listOf("atlas-loading-arc", "atlas-first-scan")) {
                compose.onNodeWithTag(tag, useUnmergedTree = true)
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,
                        ProgressBarRangeInfo.Indeterminate))
            }
            compose.waitForIdle()
            saveUiEvidence("l4-657-spheres-${if (theme) "dark" else "light"}.png")
        }
    }

    /**
     * With the system "remove animations" setting on, the loading sphere
     * rests: two captures 800 ms apart are the same picture. With
     * animations on it climbs, so the same captures differ.
     */
    @Test fun loadingSphereRestsWhenAnimationsAreRemoved() {
        withAnimatorScale("0") {
            compose.setContent {
                AtlasGalleryTheme(dark = false) {
                    // Full screen, so the evidence capture shows the resting
                    // sphere and its count line, not an empty window.
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        AtlasLoadingArc(progress = null, size = 160.dp, tag = "still-arc")
                        Spacer(Modifier.height(16.dp))
                        Text("Finding faces, 7.140 of 12.305",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            compose.waitForIdle()
            val first = compose.onNodeWithTag("still-arc").captureWhole().asAndroidBitmap()
            compose.mainClock.advanceTimeBy(800L)
            compose.waitForIdle()
            val second = compose.onNodeWithTag("still-arc").captureWhole().asAndroidBitmap()
            assertTrue("the loading sphere moved with animations removed", first.sameAs(second))
            compose.onNodeWithTag("still-arc", useUnmergedTree = true)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,
                    ProgressBarRangeInfo.Indeterminate))
            saveUiEvidence("l4-657-loading-reduced-motion.png")
        }
    }
}
