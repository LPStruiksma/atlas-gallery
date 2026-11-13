package nl.atlas.gallery

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * #522: the viewer photo follows the finger on vertical drags. Runs at the
 * owner's S24+ size (1080×2340) so the mid-drag evidence matches the phone.
 * One emulator test (budget rule); release decisions are JVM-tested in
 * ViewerScrubOverlayTest. Synthetic images only.
 */
@RunWith(AndroidJUnit4::class)
class ViewerFingerFollowTest {
    private val rule = createComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(rule)

    private fun fixtures(): List<MediaRecord> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.cacheDir, "atlas-test-gallery-522").apply { mkdirs() }
        return (0 until 64).map { i ->
            val image = File(dir, "fixture-$i.png")
            if (!image.exists()) {
                val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                paint.color = Color.rgb((i * 39) % 256, (i * 71) % 256, (i * 113) % 256)
                canvas.drawColor(paint.color)
                paint.color = Color.WHITE
                paint.textSize = 86f
                canvas.drawText("$i", 65f, 145f, paint)
                FileOutputStream(image).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            MediaRecord(i.toLong(), Uri.fromFile(image).toString(), "Fixture $i",
                "DCIM/ATLAS-TEST/", image.length(), i.toLong(), false)
        }
    }

    @Test fun verticalDragsFollowFingerThenSpringBack() {
        val media = fixtures()
        rule.setContent { MaterialTheme { GalleryViewer(media, media[10].uri) {} } }
        rule.waitForIdle()
        val baseline = rule.onNodeWithTag("atlas-viewer-drag-photo")
            .fetchSemanticsNode().boundsInRoot
        val restingScreen = screenPixels()

        fun heldDrag(startY: Float, stepY: Float, shot: String, check: () -> Unit) {
            rule.mainClock.autoAdvance = false
            try {
                rule.onNodeWithTag("atlas-main-pager").performTouchInput {
                    down(center.copy(y = height * startY))
                    repeat(5) { moveBy(Offset(0f, height * stepY)) }
                }
                rule.mainClock.advanceTimeByFrame()
                rule.waitForIdle()
                check()
                // Semantics move before the frame reaches the screen; never
                // publish a mid-drag shot that still shows the resting photo.
                awaitScreenDiffersFrom(restingScreen, shot)
                saveUiEvidence(shot)
                rule.onNodeWithTag("atlas-main-pager").performTouchInput { up() }
            } finally {
                rule.mainClock.autoAdvance = true
            }
            rule.waitForIdle()
            val restored = rule.onNodeWithTag("atlas-viewer-drag-photo")
                .fetchSemanticsNode().boundsInRoot
            assertTrue("Cancelled drag did not spring back ($shot)",
                abs(restored.top - baseline.top) < 2f)
            rule.onNodeWithTag("atlas-photo-counter").assertTextEquals("11 of 64")
        }

        heldDrag(.3f, .012f, "viewer-522-down-mid-drag.png") {
            val dragged = rule.onNodeWithTag("atlas-viewer-drag-photo")
                .fetchSemanticsNode().boundsInRoot
            assertTrue("Photo must follow finger down", dragged.top > baseline.top)
            assertTrue("Photo must shrink while dragged down", dragged.width < baseline.width)
        }
        saveUiEvidence("viewer-522-released.png")
        heldDrag(.65f, -.012f, "viewer-522-up-mid-drag.png") {
            val moved = rule.onNodeWithTag("atlas-viewer-drag-photo")
                .fetchSemanticsNode().boundsInRoot
            assertTrue("Up drag must move the photo", moved.top != baseline.top)
            rule.onNodeWithTag("atlas-details-drag-content").assertIsDisplayed()
        }
        rule.onNodeWithTag("atlas-viewer-details").assertDoesNotExist()
        rule.onNodeWithTag("atlas-details-drag-preview").assertDoesNotExist()
    }

    private fun screenPixels(): IntArray {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) {
            "Screenshot acquisition failed"
        }
        // The middle band only: the status bar clock must not count as a change.
        val top = bitmap.height / 6
        val rows = bitmap.height * 4 / 6
        return try {
            IntArray(bitmap.width * rows).also {
                bitmap.getPixels(it, 0, bitmap.width, 0, top, bitmap.width, rows)
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** Waits (max 3 s) until the drawn screen is no longer the resting viewer. */
    private fun awaitScreenDiffersFrom(resting: IntArray, shot: String) {
        repeat(30) {
            if (!screenPixels().contentEquals(resting)) return
            Thread.sleep(100L)
        }
        throw AssertionError("$shot: the drag never reached the screen")
    }
}
