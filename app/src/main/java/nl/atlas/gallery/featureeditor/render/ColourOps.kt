package nl.atlas.gallery.featureeditor.render

import nl.atlas.gallery.featureeditor.engine.AdjustParam
import nl.atlas.gallery.featureeditor.engine.RenderStage
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The twelve colour controls as plain floats, in [AdjustParam] order (the order they apply in).
 * Zero means "untouched". The AGSL shader receives exactly these values as three float4 uniforms.
 */
class AdjustValues(val values: FloatArray = FloatArray(AdjustParam.entries.size)) {
    init { require(values.size == AdjustParam.entries.size) }

    operator fun get(p: AdjustParam): Float = values[p.ordinal]

    val isIdentity: Boolean get() = values.all { it == 0f }

    /** True when a control needs neighbouring pixels (Definition, Sharpness). */
    val needsNeighbours: Boolean get() = this[AdjustParam.DEFINITION] != 0f || this[AdjustParam.SHARPNESS] != 0f

    companion object {
        val NONE = AdjustValues()

        fun of(stage: RenderStage.ColourAdjust?): AdjustValues {
            val v = FloatArray(AdjustParam.entries.size)
            stage?.values?.forEach { (p, x) -> v[p.ordinal] = x.toFloat() }
            return AdjustValues(v)
        }
    }
}

/**
 * Pixel maths for the colour controls, in linear Display P3 (see [ColourScience]).
 *
 * This is the reference the AGSL shader in `AgslRenderer` mirrors line by line; keep the two in
 * step. Every control is a smooth function of its value, is the identity at 0 and never clamps,
 * so wide-gamut and above-white values pass through untouched until the final encode.
 *
 * Definition and Sharpness need neighbourhood detail; the caller passes it as encoded-luma
 * differences ([definitionDetail], [sharpnessDetail]) measured on the warped picture before
 * colour, so tiles and the full frame compute identical values.
 */
object ColourOps {
    const val EXPOSURE_STOPS = 2f
    const val BRIGHTNESS_GAMMA = 0.7f
    const val CONTRAST_GAIN = 0.75f
    const val HIGHLIGHT_STOPS = 0.8f
    const val SHADOW_STOPS = 1.2f
    const val WB_STOPS = 0.3f
    const val HUE_RANGE = Math.PI.toFloat()
    const val DEFINITION_GAIN = 1.0f
    const val SHARPNESS_GAIN = 2.0f
    const val VIGNETTE_STOPS = 2.0f

    /** Definition's sampling radius in output pixels, for an output whose long edge is [longEdge]. */
    fun definitionRadius(longEdge: Int): Float = max(1f, longEdge * 0.004f)

    /**
     * Applies every non-zero control to [c] (linear P3, rgb at 0..2) in place. [nx], [ny] are the
     * pixel centre in normalized output coordinates (for the vignette).
     */
    fun apply(
        a: AdjustValues,
        c: FloatArray,
        nx: Float,
        ny: Float,
        definitionDetail: Float = 0f,
        sharpnessDetail: Float = 0f,
    ) {
        val v = a.values
        if (v[0] != 0f) scale(c, 2f.pow(EXPOSURE_STOPS * v[0]))
        if (v[1] != 0f) {
            val p = 2f.pow(-BRIGHTNESS_GAMMA * v[1])
            for (i in 0..2) c[i] = ColourScience.decode(spow(ColourScience.encode(c[i]), p))
        }
        if (v[2] != 0f) {
            val k = 1f + CONTRAST_GAIN * v[2]
            for (i in 0..2) c[i] = ColourScience.decode((ColourScience.encode(c[i]) - 0.5f) * k + 0.5f)
        }
        if (v[3] != 0f) {
            val ye = ColourScience.encode(luma(c))
            scale(c, 2f.pow(v[3] * HIGHLIGHT_STOPS * smoothstep(0.5f, 1f, ye)))
        }
        if (v[4] != 0f) {
            val ye = ColourScience.encode(luma(c))
            scale(c, 2f.pow(v[4] * SHADOW_STOPS * (1f - smoothstep(0f, 0.5f, ye))))
        }
        if (v[5] != 0f) {
            c[0] *= 2f.pow(WB_STOPS * v[5])
            c[2] *= 2f.pow(-WB_STOPS * v[5])
        }
        if (v[6] != 0f) c[1] *= 2f.pow(-WB_STOPS * v[6])
        if (v[7] != 0f) {
            val y = luma(c); val s = 1f + v[7]
            for (i in 0..2) c[i] = y + (c[i] - y) * s
        }
        if (v[8] != 0f) rotateHue(c, v[8] * HUE_RANGE)
        if (v[9] != 0f) addDetail(c, DEFINITION_GAIN * v[9] * definitionDetail)
        if (v[10] != 0f) addDetail(c, SHARPNESS_GAIN * v[10] * sharpnessDetail)
        if (v[11] != 0f) {
            val dx = nx - 0.5f; val dy = ny - 0.5f
            val d = sqrt(dx * dx + dy * dy) / SQRT_HALF
            scale(c, 2f.pow(-VIGNETTE_STOPS * v[11] * smoothstep(0.3f, 1f, d)))
        }
    }

    fun luma(c: FloatArray): Float = ColourScience.luma(c[0], c[1], c[2])

    /** Rodrigues rotation about the grey axis (1,1,1)/sqrt(3), so greys stay grey. */
    private fun rotateHue(c: FloatArray, theta: Float) {
        val cs = cos(theta); val sn = sin(theta)
        val k = INV_SQRT3
        val dot = (c[0] + c[1] + c[2]) * k
        val r = c[0]; val g = c[1]; val b = c[2]
        // k x c, with k = (k, k, k)
        val xr = k * (b - g); val xg = k * (r - b); val xb = k * (g - r)
        val t = dot * k * (1f - cs)
        c[0] = r * cs + xr * sn + t
        c[1] = g * cs + xg * sn + t
        c[2] = b * cs + xb * sn + t
    }

    /** Shifts encoded luma by [delta] and scales rgb to match, keeping hue and saturation. */
    private fun addDetail(c: FloatArray, delta: Float) {
        val y = luma(c)
        if (y <= 1e-6f) return
        val target = ColourScience.decode(ColourScience.encode(y) + delta)
        scale(c, max(0f, target) / y)
    }

    private fun scale(c: FloatArray, k: Float) { c[0] *= k; c[1] *= k; c[2] *= k }

    fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = min(1f, max(0f, (x - e0) / (e1 - e0)))
        return t * t * (3f - 2f * t)
    }

    private fun spow(x: Float, p: Float): Float = if (x < 0f) -((-x).pow(p)) else x.pow(p)

    private const val INV_SQRT3 = 0.57735026f
    private const val SQRT_HALF = 0.70710677f
}
