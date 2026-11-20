package nl.atlas.gallery.featureeditor.engine

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A GPU-independent, ordered list of render stages for one [EditRecipe] at one output size.
 *
 * EDIT-2 renders a plan (preview at screen size, export at full size) and EDIT-3 saves it, so
 * preview and saved file can never disagree about what the recipe means. Stage order is fixed,
 * whatever order the user worked in:
 *
 * 1. [RenderStage.Decode] the source;
 * 2. [RenderStage.RasterPatches] in history order (AI Edit, Face Swap, Erase, ...), source space;
 * 3. [RenderStage.Geometry] one projective warp + crop + scale to the output size;
 * 4. [RenderStage.ColourAdjust] all non-zero controls, in [AdjustParam] order;
 * 5. [RenderStage.Lut] the filter at its strength;
 * 6. [RenderStage.Overlays] decoration layers, placed through the same geometry matrix.
 *
 * Identity stages are left out, so an empty recipe is just Decode. [gainmap] gives the
 * matching geometry for an Ultra HDR gainmap of any resolution, so HDR survives every geometry
 * step; colour stages apply to the SDR base only (EDIT-2/EDIT-3 decide gainmap re-weighting).
 */
data class RenderPlan(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val outputWidth: Int,
    val outputHeight: Int,
    val stages: List<RenderStage>,
    val geometry: GeometryState,
) {
    val isIdentity: Boolean get() = stages.size == 1 && outputWidth == sourceWidth && outputHeight == sourceHeight

    /**
     * Geometry for an Ultra HDR gainmap of [gainmapWidth] x [gainmapHeight]: output size keeps the
     * gainmap's scale relative to the base image, and the matrix maps gainmap pixels to output
     * gainmap pixels using the same normalized transform as the base.
     */
    fun gainmap(gainmapWidth: Int, gainmapHeight: Int): RenderStage.Geometry {
        require(gainmapWidth > 0 && gainmapHeight > 0) { "Gainmap size must be positive" }
        val outW = max(1, (outputWidth.toDouble() * gainmapWidth / sourceWidth).roundToInt())
        val outH = max(1, (outputHeight.toDouble() * gainmapHeight / sourceHeight).roundToInt())
        val matrix = Matrix3.scale(outW.toDouble(), outH.toDouble()) *
            geometry.normalizedSourceToOutput() *
            Matrix3.scale(1.0 / gainmapWidth, 1.0 / gainmapHeight)
        return RenderStage.Geometry(matrix, outW, outH, geometry.validRegion)
    }

    /** Stable text form for golden tests and diagnostics (no user text, no hashes truncated). */
    fun describe(): String = buildString {
        append("source ${sourceWidth}x$sourceHeight -> output ${outputWidth}x$outputHeight\n")
        stages.forEach { append(it.describe()).append('\n') }
    }

    companion object {
        /**
         * Builds the plan for [recipe]. With [maxLongEdge] the output is scaled down (never up)
         * so its longer side fits, for previews; null renders at full resolution.
         */
        fun build(recipe: EditRecipe, maxLongEdge: Int? = null): RenderPlan {
            val problems = recipe.validate()
            require(problems.isEmpty()) { "Recipe is invalid: ${problems.joinToString()}" }
            require(maxLongEdge == null || maxLongEdge > 0) { "maxLongEdge must be positive" }
            val geometry = GeometryState.of(recipe)
            var outW = geometry.outputWidth
            var outH = geometry.outputHeight
            if (maxLongEdge != null && max(outW, outH) > maxLongEdge) {
                val s = maxLongEdge.toDouble() / max(outW, outH)
                outW = max(1, (outW * s).roundToInt())
                outH = max(1, (outH * s).roundToInt())
            }
            val folded = FoldedRecipe.of(recipe)
            val matrix = geometry.sourceToOutput(outW, outH)
            val stages = buildList {
                add(RenderStage.Decode(recipe.sourceWidth, recipe.sourceHeight))
                if (folded.patches.isNotEmpty()) add(RenderStage.RasterPatches(folded.patches))
                val needsWarp = !geometry.isIdentity || outW != recipe.sourceWidth || outH != recipe.sourceHeight
                if (needsWarp) add(RenderStage.Geometry(matrix, outW, outH, geometry.validRegion))
                val adjust = AdjustParam.entries.mapNotNull { p -> folded.adjustments[p]?.takeIf { it != 0.0 }?.let { p to it } }
                if (adjust.isNotEmpty()) add(RenderStage.ColourAdjust(adjust))
                folded.filter?.let { f ->
                    if (f.lutId != null && f.strength > 0.0) add(RenderStage.Lut(f.lutId, f.lutHash, f.strength))
                }
                if (folded.overlays.isNotEmpty()) add(RenderStage.Overlays(folded.overlays, matrix))
            }
            return RenderPlan(recipe.sourceWidth, recipe.sourceHeight, outW, outH, stages, geometry)
        }
    }
}

