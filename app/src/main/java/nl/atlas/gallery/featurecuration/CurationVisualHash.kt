package nl.atlas.gallery.featurecuration

import java.util.PriorityQueue

/*
 * E2e (#450): an on-device perceptual hash for Photo DNA "possible visual
 * matches". Pure Kotlin: the host decodes a small, orientation-corrected
 * thumbnail of an already-authorized image and hands its pixels here. Only
 * the 64-bit hash is kept (in memory); no pixels are retained.
 */

/** Two images whose dHashes differ in at most this many bits are a possible visual pair. */
const val VISUAL_PAIR_MAX_DISTANCE: Int = 5

/**
 * Below this luma step (0..255) everywhere, an image is flat (one colour, a
 * black frame): its dHash is all noise or all zero and would match every
 * other flat image, so it is never paired.
 */
const val VISUAL_HASH_MIN_CONTRAST: Int = 3

private const val GRID_W = 9
private const val GRID_H = 8

/** A 64-bit difference hash. [informative] is false for flat images, which never pair. */
data class CurationVisualHash(val value: Long, val informative: Boolean)

/**
 * dHash: area-average the image to 9x8 luma cells, then one bit per
 * horizontally adjacent cell pair (left brighter than right). Re-encoding or
 * resizing moves few bits; a different picture moves about half of them.
 * [argb] is row-major, width x height, alpha ignored.
 */
fun curationDHash(width: Int, height: Int, argb: IntArray): CurationVisualHash {
    require(width > 0 && height > 0 && argb.size >= width.toLong() * height) { "Invalid pixels" }
    val cells = IntArray(GRID_W * GRID_H)
    for (cy in 0 until GRID_H) {
        val y0 = cy * height / GRID_H
        val y1 = maxOf(y0 + 1, (cy + 1) * height / GRID_H).coerceAtMost(height)
        for (cx in 0 until GRID_W) {
            val x0 = cx * width / GRID_W
            val x1 = maxOf(x0 + 1, (cx + 1) * width / GRID_W).coerceAtMost(width)
            var sum = 0L
            var count = 0
            for (y in y0 until y1) {
                val row = y * width
                for (x in x0 until x1) {
                    sum += luma(argb[row + x])
                    count++
                }
            }
            cells[cy * GRID_W + cx] = (sum / count).toInt()
        }
    }
    var bits = 0L
    var contrast = 0
    for (cy in 0 until GRID_H) for (cx in 0 until GRID_W - 1) {
        val left = cells[cy * GRID_W + cx]
        val right = cells[cy * GRID_W + cx + 1]
        bits = bits shl 1
        if (left > right) bits = bits or 1L
        contrast = maxOf(contrast, kotlin.math.abs(left - right))
    }
    return CurationVisualHash(bits, contrast >= VISUAL_HASH_MIN_CONTRAST)
}

/** ITU-R BT.601 luma, 0..255. */
private fun luma(pixel: Int): Int {
    val r = (pixel ushr 16) and 255
    val g = (pixel ushr 8) and 255
    val b = pixel and 255
    return (299 * r + 587 * g + 114 * b) / 1000
}

fun curationHammingDistance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

/** Indices into the caller's hash array, i < j. */
data class CurationVisualMatch(val left: Int, val right: Int, val distance: Int)

data class CurationVisualMatches(
    /** Closest first, then by index; at most the requested cap. */
    val matches: List<CurationVisualMatch>,
    val comparisons: Long,
    /** The comparison budget ran out: some pairs may be missing. */
    val truncated: Boolean
)

