package nl.atlas.gallery.featureeditor.render

import nl.atlas.gallery.featureeditor.engine.AdjustParam
import nl.atlas.gallery.featureeditor.engine.EditOperation
import nl.atlas.gallery.featureeditor.engine.Matrix3
import nl.atlas.gallery.featureeditor.engine.RenderPlan
import nl.atlas.gallery.featureeditor.engine.RenderStage
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Integer pixel rectangle, half-open: `left <= x < right`. */
data class IntRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = width <= 0 || height <= 0
    val pixels: Long get() = if (isEmpty) 0 else width.toLong() * height

    fun intersect(o: IntRect) = IntRect(max(left, o.left), max(top, o.top), min(right, o.right), min(bottom, o.bottom))

    companion object {
        fun size(w: Int, h: Int) = IntRect(0, 0, w, h)
    }
}

/**
 * Where a segment's input picture comes from.
 *
 * - [Original]: the decoded photo, with its source-space raster patches composited on.
 * - [Composite]: an output-space patch (AI Edit, Face Swap) over the rendered [base] plan. The
 *   base renders through the same renderer, so it can be produced tile by tile and cached.
 */
sealed class SegmentInput {
    data object Original : SegmentInput()
    data class Composite(val base: RenderPlan, val patch: EditOperation.RasterPatch) : SegmentInput()
}

/**
 * One [RenderPlan] segment lowered to what a pixel backend needs: the output-to-source pixel map,
 * colour values, LUT and the layers composited around the pixel pass. CPU reference and AGSL
 * backends both execute exactly this program, which is what keeps preview, tiles and export in
 * agreement.
 */
