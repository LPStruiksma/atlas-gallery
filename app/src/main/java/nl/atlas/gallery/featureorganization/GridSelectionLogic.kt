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
    private val keyAt: (Int) -> String?
) {
    private var lastEnd = anchor
    private var current: Set<String> = apply(anchor, anchor)

    /** The selection after the drag reaches [end]. Unchanged end is O(1). */
    fun extendTo(end: Int): Set<String> {
        if (end == lastEnd) return current
        current = apply(minOf(anchor, end), maxOf(anchor, end))
        lastEnd = end
        return current
    }

    val selection: Set<String> get() = current

    private fun apply(from: Int, to: Int): Set<String> {
        val next = HashSet<String>(before.size + (to - from + 1))
        next.addAll(before)
        for (i in from..to) {
            val key = keyAt(i) ?: continue
            if (selecting) next.add(key) else next.remove(key)
        }
        return next
    }

    companion object {
        /** Null when [anchor] has no key (a header or a vanished item). */
        fun start(anchor: Int, before: Set<String>, keyAt: (Int) -> String?): DragSelectRange? {
            val key = keyAt(anchor) ?: return null
            return DragSelectRange(anchor, key !in before, before, keyAt)
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
