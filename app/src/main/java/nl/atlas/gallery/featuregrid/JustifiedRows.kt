package nl.atlas.gallery.featuregrid

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Original media identity, in *current sort order*. A null ratio is untrusted metadata. */
data class RatioMedia(val key: String, val displayAspectRatio: Float?)

/** All geometry is physical pixels so native Compose can convert exactly once at the edge. */
data class RatioLayoutOptions(
    val viewportWidthPx: Int,
    val viewportHeightPx: Int,
    val horizontalInsetPx: Int = 3,
    val gapPx: Int = 3,
    val targetRowHeightPx: Int = 175,
    val maxRowHeightPx: Int = 310,
    val maxItemsPerRow: Int = 6
) {
    init {
        require(canPlanRatioViewport(viewportWidthPx, viewportHeightPx,
            horizontalInsetPx, gapPx))
        require(targetRowHeightPx > 0 && maxRowHeightPx >= targetRowHeightPx)
        require(maxItemsPerRow in 1..16)
    }

    val contentWidthPx: Int get() = viewportWidthPx - 2 * horizontalInsetPx
}

/** One immutable visible row; indexes refer to source catalogue, never photo numeric IDs. */
data class RatioRow(
    val firstMediaIndex: Int,
    val mediaCount: Int,
    val topPx: Long,
    val heightPx: Int,
    val cellWidthsPx: List<Int>,
    val spansFullWidth: Boolean
) {
    init {
        require(mediaCount > 0 && heightPx > 0 && cellWidthsPx.size == mediaCount)
        require(cellWidthsPx.all { it > 0 })
    }
}

class RatioLayout(
    val rows: List<RatioRow>,
    val options: RatioLayoutOptions,
    val mediaCount: Int,
    val contentHeightPx: Long
) {
    /** Exact position mapping without a 150k-entry reverse index. O(log number of rows). */
    fun rowIndexOf(mediaIndex: Int): Int {
        require(mediaIndex in 0 until mediaCount)
        var low = 0
        var high = rows.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val row = rows[mid]
            when {
                mediaIndex < row.firstMediaIndex -> high = mid - 1
                mediaIndex >= row.firstMediaIndex + row.mediaCount -> low = mid + 1
                else -> return mid
            }
        }
        error("No row for current catalogue index $mediaIndex")
    }

    /** Keeps the same *media* under the gesture after square-to-ratio reflow. */
    fun scrollTarget(mediaIndex: Int, insideMediaFractionY: Float, focusYPx: Float): RatioScrollTarget {
        require(focusYPx.isFinite() && insideMediaFractionY.isFinite())
        val rowIndex = rowIndexOf(mediaIndex)
        val row = rows[rowIndex]
        val wanted = row.topPx.toDouble() + row.heightPx.toDouble() *
            insideMediaFractionY.coerceIn(0f, 1f).toDouble() - focusYPx.toDouble()
        val maxScroll = (contentHeightPx - options.viewportHeightPx).coerceAtLeast(0)
        val bounded = wanted.coerceIn(0.0, maxScroll.toDouble()).roundToLong()
        // Use the row containing the *viewport start*, not the focal row. Compose
        // LazyListState has a nonnegative first-visible-item scroll offset contract.
        var low = 0
        var high = rows.lastIndex
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (rows[mid].topPx <= bounded) low = mid else high = mid - 1
        }
        return RatioScrollTarget(
            firstRowIndex = low,
            firstRowScrollOffsetPx = (bounded - rows[low].topPx).toInt(),
            clampedByBounds = abs(wanted - bounded) > 0.501
        )
    }
}

data class RatioScrollTarget(
    val firstRowIndex: Int,
    val firstRowScrollOffsetPx: Int,
    val clampedByBounds: Boolean
)

/**
 * #504 Samsung-style real-ratio level for a phone viewport: edge to edge, thin
 * 2dp gaps, rows aiming at ~150dp tall. Pure so the JVM can check the geometry
 * the device actually gets.
 */
fun samsungRatioLayoutOptions(viewportWidthPx: Int, viewportHeightPx: Int, density: Float):
    RatioLayoutOptions {
    require(density.isFinite() && density > 0f)
    fun dp(value: Float) = (value * density).roundToInt().coerceAtLeast(1)
    return RatioLayoutOptions(
        viewportWidthPx, viewportHeightPx,
        horizontalInsetPx = 0,
        gapPx = dp(RATIO_GAP_DP),
        targetRowHeightPx = dp(150f),
        maxRowHeightPx = dp(240f)
    )
}

