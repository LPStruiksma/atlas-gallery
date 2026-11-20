package nl.atlas.gallery.featureeditor.filter

import kotlin.math.floor

/** Immutable RGB-major 3D LUT. Samples are ordered with red varying fastest. */
class AtlasLut(val size: Int, private val samples: DoubleArray) {
    init {
        require(size >= 2) { "LUT size must be at least two" }
        require(samples.size == size * size * size * 3) { "wrong LUT sample count" }
        require(samples.all { it.isFinite() }) { "LUT samples must be finite" }
    }

    fun sample(red: Double, green: Double, blue: Double, strength: Double = 1.0): DoubleArray {
        require(red.isFinite() && green.isFinite() && blue.isFinite()) { "input must be finite" }
        require(strength.isFinite() && strength in 0.0..1.0) { "strength out of range" }
        val input = doubleArrayOf(red.coerceIn(0.0, 1.0), green.coerceIn(0.0, 1.0), blue.coerceIn(0.0, 1.0))
        val p = input.map { it * (size - 1) }
        val lo = p.map { floor(it).toInt().coerceAtMost(size - 2) }
        val f = p.mapIndexed { i, value -> value - lo[i] }
        val filtered = DoubleArray(3)
        for (z in 0..1) for (y in 0..1) for (x in 0..1) {
            val weight = (if (x == 0) 1 - f[0] else f[0]) *
                (if (y == 0) 1 - f[1] else f[1]) * (if (z == 0) 1 - f[2] else f[2])
            val offset = (((lo[2] + z) * size + lo[1] + y) * size + lo[0] + x) * 3
            for (channel in 0..2) filtered[channel] += samples[offset + channel] * weight
        }
        return DoubleArray(3) { input[it] + (filtered[it] - input[it]) * strength }
    }

    companion object {
        fun identity(size: Int = 2): AtlasLut {
            val values = ArrayList<Double>(size * size * size * 3)
            for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
                values += r.toDouble() / (size - 1); values += g.toDouble() / (size - 1); values += b.toDouble() / (size - 1)
            }
            return AtlasLut(size, values.toDoubleArray())
        }
    }
}
