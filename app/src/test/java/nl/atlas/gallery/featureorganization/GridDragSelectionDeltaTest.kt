package nl.atlas.gallery.featureorganization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DAILY-2 (#241): compare every pointer update with the original full-range
 * algorithm. The reference is deliberately independent of the delta algorithm.
 */
class GridDragSelectionDeltaTest {
    private fun reference(
        anchor: Int,
        end: Int,
        before: Set<String>,
        keyAt: (Int) -> String?
    ): Set<String>? {
        val first = keyAt(anchor) ?: return null
        val selecting = first !in before
        val result = HashSet(before)
        for (slot in minOf(anchor, end)..maxOf(anchor, end)) {
            val key = keyAt(slot) ?: continue
            if (selecting) result.add(key) else result.remove(key)
        }
        return result
    }

    @Test fun allBoundsAndBacktracksMatchTheOriginalForBothModes() {
        // Headers (null), duplicate URIs, and members selected before the drag.
        val slots = listOf(
            "left", "dup", null, "anchor", "dup", "right",
            "already", null, "dup", "end", "outside"
        )
        val lookup: (Int) -> String? = { slots.getOrNull(it) }
        val beforeSets = listOf(
            emptySet(), setOf("anchor"), setOf("dup", "outside"),
            setOf("anchor", "dup", "already", "external"),
            setOf("left", "right", "end", "outside")
        )
        val ends = listOf(10, 9, 6, 4, 3, 1, -2, 5, 10, 0, 3, 8, 2, 2, 11, -1)

        for (before in beforeSets) {
            for (anchor in slots.indices) {
                val drag = DragSelectRange.start(anchor, before, lookup)
                if (slots[anchor] == null) {
                    assertNull(drag)
                    continue
                }
                assertTrue(drag != null)
                assertEquals(reference(anchor, anchor, before, lookup), drag!!.selection)
                for (end in ends) {
                    assertEquals(
                        "anchor=$anchor end=$end before=$before",
                        reference(anchor, end, before, lookup),
                        drag.extendTo(end)
                    )
                    assertEquals(drag.selection, reference(anchor, end, before, lookup))
                }
            }
        }
    }

    @Test fun duplicateUrisDoNotRestoreUntilTheLastOccurrenceLeaves() {
        val slots = listOf("start", "shared", "middle", "shared", "tail", "shared")
        val lookup: (Int) -> String? = { slots.getOrNull(it) }
        val before = setOf("shared", "external")
        val drag = DragSelectRange.start(0, before, lookup)!!
        assertTrue(drag.selecting)
        assertEquals(before + "start", drag.selection)
        assertEquals(before + slots.filterNotNull(), drag.extendTo(5))
        // There are still two "shared" slots in the active range.
        assertTrue("shared" in drag.extendTo(3))
        assertTrue("shared" in drag.extendTo(1))
        assertTrue("shared" in drag.extendTo(0)) // restored from before
        assertEquals(before + "start", drag.selection)

        val deselect = DragSelectRange.start(1, before + "middle", lookup)!!
        assertFalse(deselect.selecting)
        assertFalse("shared" in deselect.extendTo(5))
        assertFalse("shared" in deselect.extendTo(3))
        assertFalse("shared" in deselect.extendTo(1))
        assertEquals(setOf("middle", "external"), deselect.extendTo(0))
        // The anchor remains in range, so shared must remain deselected.
        assertFalse("shared" in deselect.selection)
    }

    @Test fun returnedSetsAreStableSnapshotsAndRepeatedEndReturnsTheSameSet() {
        val lookup: (Int) -> String? = { if (it in 0..20) "uri/$it" else null }
        val drag = DragSelectRange.start(3, setOf("uri/9", "external"), lookup)!!
        val start = drag.selection
        val expanded = drag.extendTo(12)
        val expectedExpanded = reference(3, 12, setOf("uri/9", "external"), lookup)
        assertNotSame(start, expanded)
        assertSame(expanded, drag.extendTo(12))
        drag.extendTo(-1)
        assertEquals(setOf("uri/3", "uri/9", "external"), start)
        assertEquals(expectedExpanded, expanded)
    }

    @Test fun oneSlotExtensionsAndRetractionsReadOnlyChangedSlotsInHundredThousandKeys() {
        val total = 100_000
        var reads = 0
        val lookup: (Int) -> String? = {
            reads++
            if (it in 0 until total) "content://fixture/$it" else null
        }
        val drag = DragSelectRange.start(0, setOf("unrelated"), lookup)!!
        drag.extendTo(total - 2) // The initial bulk move may visit its full delta.
        reads = 0
        val expanded = drag.extendTo(total - 1)
        assertEquals("one new slot, not the old 99,999-slot range", 1, reads)
        assertEquals(total + 1, expanded.size)
        assertTrue("content://fixture/${total - 1}" in expanded)

        reads = 0
        val shrunk = drag.extendTo(total - 2)
        assertEquals("one removed slot, not the old range", 1, reads)
        assertEquals(total, shrunk.size)
        assertFalse("content://fixture/${total - 1}" in shrunk)

        reads = 0
        assertSame(shrunk, drag.extendTo(total - 2))
        assertEquals(0, reads)
    }
}
