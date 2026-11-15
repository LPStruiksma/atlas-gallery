package nl.atlas.gallery.featureface

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import nl.atlas.gallery.featureface.onnx.YuNetFace

/**
 * COVER-0: device-local, per-observation photographic quality, never identity
 * or a comparison of different people. VERSION must change when weights/
 * normalisation change. Null in persistence means that no quality was computed.
 */
data class FaceQuality(
    val score: Float,
    val qualityVersion: Int = FaceQualityScorer.VERSION,
    val belowSizeFloor: Boolean
) {
    init {
        require(score.isFinite() && score in 0f..1f)
        require(qualityVersion > 0)
    }
}

object FaceQualityScorer {
    const val VERSION = 1
    const val MIN_FACE_SHORT_EDGE_PX = 112

    /**
     * Weighted arithmetic score, each factor in [0,1]:
     * YuNet confidence 10%, original-pixel face size 20%, 112x112 crop
     * Laplacian sharpness 25%, crop exposure/contrast 20%, landmark pose
     * (frontalness + roll) 20%, and frame-edge truncation 5%.
     *
     * All luminance and Laplacian calculations use the aligned RGB planar
     * crop already produced for SFace, not a second decode or model.
     * Detection and geometry are from the EXISTING YuNet pass.
     */
    fun score(
        detection: YuNetFace,
        frameWidth: Int,
        frameHeight: Int,
        cropRgbPlanar: FloatArray,
        cropSize: Int = 112,
        sourceScale: Float = 1f
    ): FaceQuality {
        require(frameWidth > 0 && frameHeight > 0 && cropSize >= 3)
        require(cropRgbPlanar.size == 3 * cropSize * cropSize)
        require(sourceScale.isFinite() && sourceScale > 0f)
        require(cropRgbPlanar.all { it.isFinite() && it in 0f..255f })
        val n = cropSize * cropSize
        val gray = DoubleArray(n) { i ->
            (0.2126 * cropRgbPlanar[i] + 0.7152 * cropRgbPlanar[n + i] +
                0.0722 * cropRgbPlanar[2 * n + i]).toDouble()
        }
        var sum = 0.0
        var clipped = 0
        for (v in gray) {
            sum += v
            if (v <= 8.0 || v >= 247.0) clipped++
        }
        val mean = sum / n
        var variance = 0.0
        for (v in gray) variance += (v - mean) * (v - mean)
        val contrast = kotlin.math.sqrt(variance / n)
        val exposure = (1.0 - abs(mean - 127.5) / 127.5).coerceIn(0.0, 1.0)
        val clipPenalty = (1.0 - clipped.toDouble() / n).coerceIn(0.0, 1.0)
        val tonal = exposure * clipPenalty * (contrast / 48.0).coerceIn(0.0, 1.0)

        // Discrete 4-neighbour Laplacian, variance rather than edge average:
        // a blurred crop suppresses high frequencies even when mean stays fixed.
        var lapSum = 0.0
        var lapSq = 0.0
        var count = 0
        for (y in 1 until cropSize - 1) for (x in 1 until cropSize - 1) {
            val i = y * cropSize + x
            val v = 4 * gray[i] - gray[i - 1] - gray[i + 1] -
                gray[i - cropSize] - gray[i + cropSize]
            lapSum += v
            lapSq += v * v
            count++
        }
        val lapMean = lapSum / count
        val laplacianVariance = max(0.0, lapSq / count - lapMean * lapMean)
        val sharp = (ln(1.0 + laplacianVariance) / ln(1.0 + 900.0)).coerceIn(0.0, 1.0)

        val shortSide = min(detection.width, detection.height) * sourceScale
        val size = (shortSide / MIN_FACE_SHORT_EDGE_PX).toDouble().coerceIn(0.0, 1.0)

        val p = detection.landmarks
        require(p.size == 10 && p.all { it.isFinite() })
        val eyeCenterX = (p[0] + p[2]) / 2.0
        val eyeDistance = hypot((p[2] - p[0]).toDouble(), (p[3] - p[1]).toDouble())
        val noseOffset = abs(p[4] - eyeCenterX) / max(eyeDistance, 1.0)
        val mouthCenterX = (p[6] + p[8]) / 2.0
        val symmetry = (1.0 - (noseOffset + abs(p[4] - mouthCenterX) / max(eyeDistance, 1.0))).coerceIn(0.0, 1.0)
        val rollRadians = abs(atan2((p[3] - p[1]).toDouble(), (p[2] - p[0]).toDouble()))
        val roll = (1.0 - rollRadians / (Math.PI / 3.0)).coerceIn(0.0, 1.0)
        val pose = 0.7 * symmetry + 0.3 * roll

        val margin = 2.0
        val intact = if (detection.left < margin || detection.top < margin ||
            detection.right > frameWidth - margin || detection.bottom > frameHeight - margin) 0.0 else 1.0
        val detectionConfidence = detection.score.toDouble().coerceIn(0.0, 1.0)
        val result = 0.10 * detectionConfidence + 0.20 * size + 0.25 * sharp +
            0.20 * tonal + 0.20 * pose + 0.05 * intact
        return FaceQuality(result.coerceIn(0.0, 1.0).toFloat(), VERSION,
            belowSizeFloor = shortSide < MIN_FACE_SHORT_EDGE_PX)
    }
}
