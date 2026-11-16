package nl.atlas.gallery.featuremedia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

/** DAILY-7: synthetic geometry contracts; no Android runtime or private media. */
class ThumbnailSizingExtremesTest {
    private fun decode(width: Int, height: Int) = thumbnailDecodePixels(width, height)

    @Test fun invalidDimensionsUseThe400PixelFallback() {
        val invalid = intArrayOf(Int.MIN_VALUE, -1000, -1, 0)
        val other = intArrayOf(Int.MIN_VALUE, -1, 0, 1, 31, 401, Int.MAX_VALUE)
        for (bad in invalid) for (value in other) {
            assertEquals("width=$bad height=$value", 400, decode(bad, value))
            assertEquals("width=$value height=$bad", 400, decode(value, bad))
        }
    }

    @Test fun extremePositiveDimensionsAreBoundedAndSymmetric() {
        val sizes = intArrayOf(1, 2, 3, 7, 31, 32, 33, 63, 64, 65, 95, 96,
            97, 127, 128, 129, 255, 256, 257, 399, 400, 401, 575, 599,
            600, 601, 899, 900, 901, 1024, 4096, 65535, 1_000_000,
            Int.MAX_VALUE - 1, Int.MAX_VALUE)
        for (width in sizes) for (height in sizes) {
            val actual = decode(width, height)
            assertTrue("width=$width height=$height yielded $actual", actual in 96..900)
            assertEquals("orientation must not change the budget", actual, decode(height, width))
            assertEquals("same geometry must share a key", actual, decode(width, height))
            assertTrue("uncapped requests use 32px buckets", actual == 900 || actual % 32 == 0)
        }
        assertEquals(900, decode(Int.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(900, decode(Int.MAX_VALUE, 1))
    }

    @Test fun monotonicityHoldsForValidWidthsAndHeights() {
        val dimensions = (1..1024).toList() + listOf(4096, 65535, 1_000_000, Int.MAX_VALUE)
        for (fixed in intArrayOf(1, 37, 200, 599, Int.MAX_VALUE)) {
            var previous = 0
            for (moving in dimensions) {
                val actual = decode(moving, fixed)
                assertTrue("width=$moving fixedHeight=$fixed: $actual < $previous", actual >= previous)
                previous = actual
            }
            previous = 0
            for (moving in dimensions) {
                val actual = decode(fixed, moving)
                assertTrue("fixedWidth=$fixed height=$moving: $actual < $previous", actual >= previous)
                previous = actual
            }
        }
    }

    @Test fun onePointFiveOversamplingAndBucketsRemainStable() {
        for (longest in 1..599) {
            val expected = (ceil(ceil(longest.toDouble() * 1.5) / 32.0)
                .toInt() * 32).coerceIn(96, 900)
            assertEquals("square=$longest", expected, decode(longest, longest))
            assertEquals("landscape=$longest", expected, decode(longest, 1))
            assertEquals("portrait=$longest", expected, decode(1, longest))
        }
        assertEquals(96, decode(1, 1))
        assertEquals(96, decode(64, 1))
        assertEquals(128, decode(65, 1))
        assertEquals(128, decode(85, 1))
        assertEquals(160, decode(86, 1))
        assertEquals(896, decode(597, 1))
        assertEquals(928.coerceAtMost(900), decode(598, 1))
        assertEquals(900, decode(600, 1))
    }

    @Test fun derivedDenseGridColumnsMaintainBoundsAndOrdering() {
        val viewportWidths = intArrayOf(320, 360, 720, 1080, 1440, 2160, Int.MAX_VALUE)
        val columns = intArrayOf(3, 4, 7, 12, 28)
        for (viewport in viewportWidths) {
            var previous = Int.MAX_VALUE
            for (count in columns) {
                val width = viewport / count
                val portrait = decode(width, width * 3 / 4)
                val landscape = decode(width * 3 / 4, width)
                assertEquals("viewport=$viewport columns=$count", portrait, landscape)
                assertTrue("viewport=$viewport columns=$count result=$portrait", portrait in 96..900)
                assertTrue("narrower cells must not request larger decodes", portrait <= previous)
                previous = portrait
            }
        }
    }
}
