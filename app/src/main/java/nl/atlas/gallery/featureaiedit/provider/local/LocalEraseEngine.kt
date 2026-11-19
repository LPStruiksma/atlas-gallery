package nl.atlas.gallery.featureaiedit.provider.local

import java.io.Closeable
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Half-open pixel rectangle [left, right) × [top, bottom). */
data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    init { require(left <= right && top <= bottom) { "Bad rectangle $this" } }
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = width == 0 || height == 0
}

/** Reads unpremultiplied ARGB pixels of the source, in stored (not EXIF-rotated) orientation. */
interface ArgbRegionReader {
    val width: Int
    val height: Int
    /** Fills [out] row-major with [rect]'s pixels (`out.size >= rect.width * rect.height`). */
    fun read(rect: PixelRect, out: IntArray)
}

/** Reads the erase mask: 1 = erase this pixel, 0 = keep it bit-for-bit. */
interface MaskRegionReader {
    val width: Int
    val height: Int
    fun read(rect: PixelRect, out: ByteArray)
}

/** Receives replacement pixels. Only pixels with `changed[i]` set may be written. */
interface ArgbRegionWriter {
    fun write(rect: PixelRect, argb: IntArray, changed: BooleanArray)
}

/** Cooperative cancellation, checked between bands and before inference. */
fun interface CancelSignal { fun isCancelled(): Boolean }

class EraseCancelled : RuntimeException("Erase cancelled")

/**
 * The inpainting network: 512×512 RGB in 0..1 (NCHW, masked pixels zeroed) plus a binary
 * mask in, 512×512 RGB in 0..255 out. [terminate] aborts a running [inpaint] from another
 * thread (ONNX Runtime `RunOptions.setTerminate`).
 */
interface LamaInpainter : Closeable {
    fun inpaint(image: FloatArray, mask: FloatArray, cancel: CancelSignal): FloatArray
    fun terminate() {}
}

/** What one erase did, for lineage metadata and the measurements doc. */
data class EraseReport(
    val maskBounds: PixelRect?,
    val window: PixelRect?,
    val modelScale: Double,
    val erasedPixels: Long,
    val inferenceMillis: Long,
)

/**
 * The context window around the mask that the model sees, and how it maps onto the
 * model's fixed 512×512 input. Pure geometry.
 */
object EraseWindow {
    /** Context added around the mask, as a fraction of the mask's longer side. */
    const val CONTEXT_FRACTION = 0.5
    const val MIN_CONTEXT = 32

    /**
     * A window containing [mask] plus context on every side, at least [LamaModel.SIZE] on
     * each side where the image allows (so small masks run at native resolution, unscaled),
     * shifted (never cropped) to stay inside the image.
     */
    fun plan(mask: PixelRect, imageWidth: Int, imageHeight: Int, modelSize: Int = LamaModel.SIZE): PixelRect {
        require(!mask.isEmpty && mask.right <= imageWidth && mask.bottom <= imageHeight && mask.left >= 0 && mask.top >= 0)
        val context = max(MIN_CONTEXT, ceil(max(mask.width, mask.height) * CONTEXT_FRACTION).toInt())
        val side = max(modelSize, max(mask.width, mask.height) + 2 * context)
        fun span(lo: Int, hi: Int, limit: Int): Pair<Int, Int> {
            val want = min(limit, max(side, hi - lo + 2 * context))
            var start = (lo + hi) / 2 - want / 2
            start = start.coerceIn(0, limit - want)
            return start to start + want
        }
        val (l, r) = span(mask.left, mask.right, imageWidth)
        val (t, b) = span(mask.top, mask.bottom, imageHeight)
        return PixelRect(l, t, r, b)
    }

    /** Model pixels per source pixel: the window's longer side maps onto [modelSize]. */
    fun scale(window: PixelRect, modelSize: Int = LamaModel.SIZE): Double =
        modelSize.toDouble() / max(window.width, window.height)
}

