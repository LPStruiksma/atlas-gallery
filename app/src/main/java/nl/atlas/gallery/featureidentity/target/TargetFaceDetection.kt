package nl.atlas.gallery.featureidentity.target

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import kotlin.math.ceil
import kotlin.math.sqrt
import nl.atlas.gallery.featureface.onnx.OnnxFaceDetector
import nl.atlas.gallery.featureface.onnx.YuNetFace
import nl.atlas.gallery.featureidentity.engine.FaceBox
import nl.atlas.gallery.featureidentity.engine.FacePoint
import nl.atlas.gallery.featureidentity.engine.IdentityEngineCapabilities
import nl.atlas.gallery.featureidentity.engine.TargetFace

/**
 * Orientation-aware, bounded decode adapter for the app's existing YuNet detector.
 *
 * `ImageDecoder` applies EXIF orientation, so YuNet sees the upright frame. Results are mapped back
 * to raw stored pixels with [clockwiseDegrees] (the photo's EXIF/MediaStore orientation, the same
 * value passed to [TargetFaceSelector.faceAtTap]), because the identity engine decodes without
 * EXIF. Selection by area and centre is rotation-invariant, so it can run in raw space too.
 */
object TargetFaceDetection {
    const val MAX_DETECTION_PIXELS = 1_048_576L

    fun detect(
        source: ImageDecoder.Source,
        clockwiseDegrees: Int,
        engine: IdentityEngineCapabilities,
        detectorFactory: () -> OnnxFaceDetector
    ): List<TargetFaceCandidate> {
        require(clockwiseDegrees in setOf(0, 90, 180, 270))
        val limit = minOf(MAX_DETECTION_PIXELS, engine.maxSourcePixels)
        var fullWidth = 0
        var fullHeight = 0
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            fullWidth = info.size.width
            fullHeight = info.size.height
            val scale = minOf(1.0, sqrt(limit.toDouble() / (fullWidth.toLong() * fullHeight)))
            decoder.setTargetSize(
                maxOf(1, (fullWidth * scale).toInt()),
                maxOf(1, (fullHeight * scale).toInt())
            )
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        try {
            val faces = detectorFactory().use { it.detectFaces(bitmap) }
            val sx = fullWidth.toDouble() / bitmap.width
            val sy = fullHeight.toDouble() / bitmap.height
            val rawWidth = if (clockwiseDegrees % 180 == 0) fullWidth else fullHeight
            val rawHeight = if (clockwiseDegrees % 180 == 0) fullHeight else fullWidth
            return faces.mapNotNull { detected ->
                candidate(detected, bitmap, sx, sy, fullWidth, fullHeight)?.let {
                    it.copy(face = TargetFaceSelector.uprightToRaw(it.face, rawWidth, rawHeight, clockwiseDegrees))
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun candidate(
        detected: YuNetFace, bitmap: Bitmap, sx: Double, sy: Double, width: Int, height: Int
    ): TargetFaceCandidate? {
        val left = (detected.left * sx).toInt().coerceIn(0, width - 1)
        val top = (detected.top * sy).toInt().coerceIn(0, height - 1)
        val right = ceil(detected.right * sx).toInt().coerceIn(left + 1, width)
        val bottom = ceil(detected.bottom * sy).toInt().coerceIn(top + 1, height)
        val landmarks = List(5) { index ->
            FacePoint((detected.landmarks[index * 2] * sx).toFloat(),
                (detected.landmarks[index * 2 + 1] * sy).toFloat())
        }
        return TargetFaceCandidate(
            TargetFace(FaceBox(left, top, right, bottom), landmarks),
            detected.score.toDouble().coerceIn(0.0, 1.0),
            sharpness(bitmap, detected)
        )
    }

    /** Variance of a small luma grid, normalized so ordinary focused edges saturate near one. */
    private fun sharpness(bitmap: Bitmap, face: YuNetFace): Double {
        val left = face.left.toInt().coerceIn(0, bitmap.width - 1)
        val top = face.top.toInt().coerceIn(0, bitmap.height - 1)
        val right = ceil(face.right.toDouble()).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = ceil(face.bottom.toDouble()).toInt().coerceIn(top + 1, bitmap.height)
        val values = ArrayList<Double>(64)
        for (gy in 0 until 8) for (gx in 0 until 8) {
            val x = (left + (gx + .5) * (right - left) / 8).toInt().coerceAtMost(right - 1)
            val y = (top + (gy + .5) * (bottom - top) / 8).toInt().coerceAtMost(bottom - 1)
            val pixel = bitmap.getPixel(x, y)
            values += (((pixel ushr 16) and 255) * 299 + ((pixel ushr 8) and 255) * 587 +
                (pixel and 255) * 114) / 255000.0
        }
        val mean = values.average()
        return (values.sumOf { (it - mean) * (it - mean) } / values.size / 0.04).coerceIn(0.0, 1.0)
    }
}
