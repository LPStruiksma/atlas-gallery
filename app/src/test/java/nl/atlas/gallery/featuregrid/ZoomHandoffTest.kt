package nl.atlas.gallery.featuregrid

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/** #507: the photo under the fingers keeps its screen x when columns change. */
class ZoomHandoffTest {
    private val zone = ZoneId.of("Europe/Amsterdam")
    private val origin = Instant.parse("2026-10-22T12:00:00Z").toEpochMilli()

    @Test fun shiftKeepsTheDrawnTileWhereTheFingersLeftIt() {
        // Four columns of 100 px at scale 1.3: the photo was drawn with the
        // focus 40% of the way across it. In three columns its tile (133 px,
        // drawn at scale 100*1.3/133) starts at x = 140.
        val focusX = 300f
        val oldDrawnLeft = focusX - .4f * 100f * 1.3f
        val newCell = 133f
        val scale = 100f * 1.3f / newCell
        val shift = zoomHandoffShiftPx(focusX, .4f, 140f, newCell, scale)
        val newDrawnLeft = focusX + (140f - focusX) * scale + shift
        assertEquals(oldDrawnLeft, newDrawnLeft, .01f)
    }

    @Test fun noShiftWhenTheTileAlreadyLinesUp() {
        assertEquals(0f, zoomHandoffShiftPx(150f, .5f, 100f, 100f, 1f), 1e-4f)
    }

    @Test fun columnOfMediaFollowsTheRowLayout() {
        // Three photos a day, newest first: each day is a header row, then
        // one photo row, so photo i sits in column i % 3 at three columns.
        val items = List(30) { i -> TimelineMedia("media:$i", origin - (i / 3) * 86_400_000L) }
        val index = buildGridTimelineIndex(items, TimelineSort.NEWEST, GridDensity.entries
            .first { it.columns == 3 }, zone, Locale.UK)
        val geometry = planTimelineVisualRows(index, 3, GridViewportGeometry(420, 810, 3, 3, items.size), 36)
        for (i in items.indices) assertEquals("photo $i", i % 3, geometry.columnOfMedia(i))
    }
}
