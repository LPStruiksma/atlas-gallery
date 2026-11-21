package nl.atlas.gallery.featureeditor.render

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * A 3D colour look-up table on sRGB-encoded sRGB values, the domain photo LUTs (`.cube`) are
 * authored in. [data] holds `size^3` rgb triples with red varying fastest, then green, then blue
 * (the `.cube` order).
 *
 * Wide-gamut colours lie outside the LUT's 0..1 cube. [apply] looks up the clamped colour and
 * adds back the part that was clamped away, so a P3 or above-white colour keeps its extra range
 * instead of being clipped to sRGB.
 */
class Lut3D(val size: Int, val data: FloatArray) {
    init {
        require(size in 2..65) { "LUT size must be 2..65" }
        require(data.size == size * size * size * 3) { "LUT data has the wrong length" }
        require(data.all { it.isFinite() }) { "LUT values must be finite" }
    }

    /** Trilinear lookup of encoded sRGB (r, g, b) in 0..1, into [out] at [o]. */
    fun sample(r: Float, g: Float, b: Float, out: FloatArray, o: Int = 0) {
        val n1 = (size - 1).toFloat()
        val x = r * n1; val y = g * n1; val z = b * n1
        val x0 = min(size - 2, max(0, floor(x).toInt())); val fx = x - x0
        val y0 = min(size - 2, max(0, floor(y).toInt())); val fy = y - y0
        val z0 = min(size - 2, max(0, floor(z).toInt())); val fz = z - z0
        for (ch in 0..2) {
            val c000 = at(x0, y0, z0, ch); val c100 = at(x0 + 1, y0, z0, ch)
            val c010 = at(x0, y0 + 1, z0, ch); val c110 = at(x0 + 1, y0 + 1, z0, ch)
            val c001 = at(x0, y0, z0 + 1, ch); val c101 = at(x0 + 1, y0, z0 + 1, ch)
            val c011 = at(x0, y0 + 1, z0 + 1, ch); val c111 = at(x0 + 1, y0 + 1, z0 + 1, ch)
            val c00 = c000 + (c100 - c000) * fx; val c10 = c010 + (c110 - c010) * fx
            val c01 = c001 + (c101 - c001) * fx; val c11 = c011 + (c111 - c011) * fx
            val c0 = c00 + (c10 - c00) * fy; val c1 = c01 + (c11 - c01) * fy
            out[o + ch] = c0 + (c1 - c0) * fz
        }
    }

    private fun at(x: Int, y: Int, z: Int, ch: Int): Float = data[((z * size + y) * size + x) * 3 + ch]

    /**
     * Applies the LUT at [strength] to linear Display P3 [c] in place: P3 to sRGB, encode, look
     * up the clamped colour plus its out-of-cube residual, mix by strength, decode, back to P3.
     */
    fun apply(c: FloatArray, strength: Float) {
        if (strength <= 0f) return
        val t = FloatArray(3)
        ColourScience.apply(ColourScience.P3_TO_SRGB, c[0], c[1], c[2], t)
        val e = FloatArray(3) { ColourScience.encode(t[it]) }
        val k = FloatArray(3) { min(1f, max(0f, e[it])) }
        sample(k[0], k[1], k[2], t)
        for (i in 0..2) {
            val looked = t[i] + (e[i] - k[i])
            t[i] = ColourScience.decode(e[i] + (looked - e[i]) * strength)
        }
        ColourScience.apply(ColourScience.SRGB_TO_P3, t[0], t[1], t[2], c)
    }

    companion object {
        fun identity(size: Int = 17): Lut3D {
            val d = FloatArray(size * size * size * 3)
            val n1 = (size - 1).toFloat()
            for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
                val i = ((b * size + g) * size + r) * 3
                d[i] = r / n1; d[i + 1] = g / n1; d[i + 2] = b / n1
            }
            return Lut3D(size, d)
        }

        /** Builds a LUT from a function of encoded sRGB in 0..1 (for synthetic filters and tests). */
        fun of(size: Int, f: (Float, Float, Float, FloatArray) -> Unit): Lut3D {
            val d = FloatArray(size * size * size * 3)
            val n1 = (size - 1).toFloat()
            val px = FloatArray(3)
            for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
                f(r / n1, g / n1, b / n1, px)
                val i = ((b * size + g) * size + r) * 3
                d[i] = px[0]; d[i + 1] = px[1]; d[i + 2] = px[2]
            }
            return Lut3D(size, d)
        }

        /**
         * Parses an Adobe/Resolve `.cube` 3D LUT. Only `LUT_3D_SIZE` and the default 0..1 domain
         * are accepted; 1D LUTs and custom domains are refused rather than guessed. Error
         * messages never echo the file's text.
         */
        fun parseCube(text: String): Lut3D {
            var size = 0
            val values = ArrayList<Float>()
            text.lineSequence().forEachIndexed { index, raw ->
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) return@forEachIndexed
                val first = line.substringBefore(' ').substringBefore('\t')
                when {
                    first == "TITLE" -> Unit
                    first == "LUT_3D_SIZE" -> size = line.substringAfter(first).trim().toIntOrNull()
                        ?: throw IllegalArgumentException("Bad LUT_3D_SIZE on line ${index + 1}")
                    first == "LUT_1D_SIZE" -> throw IllegalArgumentException("1D LUTs are not supported")
                    first == "DOMAIN_MIN" -> requireDomain(line, 0f, index)
                    first == "DOMAIN_MAX" -> requireDomain(line, 1f, index)
                    first[0].isLetter() -> Unit // other keywords (e.g. LUT_3D_INPUT_RANGE absent)
                    else -> {
                        val parts = line.split(' ', '\t').filter { it.isNotEmpty() }
                        require(parts.size == 3) { "Expected 3 values on line ${index + 1}" }
                        parts.forEach { values += it.toFloatOrNull() ?: throw IllegalArgumentException("Bad number on line ${index + 1}") }
                    }
                }
            }
            require(size > 0) { "Missing LUT_3D_SIZE" }
            require(values.size == size * size * size * 3) { "LUT has ${values.size / 3} entries, expected ${size * size * size}" }
            return Lut3D(size, values.toFloatArray())
        }

        private fun requireDomain(line: String, expected: Float, index: Int) {
            val parts = line.split(' ', '\t').filter { it.isNotEmpty() }.drop(1).map { it.toFloatOrNull() }
            require(parts.size == 3 && parts.all { it == expected }) { "Unsupported LUT domain on line ${index + 1}" }
        }
    }
}
