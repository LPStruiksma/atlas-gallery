package nl.atlas.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.atlas.gallery.featuredesign.AtlasDestination
import nl.atlas.gallery.featuredesign.AtlasGalleryNavigation
import nl.atlas.gallery.featuredesign.AtlasGalleryTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** #413: S24+ full-device proof of label containment in the compact floating pill. */
@RunWith(AndroidJUnit4::class)
class NavigationFontScaleEvidenceTest {
    private val compose = createComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(compose)

    @Test fun allFourLabelsRemainInsidePillAt100130And200PercentInBothThemes() {
        var fontScale by mutableFloatStateOf(1f)
        var dark by mutableStateOf(false)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                AtlasGalleryTheme(dark = dark) {
                    Box(Modifier.fillMaxSize()) {
                        AtlasGalleryNavigation(
                            selected = AtlasDestination.SEARCH,
                            onSelect = {},
                            modifier = Modifier.align(Alignment.BottomCenter)
                        )
                    }
                }
            }
        }

        for (themeDark in listOf(false, true)) {
            val labelHeights = mutableMapOf<String, Float>()
            for ((scale, suffix) in listOf(
                1f to "100", 1.3f to "130", 2f to "200"
            )) {
                compose.runOnIdle { dark = themeDark; fontScale = scale }
                compose.waitForIdle()
                val pill = compose.onNodeWithTag("atlas-nav-pill", useUnmergedTree = true)
                    .fetchSemanticsNode().boundsInRoot
                val labels = AtlasDestination.entries.map { destination ->
                    val name = destination.name.lowercase()
                    val tab = compose.onNodeWithTag("atlas-nav-$name", useUnmergedTree = true)
                        .fetchSemanticsNode().boundsInRoot
                    val label = compose.onNodeWithTag("atlas-nav-label-$name",
                        useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                    assertContained("label $name at $suffix% ${if (themeDark) "dark" else "light"}",
                        pill, label)
                    assertContained("label $name inside tab at $suffix%", tab, label)
                    label
                }
                // The cap covers the whole line box, not only the glyphs: an
                // uncapped sp line height pushes the label onto the pill edge.
                labelHeights[suffix] = labels.maxOf { it.height }
                for (i in 0 until labels.lastIndex) {
                    assertTrue("adjacent labels overlap at $suffix%: ${labels[i]}, ${labels[i + 1]}",
                        labels[i].right <= labels[i + 1].left + 1f)
                }
                saveUiEvidence("nav-413-font$suffix-${if (themeDark) "dark" else "light"}.png")
            }
            assertTrue("200% label line box ${labelHeights["200"]} grows past 130% " +
                "${labelHeights["130"]} (${if (themeDark) "dark" else "light"})",
                labelHeights.getValue("200") <= labelHeights.getValue("130") + 1f)
        }
    }

    private fun assertContained(what: String, parent: Rect, child: Rect) {
        val tolerance = 1f
        assertTrue("$what escapes bounds: parent=$parent child=$child",
            child.left >= parent.left - tolerance &&
                child.top >= parent.top - tolerance &&
                child.right <= parent.right + tolerance &&
                child.bottom <= parent.bottom + tolerance)
    }
}
