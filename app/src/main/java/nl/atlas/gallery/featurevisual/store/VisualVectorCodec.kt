package nl.atlas.gallery.featurevisual.store

import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A compact, portable signed-int8 unit embedding with a scale PER vector. */
data class QuantizedVisualVector(val bytes: ByteArray, val scale: Float)

/**
 * Symmetric int8 quantization. Scale is max(abs(component)) / 127, so the
 * largest-magnitude component does not clip; zero/NaN vectors are rejected.
 * Decode normalises again to make dot products an approximate cosine.
 */
object VisualVectorCodec {
    fun encode(vector: FloatArray): QuantizedVisualVector {
        require(vector.isNotEmpty() && vector.all { it.isFinite() }) { "Invalid visual vector" }
        var squared = 0.0
        for (value in vector) squared += value.toDouble() * value
        require(squared.isFinite() && squared > 0.0) { "Zero or invalid visual vector" }
        val inverseNorm = (1.0 / sqrt(squared)).toFloat()
        val unit = FloatArray(vector.size) { vector[it] * inverseNorm }
        val peak = unit.maxOf { kotlin.math.abs(it) }
        val scale = peak / 127f
        require(scale.isFinite() && scale > 0f)
        return QuantizedVisualVector(ByteArray(vector.size) { index ->
            (unit[index] / scale).roundToInt().coerceIn(-127, 127).toByte()
        }, scale)
    }

    fun decode(bytes: ByteArray, scale: Float, expectedDimension: Int): FloatArray {
        require(expectedDimension > 0 && bytes.size == expectedDimension) { "Wrong visual dimension" }
        require(scale.isFinite() && scale > 0f) { "Invalid visual scale" }
        val vector = FloatArray(expectedDimension) { bytes[it].toFloat() * scale }
        var squared = 0.0
        for (value in vector) squared += value.toDouble() * value
        require(squared.isFinite() && squared > 0.0) { "Invalid stored vector" }
        val inverseNorm = (1.0 / sqrt(squared)).toFloat()
        return FloatArray(expectedDimension) { vector[it] * inverseNorm }
    }
}
