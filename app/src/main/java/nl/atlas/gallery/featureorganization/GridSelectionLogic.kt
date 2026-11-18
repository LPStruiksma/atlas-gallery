package nl.atlas.gallery.featureorganization

/**
 * Samsung grid selection rules (parity research `GRID:S1`, `T6`), free of Compose and
 * Android so they run as JVM tests. Positions are indices into the grid's own
 * display order (the same list the timeline groups); [keyAt] returns the
 * selection key (MediaRecord.uri) of a position.
 */

/**
 * One long-press drag. The mode is fixed when the drag starts: starting on an
 * unselected item selects, starting on a selected one deselects. Every update
 * applies the mode to the range anchor..end and gives everything outside the
 * range back its state from before the drag, so shrinking the range undoes
 * exactly what the drag added (Samsung toggles only the difference).
 */
class DragSelectRange private constructor(
    val anchor: Int,
    val selecting: Boolean,
    private val before: Set<String>,
    private val keyAt: (Int) -> String?,
    anchorKey: String
) {
    private var lastEnd = anchor
    // Count *slots*, not just unique keys. Multiple displayed slots may share
    // a URI; moving past one must not restore that URI while another remains.
    private val activeKeyCounts = HashMap<String, Int>().apply { put(anchorKey, 1) }
    // PERF-31 (#332): the drag's effect is a small delta over the frozen
    // pre-drag set. Only keys inside the active range can be in either set,
    // so a step costs O(active range), never O(selection) or O(catalogue).
    private val added = HashSet<String>()
    private val removed = HashSet<String>()
    private var current: Set<String>

    init {
        applyEntering(anchorKey)
        current = snapshot()
    }

    /**
     * Apply just the slots entering or leaving anchor..end. The returned set
     * remains a snapshot: callers may keep an earlier result, and Compose's
     * selection holder compares it with the next result by value.
     */
    fun extendTo(end: Int): Set<String> {
        if (end == lastEnd) return current

        val oldLow = minOf(anchor, lastEnd)
        val oldHigh = maxOf(anchor, lastEnd)
        val newLow = minOf(anchor, end)
        val newHigh = maxOf(anchor, end)

        // The two inclusive ranges always overlap at the anchor. Remove
        // departed slots first, then add arriving slots, with no full scan.
        if (oldLow < newLow) visit(oldLow, newLow - 1, entering = false)
        if (newHigh < oldHigh) visit(newHigh + 1, oldHigh, entering = false)
        if (newLow < oldLow) visit(newLow, oldLow - 1, entering = true)
        if (oldHigh < newHigh) visit(oldHigh + 1, newHigh, entering = true)

        current = snapshot()
        lastEnd = end
        return current
    }

    val selection: Set<String> get() = current

    private fun snapshot(): Set<String> =
        if (added.isEmpty() && removed.isEmpty()) before
        else SelectionOverlay(before, HashSet(added), HashSet(removed))

    private fun applyEntering(key: String) {
        if (selecting) { if (key !in before) added.add(key) }
        else if (key in before) removed.add(key)
    }

    private fun visit(from: Int, to: Int, entering: Boolean) {
        for (slot in from..to) {
            val key = keyAt(slot)?.takeIf { it.isNotBlank() } ?: continue // headers do not select
            val count = activeKeyCounts[key] ?: 0
            if (entering) {
                activeKeyCounts[key] = count + 1
                if (count == 0) applyEntering(key)
            } else if (count == 1) {
                activeKeyCounts.remove(key)
                // Restore the pre-drag state only after the last occurrence exits.
                added.remove(key)
                removed.remove(key)
            } else if (count > 1) {
                activeKeyCounts[key] = count - 1
            }
        }
    }

    companion object {
        /** Null when [anchor] has no key (a header, a vanished item or a blank key). */
        fun start(anchor: Int, before: Set<String>, keyAt: (Int) -> String?): DragSelectRange? {
            val key = keyAt(anchor)?.takeIf { it.isNotBlank() } ?: return null
            // One O(selection) copy per gesture, never per pointer update. It
            // also flattens a previous drag's overlay and drops blank keys.
            val frozen = before.filterTo(HashSet(maxOf(16, before.size * 2))) { it.isNotBlank() }
            return DragSelectRange(anchor, key !in frozen, frozen, keyAt, key)
        }
    }
}

/**
 * Immutable selection: [base] minus [removed] plus [added], where [added] is
 * disjoint from [base] and [removed] is a subset of it. Size and membership
 * are O(1); building one costs O(delta). Every key is non-blank by
 * construction, so [GalleryBatchSelection.replaced] can adopt it without a
 * full copy.
 */
