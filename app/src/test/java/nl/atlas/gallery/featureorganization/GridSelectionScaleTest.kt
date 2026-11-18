package nl.atlas.gallery.featureorganization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import nl.atlas.gallery.perf.threadAllocatedBytes

/**
 * PERF-31 (#332): drag-select steps cost O(active range) and select-all at
 * 250k avoids one HashSet node per item. Synthetic keys only. The "before"
 * arm is a frozen copy of the pre-PERF-31 algorithm, including the holder's
 * `replaced` equals + copy that ran on every drag step.
 */
class GridSelectionScaleTest {
    private val total = 250_000
    private val keys = Array(total) { "content://synthetic/media/$it" }
    private val keyAt: (Int) -> String? = { keys.getOrNull(it) }
    private val anchor = 120_000
    private val steps = 200
    private val cellsPerStep = 10 // 2,000 cells in 200 pointer updates

    @Test fun dragThenSelectAllThenDeselectRangeMatchesNaiveReference() {
        // 1. Drag-select 2,000 cells from nothing.
        var holder: Set<String> = emptySet()
        val drag = requireNotNull(DragSelectRange.start(anchor, holder, keyAt))
        holder = drag.selection
        for (s in 1..steps) {
            holder = drag.extendTo(anchor + s * cellsPerStep)
            if (s % 50 == 0) assertEquals(naive(emptySet(), anchor, anchor + s * cellsPerStep, true), HashSet(holder))
        }
        assertEquals(steps * cellsPerStep + 1, holder.size)
        // Backtrack restores exactly.
        holder = drag.extendTo(anchor + 5)
        assertEquals(naive(emptySet(), anchor, anchor + 5, true), HashSet(holder))

        // 2. Select all.
        val all = toggleAll(total, holder, keyAt)
        assertTrue(all is PositionKeySet)
        assertEquals(total, all.size)
        assertEquals(keys.toHashSet(), HashSet(all))
        assertFalse("content://synthetic/media/$total" in all)
        assertFalse("" in all)

        // 3. Deselect a 2,000-cell range by dragging from a selected cell.
        val deselect = requireNotNull(DragSelectRange.start(10_000, all, keyAt))
        assertFalse(deselect.selecting)
        var after: Set<String> = all
        for (s in 1..steps) after = deselect.extendTo(10_000 + s * cellsPerStep)
        assertEquals(naive(keys.toHashSet(), 10_000, 10_000 + steps * cellsPerStep, false), HashSet(after))
        assertEquals(total - (steps * cellsPerStep + 1), after.size)

        // 4. "All" again clears when everything is selected.
        assertTrue(toggleAll(total, all, keyAt).isEmpty())
    }

    @Test fun positionKeySetCollapsesDuplicateSlotsAndSkipsHeadersAndBlanks() {
        val slots = listOf("a", null, "b", "a", "", "c", "b")
        val set = PositionKeySet.of(slots.size) { slots.getOrNull(it) }
        assertEquals(setOf("a", "b", "c"), HashSet(set))
        assertEquals(3, set.size)
        assertEquals(setOf("a", "b", "c"), set)
        assertTrue(PositionKeySet.of(0) { null }.isEmpty())
    }

    @Test fun overlaySnapshotsStayStableAfterLaterSteps() {
        val before = setOf(keys[5], keys[6], "external")
        val drag = requireNotNull(DragSelectRange.start(0, before, keyAt))
        val first = drag.extendTo(10)
        val firstCopy = HashSet(first)
        drag.extendTo(3)
        drag.extendTo(50)
        assertEquals(firstCopy, HashSet(first))
        assertEquals(firstCopy.size, first.size)
    }

    @Test fun reportsDragStepAndSelectAllCostBeforeAfter() {
        val measured = threadAllocatedBytes() != null
        fun allocated(): Long = threadAllocatedBytes() ?: 0L
        val rounds = 5
        repeat(2) { dragOverAll(old = true); dragOverAll(old = false); selectAll(true); selectAll(false) }
        val oldStep = LongArray(rounds); val newStep = LongArray(rounds)
        val oldAll = LongArray(rounds); val newAll = LongArray(rounds)
        var oldStepBytes = 0L; var newStepBytes = 0L; var oldAllBytes = 0L; var newAllBytes = 0L
        repeat(rounds) { r ->
            var a = allocated(); var t = System.nanoTime()
            dragOverAll(old = true)
            oldStep[r] = (System.nanoTime() - t) / steps; oldStepBytes += (allocated() - a) / steps
            a = allocated(); t = System.nanoTime()
            dragOverAll(old = false)
            newStep[r] = (System.nanoTime() - t) / steps; newStepBytes += (allocated() - a) / steps
            a = allocated(); t = System.nanoTime()
            selectAll(true)
            oldAll[r] = System.nanoTime() - t; oldAllBytes += allocated() - a
            a = allocated(); t = System.nanoTime()
            selectAll(false)
            newAll[r] = System.nanoTime() - t; newAllBytes += allocated() - a
        }
        listOf(oldStep, newStep, oldAll, newAll).forEach { it.sort() }
        fun p(a: LongArray, q: Double) = a[((a.size - 1) * q).toInt()]
        println(("PERF-31 at 250k selected (JVM, n=$rounds rounds): drag step (incl. holder replace) " +
            "before p50=%d us p95=%d us alloc=%d B; after p50=%d us p95=%d us alloc=%d B. " +
            "Select-all before p50=%d ms alloc=%d B; after p50=%d ms alloc=%d B").format(
            p(oldStep, .5) / 1_000, p(oldStep, .95) / 1_000, oldStepBytes / rounds,
            p(newStep, .5) / 1_000, p(newStep, .95) / 1_000, newStepBytes / rounds,
            p(oldAll, .5) / 1_000_000, oldAllBytes / rounds,
            p(newAll, .5) / 1_000_000, newAllBytes / rounds))
        if (measured && oldStepBytes > 0) {
            assertTrue(newStepBytes * 10 < oldStepBytes)
            assertTrue(newAllBytes < oldAllBytes)
        }
    }

