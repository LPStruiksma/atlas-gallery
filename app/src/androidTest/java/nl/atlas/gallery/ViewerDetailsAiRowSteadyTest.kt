package nl.atlas.gallery

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.awaitCancellation
import nl.atlas.gallery.featureaiedit.save.AiEditDetails
import nl.atlas.gallery.testkit.awaitThat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * #682 follow-up: grabbing the Details surface while it closes and pulling it
 * back up turns `loadMetadata` off and on again. The "Edited with AI" row must
 * stay put through that, not clear and wait for a second lineage read.
 * Synthetic record and reader only.
 */
@RunWith(AndroidJUnit4::class)
class ViewerDetailsAiRowSteadyTest {
    @get:Rule val compose = createComposeRule()

    @Test fun markerSurvivesCloseAndReopenWithoutBlinking() {
        val media = MediaRecord(1L, "content://synthetic-atlas-test/images/1", "Edit.png",
            "Pictures/Atlas/", 1_000L, 1_700_000_000_000L, false)
        val reads = AtomicInteger()
        // First read answers at once; any later read would hang, so a re-read
        // that cleared the row first would leave it missing.
        val reader = AiEditDetailsReader { _, _ ->
            if (reads.incrementAndGet() == 1) AiEditDetails(editedWithAi = true, group = null)
            else awaitCancellation()
        }
        var load by mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(
                LocalAiEditDetailsReader provides reader,
                LocalViewerDimensionsReader provides ViewerDimensionsReader { _, _ -> null },
                LocalViewerMetadataReader provides ViewerMetadataReader { _, _ -> null }
            ) {
                AtlasEvidenceHarness(edgeToEdge = true) {
                    ViewerDetailsContent(media, loadMetadata = load)
                }
            }
        }
        val marker = hasTestTag("atlas-details-ai-edited")
        compose.awaitThat("AI marker shown") { compose.onAllNodes(marker).fetchSemanticsNodes().isNotEmpty() }

        repeat(2) {
            compose.runOnIdle { load = false }
            compose.waitForIdle()
            assertTrue("marker kept while closing", compose.onAllNodes(marker).fetchSemanticsNodes().isNotEmpty())
            compose.runOnIdle { load = true }
            compose.waitForIdle()
            assertTrue("marker kept on reopen", compose.onAllNodes(marker).fetchSemanticsNodes().isNotEmpty())
        }
        assertEquals("lineage is read once per photo", 1, reads.get())
    }
}
