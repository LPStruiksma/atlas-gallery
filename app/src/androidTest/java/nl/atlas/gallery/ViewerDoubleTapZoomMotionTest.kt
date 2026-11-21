package nl.atlas.gallery

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.util.Log
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import nl.atlas.gallery.testkit.withAnimatorScale
import java.io.File
import kotlin.math.abs

/**
 * FLOW-4 (#713): the real viewer animates a double-tap zoom over 160 ms
 * around the tapped point, a new touch stops it where it is, and the system
 * "Remove animations" setting cuts straight to the end pose.
 *
 * Frame data, not screenshots: the Compose clock is paused and stepped one
 * frame at a time while the focused photo publishes its live pose
 * ([LocalViewerZoomPoseProbe]). Galaxy S24+ size; one synthetic photo with
 * the screen's aspect ratio, so the zoom never needs an edge clamp (the
 * clamped case is JVM-tested in ViewerMotionMathTest).
 */
@RunWith(AndroidJUnit4::class)
class ViewerDoubleTapZoomMotionTest {
    private val rule = createComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(rule)

    private val hasPose = SemanticsMatcher.keyIsDefined(ViewerZoomPoseKey)

    // The CI emulator runs with animations off; these tests turn them on
    // for their own duration (testkit rule 7).
    @Test fun doubleTapZoomsInAndOutOver160msAroundTheTap() = withAnimatorScale("1") {
        open()
        val tap = tapPoint()
        val zoomIn = doubleTapFrames(tap)
        report("in", zoomIn, tap)
        assertAnimated("zoom in", zoomIn, from = 1f, to = 2.5f)
        assertPivot(zoomIn, tap)

        val zoomOut = doubleTapFrames(tap)
        report("out", zoomOut, tap)
        assertAnimated("zoom out", zoomOut, from = 2.5f, to = 1f)
        assertEquals(ViewerZoomPosition(), zoomOut.last())
    }

    @Test fun newTouchStopsTheZoomWhereItIsWithoutSnapBack() = withAnimatorScale("1") {
        open()
        val tap = tapPoint()
        rule.mainClock.autoAdvance = false
        try {
            rule.onNode(hasPose).performTouchInput { doubleClick(tap) }
            repeat(5) { rule.mainClock.advanceTimeByFrame() } // ~80 ms in
            val mid = pose()
            assertTrue("not mid-animation: $mid", mid.scale > 1.05f && mid.scale < 2.45f)
            // A plain touch-and-hold: down, then frames pass, then up.
            rule.onNode(hasPose).performTouchInput { down(center) }
            val afterDown = pose()
            repeat(15) { rule.mainClock.advanceTimeByFrame() }
            val held = pose()
            rule.onNode(hasPose).performTouchInput { up() }
            repeat(5) { rule.mainClock.advanceTimeByFrame() }
            val released = pose()
            Log.i("ATLAS_FLOW4", "cancel mid=$mid afterDown=$afterDown held=$held released=$released")
            assertEquals(mid.scale, afterDown.scale, .02f)
            assertEquals("animation kept running under the finger", afterDown, held)
            assertEquals("pose snapped after release", held, released)
        } finally {
            rule.mainClock.autoAdvance = true
        }
    }

    @Test fun removeAnimationsCutsStraightToTheEndPose() = withAnimatorScale("0") {
        open()
        val tap = tapPoint()
        val frames = doubleTapFrames(tap)
        report("reduced", frames, tap)
        assertEquals(2.5f, frames.first().scale, .001f)
        assertTrue(frames.all { it == frames.first() })
    }

    private fun open() {
        val photo = syntheticPhoto()
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalViewerZoomPoseProbe provides true) {
                    GalleryViewer(listOf(photo), photo.uri, onBack = {})
                }
            }
        }
        // The decode sets the pan bounds; zooming earlier races the re-fit.
        rule.waitUntil(10_000) {
            rule.onAllNodes(SemanticsMatcher.expectValue(ViewerPhotoDecodedKey, true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
        assertEquals(ViewerZoomPosition(), pose())
    }

    /** Off-centre, so a wrong pivot shows as drift; inside the photo surface. */
    private fun tapPoint(): Offset {
        val size = rule.onNode(hasPose).fetchSemanticsNode().size
        return Offset(size.width * .68f, size.height * .40f)
    }

    /** The pose at the double-tap and then at each following frame. */
    private fun doubleTapFrames(tap: Offset): List<ViewerZoomPosition> {
        rule.mainClock.autoAdvance = false
        try {
            rule.onNode(hasPose).performTouchInput { doubleClick(tap) }
            val frames = mutableListOf(pose())
            repeat(20) {
                rule.mainClock.advanceTimeByFrame()
                frames += pose()
            }
            return frames
        } finally {
            rule.mainClock.autoAdvance = true
            rule.waitForIdle()
        }
    }

    private fun assertAnimated(label: String, frames: List<ViewerZoomPosition>,
                               from: Float, to: Float) {
        val moving = frames.count { abs(it.scale - from) > .01f && abs(it.scale - to) > .01f }
        val doneAt = frames.indexOfFirst { abs(it.scale - to) <= .001f }
        // 160 ms is 10 frames at 16 ms; allow one frame either way for the
        // frame the tap lands in and the frame that settles.
        assertTrue("$label not animated (in-between frames $moving): $frames", moving in 7..11)
        assertTrue("$label did not settle at $to by 192 ms: $frames", doneAt in 8..12)
        assertTrue("$label not monotonic: $frames", frames.map { it.scale }.zipWithNext()
            .all { (a, b) -> if (to > from) b >= a - .001f else b <= a + .001f })
        assertTrue(frames.drop(doneAt).all { it == frames[doneAt] })
    }

    private fun assertPivot(frames: List<ViewerZoomPosition>, tap: Offset) {
        val size = rule.onNode(hasPose).fetchSemanticsNode().size
        frames.forEach { p ->
            val x = size.width / 2f + (tap.x - size.width / 2f) * p.scale + p.translationX
            val y = size.height / 2f + (tap.y - size.height / 2f) * p.scale + p.translationY
            assertTrue("tapped pixel drifted to ($x, $y) at $p", abs(x - tap.x) <= 8f && abs(y - tap.y) <= 8f)
        }
    }

    private fun report(label: String, frames: List<ViewerZoomPosition>, tap: Offset) {
        Log.i("ATLAS_FLOW4", "double-tap $label at $tap, 1080x2340: " + frames.mapIndexed { i, p ->
            "f$i scale=%.3f tx=%.1f ty=%.1f".format(p.scale, p.translationX, p.translationY)
        }.joinToString("; "))
    }

    private fun pose(): ViewerZoomPosition =
        rule.onNode(hasPose).fetchSemanticsNode().config[ViewerZoomPoseKey]

    private fun syntheticPhoto(): MediaRecord {
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "atlas-flow4-synthetic").apply { mkdirs() }
        val file = File(dir, "flow4-1080x2340.png")
        val bitmap = Bitmap.createBitmap(540, 1_170, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(38, 72, 140))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return MediaRecord(1L, Uri.fromFile(file).toString(), "Synthetic FLOW-4 photo",
            "DCIM/SYNTHETIC/", file.length(), 1L, false)
    }
}