/** One render step. Every stage is plain data a renderer can execute in any backend. */
sealed class RenderStage {
    abstract fun describe(): String

    data class Decode(val width: Int, val height: Int) : RenderStage() {
        override fun describe() = "decode ${width}x$height"
    }

    data class RasterPatches(val patches: List<EditOperation.RasterPatch>) : RenderStage() {
        override fun describe() = "patches " + patches.joinToString("; ") {
            "${it.tool.key} ${it.contentHash.take(12)} ${it.rect.fmt()}${if (it.maskHash != null) " masked" else ""}"
        }
    }

    /**
     * Warp source pixels with [sourceToOutput] (inverse-map each output pixel through its
     * inverse), into a [width] x [height] target. [validRegion] is the warped picture outline in
     * normalized canvas coordinates, for renderers that draw crop guides.
     */
    data class Geometry(
        val sourceToOutput: Matrix3,
        val width: Int,
        val height: Int,
        val validRegion: List<Point2>,
    ) : RenderStage() {
        override fun describe() =
            "geometry ${width}x$height " + sourceToOutput.values().joinToString(",", "[", "]") { it.fmt() }
    }

    data class ColourAdjust(val values: List<Pair<AdjustParam, Double>>) : RenderStage() {
        override fun describe() = "adjust " + values.joinToString(" ") { "${it.first.key}=${it.second.fmt()}" }
    }

    data class Lut(val lutId: String, val lutHash: String?, val strength: Double) : RenderStage() {
        override fun describe() = "lut $lutId strength=${strength.fmt()}"
    }

    data class Overlays(val layers: List<OverlayLayer>, val sourceToOutput: Matrix3) : RenderStage() {
        override fun describe() = "overlays " + layers.joinToString(" ") { "${it.kind.key}:${it.layerId}" }
    }
}

/** The recipe reduced to its current state: last value wins for absolute controls. */
internal data class FoldedRecipe(
    val adjustments: Map<AdjustParam, Double>,
    val filter: EditOperation.Filter?,
    val overlays: List<OverlayLayer>,
    val patches: List<EditOperation.RasterPatch>,
) {
    companion object {
        fun of(recipe: EditRecipe): FoldedRecipe {
            val adjust = LinkedHashMap<AdjustParam, Double>()
            var filter: EditOperation.Filter? = null
            val overlays = LinkedHashMap<String, OverlayLayer>()
            val patches = mutableListOf<EditOperation.RasterPatch>()
            for (op in recipe.operations) when (op) {
                is EditOperation.Adjust -> adjust[op.param] = op.value
                is EditOperation.ResetAdjustments -> adjust.clear()
                is EditOperation.Filter -> filter = op
                is EditOperation.OverlayAdd -> overlays[op.layer.layerId] = op.layer
                is EditOperation.OverlayUpdate -> if (op.layer.layerId in overlays) overlays[op.layer.layerId] = op.layer
                is EditOperation.OverlayRemove -> overlays.remove(op.layerId)
                is EditOperation.RasterPatch -> patches += op
                else -> Unit
            }
            return FoldedRecipe(adjust, filter, overlays.values.toList(), patches)
        }
    }
}

private fun NormRect.fmt() = "[${left.fmt()},${top.fmt()},${right.fmt()},${bottom.fmt()}]"

/** Six decimals, fixed, locale-free: stable across JVMs for golden text. */
internal fun Double.fmt(): String {
    val r = Math.round(this * 1_000_000.0) / 1_000_000.0
    val v = if (r == 0.0) 0.0 else r
    return java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString()
}
