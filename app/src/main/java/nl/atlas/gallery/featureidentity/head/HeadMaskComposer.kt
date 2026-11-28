package nl.atlas.gallery.featureidentity.head

import nl.atlas.gallery.featureidentity.engine.FaceBox
import nl.atlas.gallery.featureidentity.engine.TargetFace
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Whole-head crop in source pixels, right/bottom exclusive. It is also the edit fence. */
data class HeadCrop(val left: Int, val top: Int, val width: Int, val height: Int) {
    init { require(left >= 0 && top >= 0 && width > 0 && height > 0) { "Head crop must be inside and non-empty" } }
    val right get() = left + width
    val bottom get() = top + height
    fun contains(x: Int, y: Int) = x in left until right && y in top until bottom
    fun asFaceBox() = FaceBox(left, top, right, bottom)
}

/** Full-size 8-bit mask; 0 = untouched, 255 = fully replaced. */
class HeadMask(val width: Int, val height: Int, val alpha: ByteArray) {
    init { require(width > 0 && height > 0 && alpha.size.toLong() == width.toLong() * height) }
    operator fun get(x: Int, y: Int): Int = alpha[y * width + x].toInt() and 255

    /** Bounding box of selected pixels, or null when the mask selects nothing. */
    fun bounds(): HeadCrop? {
        var l = width; var t = height; var r = -1; var b = -1
        for (y in 0 until height) for (x in 0 until width) if (alpha[y * width + x].toInt() != 0) {
            l = min(l, x); t = min(t, y); r = max(r, x); b = max(b, y)
        }
        return if (r < 0) null else HeadCrop(l, t, r - l + 1, b - t + 1)
    }
}

/**
 * Head geometry and compositing for [HeadSwapEngine]. Pure JVM, deterministic, no logging.
 *
 * - [headCrop] grows the detected face box to the whole head: hair volume and crown above,
 *   ears either side, neck boundary below. This crop is the only area the engine may change.
 * - [priorMask] is the head shape sent to the server (ellipse over crown-to-chin plus a neck
 *   column), always inside the crop.
 * - [changeMask] is the paste mask. It follows the change: every pixel the generated crop
 *   altered beyond noise and that is connected to the head is included, so new hair outside
 *   the prior shape lands and old target hair or an old head outline is not left behind. Holes
 *   are closed and the edge is feathered, fading to zero at the crop border so there is no seam.
 * - [composite] pastes into a copy of the full-resolution source. Every pixel outside the crop,
 *   and every pixel inside with mask 0, is bit-identical to the source.
 */
object HeadMaskComposer {
    /** Face width added on each side for ears and hair volume. */
    const val SIDE_GROWTH = 0.45
    /** Face height added above for crown, hair and hairline. */
    const val TOP_GROWTH = 0.75
    /** Face height added below for chin, jaw shadow and the neck boundary. */
    const val BOTTOM_GROWTH = 0.45
    const val CHANGE_THRESHOLD = 28
    const val CLOSE_RADIUS = 2
    const val FEATHER_RADIUS = 3

    fun headCrop(target: TargetFace, imageWidth: Int, imageHeight: Int): HeadCrop {
        require(imageWidth > 0 && imageHeight > 0)
        val f = target.box
        val l = floorClamp(f.left - f.width * SIDE_GROWTH, imageWidth - 1)
        val t = floorClamp(f.top - f.height * TOP_GROWTH, imageHeight - 1)
        val r = ceilClamp(f.right + f.width * SIDE_GROWTH, l + 1, imageWidth)
        val b = ceilClamp(f.bottom + f.height * BOTTOM_GROWTH, t + 1, imageHeight)
        return HeadCrop(l, t, r - l, b - t)
    }

    fun priorMask(target: TargetFace, crop: HeadCrop, imageWidth: Int, imageHeight: Int): HeadMask {
        val f = target.box
        val alpha = ByteArray(imageWidth * imageHeight)
        val cx = (f.left + f.right) / 2.0
        val crown = f.top - f.height * (TOP_GROWTH - 0.1)
        val cy = (crown + f.bottom) / 2.0
        val ry = (f.bottom - crown) / 2.0
        val rx = f.width * (0.5 + SIDE_GROWTH * 0.8)
        val neckHalf = f.width * 0.3
        val neckTop = f.top + f.height * 0.7
        for (y in crop.top until crop.bottom) for (x in crop.left until crop.right) {
            val dx = (x + 0.5 - cx) / rx
            val dy = (y + 0.5 - cy) / ry
            val inHead = dx * dx + dy * dy <= 1.0
            val inNeck = y + 0.5 >= neckTop && abs(x + 0.5 - cx) <= neckHalf
            if (inHead || inNeck) alpha[y * imageWidth + x] = 255.toByte()
        }
        return HeadMask(imageWidth, imageHeight, alpha)
    }