/** Gap between real-ratio tiles, both across and between rows. */
const val RATIO_GAP_DP = 2f

/**
 * Original independent row packing; never copies Samsung code.
 *
 * - Unknown/invalid dimensions occupy a *neutral* square estimation.
 * - Every row except the last fills the content width exactly (#504): the host
 *   crops (ContentScale.Crop, centred) inside each cell, so a capped row of very
 *   tall media or a slightly wrong ratio costs a little crop, never a bar.
 * - A very wide panorama can have a shorter-than-target row.
 * - The last row is ragged at the target height; no giant stretched final portrait.
 * - Widths are deterministic integers. Source order is never rearranged.
 */
fun planRatioRows(
    items: List<RatioMedia>,
    options: RatioLayoutOptions,
    cancellationCheckpoint: (() -> Unit)? = null
): RatioLayout {
    if (items.isEmpty()) return RatioLayout(emptyList(), options, 0, 0)
    val rows = ArrayList<RatioRow>((items.size / 2).coerceAtLeast(1))
    val ratios = DoubleArray(options.maxItemsPerRow)
    var offset = 0
    var top = 0L
    val contentWidth = options.contentWidthPx
    while (offset < items.size) {
        if ((offset and 1023) == 0) cancellationCheckpoint?.invoke()
        var count = 0
        var sum = 0.0
        while (offset + count < items.size && count < options.maxItemsPerRow) {
            val candidate = items[offset + count].displayAspectRatio?.toDouble()
            val ratio = if (candidate == null || !candidate.isFinite() || candidate <= 0.0)
                1.0 else candidate.coerceIn(0.08, 16.0)
            val newSum = sum + ratio
            val available = contentWidth - count * options.gapPx
            // A candidate needs at least one physical pixel per media tile.
            // A narrow viewport or oversized gap must end the current row,
            // even when the height-target heuristic would prefer more photos.
            if (count > 0 && available < count + 1) break
            if (count > 0 && available.toDouble() / newSum < options.targetRowHeightPx) {
                val previousAvailable = contentWidth - (count - 1) * options.gapPx
                val beforeHeight = previousAvailable / sum
                val afterHeight = available.toDouble() / newSum
                // Select whichever grouping is closer to the intended row height.
                if (abs(beforeHeight - options.targetRowHeightPx) <=
                    abs(afterHeight - options.targetRowHeightPx)) break
            }
            ratios[count] = ratio
            sum = newSum
            count++
            if (available.toDouble() / sum <= options.targetRowHeightPx) break
        }
        check(count > 0)
        val last = offset + count == items.size
        val available = contentWidth - (count - 1) * options.gapPx
        require(available >= count) { "Viewport too narrow for requested row caps" }
        val fitHeight = available.toDouble() / sum
        // Deliberately allow a single panorama to become short; it stays authentic.
        // All rows cap height to protect huge vertical spans and 150k scroll mapping.
        val height = if (last) {
            minOf(fitHeight, options.targetRowHeightPx.toDouble())
        } else {
            minOf(fitHeight, options.maxRowHeightPx.toDouble())
        }.roundToInt().coerceAtLeast(1)
        val fill = !last
        val nominalWidth = if (fill) available else {
            minOf(available, (sum * height).roundToInt()).coerceAtLeast(count)
        }
        // Balanced cumulative rounding: sum(widths) is guaranteed nominalWidth.
        // Each cell gets >=1 px even for very tall or extremely narrow media.
        val widths = ArrayList<Int>(count)
        var used = 0
        var cumulative = 0.0
        for (j in 0 until count) {
            cumulative += ratios[j]
            val target = if (j == count - 1) nominalWidth else
                (nominalWidth * cumulative / sum).roundToInt()
            val maxHere = nominalWidth - used - (count - j - 1)
            val next = (target - used).coerceIn(1, maxHere)
            widths.add(next)
            used += next
        }
        check(widths.sum() == nominalWidth)
        rows.add(RatioRow(offset, count, top, height, widths, nominalWidth == available))
        top += height.toLong() + options.gapPx
        offset += count
    }
    cancellationCheckpoint?.invoke()
    return RatioLayout(rows, options, items.size, top - options.gapPx)
}
