package nl.atlas.gallery

import android.os.SystemClock
import android.util.Log
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** No real MediaStore records: synthetic URI fails its read-only metadata query. */
@RunWith(AndroidJUnit4::class)
class ViewerDetailsSheetTest {
    @get:Rule val rule = createComposeRule()

    @Test fun detailsFollowsSettledFilmstripSelectionNotOriginalEntry() {
        val photos = (0..2).map { index ->
            MediaRecord(
                id = index.toLong(),
                uri = "content://synthetic-atlas-test/images/$index",
                name = "Fictional-$index.jpg",
                relativePath = "DCIM/Album-$index/",
                sizeBytes = 1_000_000L + index * 1_000L,
                takenAtMillis = 1_600_000_000_000L + index * 1000L,
                isVideo = false
            )
        }
        rule.setContent {
            MaterialTheme { GalleryViewer(photos, photos[0].uri, onBack = {}) }
        }
        rule.onNodeWithTag("atlas-thumbnail-2").performClick()
        rule.waitUntil(8_000) {
            rule.onAllNodesWithText("3 of 3").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("atlas-viewer-details-open").performClick()
        rule.onNodeWithTag("atlas-viewer-details").assertExists()
        assertTrue(rule.onAllNodesWithText("Fictional-2.jpg")
            .fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithText("DCIM/Album-2")
            .fetchSemanticsNodes().isNotEmpty())
    }

    /**
     * atlas-native#430: the old version waited 8 s on wall-clock for a real
     * ContentResolver lookup of an unknown authority. That lookup is a binder
     * call into system_server whose latency the test cannot bound on the
     * 2-core API35 CI emulator; every other step before the wait had already
     * passed. The UI half now uses a reader the test controls; the real
     * provider half is checked directly below, with no UI deadline.
     */
    @Test fun infoActionOpensUsefulDetailsEvenWhenProviderIsUnavailable() {
        val one = MediaRecord(
            id = 42, uri = "content://synthetic-atlas-test/images/42",
            name = "Fictional.jpg", relativePath = "DCIM/Synthetic/",
            sizeBytes = 2_500_000L, takenAtMillis = 1_600_000_000_000L,
            isVideo = false
        )
        val providerAnswer = CompletableDeferred<ViewerDimensions?>()
        val requested = mutableListOf<String>()
        val reader = ViewerDimensionsReader { _, uri ->
            synchronized(requested) { requested += uri }
            providerAnswer.await()
        }
        rule.setContent {
            CompositionLocalProvider(LocalViewerDimensionsReader provides reader) {
                MaterialTheme { GalleryViewer(listOf(one), one.uri, onBack = {}) }
            }
        }
        rule.onNodeWithTag("atlas-viewer-details-open").assertExists()
            .performClick()
        rule.onNodeWithTag("atlas-viewer-details").assertExists()
        assertTrue(rule.onAllNodesWithText("Fictional.jpg")
            .fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithText("DCIM/Synthetic")
            .fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithText(viewerFileSize(one.sizeBytes)).assertExists()
        // While the provider has not answered, the row says so truthfully.
        rule.onNodeWithText("Reading…").assertExists()
        assertEquals(listOf(one.uri), synchronized(requested) { requested.toList() })

        // Metadata access failure must be a nonfatal, truthful state.
        rule.runOnIdle { providerAnswer.complete(null) }
        rule.waitForIdle()
        rule.onNodeWithTag("atlas-viewer-details").assertExists()
        rule.onNodeWithText("Unavailable").assertExists()
        rule.onAllNodesWithText("Reading…").assertCountEquals(0)
    }

    /** The real lookup a revoked/unknown provider takes: null, never a crash. */
    @Test fun unknownProviderDimensionsQueryIsNonfatalAndUnavailable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val started = SystemClock.elapsedRealtime()
        assertNull(viewerQueryDimensions(context, "content://synthetic-atlas-test/images/42"))
        // Evidence for #430: how long the binder lookup took on this device.
        Log.i("AtlasViewerDetails", "unknown-provider dimensions lookup took " +
            "${SystemClock.elapsedRealtime() - started} ms")
    }
}