    private var sink = 0

    /** Deselect-drag 2,000 cells over a 250k selection, as after "All". */
    private fun dragOverAll(old: Boolean) {
        val all = keys.toHashSet()
        var holder: Set<String> = all
        if (old) {
            val drag = requireNotNull(OldDragSelectRange.start(10_000, all, keyAt))
            for (s in 1..steps) holder = oldReplaced(holder, drag.extendTo(10_000 + s * cellsPerStep))
        } else {
            val drag = requireNotNull(DragSelectRange.start(10_000, all, keyAt))
            for (s in 1..steps) holder = newReplaced(holder, drag.extendTo(10_000 + s * cellsPerStep))
        }
        sink += holder.size
    }

    private fun selectAll(old: Boolean) {
        val selected = setOf(keys[1])
        val result = if (old) oldReplaced(selected, oldToggleAll(total, selected, keyAt))
        else newReplaced(selected, toggleAll(total, selected, keyAt))
        sink += result.size
    }

    private fun oldReplaced(uris: Set<String>, next: Set<String>): Set<String> =
        if (next == uris) uris else next.filterTo(HashSet()) { it.isNotBlank() }

    private fun newReplaced(uris: Set<String>, next: Set<String>): Set<String> = when {
        next === uris -> uris
        next is SelectionOverlay || next is PositionKeySet -> next
        next == uris -> uris
        else -> next.filterTo(HashSet()) { it.isNotBlank() }
    }

    private fun naive(before: Set<String>, from: Int, to: Int, selecting: Boolean): Set<String> {
        val result = HashSet(before)
        for (i in minOf(from, to)..maxOf(from, to)) keyAt(i)?.let { if (selecting) result.add(it) else result.remove(it) }
        return result
    }

    private fun oldToggleAll(total: Int, selected: Set<String>, keyAt: (Int) -> String?): Set<String> {
        if (total <= 0) return emptySet()
        if (selected.size >= total && groupFullySelected(0, total, selected, keyAt)) return emptySet()
        val all = HashSet<String>(total * 2)
        for (i in 0 until total) keyAt(i)?.let(all::add)
        return all
    }

    /** Frozen pre-PERF-31 DragSelectRange (copies the whole selection per step). */
    private class OldDragSelectRange(
        val anchor: Int, val selecting: Boolean, private val before: Set<String>,
        private val keyAt: (Int) -> String?, anchorKey: String
    ) {
        private var lastEnd = anchor
        private val activeKeyCounts = HashMap<String, Int>().apply { put(anchorKey, 1) }
        private var current: Set<String> = HashSet(before).apply { if (selecting) add(anchorKey) else remove(anchorKey) }

        fun extendTo(end: Int): Set<String> {
            if (end == lastEnd) return current
            val oldLow = minOf(anchor, lastEnd); val oldHigh = maxOf(anchor, lastEnd)
            val newLow = minOf(anchor, end); val newHigh = maxOf(anchor, end)
            val next = HashSet(current)
            if (oldLow < newLow) visit(oldLow, newLow - 1, false, next)
            if (newHigh < oldHigh) visit(newHigh + 1, oldHigh, false, next)
            if (newLow < oldLow) visit(newLow, oldLow - 1, true, next)
            if (oldHigh < newHigh) visit(oldHigh + 1, newHigh, true, next)
            current = next; lastEnd = end
            return current
        }

        private fun visit(from: Int, to: Int, entering: Boolean, next: MutableSet<String>) {
            for (slot in from..to) {
                val key = keyAt(slot) ?: continue
                val count = activeKeyCounts[key] ?: 0
                if (entering) {
                    activeKeyCounts[key] = count + 1
                    if (count == 0) { if (selecting) next.add(key) else next.remove(key) }
                } else if (count == 1) {
                    activeKeyCounts.remove(key)
                    if (key in before) next.add(key) else next.remove(key)
                } else if (count > 1) activeKeyCounts[key] = count - 1
            }
        }

        companion object {
            fun start(anchor: Int, before: Set<String>, keyAt: (Int) -> String?): OldDragSelectRange? {
                val key = keyAt(anchor) ?: return null
                return OldDragSelectRange(anchor, key !in before, HashSet(before), keyAt, key)
            }
        }
    }
}
