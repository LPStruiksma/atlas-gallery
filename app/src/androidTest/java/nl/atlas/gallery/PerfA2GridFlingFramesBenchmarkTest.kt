package nl.atlas.gallery

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Window
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.SingletonImageLoader
import nl.atlas.gallery.featuremedia.MicroThumbnails
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * PERF-A2A (#547): real MainActivity Pictures-grid fling timing with disposable
 * MediaStore images at 4 and 7 grid columns. Fixture insertion, publication
 * and thumbnail-memory reset happen BEFORE each measured series.
 * Window FrameMetrics records actual window work and vsync stamps; no Compose
 * test-clock advancement is counted as a physical frame.
 *
 * Emulator-only baseline, not proof of the S24+ <1% over-budget target.
 */
@RunWith(AndroidJUnit4::class)
class PerfA2GridFlingFramesBenchmarkTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    // Outer rule: never create or delete MediaStore entries on a physical device.
    private val emulatorOnly = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("PERF-A2A only writes synthetic media on API 35 emulators",
                    Build.VERSION.SDK_INT == 35 &&
                        (Build.HARDWARE.contains("ranchu", true) ||
                            Build.HARDWARE.contains("goldfish", true)) &&
                        (Build.FINGERPRINT.contains("generic", true) ||
                            Build.PRODUCT.contains("sdk", true)))
                base.evaluate()
            }
        }
    }
    // Select density before activity launch, restore the prior preference.
    private val densityRule = TestRule { base, description ->
        object : Statement() {
            override fun evaluate() {
                val columns = when (description.methodName) {
                    "fourColumnFling" -> 4
                    "sevenColumnFling" -> 7
                    else -> error("Unknown PERF-A2A method " + description.methodName)
                }
                val prefs = context.getSharedPreferences("atlas_featuregrid_density", Context.MODE_PRIVATE)
                val previous = if (prefs.contains("photos")) prefs.getInt("photos", 4) else null
                check(prefs.edit().putInt("photos", columns).commit())
                try {
                    base.evaluate()
                } finally {
                    val edit = prefs.edit()
                    if (previous == null) edit.remove("photos") else edit.putInt("photos", previous)
                    check(edit.commit()) { "Cannot restore original grid density" }
                }
            }
        }
    }
    // Existing synthetic helper makes real 96x72 PNG MediaStore rows outside
    // measurement, grants access, clears stale catalogue, and deletes ONLY
    // its own inserted URIs even after an assertion failure.
    private val fixtures = SyntheticHeaderFixtures(
        sizes = List(FIXTURE_COUNT) { 96 to 72 },
        folderOf = { FIXTURE_FOLDER }
    )
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: TestRule = RuleChain.outerRule(emulatorOnly)
        .around(S24DisplayRule()).around(densityRule).around(fixtures).around(compose)

    private val tile = SemanticsMatcher("Pictures media tile") {
        it.config.getOrNull(SemanticsProperties.TestTag)
            ?.startsWith("atlas-timeline-media-") == true
    }

    @Test fun fourColumnFling() = measure(4)
    @Test fun sevenColumnFling() = measure(7)

    private data class GridState(val scroll: Float, val tiles: Int)

    /** The grid stays rendered, moves, and displays IDs from a granted publication. */
    private fun readGrid(vm: GalleryViewModel, columns: Int): GridState {
        val grid = compose.onNodeWithTag(GRID_TAG)
        grid.assertExists()
        grid.assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, columns.toString() + " columns"))
        val range = requireNotNull(grid.fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.VerticalScrollAxisRange)) { "No scroll range" }
        val publication = vm.publishedInventory.value
        assertTrue("Images grant lost", publication.observedGrant?.images == true)
        assertTrue("Synthetic fixture lost",
            publication.items.count {
                it.name.startsWith("IMG_HEADER_FIXTURE_") &&
                    it.relativePath == "Pictures/" + FIXTURE_FOLDER + "/"
            } >= FIXTURE_COUNT)
        val visible = compose.onAllNodes(tile, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("No displayed media tiles", visible.isNotEmpty())
        val ids = publication.items.mapTo(HashSet<Long>()) { it.id }
        assertTrue("Visible tile does not match granted publication", visible.all {
            val tag = it.config.getOrNull(SemanticsProperties.TestTag).orEmpty()
            tag.removePrefix("atlas-timeline-media-").toLongOrNull() in ids
        })
        return GridState(range.value(), visible.size)
    }

    private fun measure(columns: Int) {
        val vm = ViewModelProvider(compose.activity)[GalleryViewModel::class.java]
        compose.waitUntil(90_000L) {
            val pub = vm.publishedInventory.value
            !vm.busy.value && pub.observedGrant?.images == true &&
                pub.items.count {
                    it.name.startsWith("IMG_HEADER_FIXTURE_") &&
                        it.relativePath == "Pictures/" + FIXTURE_FOLDER + "/"
                } >= FIXTURE_COUNT
        }
        compose.waitUntil(60_000L) {
            compose.onAllNodesWithTag(GRID_TAG).fetchSemanticsNodes().any {
                (it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
                    ?.maxValue() ?: 0f) > 50f
            }
        }
        compose.waitForIdle()
        var before = readGrid(vm, columns)
        // First pass is memory-cleared, NOT guaranteed disk- or provider-cold.
        SingletonImageLoader.get(context).memoryCache?.clear()
        MicroThumbnails.memory.clear()
        val hz = compose.activity.display?.refreshRate ?: 0f
        assertTrue("Display refresh rate unknown", hz > 0f)
        val budgetNs = 1_000_000_000.0 / hz

        val bounds = compose.onNodeWithTag(GRID_TAG).fetchSemanticsNode().boundsInWindow
        val origin = IntArray(2)
        compose.activity.window.decorView.getLocationOnScreen(origin)
        val x = origin[0] + bounds.center.x
        val top = origin[1] + bounds.top + bounds.height * .30f
        val bottom = origin[1] + bounds.top + bounds.height * .76f
        val rounds = JSONArray()
        val durations = ArrayList<Long>()
        val intervals = ArrayList<Long>()
        var callbacksDropped = 0

        // Five fixed-speed, real-uptime touchscreen throws: two down, two up,
        // one down. Compose synchronization happens only BETWEEN captures.
        for ((index, down) in listOf(true, true, false, false, true).withIndex()) {
            val sampler = WindowFrames(compose.activity.window)
            val captured = try {
                sampler.start()
                injectFling(x, if (down) bottom else top, if (down) top else bottom)
                SystemClock.sleep(550L) // include natural inertia/settling
                sampler.stop()
            } finally {
                sampler.close()
            }
            compose.waitForIdle()
            val after = readGrid(vm, columns)
            assertTrue("Gesture did not scroll on throw " + (index + 1) +
                ": before=" + before.scroll + ", after=" + after.scroll,
                abs(after.scroll - before.scroll) > 1f)
            val sample = summarize(captured)
            assertTrue("No hardware-window frames on throw " + (index + 1),
                sample.frames.size >= 4 && sample.intervals.isNotEmpty())
            durations.addAll(sample.frames.map { it.durationNs })
            intervals.addAll(sample.intervals)
            callbacksDropped += captured.sumOf { it.droppedCallbacks }
            rounds.put(JSONObject()
                .put("throw", index + 1)
                .put("direction", if (down) "down_grid" else "up_grid")
                .put("thumbnail_state", if (index == 0)
                    "memory_cleared_disk_unknown" else "repeat_no_cache_clear")
                .put("tiles_before", before.tiles)
                .put("tiles_after", after.tiles)
                .put("scroll_delta_px", rounded(after.scroll - before.scroll))
                .put("frames", sample.frames.size)
                .put("callback_reports_dropped", captured.sumOf { it.droppedCallbacks })
                .put("nonpositive_vsync_intervals", sample.nonPositive)
                .put("stats", stats(sample.frames.map { it.durationNs }, sample.intervals, budgetNs)))
            before = after
        }

        val display = context.resources.displayMetrics
        val report = JSONObject()
            .put("unit", "PERF-A2A-547")
            .put("schema", 1)
            .put("source", "Window FrameMetrics TOTAL_DURATION / VSYNC_TIMESTAMP")
            .put("fixture", "1000 synthetic MediaStore PNGs 96x72, setup outside measurement")
            .put("columns", columns)
            .put("api", Build.VERSION.SDK_INT)
            .put("emulator", Build.PRODUCT)
            .put("screen", display.widthPixels.toString() + "x" +
                display.heightPixels + "@" + display.densityDpi)
            .put("refresh_hz", rounded(hz))
            .put("frame_budget_ms", rounded(budgetNs / 1e6))
            .put("flings", rounds.length())
            .put("callback_reports_dropped", callbacksDropped)
            .put("missing_cancelled_frames", "not observable; not counted as fast rendered frames")
            .put("warm_cold_caveat", "first pass memory cleared; disk cache and background warmer uncontrolled")
            .put("rounds", rounds)
            .put("pooled", stats(durations, intervals, budgetNs))
        val json = report.toString()
        Log.i("ATLAS_SCALE", "PERF-A2A " + json)
        println("ATLAS_SCALE PERF-A2A " + json)
        assertEquals(5, rounds.length())
        assertTrue("Missing real frame-interval evidence", intervals.isNotEmpty())
    }

    private fun injectFling(x: Float, startY: Float, endY: Float) {
        val automation = instrumentation.uiAutomation
        val downAt = SystemClock.uptimeMillis()
        fun send(action: Int, y: Float) {
            val motion = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action, x, y, 0)
            try {
                motion.source = InputDevice.SOURCE_TOUCHSCREEN
                assertTrue("Input injection failed", automation.injectInputEvent(motion, true))
            } finally { motion.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, startY)
        repeat(12) { i ->
            SystemClock.sleep(16L)
            send(MotionEvent.ACTION_MOVE, startY + (endY - startY) * (i + 1) / 12f)
        }
        SystemClock.sleep(16L)
        send(MotionEvent.ACTION_UP, endY)
    }

    private data class Frame(val vsyncNs: Long, val durationNs: Long, val droppedCallbacks: Int)
    private class WindowFrames(private val window: Window) : AutoCloseable {
        private val worker = HandlerThread("perf-a2a-window-frames").apply { start() }
        private val handler = Handler(worker.looper)
        private val frames = Collections.synchronizedList(ArrayList<Frame>())
        private var startNs = 0L
        private var active = false
        private val listener = Window.OnFrameMetricsAvailableListener { _, data, dropped ->
            frames.add(Frame(data.getMetric(FrameMetrics.VSYNC_TIMESTAMP),
                data.getMetric(FrameMetrics.TOTAL_DURATION), dropped))
        }
        fun start() {
            check(!active)
            startNs = System.nanoTime()
            window.addOnFrameMetricsAvailableListener(listener, handler)
            active = true
        }
        fun stop(): List<Frame> {
            check(active)
            val endNs = System.nanoTime()
            window.removeOnFrameMetricsAvailableListener(listener)
            active = false
            val latch = CountDownLatch(1)
            check(handler.post { latch.countDown() })
            check(latch.await(5, TimeUnit.SECONDS)) { "FrameMetrics worker did not drain" }
            return synchronized(frames) {
                frames.filter { it.vsyncNs in startNs..endNs }.sortedBy { it.vsyncNs }
            }
        }
        override fun close() {
            if (active) window.removeOnFrameMetricsAvailableListener(listener)
            worker.quitSafely()
            worker.join(3_000L)
        }
    }

    private data class Sample(val frames: List<Frame>, val intervals: List<Long>,
        val nonPositive: Int)

    private fun summarize(source: List<Frame>): Sample {
        val valid = source.filter { it.vsyncNs > 0 && it.durationNs > 0 }
        val gaps = valid.zipWithNext().map { (a, b) -> b.vsyncNs - a.vsyncNs }
        return Sample(valid, gaps.filter { it > 0 }, gaps.count { it <= 0 })
    }

    private companion object {
        const val GRID_TAG = "atlas-timeline-grid"
        const val FIXTURE_FOLDER = "ATLAS-PERF-A2A-547"
        const val FIXTURE_COUNT = 1_000

        fun rounded(n: Number): Double = kotlin.math.round(n.toDouble() * 100.0) / 100.0
        fun percentileNs(nums: List<Long>, p: Int): Double {
            if (nums.isEmpty()) return -1.0
            val sorted = nums.sorted()
            return sorted[((sorted.size - 1) * p + 50) / 100] / 1e6
        }
        // Duration jank and vsync gaps are separate. A long gap does not
        // establish whether a frame was missing, cancelled or not requested.
        fun stats(durations: List<Long>, intervals: List<Long>, budgetNs: Double): JSONObject {
            val valid = durations.filter { it > 0L }
            return JSONObject()
                .put("rendered_frames", valid.size)
                .put("interval_samples", intervals.size)
                .put("render_p50_ms", rounded(percentileNs(valid, 50)))
                .put("render_p95_ms", rounded(percentileNs(valid, 95)))
                .put("interval_p50_ms", rounded(percentileNs(intervals, 50)))
                .put("interval_p95_ms", rounded(percentileNs(intervals, 95)))
                .put("over_budget_frames", valid.count { it > budgetNs })
                .put("over_budget_pct", if (valid.isEmpty()) -1.0 else
                    rounded(100.0 * valid.count { it > budgetNs } / valid.size))
                .put("intervals_over_budget", intervals.count { it > budgetNs })
                .put("intervals_gt_2_refresh", intervals.count { it > budgetNs * 2 })
        }
    }
}
