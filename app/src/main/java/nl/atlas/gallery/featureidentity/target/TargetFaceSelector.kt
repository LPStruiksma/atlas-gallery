package nl.atlas.gallery.featureidentity.target

import kotlin.math.hypot
import nl.atlas.gallery.featureidentity.engine.FaceBox
import nl.atlas.gallery.featureidentity.engine.FacePoint
import nl.atlas.gallery.featureidentity.engine.TargetFace

/** Measurements used to choose a target. Sharpness is normalized to 0..1 by the detector. */
data class TargetFaceCandidate(
    val face: TargetFace,
    val detectorConfidence: Double,
    val sharpness: Double
) {
    init {
        require(detectorConfidence in 0.0..1.0 && sharpness in 0.0..1.0)
    }
}

sealed interface TargetFaceSelection {
    data object NoFace : TargetFaceSelection
    data class TooSmall(val face: TargetFace) : TargetFaceSelection
    data class Auto(val face: TargetFace) : TargetFaceSelection
    data class Ask(val faces: List<TargetFace>) : TargetFaceSelection
}

/** Pure selection and tap geometry; Android decoding and YuNet invocation live in [TargetFaceDetection]. */
object TargetFaceSelector {
    const val AREA_WEIGHT = 0.45
    const val CENTRE_WEIGHT = 0.20
    const val SHARPNESS_WEIGHT = 0.20
    const val CONFIDENCE_WEIGHT = 0.15
    const val MIN_SHARPNESS = 0.10
    const val MIN_CONFIDENCE = 0.50
    const val AUTO_MARGIN = 0.18

    fun select(
        candidates: List<TargetFaceCandidate>,
        imageWidth: Int,
        imageHeight: Int,
        minFaceEdge: Int
    ): TargetFaceSelection {
        require(imageWidth > 0 && imageHeight > 0 && minFaceEdge > 0)
        if (candidates.isEmpty()) return TargetFaceSelection.NoFace
        val scored = candidates.map { it to score(it, imageWidth, imageHeight) }
            .sortedByDescending { it.second }
        val largeEnough = scored.filter {
            it.first.face.box.width >= minFaceEdge && it.first.face.box.height >= minFaceEdge
        }
        if (largeEnough.isEmpty()) return TargetFaceSelection.TooSmall(scored.first().first.face)

        val eligible = largeEnough.filter {
            it.first.detectorConfidence >= MIN_CONFIDENCE && it.first.sharpness >= MIN_SHARPNESS
        }
        // The result model has no "low quality" state: keep dubious faces tappable, but never guess.
        if (eligible.isEmpty()) return TargetFaceSelection.Ask(largeEnough.map { it.first.face })
        if (eligible.size == 1) return TargetFaceSelection.Auto(eligible.first().first.face)
        return if (eligible[0].second - eligible[1].second >= AUTO_MARGIN) {
            TargetFaceSelection.Auto(eligible[0].first.face)
        } else {
            TargetFaceSelection.Ask(eligible.map { it.first.face })
        }
    }

    private fun score(candidate: TargetFaceCandidate, width: Int, height: Int): Double {
        val box = candidate.face.box
        val area = (box.width.toDouble() * box.height / (width.toDouble() * height)).coerceIn(0.0, 1.0)
        val cx = (box.left + box.right) / 2.0
        val cy = (box.top + box.bottom) / 2.0
        val maxDistance = hypot(width / 2.0, height / 2.0)
        val centre = (1.0 - hypot(cx - width / 2.0, cy - height / 2.0) / maxDistance).coerceIn(0.0, 1.0)
        return AREA_WEIGHT * area + CENTRE_WEIGHT * centre +
            SHARPNESS_WEIGHT * candidate.sharpness + CONFIDENCE_WEIGHT * candidate.detectorConfidence
    }

    /** Maps an aspect-fit view tap through clockwise photo rotation into raw source pixels. */
    fun faceAtTap(
        faces: List<TargetFace>, rawWidth: Int, rawHeight: Int,
        clockwiseDegrees: Int, viewWidth: Float, viewHeight: Float,
        tap: FacePoint, tolerancePx: Float
    ): TargetFace? {
        require(rawWidth > 0 && rawHeight > 0 && viewWidth > 0f && viewHeight > 0f && tolerancePx >= 0f)
        require(clockwiseDegrees in setOf(0, 90, 180, 270))
        val uprightWidth = if (clockwiseDegrees % 180 == 0) rawWidth else rawHeight
        val uprightHeight = if (clockwiseDegrees % 180 == 0) rawHeight else rawWidth
        val scale = minOf(viewWidth / uprightWidth, viewHeight / uprightHeight)
        val x = (tap.x - (viewWidth - uprightWidth * scale) / 2f) / scale
        val y = (tap.y - (viewHeight - uprightHeight * scale) / 2f) / scale
        if (x !in 0f..uprightWidth.toFloat() || y !in 0f..uprightHeight.toFloat()) return null
        val raw = when (clockwiseDegrees) {
            0 -> FacePoint(x, y)
            90 -> FacePoint(y, rawHeight - x)
            180 -> FacePoint(rawWidth - x, rawHeight - y)
            else -> FacePoint(rawWidth - y, x)
        }
        faces.firstOrNull { it.box.contains(raw) }?.let { return it }
        val sourceTolerance = tolerancePx / scale
        return faces.minByOrNull { distance(raw, it.box) }
            ?.takeIf { distance(raw, it.box) <= sourceTolerance }
    }

    private fun distance(point: FacePoint, box: FaceBox): Double {
        val dx = when { point.x < box.left -> box.left - point.x; point.x > box.right -> point.x - box.right; else -> 0f }
        val dy = when { point.y < box.top -> box.top - point.y; point.y > box.bottom -> point.y - box.bottom; else -> 0f }
        return hypot(dx.toDouble(), dy.toDouble())
    }
}