class SelectionOverlay internal constructor(
    private val base: Set<String>,
    private val added: Set<String>,
    private val removed: Set<String>
) : AbstractSet<String>() {
    override val size: Int get() = base.size - removed.size + added.size
    override fun contains(element: String): Boolean =
        element in added || (element in base && element !in removed)
    override fun iterator(): Iterator<String> = iterator {
        for (key in base) if (key !in removed) yield(key)
        yieldAll(added)
    }
}

/**
 * Compact immutable set of the distinct non-blank keys of positions
 * 0 until total, for "select all" at 250k (PERF-31, #332). One open-addressing
 * IntArray of positions instead of one HashMap node per item; membership
 * re-reads the key through [keyAt], which must stay stable for the set's life
 * (it is bound to one immutable gallery list, like the selection itself).
 */
class PositionKeySet private constructor(
    private val keyAt: (Int) -> String?,
    private val table: IntArray,
    override val size: Int
) : AbstractSet<String>() {
    override fun contains(element: String): Boolean {
        if (element.isBlank()) return false
        val mask = table.size - 1
        var i = spread(element.hashCode()) and mask
        while (true) {
            val position = table[i]
            if (position < 0) return false
            if (keyAt(position) == element) return true
            i = (i + 1) and mask
        }
    }

    override fun iterator(): Iterator<String> = iterator {
        for (position in table) if (position >= 0) keyAt(position)?.let { yield(it) }
    }

    companion object {
        private fun spread(h: Int): Int = h xor (h ushr 16)

        fun of(total: Int, keyAt: (Int) -> String?): PositionKeySet {
            var capacity = 16
            while (capacity < total * 2L && capacity < (1 shl 30)) capacity = capacity shl 1
            val table = IntArray(capacity) { -1 }
            val mask = capacity - 1
            var size = 0
            for (position in 0 until total) {
                val key = keyAt(position)?.takeIf { it.isNotBlank() } ?: continue
                var i = spread(key.hashCode()) and mask
                while (true) {
                    val existing = table[i]
                    if (existing < 0) { table[i] = position; size++; break }
                    if (keyAt(existing) == key) break // duplicate slot of the same key
                    i = (i + 1) and mask
                }
            }
            return PositionKeySet(keyAt, table, size)
        }
    }
}

/** True when the group has items and every one of them is selected (T6 auto-check). */
fun groupFullySelected(
    first: Int,
    count: Int,
    selected: Set<String>,
    keyAt: (Int) -> String?
): Boolean {
    if (count <= 0 || selected.isEmpty()) return false
    for (i in first until first + count) {
        val key = keyAt(i) ?: return false
        if (key !in selected) return false
    }
    return true
}

/**
 * Tapping a date header's check circle: a fully selected group is cleared,
 * anything less becomes fully selected.
 */
fun toggleGroup(
    first: Int,
    count: Int,
    selected: Set<String>,
    keyAt: (Int) -> String?
): Set<String> {
    if (count <= 0) return selected
    val clear = groupFullySelected(first, count, selected, keyAt)
    val next = HashSet(selected)
    for (i in first until first + count) {
        val key = keyAt(i) ?: continue
        if (clear) next.remove(key) else next.add(key)
    }
    return next
}

/** Samsung top bar "All": selects everything, or clears when all are already selected. */
fun toggleAll(total: Int, selected: Set<String>, keyAt: (Int) -> String?): Set<String> {
    if (total <= 0) return emptySet()
    if (selected.size >= total && groupFullySelected(0, total, selected, keyAt)) return emptySet()
    // PERF-31 (#332): no per-item HashSet node at 250k; see PositionKeySet.
    return PositionKeySet.of(total, keyAt)
}

/**
 * Edge auto-scroll speed for a finger at [y] in a viewport of [height] px
 * (parity research `GRID:S2`): zero outside the [edge] hot zones, growing linearly with
 * depth into a zone up to [maxStep] px per frame; negative scrolls up.
 */
fun dragAutoScrollStep(y: Float, height: Float, edge: Float, maxStep: Float, top: Float = 0f): Float {
    if (height <= 0f || edge <= 0f || !y.isFinite()) return 0f
    // #568: the zone starts at the grid's visible top, below the pinned
    // selection bar drawn over the grid's top content padding.
    val visibleTop = if (top.isFinite()) top.coerceIn(0f, (height - 2 * edge).coerceAtLeast(0f)) else 0f
    return when {
        y < visibleTop + edge -> -maxStep * ((visibleTop + edge - y) / edge).coerceIn(0f, 1f)
        y > height - edge -> maxStep * ((y - (height - edge)) / edge).coerceIn(0f, 1f)
        else -> 0f
    }
}

/** Samsung's title: "Select items" with nothing checked, else "N selected". */
fun selectionTitle(count: Int): String = if (count <= 0) "Select items" else "$count selected"
