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
    private var current: Set<String> = HashSet(before).apply {
        if (selecting) add(anchorKey) else remove(anchorKey)
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
        val next = HashSet(current)

        // The two inclusive ranges always overlap at the anchor. Remove
        // departed slots first, then add arriving slots, with no full scan.
        if (oldLow < newLow) visit(oldLow, newLow - 1, entering = false, next)
        if (newHigh < oldHigh) visit(newHigh + 1, oldHigh, entering = false, next)
        if (newLow < oldLow) visit(newLow, oldLow - 1, entering = true, next)
        if (oldHigh < newHigh) visit(oldHigh + 1, newHigh, entering = true, next)

        current = next
        lastEnd = end
        return current
    }

    val selection: Set<String> get() = current

    private fun visit(from: Int, to: Int, entering: Boolean, next: MutableSet<String>) {
        for (slot in from..to) {
            val key = keyAt(slot) ?: continue // headers do not select
            val count = activeKeyCounts[key] ?: 0
            if (entering) {
                activeKeyCounts[key] = count + 1
                if (count == 0) {
                    if (selecting) next.add(key) else next.remove(key)
                }
            } else if (count == 1) {
                activeKeyCounts.remove(key)
                // Restore the pre-drag state only after the last occurrence exits.
                if (key in before) next.add(key) else next.remove(key)
            } else if (count > 1) {
                activeKeyCounts[key] = count - 1
            }
        }
    }

    companion object {
        /** Null when [anchor] has no key (a header or a vanished item). */
        fun start(anchor: Int, before: Set<String>, keyAt: (Int) -> String?): DragSelectRange? {
            val key = keyAt(anchor) ?: return null
            return DragSelectRange(anchor, key !in before, HashSet(before), keyAt, key)
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
    val all = HashSet<String>(total * 2)
    for (i in 0 until total) keyAt(i)?.let(all::add)
    return all
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
