package nl.atlas.gallery

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.atlas.gallery.featurecuration.hashCurationBytes
import nl.atlas.gallery.testkit.tap
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream

/**
 * AIE-26 / #458 design brief states A/B/C on the real viewer and Photo DNA,
 * S24+ 1080x2340: A saved edit's Details show "Edited with AI" between Name
 * and "Edited from…"; B the original shows "AI versions (n)" and no marker;
 * C each saved-edit tile reads "Edited with AI" and the original "Original".
 * Light and dark, normal and 1.3x text, a plain photo with no lineage, and a
 * saved edit whose original was deleted (marker kept, no invented family).
 * Synthetic PNGs and app-private lineage rows only.
 */
@RunWith(AndroidJUnit4::class)
class AiEditMarkerEvidenceTest {
    private val fixtures = AiEditProvenanceMediaFixtures()
    private val compose = createComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule())
        .around(fixtures).around(compose)

    private var dark by mutableStateOf(false)
    private var fontScale by mutableStateOf(1f)
    private var opened by mutableStateOf<String?>(null)
    private var shown by mutableStateOf<List<MediaRecord>>(emptyList())

    @Test fun markerStatesAcrossThemesTextSizesAndMissingOriginal() {
        val records = fixtures.records
        val (original, editOne, editTwo, plain) = records
        shown = records
        val grant = GalleryMediaGrant(images = true, videos = false, selected = false)
        val tokens = requireNotNull(photoDnaTokensOrNull(grant, 458L, true, records))
        val result = planPhotoDna(records, tokens, hash = { record ->
            hashCurationBytes(record.sizeBytes,
                openAuthorized = { fixtures.bytes[record.uri]?.let(::ByteArrayInputStream) },
                stillAuthorized = { true })
        })
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                // Every state change restarts the screen so no sheet state leaks between shots.
                key(dark, fontScale, opened, shown) {
                    AtlasEvidenceHarness(edgeToEdge = true, dark = dark) {
                        val photo = opened
                        if (photo == null) {
                            PhotoDnaRoute(result = result, verifying = false, onBack = {},
                                onOpenPhoto = { opened = it }, onReviewRequested = {},
                                accessible = true)
                        } else {
                            GalleryViewer(shown, photo, onBack = { opened = null })
                        }
                    }
                }
            }
        }

        // C, light: the original tile is "Original"; each saved edit reads "Edited with AI".
        photoDnaFamily()
        compose.onNode(hasTestTag("photo-dna-provenance-original") and
            hasContentDescription("Original")).assertIsDisplayed()
        compose.onNode(hasTestTag("photo-dna-provenance-edit-${editOne.id}") and
            hasContentDescription(AI_EDITED_LABEL)).assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText(AI_EDITED_LABEL).fetchSemanticsNodes().size >= 1)
        saveUiEvidence("aie-26-photo-dna-light.png")

        // A, light, normal text: marker sits between Name and "Edited from…".
        openDetails(editOne.uri)
        compose.waitForNode(hasTestTag("atlas-details-ai-edited"))
        assertMarkerOrder(AiEditProvenanceMediaFixtures.EDIT_ONE, expectEditedFrom = true)
        saveUiEvidence("aie-26-edit-details-light.png")

        // A, dark, 1.3x text: still visible in the partially opened sheet, same order.
        compose.runOnIdle { dark = true; fontScale = 1.3f }
        openDetails(editOne.uri)
        compose.waitForNode(hasTestTag("atlas-details-ai-edited"))
        assertMarkerOrder(AiEditProvenanceMediaFixtures.EDIT_ONE, expectEditedFrom = true)
        saveUiEvidence("aie-26-edit-details-dark-large-text.png")

        // B, dark, 1.3x text: the original lists its versions and is never marked.
        openDetails(original.uri)
        compose.waitForNode(hasText("AI versions (2)"))
        compose.onNodeWithTag("atlas-details-ai-edited").assertDoesNotExist()
        compose.onNodeWithText(AI_EDITED_LABEL).assertDoesNotExist()
        saveUiEvidence("aie-26-original-details-dark-large-text.png")

        // C, dark.
        compose.runOnIdle { fontScale = 1f; opened = null }
        photoDnaFamily()
        saveUiEvidence("aie-26-photo-dna-dark.png")

        // No lineage: ordinary Details, no marker and no empty placeholder.
        openDetails(plain.uri)
        compose.waitForNode(hasText(AiEditProvenanceMediaFixtures.PLAIN))
        compose.waitForNode(hasText("Resolution"))
        compose.onNodeWithTag("atlas-details-ai-edited").assertDoesNotExist()
        compose.onNodeWithText("Edited from…").assertDoesNotExist()
        compose.onNodeWithText("AI versions", substring = true).assertDoesNotExist()

        // Missing original: the saved edit keeps its own label; no "Edited from…".
        fixtures.deleteOriginal()
        compose.runOnIdle { shown = records.drop(1) }
        openDetails(editTwo.uri)
        compose.waitForNode(hasTestTag("atlas-details-ai-edited"))
        compose.waitForNode(hasText("Resolution"))
        compose.onNodeWithText(AI_EDITED_LABEL).assertIsDisplayed()
        compose.onNodeWithText("Edited from…").assertDoesNotExist()
        saveUiEvidence("aie-26-edit-details-missing-original.png")
    }

    private fun photoDnaFamily() {
        val family = hasTestTag("photo-dna-provenance-group") and
            hasAnyDescendant(hasText("Original + AI versions (2)"))
        compose.waitForNode(hasText("AI edits (1)"))
        compose.onNodeWithTag("photo-dna-list").performScrollToNode(family)
        compose.onNodeWithTag("photo-dna-provenance-tiles").performScrollToIndex(0)
        compose.onNode(family).assertIsDisplayed()
        compose.waitForIdle()
    }

    private fun openDetails(uri: String) {
        compose.runOnIdle { opened = uri }
        compose.waitForNode(hasTestTag("atlas-viewer-details-open"))
        compose.tap(hasTestTag("atlas-viewer-details-open"))
        compose.waitForNode(hasTestTag("atlas-viewer-details"))
    }

    private fun assertMarkerOrder(name: String, expectEditedFrom: Boolean) {
        // The viewer header may show the filename too; measure the sheet's Name row.
        val nameTop = compose.onNode(hasText(name) and
            hasAnyAncestor(hasTestTag("atlas-viewer-details"))).assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top
        val marker = compose.onNodeWithText(AI_EDITED_LABEL).assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        assertTrue("marker below Name", marker.top > nameTop)
        if (expectEditedFrom) {
            compose.waitForNode(hasText("Edited from…"))
            val editedFrom = compose.onNodeWithText("Edited from…")
                .fetchSemanticsNode().boundsInRoot.top
            assertTrue("marker above Edited from…", marker.bottom <= editedFrom)
        }
    }
}