    /**
     * Paste mask over the crop, crop-sized. [original] and [generated] are crop-sized ARGB.
     * Seeds are the prior head pixels; growth follows changed pixels 4-connected to them.
     */
    fun changeMask(
        original: IntArray,
        generated: IntArray,
        crop: HeadCrop,
        prior: HeadMask,
        threshold: Int = CHANGE_THRESHOLD,
        closeRadius: Int = CLOSE_RADIUS,
        featherRadius: Int = FEATHER_RADIUS
    ): ByteArray {
        val w = crop.width
        val h = crop.height
        val n = w * h
        require(original.size == n && generated.size == n) { "Crop pixels must match the crop" }
        require(threshold in 1..255 && closeRadius >= 0 && featherRadius >= 1)
        val changed = BooleanArray(n) { channelDiff(original[it], generated[it]) > threshold }
        val seed = BooleanArray(n) { prior[crop.left + it % w, crop.top + it / w] != 0 }
        // Flood from the head through changed pixels; unconnected specks (re-rendered background
        // noise) are not part of the head and stay original.
        val region = BooleanArray(n)
        val queue = IntArray(n)
        var head = 0; var tail = 0
        for (i in 0 until n) if (seed[i]) { region[i] = true; queue[tail++] = i }
        while (head < tail) {
            val i = queue[head++]
            val x = i % w; val y = i / w
            fun visit(j: Int) { if (!region[j] && changed[j]) { region[j] = true; queue[tail++] = j } }
            if (x > 0) visit(i - 1)
            if (x < w - 1) visit(i + 1)
            if (y > 0) visit(i - w)
            if (y < h - 1) visit(i + w)
        }
        val closed = if (closeRadius == 0) region
        else erode(dilate(region, w, h, closeRadius), w, h, closeRadius)
        val soft = boxBlur(closed, w, h, featherRadius)
        val out = ByteArray(n)
        for (y in 0 until h) for (x in 0 until w) {
            val edge = min(min(x, w - 1 - x), min(y, h - 1 - y))
            val ramp = min(1.0, edge.toDouble() / featherRadius)
            val a = (soft[y * w + x] * ramp + 0.5).toInt().coerceIn(0, 255)
            out[y * w + x] = a.toByte()
        }
        return out
    }

    /** Pastes [generated] into a copy of [source] through [alpha]; the source is never modified. */
    fun composite(
        source: IntArray,
        imageWidth: Int,
        imageHeight: Int,
        crop: HeadCrop,
        generated: IntArray,
        alpha: ByteArray
    ): IntArray {
        require(source.size.toLong() == imageWidth.toLong() * imageHeight)
        require(crop.right <= imageWidth && crop.bottom <= imageHeight)
        require(generated.size == crop.width * crop.height && alpha.size == generated.size)
        val out = source.copyOf()
        for (y in 0 until crop.height) for (x in 0 until crop.width) {
            val a = alpha[y * crop.width + x].toInt() and 255
            if (a == 0) continue
            val si = (crop.top + y) * imageWidth + crop.left + x
            val g = generated[y * crop.width + x]
            out[si] = if (a == 255) (g or (0xff shl 24)) else blend(source[si], g, a)
        }
        return out
    }

    /** Crop-sized copy of the crop area of a full-size image. */
    fun cut(source: IntArray, imageWidth: Int, crop: HeadCrop): IntArray {
        val out = IntArray(crop.width * crop.height)
        for (y in 0 until crop.height)
            System.arraycopy(source, (crop.top + y) * imageWidth + crop.left, out, y * crop.width, crop.width)
        return out
    }

    private fun channelDiff(a: Int, b: Int): Int = max(
        abs(((a ushr 16) and 255) - ((b ushr 16) and 255)),
        max(abs(((a ushr 8) and 255) - ((b ushr 8) and 255)), abs((a and 255) - (b and 255)))
    )

    private fun blend(base: Int, top: Int, a: Int): Int {
        fun ch(shift: Int) = ((((top ushr shift) and 255) * a + ((base ushr shift) and 255) * (255 - a) + 127) / 255)
        return (0xff shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun prefix(mask: BooleanArray, w: Int, h: Int): IntArray {
        val p = IntArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0
            for (x in 0 until w) {
                if (mask[y * w + x]) row++
                p[(y + 1) * (w + 1) + x + 1] = p[y * (w + 1) + x + 1] + row
            }
        }
        return p
    }

    private fun windowSum(p: IntArray, w: Int, h: Int, x: Int, y: Int, r: Int): Pair<Int, Int> {
        val x0 = max(0, x - r); val x1 = min(w, x + r + 1)
        val y0 = max(0, y - r); val y1 = min(h, y + r + 1)
        val s = p[y1 * (w + 1) + x1] - p[y0 * (w + 1) + x1] - p[y1 * (w + 1) + x0] + p[y0 * (w + 1) + x0]
        return s to (x1 - x0) * (y1 - y0)
    }

    private fun dilate(m: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val p = prefix(m, w, h)
        return BooleanArray(m.size) { windowSum(p, w, h, it % w, it / w, r).first > 0 }
    }

    private fun erode(m: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val p = prefix(m, w, h)
        return BooleanArray(m.size) { val (s, area) = windowSum(p, w, h, it % w, it / w, r); s == area }
    }

    /** Box blur of a binary mask to 0..255. */
    private fun boxBlur(m: BooleanArray, w: Int, h: Int, r: Int): DoubleArray {
        val p = prefix(m, w, h)
        return DoubleArray(m.size) { val (s, area) = windowSum(p, w, h, it % w, it / w, r); 255.0 * s / area }
    }

    private fun floorClamp(v: Double, hi: Int) = kotlin.math.floor(v).toInt().coerceIn(0, hi)
    private fun ceilClamp(v: Double, lo: Int, hi: Int) = kotlin.math.ceil(v).toInt().coerceIn(lo, hi)
}
