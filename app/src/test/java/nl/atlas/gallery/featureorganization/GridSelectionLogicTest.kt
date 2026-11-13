package nl.atlas.gallery.featureorganization

import nl.atlas.gallery.MediaRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** #556: Samsung drag range (parity research GRID:S1), header check (T6), All and edge auto-scroll (S2). */
class GridSelectionLogicTest {
    private val keys = (0 until 30).map { "content://media/external/images/media/$it" }
    private val keyAt: (Int) -> String? = { keys.getOrNull(it) }
    private fun set(vararg i: Int) = i.map { keys[it] }.toSet()

    @Test fun longPressOnUnselectedSelectsAndDragSelectsTheRangeEitherDirection() {
        val drag = DragSelectRange.start(10, emptySet(), keyAt)!!
        assertTrue(drag.selecting)
        assertEquals(set(10), drag.selection)
        assertEquals(set(10, 11, 12, 13, 14, 15), drag.extendTo(15))
        // Dragging back past the anchor flips the range to the other side.
        assertEquals(set(7, 8, 9, 10), drag.extendTo(7))
    }

    @Test fun shrinkingTheRangeRestoresWhatWasThereBeforeTheDrag() {
        val before = set(2, 13, 20)
        val drag = DragSelectRange.start(10, before, keyAt)!!
        drag.extendTo(16)
        assertEquals(before + set(10, 11, 12, 14, 15, 16), drag.selection)
        // Back to 11: 12..16 return to their own previous state (13 stays selected).
        assertEquals(before + set(10, 11), drag.extendTo(11))
        assertEquals(before + set(10), drag.extendTo(10))
    }

    @Test fun startingOnASelectedItemDeselectsTheRange() {
        val before = set(4, 5, 6, 7, 8, 9)
        val drag = DragSelectRange.start(5, before, keyAt)!!
        assertFalse(drag.selecting)
        assertEquals(set(4, 6, 7, 8, 9), drag.selection)
        assertEquals(set(4, 9), drag.extendTo(8))
        // Past the anchor the other way: 4 and 5 cleared, 6..9 back as before.
        assertEquals(set(6, 7, 8, 9), drag.extendTo(4))
    }

    @Test fun repeatedEndIsFreeAndAHeaderAnchorDoesNotStart() {
        val drag = DragSelectRange.start(3, emptySet(), keyAt)!!
        val first = drag.extendTo(6)
        assertSame(first, drag.extendTo(6))
        assertNull(DragSelectRange.start(3, emptySet()) { null })
    }

    @Test fun dragAcrossSixInAThreeColumnRowSelectsSix() {
        // Long-press on item 0, drag to item 5 (two rows of three): six selected.
        val drag = DragSelectRange.start(0, emptySet(), keyAt)!!
        assertEquals(6, drag.extendTo(5).size)
    }

    @Test fun headerChecksItselfOnlyWhenEveryItemOfItsGroupIsSelected() {
        // Group = items 5..9.
        assertFalse(groupFullySelected(5, 5, set(5, 6, 7, 8), keyAt))
        assertTrue(groupFullySelected(5, 5, set(5, 6, 7, 8, 9), keyAt))
        assertTrue(groupFullySelected(5, 5, set(1, 5, 6, 7, 8, 9, 22), keyAt))
        assertFalse(groupFullySelected(5, 0, set(5), keyAt))
        assertFalse(groupFullySelected(5, 5, emptySet(), keyAt))
    }

    @Test fun headerTapSelectsTheWholeGroupThenClearsIt() {
        val partly = set(1, 6, 7)
        val full = toggleGroup(5, 5, partly, keyAt)
        assertEquals(set(1, 5, 6, 7, 8, 9), full)
        assertTrue(groupFullySelected(5, 5, full, keyAt))
        assertEquals(set(1), toggleGroup(5, 5, full, keyAt))
    }

    @Test fun allSelectsEverythingThenClears() {
        val all = toggleAll(keys.size, set(3), keyAt)
        assertEquals(keys.toSet(), all)
        assertEquals(emptySet<String>(), toggleAll(keys.size, all, keyAt))
        assertEquals(emptySet<String>(), toggleAll(0, set(3), keyAt))
    }

