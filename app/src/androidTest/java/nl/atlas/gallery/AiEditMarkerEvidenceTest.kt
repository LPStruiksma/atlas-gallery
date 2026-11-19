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
import androidx.test.platform.app.InstrumentationRegistry
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import kotlinx.coroutines.CompletableDeferred
import nl.atlas.gallery.testkit.awaitThat
import nl.atlas.gallery.testkit.exists
import nl.atlas.gallery.featurecuration.hashCurationBytes
import nl.atlas.gallery.testkit.tap
import org.junit.Assert.assertEquals
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
    private var reader by mutableStateOf(LineageAiEditDetailsReader)

    @Test fun markerStatesAcrossThemesTextSizesAndMissingOriginal() {
        val records = fixtures.records
        val (original, editOne, editTwo, plain) = records
        shown = records
        showGallery(records)

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
        assertReadingOrder(AiEditProvenanceMediaFixtures.EDIT_ONE)
        saveUiEvidence("aie-26-edit-details-light.png")

        // A, dark, 1.3x text: still visible in the partially opened sheet, same order.
        compose.runOnIdle { dark = true; fontScale = 1.3f }
        openDetails(editOne.uri)
        compose.waitForNode(hasTestTag("atlas-details-ai-edited"))
        assertMarkerOrder(AiEditProvenanceMediaFixtures.EDIT_ONE, expectEditedFrom = true)
        assertReadingOrder(AiEditProvenanceMediaFixtures.EDIT_ONE)
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

    /**
     * Brief fallback states: while lineage is still loading, and when reading it
     * fails (a revoked grant or a broken store), Details are ordinary, carry no
     * marker and still close with Back. When loading finishes the marker is
     * added in place without moving the Name row.
     */
    @Test fun loadingAndFailedLineageShowOrdinaryDetailsAndStillClose() {
        val records = fixtures.records
        val editOne = records[1]
        shown = records
        val gate = CompletableDeferred<Unit>()
        reader = AiEditDetailsReader { context, uri ->
            gate.await()
            LineageAiEditDetailsReader.read(context, uri)
        }
        showGallery(records)

        // Loading: ordinary Details at once, no marker, no placeholder.
        openDetails(editOne.uri)
        compose.waitForNode(hasText("Resolution"))
        val nameRow = hasText(AiEditProvenanceMediaFixtures.EDIT_ONE) and
            hasAnyAncestor(hasTestTag("atlas-viewer-details"))
        compose.waitForNode(nameRow)
        compose.onNodeWithTag("atlas-details-ai-edited").assertDoesNotExist()
        compose.onNodeWithText(AI_EDITED_LABEL).assertDoesNotExist()
        compose.waitForIdle()
        saveUiEvidence("aie-26-edit-details-loading.png")
        val nameTop = compose.onNode(nameRow).fetchSemanticsNode().boundsInRoot.top

        // Loaded: the marker appears in place; the Name row does not move.
        gate.complete(Unit)
        compose.waitForNode(hasTestTag("atlas-details-ai-edited"))
        compose.waitForIdle()
        assertEquals(nameTop, compose.onNode(nameRow).fetchSemanticsNode().boundsInRoot.top)
        assertReadingOrder(AiEditProvenanceMediaFixtures.EDIT_ONE)

        // Failure (what a revoked MediaStore grant or a broken store throws).
        compose.runOnIdle {
            reader = AiEditDetailsReader { _, _ -> throw SecurityException("synthetic revoked grant") }
        }
        openDetails(editOne.uri)
        compose.waitForNode(hasText("Resolution"))
        compose.waitForNode(nameRow)
        compose.onNodeWithTag("atlas-details-ai-edited").assertDoesNotExist()
        compose.onNodeWithText("Edited from…").assertDoesNotExist()
        compose.waitForIdle()
        saveUiEvidence("aie-26-edit-details-lineage-error.png")

        // Close stays usable: system Back dismisses the sheet, the viewer remains.
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.awaitThat("Details closed by Back") {
            !compose.exists(hasTestTag("atlas-viewer-details"))
        }
        compose.onNodeWithTag("atlas-viewer-details-open").assertExists()
    }

    private fun showGallery(records: List<MediaRecord>) {
        val grant = GalleryMediaGrant(images = true, videos = false, selected = false)
        val tokens = requireNotNull(photoDnaTokensOrNull(grant, 458L, true, records))
        val result = planPhotoDna(records, tokens, hash = { record ->
            hashCurationBytes(record.sizeBytes,
                openAuthorized = { fixtures.bytes[record.uri]?.let(::ByteArrayInputStream) },
                stillAuthorized = { true })
        })
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale),
                LocalAiEditDetailsReader provides reader) {
                // Every state change restarts the screen so no sheet state leaks between shots.
                key(dark, fontScale, opened, shown, reader) {
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

    /**
     * TalkBack follows the sheet's semantics tree order: Name, the file name,
     * then the marker read as the whole phrase (not a button), then Edited from….
     */
    private fun assertReadingOrder(name: String) {
        val root = compose.onNode(hasTestTag("atlas-viewer-details"), useUnmergedTree = true)
            .fetchSemanticsNode()
        val texts = mutableListOf<String>()
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { texts += it.text }
            node.children.forEach(::walk)
        }
        walk(root)
        val order = listOf("Name", name, AI_EDITED_LABEL, "Edited from…").map(texts::indexOf)
        assertTrue("reading order $order in $texts", order.all { it >= 0 } &&
            order.zipWithNext().all { (a, b) -> a < b })
        compose.onNodeWithTag("atlas-details-ai-edited")
            .assert(hasText(AI_EDITED_LABEL))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
    }
}
