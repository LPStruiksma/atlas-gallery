package nl.atlas.gallery.featureeditor.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.SystemClock
import android.os.Trace
import nl.atlas.gallery.featureeditor.engine.EditOperation
import nl.atlas.gallery.featureeditor.engine.Matrix3
import nl.atlas.gallery.featureeditor.engine.NormRect
import nl.atlas.gallery.featureeditor.engine.RenderPlan
import nl.atlas.gallery.featureeditor.engine.RenderStage
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Receives export tiles in row order; each bitmap is the tile's size and may be recycled after. */
fun interface TileSink {
    fun accept(tile: IntRect, pixels: Bitmap)
}

/** Assembles tiles into one output bitmap (what EDIT-3 encodes). */
class BitmapTileSink(width: Int, height: Int, space: PixelSpace) : TileSink {
    val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888, true, RenderBitmaps.colorSpace(space))
    private val canvas = Canvas(bitmap)
    private val copy = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }

    override fun accept(tile: IntRect, pixels: Bitmap) {
        canvas.drawBitmap(pixels, tile.left.toFloat(), tile.top.toFloat(), copy)
    }
}

/** Timing of one export, for the BUDGETS.md record. */
data class ExportStats(val width: Int, val height: Int, val tiles: Int, val elapsedMs: Long, val backend: String, val peakRegionPixels: Long) {
    val megapixels: Double get() = width.toDouble() * height / 1_000_000.0
    val msPerMegapixel: Double get() = elapsedMs / megapixels
}

/**
 * Renders [RenderPlan]s (EDIT-2): tiles for export, single frames for the preview. Stage order and
 * meaning come from the plan; this class only wires inputs around the [PixelBackend] pass:
 *
 * 1. the segment input: the photo region (from a [SourceProvider]) with source-space raster
 *    patches composited on, or, after an output-space patch, the rendered base plan with that
 *    patch composited on (rendered region by region, so it is tiled too);
 * 2. the pixel pass (geometry, colour, LUT) on the GPU or CPU backend;
 * 3. decoration layers drawn on top through the same geometry matrix.
 *
 * Tiles read only the source region they need, so memory is bounded by the tile size, not the
 * photo: a 50 MP export holds one tile, one source region and the sink.
 */
