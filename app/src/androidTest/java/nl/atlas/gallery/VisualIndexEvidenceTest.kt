package nl.atlas.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.window.Dialog
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.atlas.gallery.featuredesign.AtlasGalleryTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * #631 rendered proof at the owner's Galaxy S24+ size (1080×2340): with a few
 * photos that have no fixed MediaStore ID, the Visual search index panel stays
 * on, counts the rest and says in plain words what was left out and why.
 * The numbers are invented; no photo is read.
 */
@RunWith(AndroidJUnit4::class)
class VisualIndexEvidenceTest {
    private val compose = createComposeRule()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(compose)

    @Test fun leftOutPhotosAreCountedAndTheSwitchStaysOn() {
        compose.setContent {
            AtlasGalleryTheme(dark = true) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize())
                    Dialog(onDismissRequest = {}) {
                        VisualSearchIndexPanel(
                            status = VisualIndexStatus(
                                enabled = true, mayEnable = true,
                                done = 18_204, total = 251_312, skipped = 3, leftOut = 46),
                            onToggle = {}, onDelete = {})
                    }
                }
            }
        }
        compose.waitUntil(20_000) {
            compose.onAllNodes(hasTestTag("atlas-visual-index-count"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("atlas-visual-index-switch").assertIsOn()
        assertTrue(compose.onAllNodesWithText("46 photos left out", substring = true)
            .fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodesWithText("provenance", substring = true)
            .fetchSemanticsNodes().isEmpty())
        compose.waitForIdle()
        saveUiEvidence("visual-index-left-out.png")
    }
}
