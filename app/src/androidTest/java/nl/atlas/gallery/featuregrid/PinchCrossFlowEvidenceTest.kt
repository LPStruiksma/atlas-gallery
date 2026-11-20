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
import nl.atlas.gallery.MediaRecord
import nl.atlas.gallery.S24DisplayRule
import nl.atlas.gallery.benchmark.BenchmarkReport
import nl.atlas.gallery.saveUiEvidence
import nl.atlas.gallery.testkit.advanceVirtualTime
import nl.atlas.gallery.testkit.awaitScreenStill
import nl.atlas.gallery.testkit.awaitThat
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

    private fun show(items: List<MediaRecord>, dark: Boolean = true, viewKey: String,
        selected: Set<String>? = null) {
        compose.setContent {
            AtlasEvidenceHarness(dark = dark) {
                AtlasTimelineGallery(
                    items = items,
                    sort = TimelineSort.NEWEST,
                    gridState = rememberLazyGridState(),
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

    private fun pinchFlowsBetweenLevels(dark: Boolean) {
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
        inward.release()
        grid.assert(columns(7))
        saveUiEvidence("flow1-$theme-in-settled-7.png")
        assertAnchoredAndStill(tag, focusRoot, "commit 4->7")

        if (!dark) return
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

    @Test fun tinyAndEmptyAlbums() {
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

        // An empty album has no grid; a pinch there must do nothing and not crash.
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
        val samples = Collections.synchronizedList(ArrayList<Double>())
        val thread = HandlerThread("frame-metrics-711").apply { start() }
        val listener = android.view.Window.OnFrameMetricsAvailableListener { _, m, _ ->
            samples += m.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000.0
        }
        val window = compose.activity.window
        try {
            window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
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
            window.removeOnFrameMetricsAvailableListener(listener)
        } finally {
            thread.quitSafely()
        }
        val ms = synchronized(samples) { samples.toDoubleArray() }
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
            .toString()
        println("${BenchmarkReport.TAG} 711-pinch-150k json $report")
        Log.i(BenchmarkReport.TAG, "711-pinch-150k json $report")
        assertTrue("frames recorded: $report", ms.isNotEmpty())
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
                1_700_000_000_000L - (i / 9) * 86_400_000L, false)
        }
    }
}

/**
 * Note 3 of the design preflight: the landscape ladder, one mid-pinch and one
 * settled frame on the S24+ turned sideways (2340 × 1080).
 */
/** Neighbouring levels are planned after a short idle pause, off the main thread. */
private fun ComposeTestRule.awaitNeighboursPlanned(timeoutMs: Long = 30_000L) = awaitThat(
    "both neighbouring pinch levels planned", timeoutMs) {
    onAllNodes(SemanticsMatcher.expectValue(CrossNeighboursPlanned, true)).fetchSemanticsNodes().isNotEmpty()
}

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
                1_000L + i, 1_700_000_000_000L - (i / 9) * 86_400_000L, false)
        }
        compose.setContent {
            AtlasEvidenceHarness {
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