/**
 * Every pair within [maxDistance] bits, without an all-pairs scan. The 64
 * bits are cut into maxDistance + 1 bands; by pigeonhole, two hashes within
 * maxDistance agree exactly on at least one band, so comparing only hashes
 * that share a band value finds every pair a brute-force scan finds. A pair
 * is reported only from the first band it shares, so never twice.
 *
 * Bounded: at most [maxComparisons] Hamming checks (a flood of identical
 * hashes stops there, marked [CurationVisualMatches.truncated]) and only the
 * [maxPairs] closest pairs are kept. [skip] drops pairs before they take a
 * slot (for example byte-identical files that already have an exact group).
 * [checkpoint] is called regularly; it may throw to cancel.
 */
fun findCurationVisualPairs(
    hashes: LongArray,
    maxDistance: Int = VISUAL_PAIR_MAX_DISTANCE,
    maxPairs: Int = 200,
    maxComparisons: Long = 25_000_000L,
    skip: (Int, Int) -> Boolean = { _, _ -> false },
    checkpoint: () -> Unit = {}
): CurationVisualMatches {
    // At least 3 bands, so a band value (<= 22 bits) and an index fit one sort key.
    require(maxDistance in 2..31) { "Invalid distance" }
    require(maxPairs >= 0 && maxComparisons >= 0L) { "Invalid bound" }
    val bands = curationBands(maxDistance)
    // Worst kept pair at the head, so a closer pair can replace it.
    val worstFirst = compareByDescending<CurationVisualMatch> { it.distance }
        .thenByDescending { it.left }.thenByDescending { it.right }
    val kept = PriorityQueue(maxOf(1, maxPairs), worstFirst)
    var comparisons = 0L
    var truncated = false
    val keys = LongArray(hashes.size)
    bandLoop@ for ((band, range) in bands.withIndex()) {
        checkpoint()
        for (i in hashes.indices) keys[i] = (bandValue(hashes[i], range) shl 32) or i.toLong()
        keys.sort()
        var start = 0
        while (start < keys.size) {
            val value = keys[start] ushr 32
            var end = start + 1
            while (end < keys.size && keys[end] ushr 32 == value) end++
            for (a in start until end - 1) {
                val i = (keys[a] and 0xffffffffL).toInt()
                for (b in a + 1 until end) {
                    if (comparisons >= maxComparisons) {
                        truncated = true
                        break@bandLoop
                    }
                    if ((++comparisons and 0xffff) == 0L) checkpoint()
                    val j = (keys[b] and 0xffffffffL).toInt()
                    val distance = curationHammingDistance(hashes[i], hashes[j])
                    if (distance > maxDistance) continue
                    if (firstSharedBand(hashes[i], hashes[j], bands) != band) continue
                    val left = minOf(i, j)
                    val right = maxOf(i, j)
                    if (skip(left, right) || maxPairs == 0) continue
                    val match = CurationVisualMatch(left, right, distance)
                    if (kept.size < maxPairs) kept.add(match)
                    else if (worstFirst.compare(match, kept.peek()) > 0) {
                        kept.poll()
                        kept.add(match)
                    }
                }
            }
            start = end
        }
    }
    val sorted = kept.sortedWith(compareBy<CurationVisualMatch> { it.distance }
        .thenBy { it.left }.thenBy { it.right })
    return CurationVisualMatches(sorted, comparisons, truncated)
}

/** maxDistance + 1 contiguous bit ranges covering all 64 bits, as (shift, width). */
internal fun curationBands(maxDistance: Int): List<IntRange> {
    val count = maxDistance + 1
    val ranges = ArrayList<IntRange>(count)
    var from = 0
    for (band in 0 until count) {
        val width = 64 / count + if (band < 64 % count) 1 else 0
        ranges.add(from until from + width)
        from += width
    }
    return ranges
}

private fun bandValue(hash: Long, range: IntRange): Long {
    val width = range.last - range.first + 1
    return (hash ushr range.first) and ((1L shl width) - 1)
}

private fun firstSharedBand(a: Long, b: Long, bands: List<IntRange>): Int {
    for ((index, range) in bands.withIndex())
        if (bandValue(a, range) == bandValue(b, range)) return index
    return -1
}
