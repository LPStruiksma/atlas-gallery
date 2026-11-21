package nl.atlas.gallery.featuregrid

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.FrameMetrics
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.atlas.gallery.AtlasEvidenceHarness
import nl.atlas.gallery.LocalGalleryPublicationEpoch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import nl.atlas.gallery.MediaRecord
import nl.atlas.gallery.S24DisplayRule
import nl.atlas.gallery.benchmark.BenchmarkReport
import nl.atlas.gallery.saveUiEvidence
import nl.atlas.gallery.testkit.advanceVirtualTime
import nl.atlas.gallery.testkit.awaitScreenStill
import nl.atlas.gallery.testkit.awaitThat
import nl.atlas.gallery.testkit.withAnimatorScale
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import kotlin.math.abs
import kotlin.math.pow

/**
 * FLOW-1 (#711): the two-layer pinch, frame by frame, on the owner's S24+
 * size. Mid-gesture frames at a known ladder position show both levels
 * crossfading around the photo under the fingers; settled frames prove the
 * same photo stays under the fingers and nothing moves after the commit.
 * Invented, locally drawn images only; never real photos.
 */
@RunWith(AndroidJUnit4::class)
class PinchCrossFlowEvidenceTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(compose)

    private val tile = SemanticsMatcher("timeline photo tile") {
        it.config.getOrElseNullable(SemanticsProperties.TestTag) { null }
            ?.startsWith("atlas-timeline-media-") == true
    }

    private fun columns(n: Int) =
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "$n columns")

    private fun clearDensity() {
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("atlas_featuregrid_density", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private var gridState: LazyGridState? = null

    private fun show(items: List<MediaRecord>, dark: Boolean = true, viewKey: String,
        selected: Set<String>? = null) {
        compose.setContent {
            CachedThumbnailEvidence(dark = dark) {
                AtlasTimelineGallery(
                    items = items,
                    sort = TimelineSort.NEWEST,
                    gridState = rememberLazyGridState().also { gridState = it },
                    onOpen = {},
                    modifier = Modifier.fillMaxSize(),
                    viewKey = viewKey,
                    catalogueEpoch = 11L,
                    selectedMediaUris = selected,
                    onSelectionChange = if (selected != null) { _ -> } else null,
                    onMediaToggle = if (selected != null) { _ -> } else null,
                    pinHeader = selected != null,
                    // The app draws its header over the grid's top padding; the
                    // pinch must anchor under the fingers with it in place.
                    header = {
                        Box(Modifier.fillMaxWidth().height(72.dp)
                            .background(MaterialTheme.colorScheme.background)) {
                            Text(if (selected != null) "3 selected" else "Pictures",
                                Modifier.align(Alignment.CenterStart).padding(start = 20.dp),
                                style = MaterialTheme.typography.titleLarge)
                        }
                    }
                )
            }
        }
    }

    private fun awaitColumns(n: Int) {
        compose.waitUntil(30_000L) {
            compose.onAllNodes(columns(n)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.awaitNeighboursPlanned()
    }

    private val grid get() = compose.onNodeWithTag("atlas-timeline-grid")

    /** Nearest photo tile to [wanted] (root coordinates): its tag and drawn bounds. */
    private fun tileNear(wanted: Offset): Pair<String, Rect> {
        val node = compose.onAllNodes(tile, useUnmergedTree = true).fetchSemanticsNodes()
            .minBy { (it.boundsInRoot.center - wanted).getDistance() }
        return node.config[SemanticsProperties.TestTag] to node.boundsInRoot
    }

    private fun boundsOf(tag: String): Rect? = compose.onAllNodes(
        SemanticsMatcher.expectValue(SemanticsProperties.TestTag, tag), useUnmergedTree = true)
        .fetchSemanticsNodes().firstOrNull()?.boundsInRoot

    /**
     * Two fingers either side of [local] (grid coordinates), span [from] px
     * each, moved to the span that puts the ladder [stops] from the start:
     * positive toward smaller tiles (pinch in), negative toward larger.
     */
    private inner class Pinch(private val local: Offset, private val from: Float) {
        private var span = from
        init {
            grid.performTouchInput {
                down(0, local - Offset(from, 0f))
                down(1, local + Offset(from, 0f))
            }
        }
        fun to(stops: Float, steps: Int = 8) {
            val target = from * 1.5f.pow(-stops)
            val start = span
            for (i in 1..steps) {
                val s = start + (target - start) * i / steps
                grid.performTouchInput {
                    moveTo(0, local - Offset(s, 0f))
                    moveTo(1, local + Offset(s, 0f))
                }
            }
            span = target
            compose.waitForIdle()
        }
        fun release() {
            grid.performTouchInput { up(0); up(1) }
            compose.waitForIdle()
        }
    }

    /** Fingers centred on the photo nearest a point right of the middle (it changes column). */
    private fun focusPoint(fractionX: Float = .7f, fractionY: Float = .5f): Pair<Offset, Offset> {
        val g = grid.fetchSemanticsNode().boundsInRoot
        val (_, bounds) = tileNear(Offset(g.left + g.width * fractionX, g.top + g.height * fractionY))
        return bounds.center to (bounds.center - g.topLeft)
    }

    /** After a settle: the anchor photo is under the fingers and nothing moves a second later. */
    private fun assertAnchoredAndStill(tag: String, focusRoot: Offset, label: String) {
        val settled = checkNotNull(boundsOf(tag)) { "$label: anchor photo $tag not shown after settle" }
        assertTrue("$label: anchor photo $settled not under the fingers at ${focusRoot.y}",
            focusRoot.y in settled.top - 1f..settled.bottom + 1f)
        compose.advanceVirtualTime(1_000L)
        compose.awaitScreenStill("$label one second after the settle")
        val later = checkNotNull(boundsOf(tag))
        assertEquals("$label: late vertical jump", settled.top, later.top, 1f)
        assertEquals("$label: late sideways jump", settled.left, later.left, 1f)
    }

    @Test fun pinchFlowsBetweenLevelsDark() = pinchFlowsBetweenLevels(dark = true)

    @Test fun pinchFlowsBetweenLevelsLight() = pinchFlowsBetweenLevels(dark = false)

    /**
     * CI emulators run with animations off (which makes the release a cut, see
     * [reducedMotionReleaseIsACut]); this journey turns them on so the 350 ms
     * settle is the one a person sees.
     */
    private fun pinchFlowsBetweenLevels(dark: Boolean) = withAnimatorScale("1") {
        clearDensity()
        val theme = if (dark) "dark" else "light"
        show(fixtures(1_500), dark, "flow1-$theme")
        awaitColumns(4)
        grid.performTouchInput { swipeUp() }
        compose.waitForIdle()

        // 4 → 3 (spread), held at a quarter, half and three quarters, then let
        // go a little past the start: below 0.2 it settles back on 4 columns.
        var (focusRoot, local) = focusPoint()
        var (tag, _) = tileNear(focusRoot)
        val out = Pinch(local, 220f)
        out.to(-.25f); saveUiEvidence("flow1-$theme-out-25.png")
        out.to(-.5f); saveUiEvidence("flow1-$theme-out-50.png")
        out.to(-.75f); saveUiEvidence("flow1-$theme-out-75.png")
        assertTrue("both levels are drawn mid-pinch",
            compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag,
                "atlas-pinch-cross-layer"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        out.to(-.1f)
        out.release()
        grid.assert(columns(4))
        saveUiEvidence("flow1-$theme-revert-settled.png")
        assertAnchoredAndStill(tag, focusRoot, "revert")

        // 4 → 7 (pinch in), released at three quarters: commits to 7.
        focusPoint().let { focusRoot = it.first; local = it.second }
        tag = tileNear(focusRoot).first
        val inward = Pinch(local, 260f)
        inward.to(.25f); saveUiEvidence("flow1-$theme-in-25.png")
        inward.to(.5f); saveUiEvidence("flow1-$theme-in-50.png")
        inward.to(.75f); saveUiEvidence("flow1-$theme-in-75.png")
        // Half-way through the settle: still one continuous picture, no cut.
        compose.mainClock.autoAdvance = false
        inward.release()
        compose.mainClock.advanceTimeBy(48L)
        saveUiEvidence("flow1-$theme-in-settling.png")
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        grid.assert(columns(7))
        saveUiEvidence("flow1-$theme-in-settled-7.png")
        assertAnchoredAndStill(tag, focusRoot, "commit 4->7")

        if (!dark) return@withAnimatorScale
        // One pinch through several levels: 7 → month overview → year overview.
        compose.awaitNeighboursPlanned()
        focusPoint(.5f).let { focusRoot = it.first; local = it.second }
        val far = Pinch(local, 300f)
        far.to(.5f, 6); saveUiEvidence("flow1-dark-multi-7-12.png")
        far.to(1.5f, 10); saveUiEvidence("flow1-dark-multi-12-28.png")
        far.to(2f, 6)
        far.release()
        grid.assert(columns(28))
        saveUiEvidence("flow1-dark-multi-settled-28.png")
    }

    /**
     * Review 3: the fingers' centre moves while their span changes; the photo
     * follows it and the committed level keeps it under the final centre.
     */
    @Test fun pinchFollowsMovingFingers() = withAnimatorScale("1") {
        clearDensity()
        show(fixtures(1_500), true, "flow1-moving")
        awaitColumns(4)
        // Well clear of the top: the fingers end lower than they started.
        repeat(3) { grid.performTouchInput { swipeUp() }; compose.waitForIdle() }
        compose.awaitNeighboursPlanned()
        val g = grid.fetchSemanticsNode().boundsInRoot
        val (tag, before) = tileNear(Offset(g.left + g.width * .7f, g.top + g.height * .4f))
        val start = before.center - g.topLeft
        val fractionY = (start.y - (before.top - g.top)) / before.height
        val delta = Offset(40f, 160f)
        val from = 260f
        grid.performTouchInput { down(0, start - Offset(from, 0f)); down(1, start + Offset(from, 0f)) }
        val steps = 12
        val target = from * 1.5f.pow(-.75f)
        for (i in 1..steps) {
            val s = from + (target - from) * i / steps
            val c = start + delta * (i.toFloat() / steps)
            grid.performTouchInput { moveTo(0, c - Offset(s, 0f)); moveTo(1, c + Offset(s, 0f)) }
        }
        compose.waitForIdle()
        saveUiEvidence("flow1-moving-in-75.png")
        grid.performTouchInput { up(0); up(1) }
        compose.waitForIdle()
        grid.assert(columns(7))
        saveUiEvidence("flow1-moving-settled-7.png")
        val settled = checkNotNull(boundsOf(tag)) { "anchor photo $tag not shown after a moving pinch" }
        val endY = g.top + start.y + delta.y
        assertEquals("the same point of the photo sits under the fingers' final centre",
            endY, settled.top + fractionY * settled.height, 2f)
        compose.advanceVirtualTime(1_000L)
        compose.awaitScreenStill("moving pinch one second after the settle")
        assertEquals("late vertical jump", settled.top, checkNotNull(boundsOf(tag)).top, 1f)
    }

    @Test fun pinchAtTheTopOfTheLibraryNeverOverscrolls() {
        clearDensity()
        show(fixtures(1_500), true, "flow1-top")
        awaitColumns(4)
        val (focusRoot, local) = focusPoint(.7f, .25f)
        val tag = tileNear(focusRoot).first
        val pinch = Pinch(local, 220f)
        pinch.to(-.5f)
        saveUiEvidence("flow1-top-out-50.png")
        pinch.to(-1f)
        pinch.release()
        grid.assert(columns(3))
        saveUiEvidence("flow1-top-settled-3.png")
        // Clamped by the top of the library: the first row stays first.
        val first = compose.onAllNodes(tile, useUnmergedTree = true).fetchSemanticsNodes()
            .minOf { it.boundsInRoot.top }
        val gridTop = grid.fetchSemanticsNode().boundsInRoot.top
        assertTrue("no blank band above the first row ($first vs grid $gridTop)", first - gridTop < 400f)
        assertTrue("anchor still shown", boundsOf(tag) != null)
    }

    @Test fun pinchWhileSelecting() {
        clearDensity()
        val items = fixtures(1_500)
        show(items, true, "flow1-select", selected = items.take(30).filter { it.id % 4L == 1L }
            .map { it.uri }.toSet())
        awaitColumns(4)
        val (_, local) = focusPoint(.5f, .3f)
        val pinch = Pinch(local, 260f)
        pinch.to(.5f)
        saveUiEvidence("flow1-select-in-50.png")
        pinch.to(1f)
        pinch.release()
        grid.assert(columns(7))
        saveUiEvidence("flow1-select-settled-7.png")
    }

    @Test fun tinyAlbumPinch() {
        clearDensity()
        show(fixtures(5), true, "flow1-tiny")
        awaitColumns(4)
        val (focusRoot, local) = focusPoint(.3f, .1f)
        val tag = tileNear(focusRoot).first
        val pinch = Pinch(local, 200f)
        pinch.to(.5f)
        saveUiEvidence("flow1-tiny-in-50.png")
        pinch.to(.9f)
        pinch.release()
        grid.assert(columns(7))
        saveUiEvidence("flow1-tiny-settled-7.png")
        assertTrue("tiny album photo still shown", boundsOf(tag) != null)
    }

    /** An empty album has no grid; a pinch there must do nothing and not crash. */
    @Test fun emptyAlbumPinch() {
        clearDensity()
        show(emptyList(), true, "flow1-empty")
        compose.waitForIdle()
        compose.onRoot().performTouchInput {
            down(0, center - Offset(200f, 0f)); down(1, center + Offset(200f, 0f))
            moveTo(0, center - Offset(80f, 0f)); moveTo(1, center + Offset(80f, 0f))
            up(0); up(1)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("atlas-timeline-empty", useUnmergedTree = true).assertExists()
        saveUiEvidence("flow1-empty-after-pinch.png")
    }

    /** Brief state "bottom bound": a pinch at the end of the library leaves no blank band below. */
    @Test fun pinchAtTheBottomOfTheLibraryNeverOverscrolls() {
        clearDensity()
        show(fixtures(1_500), true, "flow1-bottom")
        awaitColumns(4)
        val state = checkNotNull(gridState)
        compose.runOnUiThread { state.requestScrollToItem(state.layoutInfo.totalItemsCount - 1) }
        compose.awaitThat("the grid scrolled to its end") { !state.canScrollForward }
        val (focusRoot, local) = focusPoint(.7f, .75f)
        val tag = tileNear(focusRoot).first
        val pinch = Pinch(local, 220f)
        pinch.to(-.5f)
        saveUiEvidence("flow1-bottom-out-50.png")
        pinch.to(-1f)
        pinch.release()
        grid.assert(columns(3))
        saveUiEvidence("flow1-bottom-settled-3.png")
        // Clamped by the end of the library: the last row ends at the bottom.
        val last = compose.onAllNodes(tile, useUnmergedTree = true).fetchSemanticsNodes()
            .maxOf { it.boundsInRoot.bottom }
        val gridBottom = grid.fetchSemanticsNode().boundsInRoot.bottom
        assertTrue("no blank band below the last row ($last vs grid $gridBottom)", gridBottom - last < 400f)
        assertTrue("anchor still shown", boundsOf(tag) != null)
    }

    /**
     * Brief state "cold plan": a pinch that starts before the neighbouring
     * level is planned draws the estimated anchor, then commits anchored.
     */
    @Test fun pinchBeforeTheNeighbourIsPlanned() {
        clearDensity()
        show(fixtures(1_500), true, "flow1-cold")
        compose.awaitThat("4 columns shown") {
            compose.onAllNodes(columns(4)).fetchSemanticsNodes().isNotEmpty()
        }
        // Away from the top bound, so the anchor can stay under the fingers
        // (at the top the new level is clamped, see pinchAtTheTop…).
        grid.performTouchInput { swipeUp() }
        compose.waitForIdle()
        val (focusRoot, local) = focusPoint()
        val tag = tileNear(focusRoot).first
        val pinch = Pinch(local, 260f)
        pinch.to(.5f)
        saveUiEvidence("flow1-cold-in-50.png")
        pinch.to(.9f)
        pinch.release()
        grid.assert(columns(7))
        saveUiEvidence("flow1-cold-settled-7.png")
        assertAnchoredAndStill(tag, focusRoot, "cold commit 4->7")
    }

    /** Brief state "reduced motion": with animations removed the release cuts straight to the new level. */
    @Test fun reducedMotionReleaseIsACut() = withAnimatorScale("0") {
        clearDensity()
        show(fixtures(1_500), true, "flow1-reduced")
        awaitColumns(4)
        grid.performTouchInput { swipeUp() } // away from the top bound
        compose.waitForIdle()
        val (focusRoot, local) = focusPoint()
        val tag = tileNear(focusRoot).first
        val pinch = Pinch(local, 260f)
        pinch.to(.75f)
        pinch.release()
        grid.assert(columns(7))
        // A cut: nothing is left of the pinch layers once the release is handled.
        assertTrue("no pinch layer after a reduced-motion release", compose.onAllNodes(
            SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "atlas-pinch-cross-layer"),
            useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        saveUiEvidence("flow1-reduced-settled-7.png")
        assertAnchoredAndStill(tag, focusRoot, "reduced-motion commit 4->7")
    }

    /**
     * Brief state "huge library": 150,000 rows (64 shared synthetic images),
     * pinch 4 → 7 → 4 with FrameMetrics recorded. Reported on logcat
     * `ATLAS_SCALE`; emulator numbers are not phone numbers.
     */
    @Test fun hugeLibraryPinchFrameTimes() {
        clearDensity()
        show(fixtures(150_000, distinctFiles = 64), true, "flow1-huge")
        awaitColumns(4)
        compose.awaitNeighboursPlanned(60_000L) // the two neighbouring 150k plans
        // Baseline on the same emulator: an ordinary scroll of the same grid.
        val scrollMs = recordFrames {
            repeat(3) { grid.performTouchInput { swipeUp() }; compose.waitForIdle() }
            compose.awaitNeighboursPlanned(60_000L)
        }
        // Attributable cost (#711 review): main-thread time inside the pinch
        // code itself, by kind, independent of the emulator's frame cost.
        val spent = Collections.synchronizedMap(HashMap<String, MutableList<Double>>())
        PinchCrossTiming.sink = { kind, nanos ->
            spent.getOrPut(kind) { Collections.synchronizedList(ArrayList()) } += nanos / 1_000_000.0
        }
        val ms = try { recordFrames {
            val (_, local) = focusPoint(.6f)
            val pinch = Pinch(local, 260f)
            pinch.to(.5f, 12)
            saveUiEvidence("flow1-huge-in-50.png")
            pinch.to(1f, 12)
            pinch.release()
            grid.assert(columns(7))
            val back = Pinch(local, 200f)
            back.to(-1f, 16)
            back.release()
            grid.assert(columns(4))
        } } finally { PinchCrossTiming.sink = null }
        val own = synchronized(spent) { spent.mapValues { (_, v) -> synchronized(v) { v.toDoubleArray() } } }
        val ownJson = JSONObject()
        for ((kind, v) in own) ownJson.put(kind, JSONObject().put("n", v.size)
            .put("p90_ms", BenchmarkReport.round1(BenchmarkReport.percentile(v, 90.0)))
            .put("max_ms", BenchmarkReport.round1(v.max())))
        val pinchP90 = BenchmarkReport.percentile(ms, 90.0)
        val scrollP90 = BenchmarkReport.percentile(scrollMs, 90.0)
        val report = JSONObject()
            .put("unit", "711-pinch-150k")
            .put("schema", 1)
            .put("inputs", "150000 synthetic rows, 64 shared 32px images, 1080x2340 @450dpi")
            .put("emulator", BenchmarkReport.isEmulator())
            .put("frames", ms.size)
            .put("median_ms", BenchmarkReport.round1(BenchmarkReport.percentile(ms, 50.0)))
            .put("p90_ms", BenchmarkReport.round1(BenchmarkReport.percentile(ms, 90.0)))
            .put("p99_ms", BenchmarkReport.round1(BenchmarkReport.percentile(ms, 99.0)))
            .put("over_16ms_pct", if (ms.isEmpty()) -1.0 else
                BenchmarkReport.round1(100.0 * ms.count { it > 16.7 } / ms.size))
            .put("scroll_frames", scrollMs.size)
            .put("scroll_p90_ms", BenchmarkReport.round1(scrollP90))
            .put("pinch_code_ms", ownJson)
            .toString()
        println("${BenchmarkReport.TAG} 711-pinch-150k json $report")
        Log.i(BenchmarkReport.TAG, "711-pinch-150k json $report")
        assertTrue("frames recorded: $report", ms.isNotEmpty() && scrollMs.isNotEmpty())
        // Frame budget (emulator frames are not phone frames, so it is relative):
        // a 150k-row pinch costs at most twice an ordinary scroll of the same
        // grid at p90, and never stalls like an ANR-class frame.
        assertTrue("pinch p90 within 2x scroll p90 (or 50 ms): $report",
            pinchP90 <= maxOf(2.0 * scrollP90, 50.0))
        assertTrue("no pinch frame over 700 ms: $report", ms.max() < 700.0)
        // The pinch code's own per-frame work (pose of each layer) and its
        // per-level work (ghost geometry, cache lookup, draw recording) each
        // fit one 60 Hz frame at p90 even on the emulator.
        for (kind in listOf("pose", "geometry", "draw")) {
            val v = own[kind]
            assertTrue("pinch code '$kind' recorded: $report", v != null && v.isNotEmpty())
        }
        for ((kind, v) in own) assertTrue("pinch code '$kind' p90 <= 16.6 ms: $report",
            BenchmarkReport.percentile(v, 90.0) <= 16.6)
    }

    /** Total frame durations (ms) drawn while [body] runs. */
    private fun recordFrames(body: () -> Unit): DoubleArray {
        val samples = Collections.synchronizedList(ArrayList<Double>())
        val thread = HandlerThread("frame-metrics-711").apply { start() }
        val listener = android.view.Window.OnFrameMetricsAvailableListener { _, m, _ ->
            samples += m.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000.0
        }
        val window = compose.activity.window
        try {
            window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
            body()
            compose.waitForIdle()
        } finally {
            window.removeOnFrameMetricsAvailableListener(listener)
            thread.quitSafely()
        }
        return synchronized(samples) { samples.toDoubleArray() }
    }

    private fun fixtures(count: Int, distinctFiles: Int = count): List<MediaRecord> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.cacheDir, "atlas-flow-711").apply { mkdirs() }
        val paint = Paint().apply { color = Color.WHITE; textSize = 22f; isAntiAlias = true }
        val files = (0 until minOf(count, distinctFiles)).map { i ->
            File(dir, "flow-$i.png").also { image ->
                if (!image.exists()) {
                    // A hue per photo plus its number: any wrong photo shows.
                    val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.HSVToColor(floatArrayOf((i * 37f) % 360f, .55f, .8f)))
                    canvas.drawText("$i", 8f, 56f, paint)
                    FileOutputStream(image).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
        }
        return (0 until count).map { i ->
            val file = files[i % files.size]
            val uri = if (files.size == count) Uri.fromFile(file).toString()
                else Uri.fromFile(file).buildUpon().fragment("$i").build().toString()
            // Nine photos a day, newest first: day headers interleave like a camera roll.
            MediaRecord(i.toLong(), uri, "Flow $i", "DCIM/ATLAS-TEST/", 1_000L + i,
                1_700_000_000_000L - (i / 9) * 86_400_000L, false,
                providerVersion = SYNTHETIC_PROVIDER_VERSION, generationModified = 1L)
        }
    }
}

/**
 * Synthetic records carry a provider revision and the screen a publication
 * epoch, as MediaStore rows and MainActivity do. Without them the app never
 * memory-caches a thumbnail (an unknown revision may not reuse a decode), so
 * the pinch layer, which only draws memory-cached tiles, would render empty
 * here while it shows photos on a phone.
 */
internal const val SYNTHETIC_PROVIDER_VERSION = "synthetic-711"

@Composable
internal fun CachedThumbnailEvidence(dark: Boolean = true, content: @Composable () -> Unit) =
    AtlasEvidenceHarness(dark = dark) {
        CompositionLocalProvider(LocalGalleryPublicationEpoch provides 711L, content = content)
    }

/** Neighbouring levels are planned after a short idle pause, off the main thread. */
private fun ComposeTestRule.awaitNeighboursPlanned(timeoutMs: Long = 30_000L) = awaitThat(
    "both neighbouring pinch levels planned", timeoutMs) {
    onAllNodes(SemanticsMatcher.expectValue(CrossNeighboursPlanned, true)).fetchSemanticsNodes().isNotEmpty()
}

/**
 * Note 3 of the design preflight: the landscape ladder, one mid-pinch and one
 * settled frame on the S24+ turned sideways (2340 × 1080).
 */
@RunWith(AndroidJUnit4::class)
class PinchCrossFlowLandscapeEvidenceTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule(landscape = true)).around(compose)

    @Test fun landscapePinch() {
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("atlas_featuregrid_density", Context.MODE_PRIVATE).edit().clear().commit()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.cacheDir, "atlas-flow-711-land").apply { mkdirs() }
        val items = (0 until 600).map { i ->
            val image = File(dir, "land-$i.png")
            if (!image.exists()) {
                val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
                Canvas(bitmap).drawColor(Color.HSVToColor(floatArrayOf((i * 53f) % 360f, .5f, .8f)))
                FileOutputStream(image).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            MediaRecord(i.toLong(), Uri.fromFile(image).toString(), "Land $i", "DCIM/ATLAS-TEST/",
                1_000L + i, 1_700_000_000_000L - (i / 9) * 86_400_000L, false,
                providerVersion = SYNTHETIC_PROVIDER_VERSION, generationModified = 1L)
        }
        compose.setContent {
            CachedThumbnailEvidence {
                AtlasTimelineGallery(items = items, sort = TimelineSort.NEWEST,
                    gridState = rememberLazyGridState(), onOpen = {}, modifier = Modifier.fillMaxSize(),
                    viewKey = "flow1-land", catalogueEpoch = 12L)
            }
        }
        compose.waitUntil(30_000L) {
            compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
                "4 columns")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.awaitNeighboursPlanned()
        val grid = compose.onNodeWithTag("atlas-timeline-grid")
        val g = grid.fetchSemanticsNode().boundsInRoot
        val local = Offset(g.width * .6f, g.height * .5f)
        grid.performTouchInput { down(0, local - Offset(260f, 0f)); down(1, local + Offset(260f, 0f)) }
        val target = 260f * 1.5f.pow(-.5f)
        for (i in 1..8) {
            val s = 260f + (target - 260f) * i / 8
            grid.performTouchInput { moveTo(0, local - Offset(s, 0f)); moveTo(1, local + Offset(s, 0f)) }
        }
        compose.waitForIdle()
        saveUiEvidence("flow1-landscape-in-50.png")
        grid.performTouchInput { up(0); up(1) }
        compose.waitForIdle()
        grid.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "7 columns"))
        saveUiEvidence("flow1-landscape-settled-7.png")
        assertTrue(abs(g.width - 2340f) < 2340f) // sanity: measured
    }
}
