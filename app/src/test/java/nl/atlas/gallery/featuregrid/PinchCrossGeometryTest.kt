package nl.atlas.gallery.featuregrid

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.abs

/** FLOW-1 (#711): the two-layer pinch maths. Synthetic catalogues only. */
class PinchCrossGeometryTest {
    private val width = 1080
    private val height = 2340
    private val gap = 8
    private val header = 110

    private fun geometry(count: Int, density: GridDensity, perDay: Int = 9): TimelineGridGeometry {
        val day = 86_400_000L
        val start = 1_760_000_000_000L
        val index = buildGridTimelineIndex(count, { i -> start - (i / perDay) * day },
            { "synthetic:$it" }, TimelineSort.NEWEST, density, ZoneOffset.UTC, Locale.ROOT)
        val bounds = GridViewportGeometry(width, height, gap, gap, count)
        return planTimelineVisualRows(index, density.columns, bounds, header)
    }

    private fun slotsFor(columns: Int) = composeGridColumnSlots(width - 2 * gap, gap, columns)

    @Test fun columnSlotsMatchComposeFixedCells() {
        // LazyGridDsl: (available − gaps) / n, first `remainder` slots +1 px.
        val slots = composeGridColumnSlots(1064, 8, 7)
        val withoutGaps = 1064 - 8 * 6
        assertEquals(withoutGaps, slots.width.sum())
        assertEquals(withoutGaps % 7, slots.width.count { it == withoutGaps / 7 + 1 })
        for (i in 1 until 7) assertEquals(slots.x[i - 1] + slots.width[i - 1] + 8, slots.x[i])
        assertEquals(0, slots.x[0])
    }

    @Test fun anchorRectSitsUnderTheFocusInEveryLevel() {
        val anchor = 1_234
        val focus = Offset(700f, 1_300f)
        val fractionY = .4f
        val topPad = 180
        for (level in GridDensity.entries) {
            val g = geometry(20_000, level)
            val target = g.targetMedia(anchor, fractionY, focus.y - topPad)
            val rect = crossAnchorRect(g, slotsFor(level.columns), anchor, g.scrollTopFor(target), gap, topPad)
            val pointY = rect.top + rect.height * fractionY
            assertEquals("level $level", focus.y, pointY, 1.01f)
            assertFalse(target.clampedByBounds)
        }
    }

    @Test fun visibleCellsCoverTheViewportAndIncludeTheAnchor() {
        val g = geometry(5_000, GridDensity.SMALL)
        val slots = slotsFor(GridDensity.SMALL.columns)
        val target = g.targetMedia(800, .5f, 900f)
        val scrollTop = g.scrollTopFor(target)
        val cells = crossVisibleCells(g, slots, scrollTop, gap, 180, width - 2 * gap, height)
        assertTrue(cells.isNotEmpty())
        assertTrue(cells.first().top <= gap)
        assertTrue(cells.last().top + cells.last().height >= height - g.rows.maxOf { it.heightPx })
        val anchorSlot = g.timeline.entryForMedia(800)
        val anchorCell = cells.single { it.slotIndex == anchorSlot }
        val rect = crossAnchorRect(g, slots, 800, scrollTop, gap, 180)
        assertEquals(rect.left.toInt(), anchorCell.left)
        assertEquals(rect.top.toInt(), anchorCell.top)
        // Visits only visible rows, never the catalogue.
        assertTrue(cells.size < 7 * 60)
    }

    @Test fun visibleCellsAtTheTopAndForATinyAlbum() {
        val g = geometry(5, GridDensity.MEDIUM)
        val target = g.targetMedia(0, .5f, 50f)
        assertEquals(0L, g.scrollTopFor(target))
        val cells = crossVisibleCells(g, slotsFor(4), 0L, gap, 180, width - 2 * gap, height)
        assertEquals(5 + g.rows.count { it.header }, cells.size)
        assertEquals(180, cells.first().top)
        val empty = planTimelineVisualRows(
            buildGridTimelineIndex(0, { 0L }, { "" }, TimelineSort.NEWEST, GridDensity.MEDIUM,
                ZoneOffset.UTC, Locale.ROOT),
            4, GridViewportGeometry(width, height, gap, gap, 0), header)
        assertTrue(crossVisibleCells(empty, slotsFor(4), 0L, gap, 180, width, height).isEmpty())
    }

    @Test fun bothLayersDrawTheAnchorOnTheSameRectangleAtEveryStep() {
        val a = Rect(300f, 1_200f, 560f, 1_460f)
        val b = Rect(410f, 1_255f, 555f, 1_400f)
        for (step in 0..20) {
            val frame = crossFrame(1f + step / 20f, 4)
            val target = crossAnchorAt(frame, { if (it == 1) a else b }, Offset(480f, 1_300f))
            val viaA = poseOnto(a, target)
            val viaB = poseOnto(b, target)
            val tlA = viaA.map(Offset(a.left, a.top))
            val tlB = viaB.map(Offset(b.left, b.top))
            assertEquals(tlA.x, tlB.x, .01f)
            assertEquals(tlA.y, tlB.y, .01f)
            assertEquals(a.width * viaA.scale, b.width * viaB.scale, .01f)
        }
        // Ends are exact: the live level at rest, then the new level at identity.
        assertEquals(LayerPose.Identity, poseOnto(a, crossAnchorAt(crossFrame(1f, 4), { if (it == 1) a else b }, Offset.Zero)))
        assertEquals(LayerPose.Identity, poseOnto(b, crossAnchorAt(crossFrame(2f, 4), { if (it == 1) a else b }, Offset.Zero)))
    }