    @Test fun autoScrollIsZeroMidScreenAndGrowsWithDepthIntoTheEdgeZones() {
        val edge = 84f // 28 dp at 3x
        assertEquals(0f, dragAutoScrollStep(500f, 2000f, edge, 30f), 0f)
        assertEquals(-30f, dragAutoScrollStep(0f, 2000f, edge, 30f), 0.001f)
        assertEquals(-15f, dragAutoScrollStep(42f, 2000f, edge, 30f), 0.001f)
        assertEquals(15f, dragAutoScrollStep(2000f - 42f, 2000f, edge, 30f), 0.001f)
        // A finger dragged below the grid scrolls at full speed, never faster.
        assertEquals(30f, dragAutoScrollStep(2300f, 2000f, edge, 30f), 0.001f)
        assertEquals(0f, dragAutoScrollStep(Float.NaN, 2000f, edge, 30f), 0f)
    }

    @Test fun topZoneStartsBelowThePinnedSelectionBar() {
        // #568: a 168 px bar is drawn over the grid's top padding.
        val edge = 84f
        val bar = 168f
        // Just under the bar is inside the zone; before, it scrolled only under the bar.
        assertEquals(-15f, dragAutoScrollStep(bar + 42f, 2000f, edge, 30f, top = bar), 0.001f)
        assertEquals(-30f, dragAutoScrollStep(bar, 2000f, edge, 30f, top = bar), 0.001f)
        assertEquals(-30f, dragAutoScrollStep(10f, 2000f, edge, 30f, top = bar), 0.001f)
        assertEquals(0f, dragAutoScrollStep(bar + edge + 1f, 2000f, edge, 30f, top = bar), 0f)
        // The bottom zone is unchanged, and a nonsense top never disables scrolling.
        assertEquals(15f, dragAutoScrollStep(2000f - 42f, 2000f, edge, 30f, top = bar), 0.001f)
        assertEquals(-30f, dragAutoScrollStep(0f, 2000f, edge, 30f, top = Float.NaN), 0.001f)
        assertEquals(-30f, dragAutoScrollStep(1900f - edge, 2000f, edge, 30f, top = 5000f), 0.001f)
    }

    @Test fun titleFollowsSamsung() {
        assertEquals("Select items", selectionTitle(0))
        assertEquals("1 selected", selectionTitle(1))
        assertEquals("6 selected", selectionTitle(6))
    }

    @Test fun photosSelectionStaysOpenWhenEmptyAndReplacesWholeSets() {
        val items = keys.mapIndexed { i, uri ->
            MediaRecord(i.toLong(), uri, "IMG_$i.jpg", "DCIM/Camera/", 1L, i.toLong(), false)
        }
        assertNull(GalleryBatchSelection.of(items, 1L, emptySet()))
        assertNull(GalleryBatchSelection.of(items, -1L, set(1)))
        val started = GalleryBatchSelection.of(items, 1L, set(1, 2, 3))
        assertNotNull(started)
        started!!
        assertEquals(3, started.count)
        val emptied = started.replaced(emptySet())
        assertEquals(0, emptied.count)
        assertTrue(emptied.isCurrentFor(items, 1L, true))
        val toggled = started.toggledKeepingMode(items[1]).toggledKeepingMode(items[2])
            .toggledKeepingMode(items[3])
        assertEquals(0, toggled.count)
        assertSame(started, started.replaced(set(1, 2, 3)))
    }
}

class GalleryBatchSelectionRebaseTest {
    private fun media(vararg ids: Int) = ids.map {
        MediaRecord(it.toLong(), "content://media/external/images/media/$it", "IMG_$it.jpg",
            "DCIM/Camera/", 1L, it.toLong(), false)
    }

    @Test fun reloadKeepsSurvivingUrisAndBindsToTheNewPublication() {
        val old = media(1, 2, 3, 4)
        val selection = GalleryBatchSelection.of(old, 5L,
            setOf(old[1].uri, old[3].uri))!!
        val reloaded = media(0, 1, 2, 3) // item 4 is gone, item 0 is new
        val rebased = selection.rebasedOnto(reloaded, 6L)
        assertEquals(setOf(old[1].uri), rebased.uris)
        assertTrue(rebased.isCurrentFor(reloaded, 6L, true))
        assertFalse(rebased.isCurrentFor(old, 5L, true))
        assertEquals(0, selection.rebasedOnto(media(9), 6L).count)
    }
}
