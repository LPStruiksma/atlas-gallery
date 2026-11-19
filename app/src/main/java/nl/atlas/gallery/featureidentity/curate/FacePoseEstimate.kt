package nl.atlas.gallery.featureidentity.curate

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** Coarse, explainable pose derived only from YuNet's five landmarks. */
data class FacePoseEstimate(
    val yaw: Float,
    val rollDegrees: Float,
    val expression: Float
) {
    init {
        require(yaw.isFinite() && yaw in -1f..1f)
        require(rollDegrees.isFinite() && rollDegrees in -180f..180f)
        require(expression.isFinite() && expression in 0f..1f)
    }

    val yawBand: Int get() = when {
        yaw < -0.18f -> -1
        yaw > 0.18f -> 1
        else -> 0
    }
    val expressionBand: Int get() = if (expression >= 0.34f) 1 else 0

    companion object {
        /** Points are left eye, right eye, nose, left mouth and right mouth. */
        fun fromYuNet(landmarks: List<Pair<Float, Float>>): FacePoseEstimate? {
            if (landmarks.size != 5 || landmarks.any { !it.first.isFinite() || !it.second.isFinite() }) return null
            val (leftEye, rightEye, nose, leftMouth, rightMouth) = landmarks
            val eyeDistance = distance(leftEye, rightEye)
            if (eyeDistance <= 1e-6f) return null
            val eyeX = (leftEye.first + rightEye.first) / 2f
            val mouthX = (leftMouth.first + rightMouth.first) / 2f
            // Two centre estimates make the result less sensitive to a single noisy landmark.
            val yaw = (((nose.first - eyeX) + (nose.first - mouthX)) / eyeDistance)
                .coerceIn(-1f, 1f)
            val roll = Math.toDegrees(atan2(
                (rightEye.second - leftEye.second).toDouble(),
                (rightEye.first - leftEye.first).toDouble()
            )).toFloat()
            val mouthWidth = distance(leftMouth, rightMouth) / eyeDistance
            val mouthDrop = abs((leftMouth.second + rightMouth.second) / 2f - nose.second) / eyeDistance
            val expression = ((mouthWidth - 0.55f) * 0.8f + (mouthDrop - 0.35f) * 0.5f)
                .coerceIn(0f, 1f)
            return FacePoseEstimate(yaw, roll, expression)
        }

        private fun distance(a: Pair<Float, Float>, b: Pair<Float, Float>): Float =
            hypot(a.first - b.first, a.second - b.second)
    }
}