class PlanRenderer(
    private val assets: EditorAssets,
    private val backend: PixelBackend = PixelBackend.best(),
) : AutoCloseable {
    private val patchPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val maskPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val overlayPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private var cachedBase: Pair<Any, SourceRegion>? = null
    private var cachedPatched: Pair<Any, SourceRegion>? = null

    /** Largest source region read so far, in pixels (tiling evidence). */
    var peakRegionPixels: Long = 0
        private set

    val backendName: String get() = backend.name

    /**
     * Renders output pixels [tile] of [plan] from [source] (the decoded photo, any resolution:
     * a proxy works through its region scale). [strict] refuses a missing LUT or patch instead of
     * leaving it out: exports are strict, previews are not.
     */
    fun renderTile(
        plan: RenderPlan,
        source: SourceProvider,
        tile: IntRect,
        outputSpace: PixelSpace,
        strict: Boolean = true,
        outputWidth: Int = plan.outputWidth,
        outputHeight: Int = plan.outputHeight,
    ): Bitmap {
        val program = SegmentProgram.of(plan, outputWidth, outputHeight)
        return renderProgram(program, source, tile, outputSpace, strict, regionScale(program))
    }

    /** Renders the whole of [plan] tile by tile into [sink]. */
    fun export(
        plan: RenderPlan,
        source: SourceProvider,
        sink: TileSink,
        outputSpace: PixelSpace,
        tileSize: Int = TileGrid.DEFAULT_TILE,
    ): ExportStats {
        Trace.beginSection("export_start")
        val start = SystemClock.elapsedRealtime()
        val tiles = TileGrid.tiles(plan.outputWidth, plan.outputHeight, tileSize)
        val program = SegmentProgram.of(plan)
        try {
            for (tile in tiles) {
                val pixels = renderProgram(program, source, tile, outputSpace, strict = true, scale = 1f)
                sink.accept(tile, pixels)
                pixels.recycle()
            }
        } finally {
            Trace.endSection()
        }
        return ExportStats(
            plan.outputWidth, plan.outputHeight, tiles.size, SystemClock.elapsedRealtime() - start, backend.name, peakRegionPixels,
        )
    }

    /**
     * Renders an Ultra HDR gainmap through [plan]'s geometry ([RenderPlan.gainmap]), so HDR
     * survives rotate, straighten, perspective and crop. Gainmap values are data: interpolated
     * raw, with no colour controls, LUT or overlays. Returns an `ARGB_8888` bitmap; single-channel
     * gainmaps come back with the value in r, g and b.
     */
    fun renderGainmap(plan: RenderPlan, gainmap: Bitmap): Bitmap {
        val contents = if (gainmap.config == Bitmap.Config.ALPHA_8) {
            Bitmap.createBitmap(gainmap.width, gainmap.height, Bitmap.Config.ARGB_8888).also {
                val c = Canvas(it)
                c.drawColor(Color.BLACK)
                c.drawBitmap(gainmap, 0f, 0f, Paint().apply { color = Color.WHITE })
            }
        } else {
            gainmap.copy(Bitmap.Config.ARGB_8888, false)
        }
        val geometry = plan.gainmap(gainmap.width, gainmap.height)
        val gPlan = RenderPlan(
            gainmap.width, gainmap.height, geometry.width, geometry.height,
            listOf(RenderStage.Decode(gainmap.width, gainmap.height), geometry), plan.geometry,
        )
        val program = SegmentProgram.of(gPlan, rawValues = true)
        val source = BitmapSource(contents)
        val sink = BitmapTileSink(geometry.width, geometry.height, PixelSpace.SRGB)
        for (tile in TileGrid.tiles(geometry.width, geometry.height)) {
            val px = renderProgram(program, source, tile, PixelSpace.SRGB, strict = true, scale = 1f)
            sink.accept(tile, px)
            px.recycle()
        }
        return sink.bitmap
    }

    internal fun renderProgram(
        program: SegmentProgram,
        source: SourceProvider,
        tile: IntRect,
        outputSpace: PixelSpace,
        strict: Boolean,
        scale: Float,
    ): Bitmap {
        val input = prepare(program, source, tile, scale, strict)
        val pixels = if (input == null) {
            Bitmap.createBitmap(tile.width, tile.height, Bitmap.Config.ARGB_8888, true, RenderBitmaps.colorSpace(outputSpace))
        } else {
            backend.render(program, input.region, tile, input.lut, outputSpace)
        }
        drawOverlays(Canvas(pixels), program, tile, strict)
        return pixels
    }

    /** What a backend needs for [tile]: the input region and LUT; null when the tile shows no source. */
    internal class Prepared(val region: SourceRegion, val lut: Lut3D?)

    internal fun prepare(program: SegmentProgram, source: SourceProvider, tile: IntRect, scale: Float, strict: Boolean): Prepared? {
        val need = program.sourceRegionFor(tile)
        if (need.isEmpty) return null
        val region = inputRegion(program, source, need, scale, strict)
        peakRegionPixels = max(peakRegionPixels, region.bitmap.width.toLong() * region.bitmap.height)
        val lut = program.lut?.let { stage ->
            assets.lut(stage.lutId, stage.lutHash) ?: if (strict) throw MissingAssetException("LUT") else null
        }
        return Prepared(region, lut)
    }

    /** Draws [program]'s decoration layers onto [canvas], whose origin is output pixel ([tile].left, top). */
    internal fun drawOverlays(canvas: Canvas, program: SegmentProgram, tile: IntRect, strict: Boolean) {
        program.overlays?.let { drawOverlays(canvas, it, program, tile, strict) }
    }

    internal fun regionScaleFor(program: SegmentProgram): Float = regionScale(program)

    /** The segment's input region: photo + source patches, or base render + output patch. */
    private fun inputRegion(program: SegmentProgram, source: SourceProvider, need: IntRect, scale: Float, strict: Boolean): SourceRegion =
        when (val input = program.input) {
            SegmentInput.Original -> {
                val region = source.region(need, scale)
                if (program.sourcePatches.isEmpty()) region
                else cached(cachedPatched, listOf(region.bitmap, region.originX, region.originY, program.sourcePatches)) { key ->
                    composite(region, program.sourcePatches, program.sourceWidth, program.sourceHeight, strict).also { cachedPatched = key to it }
                }
            }
            is SegmentInput.Composite -> {
                val base = input.base
                // Render the base at the scale this segment reads it, region by region.
                val s = RenderBitmaps.clampScale(scale)
                val bw = max(1, Math.round(base.outputWidth * s)); val bh = max(1, Math.round(base.outputHeight * s))
                val sx = bw.toFloat() / base.outputWidth; val sy = bh.toFloat() / base.outputHeight
                val baseTile = IntRect(
                    kotlin.math.floor(need.left * sx).toInt(), kotlin.math.floor(need.top * sy).toInt(),
                    kotlin.math.ceil(need.right * sx).toInt(), kotlin.math.ceil(need.bottom * sy).toInt(),
                ).intersect(IntRect.size(bw, bh))
                val key = listOf(base, bw, bh, baseTile, input.patch, source)
                cached(cachedBase, key) { k ->
                    val baseProgram = SegmentProgram.of(base, bw, bh)
                    val rendered = renderProgram(baseProgram, source, baseTile, PixelSpace.DISPLAY_P3, strict, if (scale >= 1f) 1f else regionScale(baseProgram))
                    val region = SourceRegion(rendered, baseTile.left / sx, baseTile.top / sy, sx, PixelSpace.DISPLAY_P3)
                    composite(region, listOf(input.patch), base.outputWidth, base.outputHeight, strict).also { cachedBase = k to it }
                }
            }
        }

    private inline fun cached(entry: Pair<Any, SourceRegion>?, key: Any, make: (Any) -> SourceRegion): SourceRegion =
        if (entry != null && entry.first == key) entry.second else make(key)

    /**
     * Composites raster patches (normalized rects in a picture of [w] x [h]) onto a copy of
     * [region], through their masks: `out = a * patch + (1 - a) * before`.
     */
    private fun composite(region: SourceRegion, patches: List<EditOperation.RasterPatch>, w: Int, h: Int, strict: Boolean): SourceRegion {
        val out = RenderBitmaps.mutableCopy(region.bitmap)
        val canvas = Canvas(out)
        for (p in patches) {
            val art = assets.bitmap(p.contentHash) ?: if (strict) throw MissingAssetException("patch") else continue
            val dst = regionRect(region, p.rect, w, h)
            if (dst.width() <= 0f || dst.height() <= 0f) continue
            val mask = p.maskHash?.let { assets.bitmap(it) ?: if (strict) throw MissingAssetException("mask") else null }
            if (mask == null) {
                canvas.drawBitmap(art, null, dst, patchPaint)
            } else {
                val layer = canvas.saveLayer(dst, null)
                canvas.drawBitmap(art, null, dst, patchPaint)
                canvas.drawBitmap(alphaMask(mask), null, dst, maskPaint)
                canvas.restoreToCount(layer)
            }
        }
        return SourceRegion(out, region.originX, region.originY, region.scale, region.space)
    }

    /** A mask's alpha: its alpha channel when it has one, else its luminance. */
    private fun alphaMask(mask: Bitmap): Bitmap {
        if (mask.config == Bitmap.Config.ALPHA_8 || mask.hasAlpha()) return mask
        val out = Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ARGB_8888)
        val p = Paint().apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(
                floatArrayOf(0f, 0f, 0f, 0f, 255f, 0f, 0f, 0f, 0f, 255f, 0f, 0f, 0f, 0f, 255f, 0.2126f, 0.7152f, 0.0722f, 0f, 0f),
            )
        }
        Canvas(out).drawBitmap(mask, 0f, 0f, p)
        return out
    }

    private fun regionRect(region: SourceRegion, r: NormRect, w: Int, h: Int) = RectF(
        ((r.left * w).toFloat() - region.originX) * region.scale,
        ((r.top * h).toFloat() - region.originY) * region.scale,
        ((r.right * w).toFloat() - region.originX) * region.scale,
        ((r.bottom * h).toFloat() - region.originY) * region.scale,
    )

    /** Draws decoration layers in z-order through the segment's source-to-output matrix. */
    private fun drawOverlays(canvas: Canvas, overlays: RenderStage.Overlays, program: SegmentProgram, tile: IntRect, strict: Boolean) {
        for (layer in overlays.layers) {
            val placed = assets.overlay(layer, program.sourceWidth, program.sourceHeight)
            if (placed == null) {
                if (strict && layer.contentHash != null) throw MissingAssetException("overlay")
                continue
            }
            val m = Matrix3.translate(-tile.left.toDouble(), -tile.top.toDouble()) * overlays.sourceToOutput * placed.rasterToSource
            canvas.save()
            canvas.concat(Matrix().apply { setValues(m.values().map { it.toFloat() }.toFloatArray()) })
            overlayPaint.alpha = (layer.opacity * 255).toInt().coerceIn(0, 255)
            canvas.drawBitmap(placed.bitmap, 0f, 0f, overlayPaint)
            canvas.restore()
        }
    }

    /** Texels per source pixel the output needs: the geometry's area scale, at most 1. */
    private fun regionScale(program: SegmentProgram): Float {
        val m = program.sourceToOutput
        val det = abs(m[0, 0] * m[1, 1] - m[0, 1] * m[1, 0]) / (m[2, 2] * m[2, 2])
        return RenderBitmaps.clampScale(sqrt(det).toFloat())
    }

    override fun close() {
        cachedBase = null; cachedPatched = null
        backend.close()
    }
}

/** A hash the recipe names is not in the asset store; exports refuse rather than guess. */
class MissingAssetException(kind: String) : IllegalStateException("Missing $kind asset for this edit")
