package nl.atlas.gallery.featuregrid

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.atlas.gallery.AtlasEvidenceHarness
import nl.atlas.gallery.MediaRecord
import nl.atlas.gallery.S24DisplayRule
import nl.atlas.gallery.saveUiEvidence
import nl.atlas.gallery.benchmark.BenchmarkReport
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.Collections

/**
 * #507: frame times of the Photos timeline grid at the owner's S24+ size
 * while flinging and while pinching between grid levels, plus renders.
 * Per-frame render durations come from the platform's FrameMetrics, so they
 * are real draw work, not the test's virtual clock. Only aggregates are
 * reported: one JSON line on logcat `ATLAS_SCALE` (CI prints that tag).
 * 1,500 invented, locally drawn images; never real photos.
 */
@RunWith(AndroidJUnit4::class)
class GridMotionFrameTimeTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(compose)

    private fun fixtures(count: Int): List<MediaRecord> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.cacheDir, "atlas-motion-507").apply { mkdirs() }
        return (0 until count).map { i ->
            val image = File(dir, "motion-$i.png")
            if (!image.exists()) {
                val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
                Canvas(bitmap).drawColor(Color.HSVToColor(floatArrayOf((i * 37f) % 360f, .55f, .85f)))
                FileOutputStream(image).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            // Three photos a day, newest first, so day headers interleave like a camera roll.
            MediaRecord(i.toLong(), Uri.fromFile(image).toString(), "Motion $i",
                "DCIM/ATLAS-TEST/", 1_000L + i, 1_700_000_000_000L - (i / 3) * 86_400_000L, false)
        }
    }

    /** Collects FrameMetrics TOTAL_DURATION (ms) of the activity window between start and stop. */
    private class FrameRecorder(private val window: Window) {
        private val thread = HandlerThread("frame-metrics-507").apply { start() }
        private val samples = Collections.synchronizedList(ArrayList<Double>())
        private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            samples += metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000.0
        }

        fun start() { samples.clear(); window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper)) }

        fun stop(): DoubleArray {
            window.removeOnFrameMetricsAvailableListener(listener)
            Thread.sleep(200) // late callbacks for the last frames
            return synchronized(samples) { samples.toDoubleArray() }
        }

        fun quit() = thread.quitSafely()
    }

    private fun stats(ms: DoubleArray): JSONObject = JSONObject()
        .put("frames", ms.size)
        .put("median_ms", BenchmarkReport.round1(BenchmarkReport.percentile(ms, 50.0)))
        .put("p90_ms", BenchmarkReport.round1(BenchmarkReport.percentile(ms, 90.0)))
        .put("p99_ms", BenchmarkReport.round1(BenchmarkReport.percentile(ms, 99.0)))
        .put("over_16ms_pct", if (ms.isEmpty()) -1.0 else
            BenchmarkReport.round1(100.0 * ms.count { it > 16.7 } / ms.size))

    private val tile = SemanticsMatcher("timeline photo tile") {
        it.config.getOrElseNullable(SemanticsProperties.TestTag) { null }
            ?.startsWith("atlas-timeline-media-") == true
    }

    /**
     * Widest composed photo tile as drawn on screen (px). boundsInRoot maps
     * the tile's rectangle through the grid's layer, so it includes the zoom
     * scale (an unclipped bound keeps the unscaled layout size); the widest
     * tile is one the viewport does not cut.
     */
    private fun tileWidth(): Float = compose.onAllNodes(tile, useUnmergedTree = true)
        .fetchSemanticsNodes().maxOf { it.boundsInRoot.width }

    @Test fun flingAndPinchFrameTimes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("atlas_featuregrid_density", Context.MODE_PRIVATE)
            .edit().clear().commit()
        val items = fixtures(1_500)
        compose.setContent {
            // #645: app theme and insets, so the renders look like the app.
            AtlasEvidenceHarness {
                AtlasTimelineGallery(
                    items = items,
                    sort = TimelineSort.NEWEST,
                    gridState = rememberLazyGridState(),
                    onOpen = {},
                    modifier = Modifier.fillMaxSize(),
                    viewKey = "motion-507",
                    catalogueEpoch = 7L
                )
            }
        }
        val grid = compose.onNodeWithTag("atlas-timeline-grid")
        compose.waitUntil(30_000L) {
            compose.onAllNodes(SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription, "4 columns")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        val recorder = FrameRecorder(compose.activity.window)
        try {
            // Fling: three throws down the timeline.
            recorder.start()
            repeat(3) {
                grid.performTouchInput { swipeUp() }
                compose.waitForIdle()
            }
            val fling = recorder.stop()

            // Pinch: spread from four to three columns, held half-way for a render.
            val restingWidth = tileWidth()
            recorder.start()
            grid.performTouchInput {
                down(0, center - Offset(60f, 0f))
                down(1, center + Offset(60f, 0f))
            }
            for (step in 1..6) {
                grid.performTouchInput {
                    moveTo(0, center - Offset(60f + step * 25f, 0f))
                    moveTo(1, center + Offset(60f + step * 25f, 0f))
                }
                compose.waitForIdle()
            }
            // Samsung: the grid follows the fingers continuously, not on release.
            val midWidth = tileWidth()
            assertTrue("tiles grow under the fingers mid-pinch ($restingWidth -> $midWidth px)",
                midWidth > restingWidth * 1.3f)
            saveUiEvidence("zoom-507-mid-pinch.png")
            for (step in 7..10) {
                grid.performTouchInput {
                    moveTo(0, center - Offset(60f + step * 25f, 0f))
                    moveTo(1, center + Offset(60f + step * 25f, 0f))
                }
                compose.waitForIdle()
            }
            grid.performTouchInput { up(0); up(1) }
            compose.waitForIdle()
            val pinch = recorder.stop()
            grid.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "3 columns"))
            val settledWidth = tileWidth()
            assertTrue("settled on unscaled three-column tiles ($restingWidth -> $settledWidth px)",
                settledWidth in restingWidth * 1.25f..restingWidth * 1.45f)
            saveUiEvidence("zoom-507-settled.png")

            val report = JSONObject()
                .put("unit", "507-motion")
                .put("schema", 1)
                .put("inputs", "1500 synthetic 32px images, 1080x2340 @450dpi")
                .put("emulator", BenchmarkReport.isEmulator())
                .put("fling", stats(fling))
                .put("pinch", stats(pinch))
                .toString()
            println("${BenchmarkReport.TAG} 507-motion json $report")
            Log.i(BenchmarkReport.TAG, "507-motion json $report")
            assertTrue("frames were recorded: $report", fling.isNotEmpty() && pinch.isNotEmpty())
        } finally {
            recorder.quit()
        }
    }

    /**
     * #507 item 2 check: the fling measured repeatedly, frame by frame. The
     * test clock is stepped one frame at a time so every animation frame of
     * each throw is really drawn and timed (the auto-advancing clock skips
     * most of them, which left the first measurement at 11 frames). Rounds
     * alternate three throws down and three back up the same 1,500 photos.
     *
     * Measurement only (it asserts that frames were recorded), so it runs only
     * with `-e atlasFlingRepeat true`: Actions-minutes audit 2026-11-12, ~220 s
     * of every PR emulator run. flingAndPinchFrameTimes keeps the fling and
     * pinch behaviour checks on every run.
     */
    @Test fun flingFrameTimesRepeated() {
        assumeTrue("Repeated fling timing is opt-in: -e atlasFlingRepeat true",
            InstrumentationRegistry.getArguments().getString("atlasFlingRepeat") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("atlas_featuregrid_density", Context.MODE_PRIVATE)
            .edit().clear().commit()
        val items = fixtures(1_500)
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    AtlasTimelineGallery(
                        items = items,
                        sort = TimelineSort.NEWEST,
                        gridState = rememberLazyGridState(),
                        onOpen = {},
                        modifier = Modifier.fillMaxSize(),
                        viewKey = "motion-507-fling",
                        catalogueEpoch = 7L
                    )
                }
            }
        }
        val grid = compose.onNodeWithTag("atlas-timeline-grid")
        compose.waitUntil(30_000L) {
            compose.onAllNodes(SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription, "4 columns")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        val recorder = FrameRecorder(compose.activity.window)
        val rounds = mutableListOf<DoubleArray>()
        try {
            compose.mainClock.autoAdvance = false
            repeat(ROUNDS) { round ->
                recorder.start()
                repeat(3) {
                    grid.performTouchInput { if (round % 2 == 0) swipeUp() else swipeDown() }
                    // A throw settles well within 1.5 s; step every frame of it.
                    repeat(90) {
                        compose.mainClock.advanceTimeByFrame()
                        compose.waitForIdle()
                    }
                }
                rounds += recorder.stop()
            }
        } finally {
            compose.mainClock.autoAdvance = true
            recorder.quit()
        }
        val perRound = org.json.JSONArray().apply { rounds.forEach { put(stats(it)) } }
        val pooled = rounds.fold(DoubleArray(0)) { acc, r -> acc + r }
        val report = JSONObject()
            .put("unit", "507-fling-repeat")
            .put("schema", 1)
            .put("inputs", "1500 synthetic 32px images, 1080x2340 @450dpi, frame-stepped")
            .put("emulator", BenchmarkReport.isEmulator())
            .put("rounds", perRound)
            .put("pooled", stats(pooled))
            .toString()
        println("${BenchmarkReport.TAG} 507-fling-repeat json $report")
        Log.i(BenchmarkReport.TAG, "507-fling-repeat json $report")
        assertTrue("every round recorded frames: $report", rounds.all { it.isNotEmpty() })
    }

    /**
     * #507 Review finding 3: the photo under the fingers must not jump when
     * the column count changes at release. Follows that photo's drawn centre
     * frame by frame from just before release until the grid has settled and
     * reports the largest single-frame step, sideways and vertically.
     */
    @Test fun focalPhotoPathAcrossCommit() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("atlas_featuregrid_density", Context.MODE_PRIVATE)
            .edit().clear().commit()
        val items = fixtures(1_500)
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    AtlasTimelineGallery(
                        items = items,
                        sort = TimelineSort.NEWEST,
                        gridState = rememberLazyGridState(),
                        onOpen = {},
                        modifier = Modifier.fillMaxSize(),
                        viewKey = "motion-507-path",
                        catalogueEpoch = 7L
                    )
                }
            }
        }
        val grid = compose.onNodeWithTag("atlas-timeline-grid")
        compose.waitUntil(30_000L) {
            compose.onAllNodes(SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription, "4 columns")).fetchSemanticsNodes().isNotEmpty()
        }
        grid.performTouchInput { swipeUp() }
        compose.waitForIdle()
        // A pinch centred to the right of the middle, so the photo there
        // changes column when four columns become three.
        val gridBounds = grid.fetchSemanticsNode().boundsInRoot
        // The point there may fall on a day header or a gap: centre the
        // fingers on the nearest photo instead.
        val wanted = Offset(gridBounds.left + gridBounds.width * .7f, gridBounds.center.y)
        val focal = compose.onAllNodes(tile, useUnmergedTree = true).fetchSemanticsNodes()
            .minBy { (it.boundsInRoot.center - wanted).getDistance() }
        val focus = focal.boundsInRoot.center
        val focalTag = focal.config[SemanticsProperties.TestTag]
        fun focalCentre(): Offset? = compose.onAllNodes(
            SemanticsMatcher.expectValue(SemanticsProperties.TestTag, focalTag), useUnmergedTree = true)
            .fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.center
        val local = focus - gridBounds.topLeft
        grid.performTouchInput {
            down(0, local - Offset(60f, 0f))
            down(1, local + Offset(60f, 0f))
        }
        for (step in 1..10) {
            grid.performTouchInput {
                moveTo(0, local - Offset(60f + step * 15f, 0f))
                moveTo(1, local + Offset(60f + step * 15f, 0f))
            }
            compose.waitForIdle()
        }
        val path = mutableListOf<Offset?>()
        compose.mainClock.autoAdvance = false
        try {
            path += focalCentre()
            grid.performTouchInput { up(0); up(1) }
            repeat(45) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                path += focalCentre()
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
        grid.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "3 columns"))
        val steps = path.zipWithNext().mapNotNull { (a, b) -> if (a != null && b != null) b - a else null }
        val report = JSONObject()
            .put("unit", "507-focal-path")
            .put("schema", 1)
            .put("frames", path.size)
            .put("missing_frames", path.count { it == null })
            .put("max_step_x_px", BenchmarkReport.round1(steps.maxOfOrNull { kotlin.math.abs(it.x).toDouble() } ?: -1.0))
            .put("max_step_y_px", BenchmarkReport.round1(steps.maxOfOrNull { kotlin.math.abs(it.y).toDouble() } ?: -1.0))
            .put("start", path.first()?.let { "${it.x.toInt()},${it.y.toInt()}" })
            .put("path", path.joinToString(" ") { it?.let { o -> "${o.x.toInt()},${o.y.toInt()}" } ?: "-" })
            .put("end", path.last()?.let { "${it.x.toInt()},${it.y.toInt()}" })
            .toString()
        println("${BenchmarkReport.TAG} 507-focal-path json $report")
        Log.i(BenchmarkReport.TAG, "507-focal-path json $report")
        // A slide into the new column is fine; a jump is not. 60 px is well
        // under a tile (~350 px at three columns) and over spring steps.
        assertTrue("photo under the fingers jumped at commit: $report",
            path.none { it == null } && steps.all { kotlin.math.abs(it.x) < 60f && kotlin.math.abs(it.y) < 60f })
    }

    private companion object { const val ROUNDS = 6 }
}