/**
 * AIE-22 on-device erase pipeline. Never touches the network and never holds the whole
 * image: every pass streams the source in bands of [bandRows] rows, so a 50 MP photo needs
 * a few MB of working memory plus the model.
 *
 * Fidelity guarantee (tested): a pixel whose mask value is 0 is never written, so it stays
 * bit-identical to the source at full resolution. Mask pixels get the model's fill,
 * feathered over [featherPx] pixels inside the mask edge only.
 */
class LocalEraseEngine(
    private val bandRows: Int = 256,
    private val featherPx: Int = 2,
    private val modelSize: Int = LamaModel.SIZE,
) {
    init {
        require(bandRows in 1..4096)
        require(featherPx in 1..16)
        require(modelSize in 8..2048)
    }

    /** Exact bounding box of mask pixels, or null when nothing is marked. Streams in bands. */
    fun maskBounds(mask: MaskRegionReader, cancel: CancelSignal = CancelSignal { false }): PixelRect? {
        var left = Int.MAX_VALUE; var top = Int.MAX_VALUE; var right = -1; var bottom = -1
        val w = mask.width
        val buffer = ByteArray(w * min(bandRows, mask.height))
        var y0 = 0
        while (y0 < mask.height) {
            if (cancel.isCancelled()) throw EraseCancelled()
            val y1 = min(mask.height, y0 + bandRows)
            mask.read(PixelRect(0, y0, w, y1), buffer)
            for (y in y0 until y1) {
                val row = (y - y0) * w
                var first = -1; var last = -1
                for (x in 0 until w) if (buffer[row + x].toInt() != 0) { if (first < 0) first = x; last = x }
                if (first >= 0) {
                    left = min(left, first); right = max(right, last + 1)
                    top = min(top, y); bottom = y + 1
                }
            }
            y0 = y1
        }
        return if (right < 0) null else PixelRect(left, top, right, bottom)
    }

    /**
     * Erases the masked pixels of [source] into [target] (which starts as a copy of the
     * source; the Android IO uses the same bitmap for both). Returns what it did.
     */
    fun erase(
        source: ArgbRegionReader,
        mask: MaskRegionReader,
        target: ArgbRegionWriter,
        inpainter: LamaInpainter,
        cancel: CancelSignal = CancelSignal { false },
        progress: (Double) -> Unit = {},
    ): EraseReport {
        require(source.width == mask.width && source.height == mask.height) { "Mask does not align with source" }
        val bounds = maskBounds(mask, cancel) ?: return EraseReport(null, null, 1.0, 0, 0)
        progress(0.1)
        val window = EraseWindow.plan(bounds, source.width, source.height, modelSize)
        val scale = EraseWindow.scale(window, modelSize)
        val tensors = buildInput(source, mask, window, scale, cancel)
        progress(0.3)
        if (cancel.isCancelled()) throw EraseCancelled()
        val started = System.nanoTime()
        val output = inpainter.inpaint(tensors.image, tensors.mask, cancel)
        val inferenceMillis = (System.nanoTime() - started) / 1_000_000
        if (cancel.isCancelled()) throw EraseCancelled()
        require(output.size == 3 * modelSize * modelSize) { "Model output has ${output.size} values" }
        progress(0.8)
        val erased = compose(source, mask, target, window, scale, tensors, output, cancel)
        progress(1.0)
        return EraseReport(bounds, window, scale, erased, inferenceMillis)
    }

    internal class ModelInput(val image: FloatArray, val mask: FloatArray, val usedWidth: Int, val usedHeight: Int)

    /**
     * Area-averages (downscale) or nearest-samples (upscale) the window into the model's
     * input. Mask: a model pixel is marked when ANY covered source pixel is marked, so the
     * hole always covers the whole object. Unused model area (non-square window) repeats
     * the last row/column, unmasked. Masked image pixels are zeroed, as LaMa was trained.
     */
    internal fun buildInput(
        source: ArgbRegionReader, mask: MaskRegionReader, window: PixelRect, scale: Double, cancel: CancelSignal,
    ): ModelInput {
        val n = modelSize
        val plane = n * n
        val usedW = min(n, max(1, (window.width * scale).roundToInt()))
        val usedH = min(n, max(1, (window.height * scale).roundToInt()))
        val sums = DoubleArray(3 * plane)
        val counts = IntArray(plane)
        val marks = BooleanArray(plane)
        val ww = window.width
        val rows = min(bandRows, window.height)
        val pixels = IntArray(ww * rows)
        val bits = ByteArray(ww * rows)
        // Each source pixel contributes to the model pixel that contains its centre; when
        // upscaling, model pixels are later filled from the nearest contributing pixel.
        val colOf = IntArray(ww) { x -> min(usedW - 1, floor((x + 0.5) * scale).toInt()) }
        var y0 = window.top
        while (y0 < window.bottom) {
            if (cancel.isCancelled()) throw EraseCancelled()
            val y1 = min(window.bottom, y0 + rows)
            val band = PixelRect(window.left, y0, window.right, y1)
            source.read(band, pixels)
            mask.read(band, bits)
            for (y in y0 until y1) {
                val my = min(usedH - 1, floor((y - window.top + 0.5) * scale).toInt())
                val row = (y - y0) * ww
                for (x in 0 until ww) {
                    val m = my * n + colOf[x]
                    val p = pixels[row + x]
                    sums[m] += ((p ushr 16) and 255).toDouble()
                    sums[plane + m] += ((p ushr 8) and 255).toDouble()
                    sums[2 * plane + m] += (p and 255).toDouble()
                    counts[m]++
                    if (bits[row + x].toInt() != 0) marks[m] = true
                }
            }
            y0 = y1
        }
        val image = FloatArray(3 * plane)
        val maskOut = FloatArray(plane)
        for (my in 0 until n) {
            for (mx in 0 until n) {
                val m = my * n + mx
                // Upscaled or padded pixels take the nearest model pixel that has data.
                val sx = min(mx, usedW - 1)
                val sy = min(my, usedH - 1)
                val src = nearestFilled(sx, sy, counts, usedW, usedH, scale)
                val inside = mx < usedW && my < usedH
                val marked = inside && marks[src]
                maskOut[m] = if (marked) 1f else 0f
                if (!marked) {
                    val c = counts[src].toDouble()
                    image[m] = (sums[src] / c / 255.0).toFloat()
                    image[plane + m] = (sums[plane + src] / c / 255.0).toFloat()
                    image[2 * plane + m] = (sums[2 * plane + src] / c / 255.0).toFloat()
                }
            }
        }
        return ModelInput(image, maskOut, usedW, usedH)
    }

    private fun nearestFilled(mx: Int, my: Int, counts: IntArray, usedW: Int, usedH: Int, scale: Double): Int {
        val n = modelSize
        if (counts[my * n + mx] > 0) return my * n + mx
        // Only happens when upscaling (scale > 1): map back to the source pixel and forward.
        val sx = min(usedW - 1, floor((floor(mx / scale) + 0.5) * scale).toInt())
        val sy = min(usedH - 1, floor((floor(my / scale) + 0.5) * scale).toInt())
        return sy * n + sx
    }

    /**
     * Writes the fill back at full resolution, band by band. Output is sampled bilinearly
     * from the model's result; the weight is 1 deep inside the mask and ramps to
     * 1/[featherPx] at its edge (Chebyshev distance to the nearest kept pixel), so the
     * blend happens only inside the mask. Kept pixels are never written.
     */
    internal fun compose(
        source: ArgbRegionReader, mask: MaskRegionReader, target: ArgbRegionWriter,
        window: PixelRect, scale: Double, input: ModelInput, output: FloatArray, cancel: CancelSignal,
    ): Long {
        val n = modelSize
        val plane = n * n
        val ww = window.width
        val halo = featherPx
        val rows = min(bandRows, window.height)
        val pixels = IntArray(ww * rows)
        val changed = BooleanArray(ww * rows)
        val bits = ByteArray((ww + 2 * halo) * (rows + 2 * halo))
        var erased = 0L
        var y0 = window.top
        while (y0 < window.bottom) {
            if (cancel.isCancelled()) throw EraseCancelled()
            val y1 = min(window.bottom, y0 + rows)
            val band = PixelRect(window.left, y0, window.right, y1)
            // Mask rows above and below the band (clamped to the image) for the feather test.
            val hy0 = max(0, y0 - halo)
            val hy1 = min(mask.height, y1 + halo)
            val hx0 = max(0, window.left - halo)
            val hx1 = min(mask.width, window.right + halo)
            val haloRect = PixelRect(hx0, hy0, hx1, hy1)
            val hw = haloRect.width
            val haloBits = if (bits.size >= hw * haloRect.height) bits else ByteArray(hw * haloRect.height)
            mask.read(haloRect, haloBits)
            source.read(band, pixels)
            var any = false
            for (y in y0 until y1) {
                val row = (y - y0) * ww
                for (x in 0 until ww) {
                    val gx = window.left + x
                    val idx = row + x
                    if (haloBits[(y - hy0) * hw + (gx - hx0)].toInt() == 0) { changed[idx] = false; continue }
                    val weight = featherWeight(haloBits, hw, haloRect, gx, y)
                    // Bilinear sample of the model output at this pixel's centre.
                    val u = ((x + 0.5) * scale - 0.5).coerceIn(0.0, (input.usedWidth - 1).toDouble())
                    val v = ((y - window.top + 0.5) * scale - 0.5).coerceIn(0.0, (input.usedHeight - 1).toDouble())
                    val ux = floor(u).toInt(); val vy = floor(v).toInt()
                    val ux1 = min(ux + 1, input.usedWidth - 1); val vy1 = min(vy + 1, input.usedHeight - 1)
                    val fx = u - ux; val fy = v - vy
                    val src = pixels[idx]
                    var outPixel = src and -0x1000000
                    for (c in 0 until 3) {
                        val base = c * plane
                        val top = output[base + vy * n + ux] * (1 - fx) + output[base + vy * n + ux1] * fx
                        val bottom = output[base + vy1 * n + ux] * (1 - fx) + output[base + vy1 * n + ux1] * fx
                        val fill = (top * (1 - fy) + bottom * fy).coerceIn(0.0, 255.0)
                        val shift = 16 - 8 * c
                        val orig = (src ushr shift) and 255
                        val mixed = (fill * weight + orig * (1 - weight)).roundToInt().coerceIn(0, 255)
                        outPixel = outPixel or (mixed shl shift)
                    }
                    pixels[idx] = outPixel
                    changed[idx] = true
                    erased++
                    any = true
                }
            }
            if (any) {
                val count = band.width * band.height
                if (count < changed.size) java.util.Arrays.fill(changed, count, changed.size, false)
                target.write(band, pixels, changed)
            }
            y0 = y1
        }
        return erased
    }

    private fun featherWeight(bits: ByteArray, hw: Int, halo: PixelRect, gx: Int, gy: Int): Double {
        for (d in 1 until featherPx) {
            for (dy in -d..d) {
                val y = gy + dy
                for (dx in -d..d) {
                    if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != d) continue
                    val x = gx + dx
                    // Outside the image counts as masked (no kept pixel there to blend with).
                    if (x < halo.left || x >= halo.right || y < halo.top || y >= halo.bottom) continue
                    if (bits[(y - halo.top) * hw + (x - halo.left)].toInt() == 0) return d.toDouble() / featherPx
                }
            }
        }
        return 1.0
    }
}
