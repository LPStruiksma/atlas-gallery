package nl.atlas.gallery

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.atlas.gallery.benchmark.BenchmarkReport
import nl.atlas.gallery.testkit.awaitThat
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.Collections

/**
 * Swipe-up Details motion: frame times of a finger-tracked swipe-up that
 * opens Details, its settle, and a swipe-down that closes it, at the owner's
 * S24+ size (1080x2340). Per-frame durations come from the platform's
 * FrameMetrics (real draw work). One JSON line goes to logcat `ATLAS_SCALE`
 * and `ATLAS_TESTKIT` (the tag an opt-in stress run prints). Also records
 * whether the surface seen mid-drag is the same node as the settled one
 * ("same_surface"): Lukas saw one panel slide up and a differently shaped one
 * replace it.
 *
 * CI disables animations device-wide, which makes the viewer cut straight to
 * the end state (Quiet Frame: "remove animations" cuts). This test tells the
 * viewer animations are on through [LocalViewerAnimationsDisabled], without
 * touching device settings, so the settle spring is drawn and measured.
 * Synthetic, locally drawn images only.
 */
@RunWith(AndroidJUnit4::class)
class ViewerDetailsMotionFrameTimeTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(compose)

    private fun fixtures(): List<MediaRecord> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.cacheDir, "atlas-details-motion").apply { mkdirs() }
        return (0 until 12).map { i ->
            val image = File(dir, "details-motion-$i.png")
            if (!image.exists()) {
                // Portrait 2:3, like a phone photo, so the photo must shrink to fit.
                val bitmap = Bitmap.createBitmap(640, 960, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.HSVToColor(floatArrayOf((i * 47f) % 360f, .5f, .8f)))
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE; textSize = 220f
                }
                canvas.drawText("$i", 220f, 560f, paint)
                FileOutputStream(image).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            MediaRecord(i.toLong(), Uri.fromFile(image).toString(), "Motion-$i.png",
                "DCIM/ATLAS-TEST/", image.length(), 1_700_000_000_000L + i, false)
        }
    }

    private class FrameRecorder(private val window: Window) {
        private val thread = HandlerThread("frame-metrics-details").apply { start() }
        private val samples = Collections.synchronizedList(ArrayList<Double>())
        private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            samples += metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000.0
        }

        fun start() {
            samples.clear()
            window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
        }

        // A frame's metrics arrive shortly after it is drawn; the last one or
        // two of a phase may land in the next phase or be dropped.
        fun stop(): DoubleArray {
            window.removeOnFrameMetricsAvailableListener(listener)
            return synchronized(samples) { samples.toDoubleArray() }
        }

        fun quit() = thread.quitSafely()
    }

    private fun stats(ms: DoubleArray): JSONObject = JSONObject()
        .put("frames", ms.size)
        .put("median_ms", BenchmarkReport.round1(BenchmarkReport.percentile(ms, 50.0)))
        .put("p90_ms", BenchmarkReport.round1(BenchmarkReport.percentile(ms, 90.0)))
        .put("p99_ms", BenchmarkReport.round1(BenchmarkReport.percentile(ms, 99.0)))
        .put("over_8ms_pct", pct(ms, 8.33))
        .put("over_16ms_pct", pct(ms, 16.7))

    private fun pct(ms: DoubleArray, budget: Double): Double =
        if (ms.isEmpty()) -1.0 else BenchmarkReport.round1(100.0 * ms.count { it > budget } / ms.size)

    private fun idOf(tag: String): Int? = compose.onAllNodes(
        androidx.compose.ui.test.hasTestTag(tag), useUnmergedTree = true)
        .fetchSemanticsNodes().firstOrNull()?.id

    @Test fun swipeUpOpenSettleAndSwipeDownCloseFrameTimes() {
        val media = fixtures()
        val recorder = FrameRecorder(compose.activity.window)
        try {
            compose.setContent {
                CompositionLocalProvider(LocalViewerAnimationsDisabled provides { false }) {
                    AtlasEvidenceHarness(edgeToEdge = true) { GalleryViewer(media, media[5].uri) {} }
                }
            }
            compose.waitForIdle()
            val pager = compose.onNodeWithTag("atlas-main-pager")
            // The Compose clock is stepped one 16 ms frame per input event or
            // settle step, so every animation frame is laid out and drawn and
            // reaches FrameMetrics (with the clock free-running, a whole phase
            // collapsed into one or two drawn frames).
            compose.mainClock.autoAdvance = false
            fun frame() {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
            }

            // 1) Finger-tracked drag: 3 x 8 steps of 1.5 % of the height, one
            //    frame per step, held after each third for a frame of evidence
            //    (about 1/4, 1/2, 3/4 open).
            val dragFrames = ArrayList<Double>()
            var midSurface: Int? = null
            pager.performTouchInput { down(center.copy(y = height * .8f)) }
            frame()
            for (third in 1..3) {
                recorder.start()
                repeat(8) {
                    pager.performTouchInput { moveBy(Offset(0f, -height * .015f), delayMillis = 16L) }
                    frame()
                }
                dragFrames += recorder.stop().toList()
                if (third == 2) midSurface = idOf("atlas-details-drag-preview")
                saveUiEvidence("details-motion-drag-$third.png")
            }
            val drag = dragFrames.toDoubleArray()

            // 2) Release (a quick swipe) and let the spring settle, 45 frames.
            recorder.start()
            pager.performTouchInput { up() }
            repeat(45) { frame() }
            val settle = recorder.stop()
            val settledSurface = idOf("atlas-viewer-details")
            saveUiEvidence("details-motion-open.png")

            // 3) Drag the open surface down (rows at the top) and let it close.
            recorder.start()
            val surface = compose.onNodeWithTag("atlas-viewer-details")
            surface.performTouchInput { down(center.copy(y = height * .2f)) }
            frame()
            repeat(12) {
                surface.performTouchInput { moveBy(Offset(0f, height * .06f), delayMillis = 16L) }
                frame()
            }
            surface.performTouchInput { up() }
            repeat(45) { frame() }
            val close = recorder.stop()
            compose.mainClock.autoAdvance = true
            compose.awaitThat("Details closed") {
                idOf("atlas-viewer-details") == null && idOf("atlas-details-drag-preview") == null
            }

            val sameSurface = midSurface != null && midSurface == settledSurface
            val report = JSONObject()
                .put("unit", "details-motion")
                .put("schema", 1)
                .put("inputs", "12 synthetic 640x960 images, 1080x2340, animations on")
                .put("emulator", BenchmarkReport.isEmulator())
                .put("same_surface", sameSurface)
                .put("drag", stats(drag))
                .put("settle", stats(settle))
                .put("close", stats(close))
                .toString()
            println("${BenchmarkReport.TAG} details-motion json $report")
            Log.i(BenchmarkReport.TAG, "details-motion json $report")
            Log.i("ATLAS_TESTKIT", "details-motion json $report")
            assertTrue("frames were recorded: $report", drag.isNotEmpty() && settle.isNotEmpty())
            assertTrue("the surface seen mid-drag must be the settled one: $report", sameSurface)
        } finally {
            recorder.quit()
        }
    }
}
