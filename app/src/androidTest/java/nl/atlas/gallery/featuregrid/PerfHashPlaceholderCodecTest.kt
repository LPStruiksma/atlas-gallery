package nl.atlas.gallery.featuregrid

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * PERF-HASH (#592): the test-only placeholder encodings round-trip on
 * synthetic pixels. Milliseconds, no media, no UI: safe in every suite.
 */
@RunWith(AndroidJUnit4::class)
class PerfHashPlaceholderCodecTest {
    @Test fun averageColourKeepsEachQuadrant() {
        val w = 8
        val h = 6
        val colours = intArrayOf(Color.rgb(250, 10, 10), Color.rgb(10, 250, 10),
            Color.rgb(10, 10, 250), Color.rgb(200, 200, 40))
        val pixels = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            colours[(if (y < h / 2) 0 else 2) + (if (x < w / 2) 0 else 1)]
        }
        val packed = PerfHashCodec.averageColour2x2(pixels, w, h)
        for (q in 0 until 4) assertClose(colours[q], PerfHashCodec.quadrantArgb(packed, q), 8)
    }

    @Test fun thumbHashFitsTwentyFiveBytesAndKeepsMeanColour() {
        for ((w, h) in listOf(64 to 48, 48 to 64, 64 to 64, 100 to 20)) {
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            // Left half orange, right half teal: low-frequency structure.
            for (y in 0 until h) for (x in 0 until w)
                bitmap.setPixel(x, y, if (x < w / 2) Color.rgb(230, 140, 40) else Color.rgb(30, 150, 160))
            val hash = PerfHashCodec.thumbHash(bitmap)
            assertTrue("${w}x$h hash is ${hash.size} bytes", hash.size in 5..ThumbHashTable.MAX_HASH)
            val ratio = PerfHashCodec.aspectRatio(hash)
            assertEquals("${w}x$h orientation", w >= h, ratio >= 1f)
            val decoded = PerfHashCodec.decode(hash)
            assertTrue(decoded.width <= PerfHashCodec.DECODE_MAX_SIDE && decoded.height <= PerfHashCodec.DECODE_MAX_SIDE)
            // Left and right edges keep their side's hue.
            val left = decoded.getPixel(1, decoded.height / 2)
            val right = decoded.getPixel(decoded.width - 2, decoded.height / 2)
            assertTrue("${w}x$h left should be warm: ${Integer.toHexString(left)}",
                Color.red(left) > Color.blue(left) + 40)
            assertTrue("${w}x$h right should be cool: ${Integer.toHexString(right)}",
                Color.blue(right) > Color.red(right) + 40)
            bitmap.recycle()
            decoded.recycle()
        }
    }

    @Test fun tablesUseOnePrimitiveArray() {
        val avg = AverageColourTable(250_000)
        val hashes = ThumbHashTable(250_000)
        assertTrue("avg bytes ${avg.bytes}", avg.bytes < 2_100_000)
        assertEquals(250_000L * ThumbHashTable.STRIDE, hashes.bytes)
        val bitmap = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(90, 120, 200)) }
        hashes.put(7, PerfHashCodec.thumbHash(bitmap))
        avg.put(7, PerfHashCodec.averageColour2x2(bitmap))
        assertTrue(hashes.has(7) && !hashes.has(8) && avg.has(7) && !avg.has(8))
        assertClose(Color.rgb(90, 120, 200), PerfHashCodec.quadrantArgb(avg.get(7), 3), 8)
        val decoded = hashes.decode(7)
        assertClose(Color.rgb(90, 120, 200), decoded.getPixel(decoded.width / 2, decoded.height / 2), 24)
        bitmap.recycle()
        decoded.recycle()
    }

    private fun assertClose(expected: Int, actual: Int, tolerance: Int) {
        val ok = abs(Color.red(expected) - Color.red(actual)) <= tolerance &&
            abs(Color.green(expected) - Color.green(actual)) <= tolerance &&
            abs(Color.blue(expected) - Color.blue(actual)) <= tolerance
        assertTrue("expected ${Integer.toHexString(expected)} got ${Integer.toHexString(actual)}", ok)
    }
}
