package nl.atlas.gallery

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Reproduces the high-risk regression where a generic transform detector
 * consumed one-finger drags before HorizontalPager could change photo.
 * Images are written to this test app's isolated cache and are fictional.
 */
@RunWith(AndroidJUnit4::class)
class ViewerGestureOwnershipTest {
    @get:Rule val rule = createComposeRule()

    private fun photos(): List<MediaRecord> {
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "atlas-viewer-gesture-synthetic").apply { mkdirs() }
        return (0 until 3).map { index ->
            val file = File(dir, "frame-$index.png")
            val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(if (index % 2 == 0) Color.BLUE else Color.GREEN)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            MediaRecord(index.toLong(), Uri.fromFile(file).toString(),
                "Synthetic frame $index", "DCIM/SYNTHETIC/", file.length(),
                index.toLong(), false)
        }
    }

    @Test fun secondOutwardSwipeAtZoomEdgeRequestsAdjacentPhoto() {
        val fixtures = photos()
        rule.setContent {
            MaterialTheme { GalleryViewer(fixtures, fixtures[1].uri, onBack = {}) }
        }
        rule.onNodeWithTag("atlas-photo-counter").assertTextEquals("2 of 3")
        // Wait for the HQ decode of the synthetic PNG. Coil decodes off the
        // Compose clock, so waitForIdle alone can return first; the decode
        // then re-fits the zoom pose between the two swipes and the second
        // swipe no longer starts at the image edge (intermittent on API 35).
        rule.waitUntil(10_000) {
            rule.onAllNodes(SemanticsMatcher.expectValue(ViewerPhotoDecodedKey, true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
        rule.onNodeWithTag("atlas-main-pager").performTouchInput { doubleClick(center) }
        rule.waitForIdle()
        // Start in the middle: first gesture must reach the left image edge,
        // not unexpectedly change photo during an ordinary zoomed pan.
        rule.onNodeWithTag("atlas-main-pager").performTouchInput { swipeRight() }
        rule.waitForIdle()
        rule.onNodeWithTag("atlas-photo-counter").assertTextEquals("2 of 3")
        // A new outward gesture FROM the boundary is an explicit page turn.
        rule.onNodeWithTag("atlas-main-pager").performTouchInput { swipeRight() }
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("1 of 3").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("atlas-photo-counter").assertTextEquals("1 of 3")
    }

    @Test fun oneFingerAtNormalZoomStillPagesThenDoubleTapKeepsZoomedPhoto() {
        val fixtures = photos()
        rule.setContent {
            MaterialTheme { GalleryViewer(fixtures, fixtures[0].uri, onBack = {}) }
        }
        rule.onNodeWithTag("atlas-photo-counter").assertTextEquals("1 of 3")
        rule.onNodeWithTag("atlas-main-pager").performTouchInput { swipeLeft() }
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("2 of 3").fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
        rule.onNodeWithTag("atlas-main-pager").performTouchInput { doubleClick(center) }
        rule.waitForIdle()
        rule.onNodeWithTag("atlas-main-pager").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        rule.onNodeWithTag("atlas-photo-counter").assertTextEquals("2 of 3")
        rule.onNodeWithTag("atlas-main-pager").performTouchInput { doubleClick(center) }
        rule.waitForIdle()
        rule.onNodeWithTag("atlas-main-pager").performTouchInput { swipeLeft() }
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("3 of 3").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("atlas-photo-counter").assertTextEquals("3 of 3")
    }
}
