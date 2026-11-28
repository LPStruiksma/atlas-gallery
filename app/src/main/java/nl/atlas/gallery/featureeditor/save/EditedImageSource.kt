package nl.atlas.gallery.featureeditor.save

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Gainmap
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Build
import androidx.annotation.RequiresApi
import nl.atlas.gallery.featureeditor.engine.RenderPlan
import nl.atlas.gallery.featureeditor.engine.RenderStage

/**
 * The save path's only input from the renderer (EDIT-3 #633, Strategy route on #633): the fully
 * rendered, full-resolution base picture plus, for Ultra HDR sources, its gainmap after the same
 * geometry. EDIT-2's export produces it; tests build it from synthetic bitmaps with [warp].
 *
 * [base] holds final pixels: orientation, crop and every colour step are already applied, so the
 * saved file always says `Orientation = normal`. The gainmap is held untyped so this class loads
 * on Android 10-13, where `android.graphics.Gainmap` does not exist; use [gainmap].
 */
internal class EditedImageSource private constructor(
    val base: Bitmap,
    private val gainmapOrNull: Any?,
) {
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun gainmap(): Gainmap? = gainmapOrNull as Gainmap?

    val hasGainmap: Boolean get() = gainmapOrNull != null

    companion object {
        fun sdr(base: Bitmap) = EditedImageSource(base, null)

        @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        fun hdr(base: Bitmap, gainmap: Gainmap?) = EditedImageSource(base, gainmap)

        /**
         * Draws [bitmap] through [stage]'s projective pixel matrix into an exact
         * `width x height` bitmap of the same config. Pixels outside the picture stay transparent
         * (base) or zero (gainmap: no HDR boost).
         */
        fun warp(bitmap: Bitmap, stage: RenderStage.Geometry): Bitmap {
            val config = bitmap.config?.takeIf { it != Bitmap.Config.HARDWARE } ?: Bitmap.Config.ARGB_8888
            val out = Bitmap.createBitmap(stage.width, stage.height, config)
            val values = stage.sourceToOutput.normalized().values()
            val matrix = Matrix().apply { setValues(FloatArray(9) { values[it].toFloat() }) }
            Canvas(out).drawBitmap(bitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            return out
        }

        /** [bitmap] (base or gainmap, any resolution) through [plan]'s full geometry. */
        fun warpThroughPlan(bitmap: Bitmap, plan: RenderPlan): Bitmap =
            warp(bitmap, plan.gainmap(bitmap.width, bitmap.height))

        /**
         * The source gainmap moved through the same normalized geometry as the base
         * ([RenderPlan.gainmap]); its HDR parameters are copied unchanged. Colour steps apply to
         * the SDR base only (`docs/editor/SAVE.md`), so the HDR boost per pixel is unchanged.
         */
        @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        fun transformGainmap(source: Gainmap, plan: RenderPlan): Gainmap {
            val contents = source.gainmapContents
            val warped = warp(contents, plan.gainmap(contents.width, contents.height))
            return Gainmap(warped).apply {
                source.ratioMin.let { setRatioMin(it[0], it[1], it[2]) }
                source.ratioMax.let { setRatioMax(it[0], it[1], it[2]) }
                source.gamma.let { setGamma(it[0], it[1], it[2]) }
                source.epsilonSdr.let { setEpsilonSdr(it[0], it[1], it[2]) }
                source.epsilonHdr.let { setEpsilonHdr(it[0], it[1], it[2]) }
                displayRatioForFullHdr = source.displayRatioForFullHdr
                minDisplayRatioForHdrTransition = source.minDisplayRatioForHdrTransition
            }
        }
    }
}
