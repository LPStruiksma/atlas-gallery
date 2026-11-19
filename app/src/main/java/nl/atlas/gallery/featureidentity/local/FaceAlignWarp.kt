package nl.atlas.gallery.featureidentity.local

import kotlin.math.ceil
import kotlin.math.floor

/*
 * IDS-9 (#475): five-point alignment between a source photo and a swap
 * network's square crop. Pure JVM, ARGB ints, no Android types. The maths is
 * the closed-form least-squares similarity (Umeyama without reflection), the
 * same family as featureface/onnx/SFaceAlignment, but with the template and
 * crop size taken from the network's spec instead of SFace's fixed 112 px.
 */

/** A 2x3 affine matrix `[a, b, tx, c, d, ty]` mapping (x, y) to (a x + b y + tx, c x + d y + ty). */
class Affine(val m: DoubleArray) {
    init {
        require(m.size == 6 && m.all { it.isFinite() }) { "An affine needs six finite numbers" }
    }

    fun mapX(x: Double, y: Double) = m[0] * x + m[1] * y + m[2]
    fun mapY(x: Double, y: Double) = m[3] * x + m[4] * y + m[5]

    fun inverse(): Affine {
        val det = m[0] * m[4] - m[1] * m[3]
        require(det != 0.0 && det.isFinite()) { "Singular alignment" }
        val i00 = m[4] / det
        val i01 = -m[1] / det
        val i10 = -m[3] / det
        val i11 = m[0] / det
        return Affine(doubleArrayOf(i00, i01, -(i00 * m[2] + i01 * m[5]), i10, i11, -(i10 * m[2] + i11 * m[5])))
    }

    /** Scale factor of a similarity (length of the first column). */
    val scale: Double get() = kotlin.math.sqrt(m[0] * m[0] + m[3] * m[3])
}

/** A rectangle of source pixels: left/top inclusive, right/bottom exclusive. */
data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    init { require(right > left && bottom > top) { "Empty rectangle" } }
    val width get() = right - left
    val height get() = bottom - top
}

object FaceAlignWarp {
    /** ArcFace 112 px five-point template: left eye, right eye, nose tip, left and right mouth corner. */
    val ARCFACE_112: FloatArray = floatArrayOf(
        38.2946f, 51.6963f, 73.5318f, 51.5014f, 56.0252f, 71.7366f, 41.5493f, 92.3655f, 70.7299f, 92.2041f
    )

    /** The ArcFace template scaled to a [size] px crop (what most 128/256/512 px swap networks use). */
    fun arcfaceTemplate(size: Int): FloatArray {
        require(size in 32..2048)
        val s = size / 112f
        return FloatArray(10) { ARCFACE_112[it] * s }
    }

    /**
     * Least-squares similarity (rotation, uniform scale, translation) that maps the five
     * source [landmarks] onto [template]. Both are `x0, y0, ... x4, y4`.
     */
    fun similarity(landmarks: FloatArray, template: FloatArray): Affine {
        require(landmarks.size == 10 && template.size == 10) { "Alignment needs five points" }
        require(landmarks.all { it.isFinite() } && template.all { it.isFinite() }) { "Points must be finite" }
        var sx = 0.0; var sy = 0.0; var tx = 0.0; var ty = 0.0
        for (i in 0 until 5) {
            sx += landmarks[2 * i]; sy += landmarks[2 * i + 1]
            tx += template[2 * i]; ty += template[2 * i + 1]
        }
        sx /= 5; sy /= 5; tx /= 5; ty /= 5
        var norm = 0.0; var dot = 0.0; var cross = 0.0
        for (i in 0 until 5) {
            val ax = landmarks[2 * i] - sx
            val ay = landmarks[2 * i + 1] - sy
            val bx = template[2 * i] - tx
            val by = template[2 * i + 1] - ty
            norm += ax * ax + ay * ay
            dot += ax * bx + ay * by
            cross += ax * by - ay * bx
        }
        require(norm > 1e-6) { "Landmarks collapse to a point" }
        val a = dot / norm
        val b = cross / norm
        require(a * a + b * b > 1e-12) { "Landmarks give no usable scale" }
        return Affine(doubleArrayOf(a, -b, tx - (a * sx - b * sy), b, a, ty - (b * sx + a * sy)))
    }

