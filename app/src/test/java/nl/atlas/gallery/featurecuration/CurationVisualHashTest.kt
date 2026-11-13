package nl.atlas.gallery.featurecuration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.random.Random

/** E2e (#450): perceptual hash and banded pair search, on invented pixels only. */
class CurationVisualHashTest {

    /** A seeded, smooth invented "photo": a gradient plus a few soft blobs. */
    private class Scene(seed: Int) {
        private val random = Random(seed)
        private val gx = random.nextDouble(-60.0, 60.0)
        private val gy = random.nextDouble(-60.0, 60.0)
        private val blobs = List(5) {
            doubleArrayOf(random.nextDouble(), random.nextDouble(),
                random.nextDouble(0.05, 0.25), random.nextDouble(-120.0, 120.0))
        }

        fun luma(u: Double, v: Double): Double {
            var value = 128.0 + gx * (u - 0.5) + gy * (v - 0.5)
            for ((cx, cy, r, a) in blobs.map { it.toList() }) {
                val d2 = (u - cx) * (u - cx) + (v - cy) * (v - cy)
                value += a * exp(-d2 / (r * r))
            }
            return value
        }

        fun render(width: Int, height: Int, noise: Random? = null, quantize: Int = 1): IntArray =
            IntArray(width * height) { index ->
                val x = index % width
                val y = index / width
                var l = luma((x + 0.5) / width, (y + 0.5) / height)
                if (noise != null) l += noise.nextDouble(-4.0, 4.0)
                val g = (l.roundToInt().coerceIn(0, 255) / quantize) * quantize
                // A slight colour cast, as a different encoder might give.
                val r = (g + 3).coerceIn(0, 255)
                (0xff shl 24) or (r shl 16) or (g shl 8) or g
            }
    }

    private fun hash(scene: Scene, w: Int, h: Int, noise: Random? = null, quantize: Int = 1) =
        curationDHash(w, h, scene.render(w, h, noise, quantize))

    @Test fun sameImageGivesTheSameHash() {
        val scene = Scene(11)
        assertEquals(hash(scene, 120, 90), hash(scene, 120, 90))
        assertTrue(hash(scene, 120, 90).informative)
    }

    @Test fun resizedOrReEncodedCopiesStayWithinTheThresholdAndUnrelatedImagesDoNot() {
        for (seed in 1..25) {
            val scene = Scene(seed)
            val original = hash(scene, 120, 90).value
            val resized = hash(scene, 360, 270).value
            val small = hash(scene, 64, 48).value
            val reEncoded = hash(scene, 120, 90, Random(seed * 7), quantize = 8).value
            assertTrue("resized seed $seed",
                curationHammingDistance(original, resized) <= VISUAL_PAIR_MAX_DISTANCE)
            assertTrue("downscaled seed $seed",
                curationHammingDistance(original, small) <= VISUAL_PAIR_MAX_DISTANCE)
            assertTrue("re-encoded seed $seed",
                curationHammingDistance(original, reEncoded) <= VISUAL_PAIR_MAX_DISTANCE)
            val unrelated = hash(Scene(seed + 1000), 120, 90).value
            assertTrue("unrelated seed $seed",
                curationHammingDistance(original, unrelated) > VISUAL_PAIR_MAX_DISTANCE)
        }
    }

    @Test fun flatImagesAreNotInformative() {
        val grey = IntArray(96 * 96) { 0xff808080.toInt() }
        assertFalse(curationDHash(96, 96, grey).informative)
        val black = IntArray(10 * 10) { 0xff000000.toInt() }
        assertFalse(curationDHash(10, 10, black).informative)
        // Smaller than the 9x8 grid still hashes (cells reuse the nearest pixel).
        curationDHash(3, 2, IntArray(6) { it * 40 })
    }

    @Test fun bandsCoverAll64BitsOnce() {
        for (d in 2..31) {
            val bands = curationBands(d)
            assertEquals(d + 1, bands.size)
            assertEquals((0 until 64).toList(), bands.flatMap { it.toList() })
        }
    }

