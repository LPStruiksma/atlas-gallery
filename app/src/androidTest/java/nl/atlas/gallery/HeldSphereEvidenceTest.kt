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
import nl.atlas.gallery.featuredesign.AtlasGalleryLoading
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
                            AtlasLoadingArc(progress, size = 28.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(line, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(260.dp)) {
                        AtlasGalleryLoading(bottomInset = 0.dp)
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
            compose.waitForIdle()
            saveUiEvidence("l4-657-spheres-${if (theme) "dark" else "light"}.png")
        }
    }
}
