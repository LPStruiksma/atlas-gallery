package nl.atlas.gallery.featureeditor.filter

import kotlin.math.floor

/** Encoded-sRGB CPU reference; R varies fastest, then G, then B. */
class AtlasLut(val size: Int, private val samples: DoubleArray) {
    init {
        require(size in 2..65) { "unsupported LUT dimension" }
        require(samples.size == size * size * size * 3) { "wrong LUT sample count" }
        require(samples.all { it.isFinite() }) { "LUT samples must be finite" }
    }

    fun sample(red: Double, green: Double, blue: Double, strength: Double = 1.0): DoubleArray {
        require(red.isFinite() && green.isFinite() && blue.isFinite()) { "input must be finite" }
        require(strength.isFinite() && strength in 0.0..1.0) { "strength out of range" }
        val input = doubleArrayOf(red.coerceIn(0.0, 1.0), green.coerceIn(0.0, 1.0), blue.coerceIn(0.0, 1.0))
        if (strength == 0.0) return input
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

    fun samplePercent(red: Double, green: Double, blue: Double, strength: Int): DoubleArray {
        require(strength in 0..100) { "strength must be 0..100" }
        return sample(red, green, blue, strength / 100.0)
    }

    companion object {
        private val magic = "ATLASLUT1".toByteArray(Charsets.US_ASCII)

        fun fromBytes(bytes: ByteArray): AtlasLut {
            require(bytes.size >= 10 && bytes.copyOfRange(0, 9).contentEquals(magic)) { "invalid LUT magic" }
            val edge = bytes[9].toInt() and 0xff
            require(edge in 2..65 && bytes.size == 10 + edge * edge * edge * 3) { "invalid LUT dimensions" }
            val values = DoubleArray(edge * edge * edge * 3) { (bytes[it + 10].toInt() and 0xff) / 255.0 }
            return AtlasLut(edge, values)
        }

        fun identity(size: Int = 2): AtlasLut {
            require(size in 2..65)
            val values = DoubleArray(size * size * size * 3)
            var index = 0
            for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
                values[index++] = r.toDouble() / (size - 1)
                values[index++] = g.toDouble() / (size - 1)
                values[index++] = b.toDouble() / (size - 1)
            }
            return AtlasLut(size, values)
        }
    }
}