    /** Seeded hashes with planted near, borderline and far pairs. */
    private fun syntheticHashes(count: Int, planted: Int, seed: Int): LongArray {
        val random = Random(seed)
        val hashes = LongArray(count) { random.nextLong() }
        repeat(planted) {
            val source = random.nextInt(count)
            val target = random.nextInt(count)
            if (source == target) return@repeat
            var value = hashes[source]
            repeat(random.nextInt(0, 9)) { value = value xor (1L shl random.nextInt(64)) }
            hashes[target] = value
        }
        return hashes
    }

    private fun bruteForce(hashes: LongArray, max: Int): Set<Pair<Int, Int>> {
        val pairs = HashSet<Pair<Int, Int>>()
        for (i in hashes.indices) for (j in i + 1 until hashes.size)
            if (curationHammingDistance(hashes[i], hashes[j]) <= max) pairs.add(i to j)
        return pairs
    }

    @Test fun bandingFindsEveryPairBruteForceFinds() {
        for (seed in 1..4) {
            val hashes = syntheticHashes(3_000, 600, seed)
            val expected = bruteForce(hashes, VISUAL_PAIR_MAX_DISTANCE)
            assertTrue(expected.size > 100)
            val found = findCurationVisualPairs(hashes, maxPairs = 100_000)
            assertFalse(found.truncated)
            assertEquals(expected, found.matches.map { it.left to it.right }.toSet())
            assertEquals("no pair twice", found.matches.size, expected.size)
            found.matches.forEach {
                assertEquals(curationHammingDistance(hashes[it.left], hashes[it.right]), it.distance)
            }
        }
    }

    @Test fun capKeepsTheClosestPairsAndSkipDropsPairsBeforeTheyTakeASlot() {
        val hashes = syntheticHashes(3_000, 600, 9)
        val all = findCurationVisualPairs(hashes, maxPairs = 100_000).matches
        val capped = findCurationVisualPairs(hashes, maxPairs = 20).matches
        assertEquals(all.take(20), capped)
        val skipped = all.first()
        val withSkip = findCurationVisualPairs(hashes, maxPairs = 20,
            skip = { i, j -> i == skipped.left && j == skipped.right }).matches
        assertFalse(withSkip.any { it.left == skipped.left && it.right == skipped.right })
        assertEquals(20, withSkip.size)
    }

    @Test fun aFloodOfIdenticalHashesStopsAtTheComparisonBudget() {
        val hashes = LongArray(5_000) { 0x5a5a5a5a5a5a5a5aL }
        val found = findCurationVisualPairs(hashes, maxPairs = 10, maxComparisons = 50_000)
        assertTrue(found.truncated)
        assertEquals(50_000L, found.comparisons)
        assertEquals(10, found.matches.size)
    }

    @Test fun cancellationIsChecked() {
        val hashes = LongArray(5_000) { 7L }
        var calls = 0
        try {
            findCurationVisualPairs(hashes, checkpoint = { if (++calls > 3) throw IllegalStateException("cancel") })
            throw AssertionError("not cancelled")
        } catch (expected: IllegalStateException) {
            assertEquals("cancel", expected.message)
        }
    }

    /** The cost the PR states: 50k seeded hashes with planted pairs, one pass. */
    @Test fun fiftyThousandHashesStayWellInsideTheBudget() {
        val hashes = syntheticHashes(50_000, 2_000, 50)
        val started = System.nanoTime()
        val found = findCurationVisualPairs(hashes)
        val millis = (System.nanoTime() - started) / 1_000_000
        println("ATLAS_VISUAL_50K comparisons=${found.comparisons} pairs=${found.matches.size} " +
            "truncated=${found.truncated} ms=$millis")
        assertFalse(found.truncated)
        // All-pairs would be 1.25e9 checks; 6 bands of ~11 bits give ~50k*24*6/2.
        assertTrue("comparisons ${found.comparisons}", found.comparisons < 10_000_000L)
        assertEquals(200, found.matches.size)
    }
}
