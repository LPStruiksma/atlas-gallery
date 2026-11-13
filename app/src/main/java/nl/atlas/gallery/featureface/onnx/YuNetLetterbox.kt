package nl.atlas.gallery.featureface.onnx

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import nl.atlas.gallery.featureface.FacePixelBounds

/**
 * Fits a decoded frame into YuNet's fixed square input: scale so the long edge equals
 * [inputSize], keep the aspect ratio, place it top-left and pad the rest with zeros.
 * Boxes map back by multiplying by the inverse scale. Pure Kotlin so the geometry is JVM-tested.
 */
class YuNetLetterbox(val frameWidth: Int, val frameHeight: Int, val inputSize: Int = YuNetModel.INPUT_SIZE) {
    init { require(frameWidth > 0 && frameHeight > 0 && inputSize > 0) }

    val scale: Float = inputSize.toFloat() / max(frameWidth, frameHeight)
    /** Frame pixels per input pixel; multiplying avoids 1/scale rounding drift (e.g. 960/640 = 1.5 exactly). */
    private val inverse: Float = max(frameWidth, frameHeight).toFloat() / inputSize
    val scaledWidth: Int = (frameWidth * scale).roundToInt().coerceIn(1, inputSize)
    val scaledHeight: Int = (frameHeight * scale).roundToInt().coerceIn(1, inputSize)

    /**
     * Packs ARGB_8888 pixels of the already scaled image (scaledWidth x scaledHeight, row major)
     * into a planar `[1, 3, inputSize, inputSize]` tensor in B, G, R order, values 0..255,
     * which is what OpenCV's `blobFromImage` feeds YuNet.
     */
    fun packBgrPlanar(scaledArgb: IntArray, into: FloatArray = FloatArray(3 * inputSize * inputSize)): FloatArray {
        require(scaledArgb.size == scaledWidth * scaledHeight) { "pixel count does not match the scaled frame" }
        val plane = inputSize * inputSize
        require(into.size == 3 * plane)
        into.fill(0f)
        for (y in 0 until scaledHeight) {
            val src = y * scaledWidth
            val dst = y * inputSize
            for (x in 0 until scaledWidth) {
                val argb = scaledArgb[src + x]
                into[dst + x] = (argb and 0xFF).toFloat()                       // B
                into[plane + dst + x] = ((argb shr 8) and 0xFF).toFloat()       // G
                into[2 * plane + dst + x] = ((argb shr 16) and 0xFF).toFloat()  // R
            }
        }
        return into
    }

    /** Maps a face from input-canvas pixels to frame pixels, clipped; null if nothing is left. */
    fun toFrame(face: YuNetFace): FacePixelBounds? {
        val left = floor(face.left * inverse).toInt().coerceIn(0, frameWidth)
        val top = floor(face.top * inverse).toInt().coerceIn(0, frameHeight)
        val right = ceil(face.right * inverse).toInt().coerceIn(0, frameWidth)
        val bottom = ceil(face.bottom * inverse).toInt().coerceIn(0, frameHeight)
        if (left >= right || top >= bottom) return null
        return FacePixelBounds(left, top, right, bottom)
    }

    /** Same mapping for a full detection, landmarks included (for D2-2 alignment). */
    fun faceToFrame(face: YuNetFace): YuNetFace =
        YuNetFace(face.left * inverse, face.top * inverse, face.width * inverse, face.height * inverse,
            face.score, FloatArray(10) { face.landmarks[it] * inverse })
}
