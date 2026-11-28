package nl.atlas.gallery.featuregrid

import android.graphics.Bitmap
import java.util.BitSet
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * PERF-HASH (#592), test-only prototype: compact per-item placeholders a grid
 * tile could draw while its micro thumbnail is not yet in memory.
 *
 * Two encodings, both held in ONE primitive array for the whole library:
 * - [AverageColourTable]: a 2x2 block of average colours, four RGB565 values
 *   packed into one Long (8 bytes per item).
 * - [ThumbHashTable]: a ThumbHash-class DCT hash (opaque photos: at most 25
 *   bytes), written in our own Kotlin from the public algorithm description
 *   (luminance plus two chroma channels as low-frequency cosine terms, a
 *   5-byte header, 4-bit AC terms). Fixed stride of 26 bytes (1 length byte).
 *
 * Synthetic media only. Nothing in app/src/main uses this; a production unit
 * would move it there only if the benchmark justifies it.
 */
internal object PerfHashCodec {
    /** Longest side the hash encoder reads; the public description allows up to 100. */
    const val ENCODE_MAX_SIDE = 64

    /** Longest side of a decoded hash bitmap (as in the public description). */
    const val DECODE_MAX_SIDE = 32

    /** Four quadrant averages of [bitmap] as RGB565, packed top-left first. */
    fun averageColour2x2(bitmap: Bitmap): Long {
        val w = bitmap.width
        val h = bitmap.height
        require(w >= 2 && h >= 2) { "bitmap too small: ${w}x$h" }
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        return averageColour2x2(pixels, w, h)
    }

    fun averageColour2x2(argb: IntArray, w: Int, h: Int): Long {
        val sums = LongArray(12)
        val counts = IntArray(4)
        val midX = w / 2
        val midY = h / 2
        for (y in 0 until h) {
            val row = if (y < midY) 0 else 2
            for (x in 0 until w) {
                val q = row + if (x < midX) 0 else 1
                val c = argb[y * w + x]
                sums[q * 3] += (c ushr 16 and 0xFF).toLong()
                sums[q * 3 + 1] += (c ushr 8 and 0xFF).toLong()
                sums[q * 3 + 2] += (c and 0xFF).toLong()
                counts[q]++
            }
        }
        var packed = 0L
        for (q in 0 until 4) {
            val n = counts[q].coerceAtLeast(1)
            val r = (sums[q * 3] / n).toInt()
            val g = (sums[q * 3 + 1] / n).toInt()
            val b = (sums[q * 3 + 2] / n).toInt()
            val rgb565 = ((r shr 3) shl 11) or ((g shr 2) shl 5) or (b shr 3)
            packed = packed or (rgb565.toLong() shl (q * 16))
        }
        return packed
    }

    /** Quadrant [q] (0 top-left, 1 top-right, 2 bottom-left, 3 bottom-right) as opaque ARGB. */
    fun quadrantArgb(packed: Long, q: Int): Int {
        val v = (packed ushr (q * 16)).toInt() and 0xFFFF
        val r = (v ushr 11) and 0x1F
        val g = (v ushr 5) and 0x3F
        val b = v and 0x1F
        return (0xFF shl 24) or (((r shl 3) or (r shr 2)) shl 16) or
            (((g shl 2) or (g shr 4)) shl 8) or ((b shl 3) or (b shr 2))
    }

    /** Hash of an opaque bitmap; scaled down to [ENCODE_MAX_SIDE] first. */
    fun thumbHash(bitmap: Bitmap): ByteArray {
        val scale = min(1.0, ENCODE_MAX_SIDE.toDouble() / max(bitmap.width, bitmap.height))
        val w = max(1, (bitmap.width * scale).roundToInt())
        val h = max(1, (bitmap.height * scale).roundToInt())
        val small = if (w == bitmap.width && h == bitmap.height) bitmap
            else Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        if (small !== bitmap) small.recycle()
        return thumbHash(pixels, w, h)
    }

    /**
     * Opaque-image encoder (alpha ignored; photos and micro thumbnails are
     * opaque). Luminance gets up to 7 terms on the long side, chroma 3.
     */
    fun thumbHash(argb: IntArray, w: Int, h: Int): ByteArray {
        require(w in 1..100 && h in 1..100) { "${w}x$h does not fit in 100x100" }
        val n = w * h
        val l = DoubleArray(n)
        val p = DoubleArray(n)
        val q = DoubleArray(n)
        for (i in 0 until n) {
            val c = argb[i]
            val r = (c ushr 16 and 0xFF) / 255.0
            val g = (c ushr 8 and 0xFF) / 255.0
            val b = (c and 0xFF) / 255.0
            l[i] = (r + g + b) / 3.0
            p[i] = (r + g) / 2.0 - b
            q[i] = r - g
        }
        val longSide = max(w, h)
        val lx = max(1, (7.0 * w / longSide).roundToInt())
        val ly = max(1, (7.0 * h / longSide).roundToInt())
        val lc = encodeChannel(l, w, h, max(3, lx), max(3, ly))
        val pc = encodeChannel(p, w, h, 3, 3)
        val qc = encodeChannel(q, w, h, 3, 3)
        val landscape = w > h
        val header24 = (63.0 * lc.dc).roundToInt() or
            ((31.5 + 31.5 * pc.dc).roundToInt() shl 6) or
            ((31.5 + 31.5 * qc.dc).roundToInt() shl 12) or
            ((31.0 * lc.scale).roundToInt() shl 18)
        val header16 = (if (landscape) ly else lx) or
            ((63.0 * pc.scale).roundToInt() shl 3) or
            ((63.0 * qc.scale).roundToInt() shl 9) or
            ((if (landscape) 1 else 0) shl 15)
        val acCount = lc.ac.size + pc.ac.size + qc.ac.size
        val out = ByteArray(5 + (acCount + 1) / 2)
        out[0] = (header24 and 0xFF).toByte()
        out[1] = (header24 shr 8 and 0xFF).toByte()
        out[2] = (header24 shr 16 and 0xFF).toByte()
        out[3] = (header16 and 0xFF).toByte()
        out[4] = (header16 shr 8 and 0xFF).toByte()
        var index = 0
        for (ac in arrayOf(lc.ac, pc.ac, qc.ac)) {
            for (f in ac) {
                val nibble = (15.0 * f).roundToInt().coerceIn(0, 15)
                val at = 5 + (index shr 1)
                out[at] = (out[at].toInt() or (nibble shl ((index and 1) shl 2))).toByte()
                index++
            }
        }
        return out
    }

    private class Channel(val dc: Double, val ac: DoubleArray, val scale: Double)

    private fun encodeChannel(channel: DoubleArray, w: Int, h: Int, nx: Int, ny: Int): Channel {
        var dc = 0.0
        val ac = ArrayList<Double>()
        var scale = 0.0
        val fx = DoubleArray(w)
        for (cy in 0 until ny) {
            var cx = 0
            while (cx * ny < nx * (ny - cy)) {
                for (x in 0 until w) fx[x] = cos(PI / w * cx * (x + 0.5))
                var f = 0.0
                for (y in 0 until h) {
                    val fy = cos(PI / h * cy * (y + 0.5))
                    val row = y * w
                    for (x in 0 until w) f += channel[row + x] * fx[x] * fy
                }
                f /= (w * h).toDouble()
                if (cx > 0 || cy > 0) {
                    ac += f
                    scale = max(scale, abs(f))
                } else {
                    dc = f
                }
                cx++
            }
        }
        val normalised = DoubleArray(ac.size) { if (scale > 0.0) 0.5 + 0.5 / scale * ac[it] else 0.5 }
        return Channel(dc, normalised, scale)
    }

    /** Width / height of the hashed image, from the header (approximate). */
    fun aspectRatio(hash: ByteArray, offset: Int = 0): Float {
        val header = hash[offset + 3].toInt() and 0xFF
        val landscape = hash[offset + 4].toInt() and 0x80 != 0
        val lx = if (landscape) 7 else header and 7
        val ly = if (landscape) header and 7 else 7
        return lx.toFloat() / max(1, ly)
    }

    /** Decodes a hash into an opaque ARGB_8888 bitmap at most [DECODE_MAX_SIDE] px. */
    fun decode(hash: ByteArray, offset: Int = 0): Bitmap {
        fun u(i: Int) = hash[offset + i].toInt() and 0xFF
        val header24 = u(0) or (u(1) shl 8) or (u(2) shl 16)
        val header16 = u(3) or (u(4) shl 8)
        val lDc = (header24 and 63) / 63.0
        val pDc = ((header24 shr 6) and 63) / 31.5 - 1.0
        val qDc = ((header24 shr 12) and 63) / 31.5 - 1.0
        val lScale = ((header24 shr 18) and 31) / 31.0
        val pScale = ((header16 shr 3) and 63) / 63.0
        val qScale = ((header16 shr 9) and 63) / 63.0
        val landscape = (header16 shr 15) != 0
        val lx = max(3, if (landscape) 7 else header16 and 7)
        val ly = max(3, if (landscape) header16 and 7 else 7)
        var index = 0
        fun channel(nx: Int, ny: Int, scale: Double): DoubleArray {
            val values = ArrayList<Double>()
            for (cy in 0 until ny) {
                var cx = if (cy > 0) 0 else 1
                while (cx * ny < nx * (ny - cy)) {
                    val nibble = (u(5 + (index shr 1)) shr ((index and 1) shl 2)) and 15
                    values += (nibble / 7.5 - 1.0) * scale
                    index++
                    cx++
                }
            }
            return values.toDoubleArray()
        }
        val lAc = channel(lx, ly, lScale)
        // Chroma boost compensates for 4-bit quantisation (public description).
        val pAc = channel(3, 3, pScale * 1.25)
        val qAc = channel(3, 3, qScale * 1.25)
        val ratio = aspectRatio(hash, offset)
        val w = max(1, if (ratio > 1f) DECODE_MAX_SIDE else (DECODE_MAX_SIDE * ratio).roundToInt())
        val h = max(1, if (ratio > 1f) (DECODE_MAX_SIDE / ratio).roundToInt() else DECODE_MAX_SIDE)
        val pixels = IntArray(w * h)
        val fx = DoubleArray(max(lx, 3))
        val fy = DoubleArray(max(ly, 3))
        var i = 0
        for (y in 0 until h) {
            for (cy in fy.indices) fy[cy] = cos(PI / h * (y + 0.5) * cy)
            for (x in 0 until w) {
                for (cx in fx.indices) fx[cx] = cos(PI / w * (x + 0.5) * cx)
                var l = lDc
                var p = pDc
                var q = qDc
                var j = 0
                for (cy in 0 until ly) {
                    var cx = if (cy > 0) 0 else 1
                    val fy2 = fy[cy] * 2.0
                    while (cx * ly < lx * (ly - cy)) {
                        l += lAc[j] * fx[cx] * fy2
                        j++
                        cx++
                    }
                }
                j = 0
                for (cy in 0 until 3) {
                    var cx = if (cy > 0) 0 else 1
                    val fy2 = fy[cy] * 2.0
                    while (cx < 3 - cy) {
                        val f = fx[cx] * fy2
                        p += pAc[j] * f
                        q += qAc[j] * f
                        j++
                        cx++
                    }
                }
                val b = l - 2.0 / 3.0 * p
                val r = (3.0 * l - b + q) / 2.0
                val g = r - q
                pixels[i++] = (0xFF shl 24) or (channel255(r) shl 16) or (channel255(g) shl 8) or channel255(b)
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun channel255(v: Double): Int = (255.0 * v.coerceIn(0.0, 1.0)).roundToInt()
}

/** One Long per item (four RGB565 quadrants) plus a presence bit. */
internal class AverageColourTable(val size: Int) {
    private val packed = LongArray(size)
    private val present = BitSet(size)
    fun put(index: Int, value: Long) { packed[index] = value; present.set(index) }
    fun has(index: Int): Boolean = index in 0 until size && present[index]
    fun get(index: Int): Long = packed[index]
    /** Bytes held for the whole library (array plus presence bits). */
    val bytes: Long get() = size * 8L + (size + 7) / 8
    fun count(): Int = present.cardinality()
}

/** Fixed 26-byte stride (length byte + up to 25 hash bytes) in one ByteArray. */
internal class ThumbHashTable(val size: Int) {
    private val data = ByteArray(size * STRIDE)
    fun put(index: Int, hash: ByteArray) {
        require(hash.size in 5..MAX_HASH) { "hash of ${hash.size} bytes" }
        val at = index * STRIDE
        data[at] = hash.size.toByte()
        System.arraycopy(hash, 0, data, at + 1, hash.size)
    }
    fun has(index: Int): Boolean = index in 0 until size && data[index * STRIDE] != 0.toByte()
    fun decode(index: Int): Bitmap = PerfHashCodec.decode(data, index * STRIDE + 1)
    val bytes: Long get() = data.size.toLong()
    fun meanHashBytes(): Double {
        var total = 0L
        var n = 0
        for (i in 0 until size) {
            val len = data[i * STRIDE].toInt()
            if (len > 0) { total += len; n++ }
        }
        return if (n == 0) 0.0 else total.toDouble() / n
    }

    companion object {
        const val MAX_HASH = 25
        const val STRIDE = MAX_HASH + 1
    }
}