    @Test fun crossfadeNeverShowsTheBackgroundAndSwapsOrderInvisibly() {
        val linear: (Float) -> Float = { it }
        for (step in 1 until 20) {
            val frame = crossFrame(2f + step / 20f, 4)
            val lower = crossLayerLook(2, frame, linear)!!
            val upper = crossLayerLook(3, frame, linear)!!
            // One layer is always opaque underneath; the other fades on top.
            assertTrue(lower.onTop != upper.onTop)
            val under = if (lower.onTop) upper else lower
            assertEquals(1f, under.alpha, 0f)
            // Visible share of the upper level equals the eased fraction either way.
            val upperShare = if (upper.onTop) upper.alpha else 1f - lower.alpha
            assertEquals(step / 20f, upperShare, 1e-5f)
            assertNull(crossLayerLook(1, frame, linear))
        }
        val atRest = crossFrame(3f, 4)
        assertEquals(CrossLayerLook(1f, false), crossLayerLook(3, atRest, linear))
        assertNull(crossLayerLook(2, atRest, linear))
    }

    @Test fun zoomPastThreeColumnsKeepsGrowingAboutTheFingers() {
        val rest = Rect(100f, 100f, 400f, 400f)
        val focus = Offset(250f, 250f)
        val frame = crossFrame(-1f, 4)
        assertEquals(0, frame.lower)
        val grown = crossAnchorAt(frame, { rest }, focus)
        assertEquals(300f * CROSS_STOP_ZOOM, grown.width, .01f)
        assertEquals(focus.x, grown.center.x, .01f)
        assertEquals(rest, crossAnchorAt(crossFrame(0f, 4), { rest }, focus))
    }

    @Test fun frameClampsAndRejectsInvalidPositions() {
        assertEquals(CrossFrame(4, 4, 0f, 0f), crossFrame(9f, 4))
        assertEquals(CrossFrame(0, 0, 0f, 0f), crossFrame(Float.NaN, 4))
        val mid = crossFrame(1.25f, 4)
        assertEquals(1, mid.lower)
        assertEquals(2, mid.upper)
        assertEquals(.25f, mid.fraction, 1e-6f)
    }

    @Test fun settleTakesThreeHundredFiftyMillisecondsPerLevelWithAFloor() {
        assertEquals(350, crossSettleMillis(1f, 2f, 350, 120))
        assertEquals(175, crossSettleMillis(1.5f, 2f, 350, 120))
        assertEquals(120, crossSettleMillis(1.95f, 2f, 350, 120))
        assertEquals(0, crossSettleMillis(2f, 2f, 350, 120))
        assertEquals(350, crossSettleMillis(0f, 3f, 350, 120))
    }

    @Test fun estimatedAnchorKeepsTheFocusPointFixed() {
        val rect = estimatedAnchorRect(Offset(500f, 900f), .25f, .75f, 200f)
        assertEquals(500f, rect.left + .25f * rect.width, 1e-3f)
        assertEquals(900f, rect.top + .75f * rect.height, 1e-3f)
        assertTrue(abs(rect.width - 200f) < 1e-3f)
    }

    private fun uniform(count: Int, columns: Int) = UniformGridLevel(count,
        composeGridColumnSlots(width - 2 * gap, 5, columns), 5, gap, height, 200)

    @Test fun plainGridAnchorSitsUnderTheFocusInEveryLevel() {
        val focus = Offset(700f, 1_300f)
        for (columns in listOf(3, 4, 7)) {
            val level = uniform(5_000, columns)
            val target = level.target(1_234, .4f, focus.y)
            assertFalse(target.clampedByBounds)
            val rect = level.anchorRect(1_234, level.scrollTopFor(target))
            assertEquals("columns $columns", focus.y, rect.top + rect.height * .4f, 1.01f)
            val cells = level.visibleCells(level.scrollTopFor(target))
            val anchor = cells.single { it.slotIndex == 1_234 }
            assertEquals(rect.left.toInt(), anchor.left)
            assertEquals(rect.top.toInt(), anchor.top)
            assertTrue(cells.size <= columns * (height / level.pitchPx + 2))
        }
    }

    @Test fun plainGridClampsAtBothEndsAndHandlesTinyAndEmpty() {
        val level = uniform(5_000, 4)
        val top = level.target(1, .5f, 1_500f)
        assertTrue(top.clampedByBounds)
        assertEquals(0L, level.scrollTopFor(top))
        val bottom = level.target(4_999, .5f, 10f)
        assertTrue(bottom.clampedByBounds)
        assertEquals(level.maxScrollPx, level.scrollTopFor(bottom))
        val lastRow = level.visibleCells(level.maxScrollPx).maxOf { it.top + it.height }
        assertEquals(height - 200, lastRow)
        val tiny = uniform(5, 4)
        assertEquals(0L, tiny.maxScrollPx)
        assertEquals(5, tiny.visibleCells(0L).size)
        assertTrue(uniform(0, 4).visibleCells(0L).isEmpty())
    }
}