    /** Source pixels a [size] px crop under [toCrop] can touch, clipped to the image; null if outside. */
    fun sourceRect(toCrop: Affine, size: Int, imageWidth: Int, imageHeight: Int): PixelRect? {
        val inv = toCrop.inverse()
        var minX = Double.POSITIVE_INFINITY; var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY; var maxY = Double.NEGATIVE_INFINITY
        val last = size.toDouble()
        for ((x, y) in listOf(0.0 to 0.0, last to 0.0, 0.0 to last, last to last)) {
            val px = inv.mapX(x, y); val py = inv.mapY(x, y)
            minX = minOf(minX, px); maxX = maxOf(maxX, px)
            minY = minOf(minY, py); maxY = maxOf(maxY, py)
        }
        val l = floor(minX).coerceIn(0.0, imageWidth.toDouble()).toInt()
        val t = floor(minY).coerceIn(0.0, imageHeight.toDouble()).toInt()
        val r = (ceil(maxX) + 1).coerceIn(0.0, imageWidth.toDouble()).toInt()
        val b = (ceil(maxY) + 1).coerceIn(0.0, imageHeight.toDouble()).toInt()
        return if (l >= r || t >= b) null else PixelRect(l, t, r, b)
    }

    /**
     * Bilinear warp of a source region (ARGB, row-major, [rect] in source coordinates)
     * into a [size] x [size] ARGB crop. Samples past the region repeat its edge pixels.
     */
    fun warpToCrop(regionArgb: IntArray, rect: PixelRect, toCrop: Affine, size: Int): IntArray {
        require(regionArgb.size == rect.width * rect.height) { "Pixel count does not match the region" }
        val inv = toCrop.inverse()
        val out = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            // Pixel centres: crop (x+0.5, y+0.5) -> source, then back to the corner convention.
            val sx = inv.mapX(x + 0.5, y + 0.5) - 0.5
            val sy = inv.mapY(x + 0.5, y + 0.5) - 0.5
            out[y * size + x] = sample(regionArgb, rect, sx, sy)
        }
        return out
    }

    /** Bilinear sample of a crop (ARGB, [size] square) at crop coordinates; null outside the crop. */
    fun sampleCrop(crop: IntArray, size: Int, cx: Double, cy: Double): Int? {
        if (cx < -0.5 || cy < -0.5 || cx > size - 0.5 || cy > size - 0.5) return null
        return sample(crop, PixelRect(0, 0, size, size), cx, cy)
    }

    private fun sample(argb: IntArray, rect: PixelRect, sx: Double, sy: Double): Int {
        val x0 = floor(sx).toInt(); val y0 = floor(sy).toInt()
        val fx = sx - x0; val fy = sy - y0
        var r = 0.0; var g = 0.0; var b = 0.0
        fun add(px: Int, py: Int, w: Double) {
            if (w == 0.0) return
            val cx = (px - rect.left).coerceIn(0, rect.width - 1)
            val cy = (py - rect.top).coerceIn(0, rect.height - 1)
            // Clamp to the region edge rather than black: the paste never reads past the photo.
            val p = argb[cy * rect.width + cx]
            r += w * ((p shr 16) and 255); g += w * ((p shr 8) and 255); b += w * (p and 255)
        }
        add(x0, y0, (1 - fx) * (1 - fy)); add(x0 + 1, y0, fx * (1 - fy))
        add(x0, y0 + 1, (1 - fx) * fy); add(x0 + 1, y0 + 1, fx * fy)
        return (0xFF shl 24) or (round8(r) shl 16) or (round8(g) shl 8) or round8(b)
    }

    internal fun round8(v: Double): Int = floor(v + 0.5).toInt().coerceIn(0, 255)
}
