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
import androidx.compose.ui.layout.onSizeChanged
import kotlin.math.abs
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.atlas.gallery.featuredesign.AtlasDestination
import nl.atlas.gallery.featuredesign.AtlasGalleryNavigation
import nl.atlas.gallery.featuredesign.AtlasGalleryTheme
import nl.atlas.gallery.featuredesign.AtlasTokens
import nl.atlas.gallery.featuredesign.navigationSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * BRAND-L4 (#657, #413): S24+ full-device proof of the Lapis slab: Pictures,
 * Collections and the search sphere stay in ONE centred slab at 100, 130 and
 * 200 % font in both themes; every target is at least 48 dp and no two
 * overlap; each label stays inside its own pill; People keeps Collections
 * current (it is a Collections section, not a place).
 */
@RunWith(AndroidJUnit4::class)
class NavigationFontScaleEvidenceTest {
    private val compose = createComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(compose)

    private val targets = listOf("photos", "albums", "search")

    @Test fun peopleRouteKeepsCollectionsCurrent() {
        assertEquals(AtlasDestination.ALBUMS, navigationSelection(AtlasDestination.PEOPLE))
        listOf(AtlasDestination.PHOTOS, AtlasDestination.ALBUMS, AtlasDestination.SEARCH)
            .forEach { assertEquals(it, navigationSelection(it)) }
    }

    @Test fun slabKeepsThreeSeparateTargetsInOneCentredSlabAt100130And200PercentInBothThemes() {
        var fontScale by mutableFloatStateOf(1f)
        var dark by mutableStateOf(false)
        var selected by mutableStateOf(AtlasDestination.PHOTOS)
        var minTargetPx = 0f
        var screenWidthPx = 0f
        compose.setContent {
            val density = LocalDensity.current
            minTargetPx = with(density) { AtlasTokens.Spacing.MinimumTarget.toPx() }
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                AtlasGalleryTheme(dark = dark) {
                    Box(Modifier.fillMaxSize().onSizeChanged { screenWidthPx = it.width.toFloat() }) {
                        AtlasGalleryNavigation(
                            selected = selected,
                            onSelect = {},
                            modifier = Modifier.align(Alignment.BottomCenter)
                        )
                    }
                }
            }
        }

        for (themeDark in listOf(false, true)) {
            for ((scale, suffix, choice) in listOf(
                Triple(1f, "100", AtlasDestination.PHOTOS),
                Triple(1.3f, "130", AtlasDestination.PHOTOS),
                Triple(2f, "200", AtlasDestination.PHOTOS),
                Triple(2f, "200", AtlasDestination.ALBUMS),
                Triple(2f, "200", AtlasDestination.PEOPLE),
                Triple(2f, "200", AtlasDestination.SEARCH)
            )) {
                compose.runOnIdle { dark = themeDark; fontScale = scale; selected = choice }
                compose.waitForIdle()
                val current = navigationSelection(choice).name.lowercase()
                for (name in targets) {
                    val tab = compose.onNodeWithTag("atlas-nav-$name", useUnmergedTree = true)
                    if (name == current) tab.assertIsSelected() else tab.assertIsNotSelected()
                }
                compose.onNodeWithTag("atlas-nav-people", useUnmergedTree = true).assertDoesNotExist()
                val where = "at $suffix% ${if (themeDark) "dark" else "light"} with $choice"
                val slab = compose.onNodeWithTag("atlas-nav-pill", useUnmergedTree = true)
                    .fetchSemanticsNode().boundsInRoot
                // Short and centred: equal margins left and right, inside the screen.
                assertTrue("slab off-centre $where: $slab in $screenWidthPx",
                    abs(slab.left - (screenWidthPx - slab.right)) <= 2f)
                assertTrue("slab escapes screen $where", slab.left >= 0f && slab.right <= screenWidthPx)
                val boxes = targets.map { name ->
                    val box = compose.onNodeWithTag("atlas-nav-$name", useUnmergedTree = true)
                        .fetchSemanticsNode().boundsInRoot
                    assertContained("target $name $where", slab, box)
                    assertTrue("target $name under 48 dp $where: $box",
                        box.width >= minTargetPx - 1f && box.height >= minTargetPx - 1f)
                    box
                }
                for (i in boxes.indices) for (j in i + 1 until boxes.size) {
                    val overlap = boxes[i].intersect(boxes[j])
                    assertTrue("targets ${targets[i]} and ${targets[j]} overlap $where",
                        overlap.width <= 0.5f || overlap.height <= 0.5f)
                }
                for ((name, box) in targets.zip(boxes).take(2)) {
                    val label = compose.onNodeWithTag("atlas-nav-label-$name",
                        useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                    assertContained("label $name inside its pill $where", box, label)
                }
                val state = if (choice == AtlasDestination.PHOTOS) "" else "-" + choice.name.lowercase()
                saveUiEvidence("nav-413-font$suffix$state-${if (themeDark) "dark" else "light"}.png")
            }
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
