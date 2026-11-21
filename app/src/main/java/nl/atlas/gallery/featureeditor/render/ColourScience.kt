package nl.atlas.gallery.featureeditor.render

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign

/**
 * Colour spaces the renderer reads and writes (EDIT-2, goal #629).
 *
 * The working space is **linear Display P3** with extended range: values below 0 or above 1 are
 * kept, never clipped, until the final encode. sRGB sources convert into it exactly (sRGB is
 * inside P3), and Display P3 or Ultra HDR base images keep their full gamut. Both encodings use
 * the sRGB transfer curve, extended sign-symmetrically so out-of-range values survive.
 */
enum class PixelSpace(val key: String) {
    SRGB("srgb"),
    DISPLAY_P3("display-p3"),
    ;

    /** Linear values in this space to linear Display P3 (the working space). */
    val toWorking: FloatArray get() = if (this == SRGB) ColourScience.SRGB_TO_P3 else ColourScience.IDENTITY

    /** Linear Display P3 to linear values in this space. */
    val fromWorking: FloatArray get() = if (this == SRGB) ColourScience.P3_TO_SRGB else ColourScience.IDENTITY
}

/** Matrices and transfer curves, shared by the CPU reference and (as uniforms) the AGSL shader. */
object ColourScience {
    val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    // D65 RGB-to-XYZ matrices (row-major), from the colour-space primaries.
    private val SRGB_TO_XYZ = doubleArrayOf(
        0.4123907992659595, 0.357584339383878, 0.1804807884018343, // guard: allow-public (colour-space constant)
        0.21263900587151036, 0.715168678767756, 0.07219231536073371, // guard: allow-public (colour-space constant)
        0.01933081871559185, 0.11919477979462598, 0.9505321522496606, // guard: allow-public (colour-space constant)
    )
    private val P3_TO_XYZ = doubleArrayOf(
        0.4865709486482162, 0.26566769316909306, 0.1982172852343625, // guard: allow-public (colour-space constant)
        0.2289745640697488, 0.6917385218365064, 0.079286914093745, // guard: allow-public (colour-space constant)
        0.0, 0.04511338185890264, 1.043944368900976, // guard: allow-public (colour-space constant)
    )

    /** Linear sRGB to linear Display P3 (row-major). */
    val SRGB_TO_P3: FloatArray = mul(inverse(P3_TO_XYZ), SRGB_TO_XYZ).toFloats()

    /** Linear Display P3 to linear sRGB (row-major); P3-only colours give values outside 0..1. */
    val P3_TO_SRGB: FloatArray = mul(inverse(SRGB_TO_XYZ), P3_TO_XYZ).toFloats()

    /** Luminance weights of linear Display P3 (the Y row of [P3_TO_XYZ]). */
    val LUMA_P3 = floatArrayOf(0.22897457f, 0.6917385f, 0.07928691f)

    /** sRGB transfer, encoded to linear, extended: odd-symmetric and unclamped. */
    fun decode(e: Float): Float {
        val a = abs(e)
        val l = if (a <= 0.04045f) a / 12.92f else ((a + 0.055f) / 1.055f).pow(2.4f)
        return l * sign(e)
    }

    /** sRGB transfer, linear to encoded, extended: odd-symmetric and unclamped. */
    fun encode(l: Float): Float {
        val a = abs(l)
        val e = if (a <= 0.0031308f) a * 12.92f else 1.055f * a.pow(1f / 2.4f) - 0.055f
        return e * sign(l)
    }

    fun luma(r: Float, g: Float, b: Float): Float = LUMA_P3[0] * r + LUMA_P3[1] * g + LUMA_P3[2] * b

    /** Applies a row-major 3x3 [m] to (r, g, b) in place in [out] at [o]. */
    fun apply(m: FloatArray, r: Float, g: Float, b: Float, out: FloatArray, o: Int = 0) {
        out[o] = m[0] * r + m[1] * g + m[2] * b
        out[o + 1] = m[3] * r + m[4] * g + m[5] * b
        out[o + 2] = m[6] * r + m[7] * g + m[8] * b
    }

    private fun mul(a: DoubleArray, b: DoubleArray): DoubleArray = DoubleArray(9) { i ->
        val r = i / 3; val c = i % 3
        a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
    }

    private fun inverse(m: DoubleArray): DoubleArray {
        val det = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])
        return doubleArrayOf(
            (m[4] * m[8] - m[5] * m[7]) / det, (m[2] * m[7] - m[1] * m[8]) / det, (m[1] * m[5] - m[2] * m[4]) / det,
            (m[5] * m[6] - m[3] * m[8]) / det, (m[0] * m[8] - m[2] * m[6]) / det, (m[2] * m[3] - m[0] * m[5]) / det,
            (m[3] * m[7] - m[4] * m[6]) / det, (m[1] * m[6] - m[0] * m[7]) / det, (m[0] * m[4] - m[1] * m[3]) / det,
        )
    }

    private fun DoubleArray.toFloats() = FloatArray(size) { this[it].toFloat() }
}
