package nl.atlas.gallery.featuremedia

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * #555: which grid slots to warm before they scroll on screen (Samsung
 * thumbnail behaviour T4/T5/T7/T8, adapted to Coil). Pure and allocation-light so
 * it can run every frame and be tested on the JVM.
 *
 * - Ahead of the scroll direction the window grows with speed: at rest it
 *   covers [IDLE_ROWS_AHEAD] rows, while flinging it covers the rows the grid
 *   will cross in [LOOKAHEAD_SECONDS], capped at [MAX_ROWS_AHEAD].
 * - A short window is always kept behind, so a direction change is not grey.
 * - Slots ahead come first, nearest first: the next row decodes before a far
 *   one (Samsung serves the newest, closest request first).
 */
internal object ThumbnailPrefetchPlan {
    const val IDLE_ROWS_AHEAD = 2
    const val ROWS_BEHIND = 1
    const val MAX_ROWS_AHEAD = 12
    const val LOOKAHEAD_SECONDS = 0.35f

    /**
     * Hard ceiling on slots per side, whatever `columns` says. The widest real
     * grid (28 columns at the densest zoom stop) needs 12 * 28 = 336 slots;
     * without a ceiling a corrupt or extreme column count against a large
     * catalogue asked for an IntArray of ~2^31 slots (OutOfMemoryError in the
     * PERF-21 scale tests, run 43848830646).
     */
    const val MAX_SLOTS_PER_SIDE = 512

    /**
     * Rows to warm ahead for a scroll speed in rows per second (sign ignored).
     * Non-finite speeds are treated as idle rather than as "infinitely fast".
     */
    fun rowsAhead(rowsPerSecond: Float): Int {
        if (!rowsPerSecond.isFinite()) return IDLE_ROWS_AHEAD
        val needed = ceil(abs(rowsPerSecond) * LOOKAHEAD_SECONDS).toInt()
        return min(MAX_ROWS_AHEAD, max(IDLE_ROWS_AHEAD, needed))
    }

    /**
     * Slot indices to prefetch, nearest first, excluding the visible range.
     *
     * @param firstVisible first visible lazy slot (inclusive)
     * @param lastVisible last visible lazy slot (inclusive)
     * @param slotCount total lazy slots (headers included)
     * @param columns slots per full row; header slots count as one slot, which
     *   only makes the window slightly longer in rows, never shorter
     * @param direction +1 scrolling towards higher indices, -1 towards lower, 0 idle
     */
    fun slots(
        firstVisible: Int,
        lastVisible: Int,
        slotCount: Int,
        columns: Int,
        direction: Int,
        rowsPerSecond: Float
    ): IntArray {
        if (slotCount <= 0 || columns <= 0 || firstVisible < 0 || lastVisible < firstVisible) {
            return IntArray(0)
        }
        // A completely out-of-range visible span has no valid near-side window.
        if (firstVisible >= slotCount) return IntArray(0)
        val last = min(lastVisible, slotCount - 1)
        val availableAfter = slotCount - 1 - last
        val availableBefore = firstVisible

        // Clip before converting to Int. MAX_ROWS_AHEAD * Int.MAX_VALUE fits Long;
        // each side is bounded by its physical gap and by MAX_SLOTS_PER_SIDE.
        fun boundedRows(rows: Int, available: Int): Int =
            (rows.toLong() * columns.toLong())
                .coerceAtMost(available.toLong())
                .coerceAtMost(MAX_SLOTS_PER_SIDE.toLong())
                .toInt()
        val aheadAfter = boundedRows(rowsAhead(rowsPerSecond), availableAfter)
        val aheadBefore = boundedRows(rowsAhead(rowsPerSecond), availableBefore)
        val behindAfter = boundedRows(ROWS_BEHIND, availableAfter)
        val behindBefore = boundedRows(ROWS_BEHIND, availableBefore)
        val idleAfter = boundedRows(IDLE_ROWS_AHEAD, availableAfter)
        val idleBefore = boundedRows(IDLE_ROWS_AHEAD, availableBefore)
        // Idle: warm both sides equally (a user may scroll either way next).
        val after = when {
            direction > 0 -> aheadAfter
            direction < 0 -> behindAfter
            else -> idleAfter
        }
        val before = when {
            direction < 0 -> aheadBefore
            direction > 0 -> behindBefore
            else -> idleBefore
        }
        val out = IntArray(after + before)
        var n = 0
        if (direction == 0) {
            // Idle: alternate sides by distance.
            for (d in 1..max(after, before)) {
                if (d <= after) out[n++] = last + d
                if (d <= before) out[n++] = firstVisible - d
            }
        } else if (direction > 0) {
            // Ahead first, nearest first; rows just passed are usually still warm.
            for (d in 1..after) out[n++] = last + d
            for (d in 1..before) out[n++] = firstVisible - d
        } else {
            for (d in 1..before) out[n++] = firstVisible - d
            for (d in 1..after) out[n++] = last + d
        }
        return out
    }
}

/**
 * Smoothed scroll speed in slots per second from successive frame samples.
 * One instance per grid; [sample] is called once per frame with the
 * absolute slot position (first visible index + fractional offset).
 */
internal class ScrollVelocityTracker(private val smoothing: Float = 0.5f) {
    private var lastPosition = Float.NaN
    private var lastNanos = 0L
    var slotsPerSecond = 0f
        private set

    fun sample(position: Float, frameNanos: Long) {
        if (lastPosition.isNaN() || frameNanos <= lastNanos) {
            lastPosition = position
            lastNanos = frameNanos
            return
        }
        val dt = (frameNanos - lastNanos) / 1_000_000_000f
        // A long pause (no frames) is rest, not a slow scroll.
        val instant = if (dt > 0.25f) 0f else (position - lastPosition) / dt
        slotsPerSecond = slotsPerSecond * smoothing + instant * (1f - smoothing)
        if (abs(slotsPerSecond) < 0.05f) slotsPerSecond = 0f
        lastPosition = position
        lastNanos = frameNanos
    }

    val direction: Int get() = when {
        slotsPerSecond > 0f -> 1
        slotsPerSecond < 0f -> -1
        else -> 0
    }
}
