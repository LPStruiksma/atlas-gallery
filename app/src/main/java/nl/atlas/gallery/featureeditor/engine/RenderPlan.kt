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
 * 2. [RenderStage.RasterPatches] source-space patches in history order;
 * 3. [RenderStage.Geometry] one projective warp + crop + scale to the output size;
 * 4. [RenderStage.ShapeCrop] display-canvas mask, then [RenderStage.Resize] target metadata;
 * 5. [RenderStage.ColourAdjust] all non-zero controls, in [AdjustParam] order;
 * 6. [RenderStage.Curves], [RenderStage.ColorMix], [RenderStage.SpotColor], in that order, after
 *    colour adjust;
 * 7. [RenderStage.Lut] and [RenderStage.Overlays] finish the image without editing layers.
 *
 * Shape and resize therefore come before every colour stage: a solid-filled shape mask is
 * not colour-adjusted, and colour stages see the masked, resized output. docs/editor/ENGINE.md
 * lists the same order stage by stage.
 *
 * Output-space patches ([PatchSpace.OUTPUT], what AI Edit and Face Swap return) split the
 * recipe at the last such patch: [RenderStage.Base] renders every earlier step as its own plan,
 * [RenderStage.OutputPatch] composites the patch over that result, and the steps after it run
 * stages 2 to 7 (everything except Decode) on the composite. Earlier steps are therefore never
 * applied to the AI result a second time, and undoing the patch restores them untouched. The base is static while later
 * steps change, so a renderer can cache it.
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
    /** Geometry of the steps after the last output-space patch (all steps when there is none). */
    val geometry: GeometryState,
    /** Normalized original source (`0..1`) to normalized final output, across every step. */
    val normalizedSourceToOutput: Matrix3 = geometry.normalizedSourceToOutput(),
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
            normalizedSourceToOutput *
            Matrix3.scale(1.0 / gainmapWidth, 1.0 / gainmapHeight)
        return RenderStage.Geometry(matrix, outW, outH, geometry.validRegion)
    }

    /** Maps a tap in normalized output coordinates back to the normalized original source. */
    fun outputToSource(p: Point2): Point2 = normalizedSourceToOutput.inverse().map(p)

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
            val ops = recipe.operations
            val split = ops.indexOfLast { it is EditOperation.RasterPatch && it.space == PatchSpace.OUTPUT }
            if (split < 0) return buildSegment(recipe, maxLongEdge)
            // Everything before the patch renders as the base, at full resolution.
            val base = build(recipe.copy(operations = ops.subList(0, split)))
            val patch = ops[split] as EditOperation.RasterPatch
            val rest = buildSegment(
                EditRecipe(base.outputWidth, base.outputHeight, ops.subList(split + 1, ops.size), recipe.schemaVersion),
                maxLongEdge,
            )
            val stages = listOf(RenderStage.Base(base), RenderStage.OutputPatch(patch)) + rest.stages.drop(1)
            return RenderPlan(
                recipe.sourceWidth, recipe.sourceHeight, rest.outputWidth, rest.outputHeight, stages,
                rest.geometry, rest.normalizedSourceToOutput * base.normalizedSourceToOutput,
            )
        }

        /** One segment without output-space patches: Decode, patches, geometry, colour, LUT, overlays. */
        private fun buildSegment(recipe: EditRecipe, maxLongEdge: Int?): RenderPlan {
            val geometry = GeometryState.of(recipe)
            val folded = FoldedRecipe.of(recipe)
            var outW = geometry.outputWidth
            var outH = geometry.outputHeight
            val requested = folded.resize
            if (requested != null) {
                val scale = requested.longEdge?.let { it.toDouble() / max(outW, outH) }
                    ?: (requested.percentage!! / 100.0)
                outW = max(1, (outW * scale).roundToInt())
                outH = max(1, (outH * scale).roundToInt())
            }
            if (maxLongEdge != null && max(outW, outH) > maxLongEdge) {
                val s = maxLongEdge.toDouble() / max(outW, outH)
                outW = max(1, (outW * s).roundToInt())
                outH = max(1, (outH * s).roundToInt())
            }
            val matrix = geometry.sourceToOutput(outW, outH)
            val stages = buildList {
                add(RenderStage.Decode(recipe.sourceWidth, recipe.sourceHeight))
                if (folded.patches.isNotEmpty()) add(RenderStage.RasterPatches(folded.patches))
                val needsWarp = !geometry.isIdentity || outW != recipe.sourceWidth || outH != recipe.sourceHeight
                if (needsWarp) add(RenderStage.Geometry(matrix, outW, outH, geometry.validRegion))
                folded.shapeCrop?.let { shape ->
                    val c = geometry.effectiveCrop
                    val canvasToOutput = Matrix3.scale(1.0 / c.width, 1.0 / c.height) *
                        Matrix3.translate(-c.left, -c.top)
                    add(RenderStage.ShapeCrop(shape, canvasToOutput))
                }
                if (requested != null && (outW != geometry.outputWidth || outH != geometry.outputHeight)) {
                    // Metadata only: Geometry above already allocates the resized output target.
                    add(RenderStage.Resize(outW, outH, requested))
                }
                val adjust = AdjustParam.entries.mapNotNull { p -> folded.adjustments[p]?.takeIf { it != 0.0 }?.let { p to it } }
                if (adjust.isNotEmpty()) add(RenderStage.ColourAdjust(adjust))
                folded.curves?.takeUnless { it.isNeutral() }?.let { add(RenderStage.Curves(it)) }
                folded.colorMix?.takeUnless { it.isNeutral() }?.let { add(RenderStage.ColorMix(it)) }
                folded.spotColor?.takeUnless { it.isNeutral() }?.let { add(RenderStage.SpotColor(it)) }
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

    /** The rendered result of every step before an output-space patch, as its own plan. */
    data class Base(val plan: RenderPlan) : RenderStage() {
        override fun describe() =
            "base {\n" + plan.describe().trimEnd('\n').lines().joinToString("\n") { "  $it" } + "\n}"
    }

    /** Composite [patch] over the current picture at its output-normalized rect (mask optional). */
    data class OutputPatch(val patch: EditOperation.RasterPatch) : RenderStage() {
        override fun describe() =
            "output-patch ${patch.tool.key} ${patch.contentHash.take(12)} ${patch.rect.fmt()}" +
                if (patch.maskHash != null) " masked" else ""
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

    /** Display-canvas mask. [canvasToOutput] maps normalized canvas knots to normalized output. */
    data class ShapeCrop(
        val operation: EditOperation.ShapeCrop,
        val canvasToOutput: Matrix3,
    ) : RenderStage() {
        override fun describe() = "shape-crop " +
            (operation.preset?.key ?: "lasso:${operation.polygon?.size}") +
            " outside=${operation.outside.key}"
    }

    /** Target-size annotation; Geometry's target already includes this resize. */
    data class Resize(val width: Int, val height: Int, val operation: EditOperation.Resize) : RenderStage() {
        override fun describe() = "resize ${width}x$height"
    }

    data class Curves(val operation: EditOperation.Curves) : RenderStage() {
        override fun describe() = "curves rgb=${operation.rgb.size} r=${operation.red.size} g=${operation.green.size} b=${operation.blue.size}"
    }

    data class ColorMix(val operation: EditOperation.ColorMix) : RenderStage() {
        override fun describe() = "color-mix presets=${operation.presets.size} samples=${operation.custom.size}"
    }

    data class SpotColor(val operation: EditOperation.SpotColor) : RenderStage() {
        override fun describe() = "spot-color keep=${operation.keep.size} remove=${operation.remove.size}" +
            if (operation.brushMaskHash != null) " brush-mask" else ""
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
    val curves: EditOperation.Curves?,
    val colorMix: EditOperation.ColorMix?,
    val spotColor: EditOperation.SpotColor?,
    val shapeCrop: EditOperation.ShapeCrop?,
    val resize: EditOperation.Resize?,
) {
    companion object {
        fun of(recipe: EditRecipe): FoldedRecipe {
            val adjust = LinkedHashMap<AdjustParam, Double>()
            var filter: EditOperation.Filter? = null
            val overlays = LinkedHashMap<String, OverlayLayer>()
            val patches = mutableListOf<EditOperation.RasterPatch>()
            var curves: EditOperation.Curves? = null
            var colorMix: EditOperation.ColorMix? = null
            var spotColor: EditOperation.SpotColor? = null
            var shapeCrop: EditOperation.ShapeCrop? = null
            var resize: EditOperation.Resize? = null
            for (op in recipe.operations) when (op) {
                is EditOperation.Adjust -> adjust[op.param] = op.value
                is EditOperation.ResetAdjustments -> {
                    adjust.clear(); curves = null; colorMix = null; spotColor = null
                }
                is EditOperation.Curves -> curves = op
                is EditOperation.ColorMix -> colorMix = op
                is EditOperation.SpotColor -> spotColor = op
                is EditOperation.ShapeCrop -> shapeCrop = op
                is EditOperation.Resize -> resize = op
                is EditOperation.ResetGeometry -> { shapeCrop = null; resize = null }
                is EditOperation.Filter -> filter = op
                is EditOperation.OverlayAdd -> overlays[op.layer.layerId] = op.layer
                is EditOperation.OverlayUpdate -> if (op.layer.layerId in overlays) overlays[op.layer.layerId] = op.layer
                is EditOperation.OverlayRemove -> overlays.remove(op.layerId)
                is EditOperation.RasterPatch -> patches += op
                else -> Unit
            }
            return FoldedRecipe(adjust, filter, overlays.values.toList(), patches,
                curves, colorMix, spotColor, shapeCrop, resize)
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
