package nl.atlas.gallery.featuregrid

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.atlas.gallery.MediaRecord
import nl.atlas.gallery.S24DisplayRule
import nl.atlas.gallery.benchmark.BenchmarkReport
import nl.atlas.gallery.featuredesign.AtlasTokens
import nl.atlas.gallery.saveUiEvidence
import nl.atlas.gallery.testkit.advanceVirtualTime
import nl.atlas.gallery.testkit.awaitScreenStill
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
import kotlin.math.abs
import kotlin.math.pow

/**
 * FLOW-1 (#711, review 2): the same two-layer pinch on the plain grid that
 * People albums, Search and the ungrouped Photos fallback use. Mid-gesture
 * frames show both levels; the release settles with the photo under the
 * fingers, the new grid lands within 1 px of where its pinch layer was drawn,
 * and the handover frame pair is measured. Invented images only.
 */
@RunWith(AndroidJUnit4::class)
class PlainGridPinchFlowEvidenceTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(compose)

    private val tile = SemanticsMatcher("plain grid photo tile") {
        it.config.getOrElseNullable(SemanticsProperties.TestTag) { null }
            ?.startsWith("atlas-grid-media-") == true
    }

    private fun columns(n: Int) =
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "$n columns")

    private val grid get() = compose.onNodeWithTag("atlas-feature-grid")

    private var state: LazyGridState? = null

    /** Shows 600 photos at 4 columns, scrolled well into the middle (no clamp at either end). */
    private fun show(dark: Boolean, viewKey: String, showViewAs: Boolean = true) {
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("atlas_featuregrid_density", Context.MODE_PRIVATE).edit().clear().commit()
        val items = fixtures(600)
        compose.setContent {
            CachedThumbnailEvidence(dark = dark) {
                AtlasFeatureGrid(items = items, state = rememberLazyGridState().also { state = it }, busy = false,
                    onOpen = {}, modifier = Modifier.fillMaxSize(), viewKey = viewKey,
                    catalogueEpoch = 21L, showViewAs = showViewAs)
            }
        }
        compose.waitUntil(30_000L) { compose.onAllNodes(columns(4)).fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { checkNotNull(state).requestScrollToItem(240) }
        compose.waitForIdle()
    }

    private fun tileNear(wanted: Offset): Pair<String, Rect> {
        val node = compose.onAllNodes(tile, useUnmergedTree = true).fetchSemanticsNodes()
            .minBy { (it.boundsInRoot.center - wanted).getDistance() }
        return node.config[SemanticsProperties.TestTag] to node.boundsInRoot
    }

    private fun boundsOf(tag: String): Rect? = compose.onAllNodes(
        SemanticsMatcher.expectValue(SemanticsProperties.TestTag, tag), useUnmergedTree = true)
        .fetchSemanticsNodes().firstOrNull()?.boundsInRoot

    private fun crossLayerShown() = compose.onAllNodes(hasTestTag("atlas-pinch-cross-layer"),
        useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun pinch(local: Offset, from: Float, stops: Float, steps: Int = 8) {
        grid.performTouchInput { down(0, local - Offset(from, 0f)); down(1, local + Offset(from, 0f)) }
        moveTo(local, from, from, stops, steps)
    }

    private fun moveTo(local: Offset, from: Float, start: Float, stops: Float, steps: Int = 8): Float {
        val target = from * 1.5f.pow(-stops)
        for (i in 1..steps) {
            val s = start + (target - start) * i / steps
            grid.performTouchInput { moveTo(0, local - Offset(s, 0f)); moveTo(1, local + Offset(s, 0f)) }
        }
        compose.waitForIdle()
        return target
    }

    @Test fun plainGridPinchFlowsDark() = plainGridPinchFlows(dark = true)

    @Test fun plainGridPinchFlowsLight() = plainGridPinchFlows(dark = false)

    private fun plainGridPinchFlows(dark: Boolean) = withAnimatorScale("1") {
        val theme = if (dark) "dark" else "light"
        show(dark, "flow1-plain-$theme")
        val g = grid.fetchSemanticsNode().boundsInRoot
        val (tag, before) = tileNear(Offset(g.left + g.width * .7f, g.top + g.height * .5f))
        val focusRoot = before.center
        val local = focusRoot - g.topLeft
        val index = tag.removePrefix("atlas-grid-media-").substringBefore('-').toInt()

        // 4 → 7, held at a quarter, half and three quarters.
        pinch(local, 260f, .25f); saveUiEvidence("flow1-plain-$theme-in-25.png")
        var span = moveTo(local, 260f, 260f * 1.5f.pow(-.25f), .5f); saveUiEvidence("flow1-plain-$theme-in-50.png")
        assertTrue("both levels are drawn mid-pinch", crossLayerShown())
        span = moveTo(local, 260f, span, .75f); saveUiEvidence("flow1-plain-$theme-in-75.png")

        // Release and step the settle frame by frame: keep the last frame that
        // still shows the pinch layer and the first frame of the real grid.
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        compose.mainClock.autoAdvance = false
        grid.performTouchInput { up(0); up(1) }
        compose.mainClock.advanceTimeBy(48L)
        saveUiEvidence("flow1-plain-$theme-in-settling.png")
        var lastOverlay: Bitmap? = null
        var firstGrid: Bitmap? = null
        for (frame in 0 until 60) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            val shot = automation.takeScreenshot() ?: continue
            if (crossLayerShown()) { lastOverlay?.recycle(); lastOverlay = shot }
            else { firstGrid = shot; break }
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        grid.assert(columns(7))
        saveUiEvidence("flow1-plain-$theme-in-settled-7.png")

        // The anchor photo is under the fingers, and exactly where the pinch
        // layer drew it at rest (UniformGridLevel vs the real LazyGrid).
        val settled = checkNotNull(boundsOf(tag)) { "anchor photo $tag not shown after settle" }
        assertTrue("anchor $settled not under the fingers at ${focusRoot.y}",
            focusRoot.y in settled.top - 1f..settled.bottom + 1f)
        val density = compose.density
        val startPad = with(density) { 3.dp.roundToPx() }
        val gap = with(density) { AtlasTokens.Spacing.GridGap.roundToPx() }
        val level7 = UniformGridLevel(600, composeGridColumnSlots(g.width.toInt() - 2 * startPad, gap, 7),
            gap, startPad, g.height.toInt(), 0)
        val fractionY = ((local.y - (before.top - g.top)) / before.height).coerceIn(0f, 1f)
        val target = level7.target(index, fractionY, local.y)
        val drawn = level7.anchorRect(index, level7.scrollTopFor(target))
        assertEquals("handover: anchor top vs its pinch layer", drawn.top, settled.top - g.top, 1f)
        assertEquals("handover: anchor left vs its pinch layer", drawn.left, settled.left - g.left, 1f)
        assertEquals("handover: anchor size vs its pinch layer", drawn.width, settled.width, 1f)

        // Handover frame pair: the last pinch-layer frame against the first
        // real-grid frame, inside the grid. The layer is drawn from the memory
        // cache and the grid from its own decode, so a small difference is fine.
        val diff = if (lastOverlay != null && firstGrid != null)
            frameDiff(lastOverlay, firstGrid, g) else null
        val report = JSONObject().put("unit", "711-plain-handover").put("theme", theme)
            .put("emulator", BenchmarkReport.isEmulator())
            .put("anchor_top_delta_px", BenchmarkReport.round1((settled.top - g.top - drawn.top).toDouble()))
            .put("anchor_left_delta_px", BenchmarkReport.round1((settled.left - g.left - drawn.left).toDouble()))
            .put("overlay_frame", lastOverlay != null).put("grid_frame", firstGrid != null)
            .put("mean_abs_diff", diff?.first ?: -1.0).put("pixels_over_32_pct", diff?.second ?: -1.0)
            .toString()
        println("${BenchmarkReport.TAG} 711-plain-handover json $report")
        Log.i(BenchmarkReport.TAG, "711-plain-handover json $report")
        lastOverlay?.recycle(); firstGrid?.recycle()
        // The pinch layer draws the same photos the new grid opens with: an
        // empty (grey) layer or a shifted grid differs on most pixels.
        assertTrue("handover frames captured: $report", diff != null)
        assertTrue("handover frame pair close (mean channel diff < 40): $report", diff!!.first < 40.0)

        compose.advanceVirtualTime(1_000L)
        compose.awaitScreenStill("plain grid one second after the settle")
        val later = checkNotNull(boundsOf(tag))
        assertEquals("late vertical jump", settled.top, later.top, 1f)

        if (!dark) return@withAnimatorScale
        // 7 → 4 (spread) half-way, then released below the line: back on 7.
        val (tag2, b2) = tileNear(Offset(g.left + g.width * .4f, g.top + g.height * .4f))
        val local2 = b2.center - g.topLeft
        pinch(local2, 200f, -.5f)
        saveUiEvidence("flow1-plain-dark-out-50.png")
        moveTo(local2, 200f, 200f * 1.5f.pow(.5f), -.1f)
        grid.performTouchInput { up(0); up(1) }
        compose.waitForIdle()
        grid.assert(columns(7))
        saveUiEvidence("flow1-plain-dark-revert-settled-7.png")
        assertTrue("anchor still shown after revert", boundsOf(tag2) != null)
    }

    /**
     * Review 3: the fingers' centre moves while their span changes. The photo
     * under them follows the centre during the pinch, and the new level opens
     * with it where the fingers left it (same point of the photo under the
     * final centre), with no late jump.
     */
    @Test fun plainGridPinchFollowsMovingFingers() = withAnimatorScale("1") {
        show(true, "flow1-plain-moving")
        val g = grid.fetchSemanticsNode().boundsInRoot
        val (tag, before) = tileNear(Offset(g.left + g.width * .7f, g.top + g.height * .4f))
        val start = before.center - g.topLeft
        val fractionY = (start.y - (before.top - g.top)) / before.height
        val delta = Offset(40f, 160f)
        val from = 260f
        grid.performTouchInput { down(0, start - Offset(from, 0f)); down(1, start + Offset(from, 0f)) }
        // The span alone first, until the pinch is recognised (touch slop):
        // the centre then moves only while the pinch is live, so the photo
        // must follow all of [delta].
        val steps = 16
        val still = 6
        val target = from * 1.5f.pow(-.75f)
        for (i in 1..steps) {
            val s = from + (target - from) * i / steps
            val c = start + delta * ((i - still).coerceAtLeast(0).toFloat() / (steps - still))
            grid.performTouchInput { moveTo(0, c - Offset(s, 0f)); moveTo(1, c + Offset(s, 0f)) }
        }
        compose.waitForIdle()
        saveUiEvidence("flow1-plain-moving-in-75.png")
        grid.performTouchInput { up(0); up(1) }
        compose.waitForIdle()
        grid.assert(columns(7))
        saveUiEvidence("flow1-plain-moving-settled-7.png")
        val settled = checkNotNull(boundsOf(tag)) { "anchor photo $tag not shown after a moving pinch" }
        val endY = g.top + start.y + delta.y
        assertEquals("the same point of the photo sits under the fingers' final centre",
            endY, settled.top + fractionY * settled.height, 2f)
        compose.advanceVirtualTime(1_000L)
        compose.awaitScreenStill("moving pinch one second after the settle")
        assertEquals("late vertical jump", settled.top, checkNotNull(boundsOf(tag)).top, 1f)
    }

    /** TalkBack's Smaller tiles takes the same anchored commit as a pinch. */
    @Test fun plainGridSmallerTilesActionKeepsTheMiddlePhoto() {
        show(true, "flow1-plain-a11y", showViewAs = false)
        val g = grid.fetchSemanticsNode().boundsInRoot
        val (tag, _) = tileNear(g.center)
        val smaller = grid.fetchSemanticsNode().config[SemanticsActions.CustomActions]
            .first { it.label == SMALLER_TILES_ACTION }
        compose.runOnIdle { assertTrue(smaller.action()) }
        compose.waitUntil(30_000L) { compose.onAllNodes(columns(7)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        val after = checkNotNull(boundsOf(tag)) { "middle photo $tag not shown after Smaller tiles" }
        assertTrue("middle photo stays near the middle ($after vs ${g.center.y})",
            abs(after.center.y - g.center.y) < after.height * 1.5f)
    }

    /** Mean absolute channel difference and % of pixels differing by more than 32, inside [area]. */
    private fun frameDiff(a: Bitmap, b: Bitmap, area: Rect): Pair<Double, Double> {
        val left = area.left.toInt().coerceAtLeast(0)
        val top = area.top.toInt().coerceAtLeast(0)
        val right = minOf(area.right.toInt(), a.width, b.width)
        val bottom = minOf(area.bottom.toInt(), a.height, b.height)
        var sum = 0L; var over = 0L; var n = 0L
        val rowA = IntArray(right - left); val rowB = IntArray(right - left)
        for (y in top until bottom step 2) {
            a.getPixels(rowA, 0, rowA.size, left, y, rowA.size, 1)
            b.getPixels(rowB, 0, rowB.size, left, y, rowB.size, 1)
            for (x in rowA.indices step 2) {
                val p = rowA[x]; val q = rowB[x]
                val d = maxOf(abs(Color.red(p) - Color.red(q)), abs(Color.green(p) - Color.green(q)),
                    abs(Color.blue(p) - Color.blue(q)))
                sum += d; if (d > 32) over++; n++
            }
        }
        if (n == 0L) return -1.0 to -1.0
        return BenchmarkReport.round1(sum.toDouble() / n) to BenchmarkReport.round1(100.0 * over / n)
    }

    private fun fixtures(count: Int): List<MediaRecord> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.cacheDir, "atlas-flow-711-plain").apply { mkdirs() }
        return (0 until count).map { i ->
            val image = File(dir, "plain-$i.png")
            if (!image.exists()) {
                val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
                Canvas(bitmap).drawColor(Color.HSVToColor(floatArrayOf((i * 41f) % 360f, .5f, .8f)))
                FileOutputStream(image).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            MediaRecord(i.toLong(), Uri.fromFile(image).toString(), "Plain $i", "DCIM/ATLAS-TEST/",
                1_000L + i, 1_700_000_000_000L - i * 3_600_000L, false,
                providerVersion = SYNTHETIC_PROVIDER_VERSION, generationModified = 1L)
        }
    }
}
