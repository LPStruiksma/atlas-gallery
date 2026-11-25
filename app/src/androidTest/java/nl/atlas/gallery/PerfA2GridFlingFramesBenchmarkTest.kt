package nl.atlas.gallery

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.SingletonImageLoader
import nl.atlas.gallery.featuremedia.MicroThumbnails
import nl.atlas.gallery.testkit.describeDevice
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * PERF-A2A (#547): real MainActivity Pictures-grid fling timing with disposable
 * MediaStore images at 4 and 7 grid columns. Fixture insertion, publication
 * and thumbnail-memory reset happen BEFORE each measured series.
 * Frame cadence comes from real Choreographer.FrameCallback timestamps (as in
 * GridThumbnailFlingBenchmarkTest); the callback re-arms each frame so the
 * display keeps ticking, and a main-thread stall shows as a long interval. No
 * Compose test-clock advancement is counted as a physical frame.
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
                val intervals = ArrayList<Long>()

        // Five fixed-speed, real-uptime touchscreen throws: two down, two up,
        // one down. Compose synchronization happens only BETWEEN captures.
        for ((index, down) in listOf(true, true, false, false, true).withIndex()) {
            val sampler = ChoreographerFrames(instrumentation)
            var postGestureFrames = false
            val captured = try {
                sampler.start()
                injectFling(x, if (down) bottom else top, if (down) top else bottom)
                // Keep capturing until 12 real post-release Choreographer frames,
                // or report that the emulator stopped drawing before then.
                postGestureFrames = sampler.awaitPostGestureFrames(12, 950L)
                sampler.stop()
            } finally {
                sampler.close()
            }
            compose.waitForIdle()
            val after = readGrid(vm, columns)
            val scrolled = abs(after.scroll - before.scroll) > 1f
            val scrollFailure = "Gesture did not scroll on throw " + (index + 1) +
                ": before=" + before.scroll + ", after=" + after.scroll
            // Snapshot only on failure, before the activity rule tears down.
            // This cannot establish which window received the original DOWN.
            if (!scrolled) {
                val device = runCatching { describeDevice() }
                    .getOrElse { "device state unavailable (" + it.javaClass.simpleName + ")" }
                    .take(6000)
                assertTrue(scrollFailure +
                    " | columns=" + columns +
                    ", screenX=" + x +
                    ", startY=" + (if (down) bottom else top) +
                    ", endY=" + (if (down) top else bottom) +
                    ", frameCallbacks=" + captured.size +
                    ", postRelease12=" + postGestureFrames +
                    " | post-failure snapshot: " + device, scrolled)
            }
            val sample = summarize(captured)
            assertTrue("Insufficient Choreographer frames on throw " + (index + 1) +
                ": frames=" + sample.frames + ", intervals=" + sample.intervals.size +
                ", nonPositive=" + sample.nonPositive +
                ", timestamps=" + captured.take(4) +
                ", postRelease12=" + postGestureFrames,
                sample.frames >= 4)
            assertTrue("No positive frame interval on throw " + (index + 1) +
                ": frames=" + sample.frames + ", nonPositive=" + sample.nonPositive,
                sample.intervals.isNotEmpty())
            intervals.addAll(sample.intervals)
            rounds.put(JSONObject()
                .put("throw", index + 1)
                .put("direction", if (down) "down_grid" else "up_grid")
                .put("thumbnail_state", if (index == 0)
                    "memory_cleared_disk_unknown" else "repeat_no_cache_clear")
                .put("tiles_before", before.tiles)
                .put("tiles_after", after.tiles)
                .put("scroll_delta_px", rounded(after.scroll - before.scroll))
                .put("frames", sample.frames)
                .put("frame_callbacks", captured.size)
                .put("nonpositive_vsync_intervals", sample.nonPositive)
                .put("post_release_12_frames_observed", postGestureFrames)
                .put("stats", stats(sample.intervals, budgetNs)))
            before = after
        }

        val display = context.resources.displayMetrics
        val report = JSONObject()
            .put("unit", "PERF-A2A-547")
            .put("schema", 1)
            .put("source", "Choreographer")
            .put("fixture", "1000 synthetic MediaStore PNGs 96x72, setup outside measurement")
            .put("columns", columns)
            .put("api", Build.VERSION.SDK_INT)
            .put("emulator", Build.PRODUCT)
            .put("screen", display.widthPixels.toString() + "x" +
                display.heightPixels + "@" + display.densityDpi)
            .put("refresh_hz", rounded(hz))
            .put("frame_budget_ms", rounded(budgetNs / 1e6))
            .put("flings", rounds.length())
            .put("missing_cancelled_frames", "not observable from callback timestamps; long intervals indicate skipped vsyncs")
            .put("warm_cold_caveat", "first pass memory cleared; disk cache and background warmer uncontrolled")
            .put("rounds", rounds)
            .put("pooled", stats(intervals, budgetNs))
        val json = report.toString()
        Log.i("ATLAS_SCALE", "PERF-A2A " + json)
        println("ATLAS_SCALE PERF-A2A " + json)
        assertEquals(5, rounds.length())
        assertTrue("Missing real frame-interval evidence", intervals.isNotEmpty())
    }

    private fun injectFling(x: Float, startY: Float, endY: Float) {
        // Schedule a real-uptime input sequence rather than advancing the
        // Compose test clock. Await the last injected event, not a timer.
        val automation = instrumentation.uiAutomation
        val downAt = SystemClock.uptimeMillis()
        val scheduled = Executors.newSingleThreadScheduledExecutor()
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        try {
            for (step in 0..13) {
                scheduled.schedule(Runnable {
                    try {
                        if (failure.get() == null) {
                            val action = when (step) {
                                0 -> MotionEvent.ACTION_DOWN
                                13 -> MotionEvent.ACTION_UP
                                else -> MotionEvent.ACTION_MOVE
                            }
                            val y = when (step) {
                                0 -> startY
                                13 -> endY
                                else -> startY + (endY - startY) * step / 12f
                            }
                            val event = MotionEvent.obtain(
                                downAt, SystemClock.uptimeMillis(), action, x, y, 0)
                            try {
                                event.source = InputDevice.SOURCE_TOUCHSCREEN
                                check(automation.injectInputEvent(event, true)) {
                                    "Real touchscreen injection failed at step " + step
                                }
                            } finally { event.recycle() }
                        }
                    } catch (t: Throwable) {
                        failure.compareAndSet(null, t)
                    } finally {
                        if (step == 13) finished.countDown()
                    }
                }, step * 16L, TimeUnit.MILLISECONDS)
            }
            check(finished.await(10, TimeUnit.SECONDS)) { "Scheduled real-time gesture never finished" }
            failure.get()?.let { throw it }
        } finally {
            scheduled.shutdownNow()
        }
    }

    /** Re-arming Choreographer.FrameCallback; records every frameTimeNanos. */
    private class ChoreographerFrames(
        private val instrumentation: android.app.Instrumentation
    ) : AutoCloseable {
        private val stamps = Collections.synchronizedList(ArrayList<Long>())
        @Volatile private var running = false
        @Volatile private var postReleaseLatch: CountDownLatch? = null
        private val stopped = CountDownLatch(1)
        private val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                stamps.add(frameTimeNanos)
                postReleaseLatch?.countDown()
                if (running) Choreographer.getInstance().postFrameCallback(this)
                else stopped.countDown()
            }
        }
        fun start() {
            check(!running)
            running = true
            instrumentation.runOnMainSync { Choreographer.getInstance().postFrameCallback(callback) }
        }
        fun awaitPostGestureFrames(count: Int, timeoutMs: Long): Boolean {
            val latch = CountDownLatch(count)
            postReleaseLatch = latch
            val completed = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            postReleaseLatch = null
            return completed
        }
        fun stop(): List<Long> {
            check(running)
            running = false
            check(stopped.await(5, TimeUnit.SECONDS)) { "Choreographer callback did not stop" }
            return synchronized(stamps) { stamps.toList() }
        }
        override fun close() {
            if (running) {
                running = false
                instrumentation.runOnMainSync {
                    Choreographer.getInstance().removeFrameCallback(callback)
                }
            }
        }
    }

    private data class Sample(val frames: Int, val intervals: List<Long>, val nonPositive: Int)

    private fun summarize(stamps: List<Long>): Sample {
        val valid = stamps.filter { it > 0 }
        val gaps = valid.zipWithNext().map { (a, b) -> b - a }
        return Sample(valid.size, gaps.filter { it > 0 }, gaps.count { it <= 0 })
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
        // Frame-to-frame Choreographer intervals. Vsync timestamps are rounded
        // to ns, so an interval is "over budget" only when it exceeds 1.5 x the
        // refresh period, i.e. at least one vsync was skipped. A long gap means
        // skipped vsyncs (main thread or display stall), not a classified cause.
        fun stats(intervals: List<Long>, budgetNs: Double): JSONObject {
            val skip1 = budgetNs * 1.5
            val skip2 = budgetNs * 2.5
            val over = intervals.count { it > skip1 }
            return JSONObject()
                .put("interval_samples", intervals.size)
                .put("interval_p50_ms", rounded(percentileNs(intervals, 50)))
                .put("interval_p95_ms", rounded(percentileNs(intervals, 95)))
                .put("over_budget_threshold_ms", rounded(skip1 / 1e6))
                .put("over_budget_intervals", over)
                .put("over_budget_pct", if (intervals.isEmpty()) -1.0 else
                    rounded(100.0 * over / intervals.size))
                .put("interval_budget_bins", JSONObject()
                    .put("on_time", intervals.count { it <= skip1 })
                    .put("one_vsync_skipped", intervals.count { it > skip1 && it <= skip2 })
                    .put("two_or_more_skipped", intervals.count { it > skip2 }))
        }
    }
}