class SegmentProgram private constructor(
    val input: SegmentInput,
    /** Size of the segment's input picture at full resolution. */
    val sourceWidth: Int,
    val sourceHeight: Int,
    val outputWidth: Int,
    val outputHeight: Int,
    /** Source pixels to output pixels (projective). */
    val sourceToOutput: Matrix3,
    val adjust: AdjustValues,
    val lut: RenderStage.Lut?,
    val sourcePatches: List<EditOperation.RasterPatch>,
    val overlays: RenderStage.Overlays?,
    /**
     * True for data images (Ultra HDR gainmaps): bytes are interpolated and written as they are,
     * with no transfer curve, colour matrix, colour controls or LUT.
     */
    val rawValues: Boolean,
) {
    /** Output pixels to source pixels, row-major, as the shader uniform. */
    val outputToSource: Matrix3 = sourceToOutput.inverse()
    val outputToSourceFloats: FloatArray = outputToSource.values().let { v -> FloatArray(9) { v[it].toFloat() } }

    val definitionRadius: Float = ColourOps.definitionRadius(max(outputWidth, outputHeight))

    /** Output pixels a pixel may read around itself (detail taps), plus one for bilinear. */
    val outputPad: Int = 1 + (if (adjust[AdjustParam.DEFINITION] != 0f) ceil(definitionRadius).toInt() else 0) +
        (if (adjust[AdjustParam.SHARPNESS] != 0f) 1 else 0)

    /**
     * The source pixels (full resolution) that rendering [tile] can read, clipped to the source.
     * Empty when the tile shows no source at all.
     */
    fun sourceRegionFor(tile: IntRect): IntRect {
        val l = (tile.left - outputPad).toDouble(); val t = (tile.top - outputPad).toDouble()
        val r = (tile.right + outputPad).toDouble(); val b = (tile.bottom + outputPad).toDouble()
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        // A projective map with w > 0 over the tile sends the rectangle to a convex quad, so its
        // corners bound it. Edges are sampled too, in case w changes sign near the horizon.
        for (i in 0..8) for (j in 0..8) {
            if (i in 1..7 && j in 1..7) continue
            val x = l + (r - l) * i / 8; val y = t + (b - t) * j / 8
            val p = outputToSource.map(x, y)
            minX = min(minX, p.x); minY = min(minY, p.y); maxX = max(maxX, p.x); maxY = max(maxY, p.y)
        }
        val m = SOURCE_MARGIN
        val region = IntRect(floor(minX).toInt() - m, floor(minY).toInt() - m, ceil(maxX).toInt() + m, ceil(maxY).toInt() + m)
        return region.intersect(IntRect.size(sourceWidth, sourceHeight))
    }

    /** Normalized source rect of a patch to source pixels. */
    fun patchPixels(p: EditOperation.RasterPatch): IntRect = IntRect(
        (p.rect.left * sourceWidth).roundToInt(), (p.rect.top * sourceHeight).roundToInt(),
        (p.rect.right * sourceWidth).roundToInt(), (p.rect.bottom * sourceHeight).roundToInt(),
    )

    companion object {
        const val SOURCE_MARGIN = 2

        /**
         * Lowers the last segment of [plan] at [outputWidth] x [outputHeight] (default: the
         * plan's own size; a different size scales the output, as previews of a cached base do).
         */
        fun of(
            plan: RenderPlan,
            outputWidth: Int = plan.outputWidth,
            outputHeight: Int = plan.outputHeight,
            rawValues: Boolean = false,
        ): SegmentProgram {
            require(outputWidth > 0 && outputHeight > 0) { "Output size must be positive" }
            var input: SegmentInput = SegmentInput.Original
            var srcW = plan.sourceWidth; var srcH = plan.sourceHeight
            var geometry: RenderStage.Geometry? = null
            var adjust: RenderStage.ColourAdjust? = null
            var lut: RenderStage.Lut? = null
            var overlays: RenderStage.Overlays? = null
            var patches: List<EditOperation.RasterPatch> = emptyList()
            var base: RenderPlan? = null
            for (stage in plan.stages) when (stage) {
                is RenderStage.Decode -> { srcW = stage.width; srcH = stage.height }
                is RenderStage.Base -> { base = stage.plan; srcW = stage.plan.outputWidth; srcH = stage.plan.outputHeight }
                is RenderStage.OutputPatch -> input = SegmentInput.Composite(requireNotNull(base) { "Output patch without base" }, stage.patch)
                is RenderStage.RasterPatches -> patches = stage.patches
                is RenderStage.Geometry -> geometry = stage
                is RenderStage.ColourAdjust -> adjust = stage
                is RenderStage.Lut -> lut = stage
                is RenderStage.Overlays -> overlays = stage
                // EDIT-8 schema-v2 stages are plan metadata until their pixel backends land.
                // Resize dimensions are already represented by plan.outputWidth/outputHeight.
                is RenderStage.ShapeCrop,
                is RenderStage.Resize,
                is RenderStage.Curves,
                is RenderStage.ColorMix,
                is RenderStage.SpotColor -> Unit
            }
            val planToOutput = Matrix3.scale(outputWidth.toDouble() / plan.outputWidth, outputHeight.toDouble() / plan.outputHeight)
            val s2o = planToOutput * (geometry?.sourceToOutput ?: Matrix3.IDENTITY)
            return SegmentProgram(
                input, srcW, srcH, outputWidth, outputHeight, s2o,
                if (rawValues) AdjustValues.NONE else AdjustValues.of(adjust),
                if (rawValues) null else lut,
                patches,
                if (rawValues) null else overlays?.let { RenderStage.Overlays(it.layers, planToOutput * it.sourceToOutput) },
                rawValues,
            )
        }
    }
}

/** Splits an output into tiles of at most [tileSize] pixels a side, row by row. */
object TileGrid {
    const val DEFAULT_TILE = 1024

    fun tiles(width: Int, height: Int, tileSize: Int = DEFAULT_TILE): List<IntRect> {
        require(width > 0 && height > 0 && tileSize > 0)
        val out = ArrayList<IntRect>()
        var y = 0
        while (y < height) {
            var x = 0
            val b = min(height, y + tileSize)
            while (x < width) {
                out += IntRect(x, y, min(width, x + tileSize), b)
                x += tileSize
            }
            y = b
        }
        return out
    }
}
