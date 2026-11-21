package nl.atlas.gallery.featureeditor.render

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.SystemClock
import android.util.Log
import nl.atlas.gallery.featureeditor.engine.AdjustParam
import nl.atlas.gallery.featureeditor.engine.EditOperation
import nl.atlas.gallery.featureeditor.engine.EditRecipe
import nl.atlas.gallery.featureeditor.engine.FlipAxis
import nl.atlas.gallery.featureeditor.engine.NormRect
import nl.atlas.gallery.featureeditor.engine.OverlayLayer
import nl.atlas.gallery.featureeditor.engine.Quad
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max

/** Synthetic fixtures and helpers for EDIT-2 device tests. No real photos, no personal data. */
internal object RenderTestKit {
    const val TAG = "ATLAS_EDIT2"

    fun log(line: String) { Log.i(TAG, line) }

    /** Gradients, a checkerboard and saturated colour bars, labelled with [space]. */
    fun picture(w: Int, h: Int, space: PixelSpace = PixelSpace.SRGB): Bitmap {
        val buf = ByteBuffer.allocate(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) {
            val checker = ((x / 7) + (y / 7)) % 2 == 0
            val bar = x * 6 / w
            val (r, g, b) = when {
                y < h / 3 -> Triple(x * 255 / max(1, w - 1), y * 255 / max(1, h - 1), 255 - x * 255 / max(1, w - 1))
                y < 2 * h / 3 -> if (checker) Triple(230, 230, 230) else Triple(25, 25, 25)
                else -> Triple(if (bar % 3 == 0) 255 else 20, if (bar % 3 == 1) 255 else 30, if (bar % 3 == 2) 255 else 40)
            }
            buf.put(r.toByte()); buf.put(g.toByte()); buf.put(b.toByte()); buf.put(255.toByte())
        }
        buf.rewind()
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888, true, RenderBitmaps.colorSpace(space)).apply { copyPixelsFromBuffer(buf) }
    }

    fun solid(w: Int, h: Int, r: Int, g: Int, b: Int): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888, true, ColorSpace.get(ColorSpace.Named.SRGB)).apply {
            eraseColor(android.graphics.Color.rgb(r, g, b))
        }

    fun raw(bitmap: Bitmap): ByteArray {
        val b = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val buf = ByteBuffer.allocate(b.byteCount)
        b.copyPixelsToBuffer(buf)
        return buf.array()
    }

    /** Largest per-channel difference in 8-bit steps, and the mean. */
    fun diff(a: Bitmap, b: Bitmap): Pair<Int, Double> {
        require(a.width == b.width && a.height == b.height) { "size ${a.width}x${a.height} vs ${b.width}x${b.height}" }
        val x = raw(a); val y = raw(b)
        var m = 0; var sum = 0L
        for (i in x.indices) {
            val d = abs((x[i].toInt() and 0xff) - (y[i].toInt() and 0xff))
            m = max(m, d); sum += d
        }
        return m to sum.toDouble() / x.size
    }

    fun stitch(width: Int, height: Int, space: PixelSpace, render: (IntRect) -> Bitmap, tileSize: Int): Bitmap {
        val sink = BitmapTileSink(width, height, space)
        for (t in TileGrid.tiles(width, height, tileSize)) sink.accept(t, render(t))
        return sink.bitmap
    }

    val warmLut: Lut3D = Lut3D.of(17) { r, g, b, out ->
        out[0] = (r * 1.1f + 0.03f).coerceIn(0f, 1f)
        out[1] = g * g * (3f - 2f * g) * 0.9f + g * 0.1f
        out[2] = b * 0.85f
    }

    fun assets(bitmaps: Map<String, Bitmap> = emptyMap(), stickerPlacement: ((OverlayLayer, Int, Int) -> PlacedRaster?)? = null) =
        object : EditorAssets {
            override fun bitmap(contentHash: String) = bitmaps[contentHash]
            override fun lut(lutId: String, lutHash: String?) = if (lutId == "synthetic-warm") warmLut else null
            override fun overlay(layer: OverlayLayer, sourceWidth: Int, sourceHeight: Int) =
                stickerPlacement?.invoke(layer, sourceWidth, sourceHeight)
        }

    fun geometryOps() = listOf(
        EditOperation.Rotate90("g1", 1),
        EditOperation.Flip("g2", FlipAxis.HORIZONTAL),
        EditOperation.Straighten("g3", 6.5),
        EditOperation.Perspective("g4", Quad.fromKeystone(0.3, -0.15)),
        EditOperation.Crop("g5", NormRect(0.08, 0.1, 0.92, 0.85)),
    )

    /** Every colour control (non-zero), every geometry step and the synthetic filter. */
    fun everything(w: Int, h: Int) = EditRecipe(
        w, h,
        geometryOps() + AdjustParam.entries.mapIndexed { i, p ->
            EditOperation.Adjust("a$i", p, if (p == AdjustParam.SHARPNESS) 0.6 else if (i % 2 == 0) 0.35 else -0.3)
        } + EditOperation.Filter("f1", "synthetic-warm", null, 0.7),
    )

    /** An ImageReader the GPU can draw into and the test can read back. */
    fun reader(w: Int, h: Int, images: Int = 3): ImageReader = ImageReader.newInstance(
        w, h, PixelFormat.RGBA_8888, images,
        HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT,
    )

    fun readLatest(reader: ImageReader, space: PixelSpace): Bitmap {
        val deadline = SystemClock.uptimeMillis() + 5_000
        var image = reader.acquireLatestImage()
        while (image == null) {
            check(SystemClock.uptimeMillis() < deadline) { "No frame arrived" }
            Thread.sleep(2)
            image = reader.acquireLatestImage()
        }
        image.use { img ->
            val hb = requireNotNull(img.hardwareBuffer)
            hb.use {
                val wrapped = requireNotNull(Bitmap.wrapHardwareBuffer(it, RenderBitmaps.colorSpace(space)))
                return wrapped.copy(Bitmap.Config.ARGB_8888, false)
            }
        }
    }

    /** Nearest-rank percentile of an ascending list (BUDGETS.md). */
    fun percentile(sorted: List<Long>, p: Double): Long =
        sorted[(kotlin.math.ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)]
}
