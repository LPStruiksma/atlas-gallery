package nl.atlas.gallery.featurevisual.pose

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Mirror- and yaw-tolerant body-pose descriptor for the "emphasise pose" control (goal #468,
 * VS-S2p). A 1:1 port of `tools/visual_search_bench/pose_descriptor.py`, the version the VS-S0
 * benchmark measured (`docs/visual-search/VS_S0_MODEL_SPIKE.md`).
 *
 * Input: the 33 BlazePose metric "world" landmarks (x right, y down, z depth, hip-centred), as
 * [OnnxPoseEstimator.worldLandmarks] returns them. A pose is described by the unit directions of
 * 12 bones after rotating about the vertical axis so the hip line points along +x (yaw removed).
 * Gravity is kept: an inverted cartwheel differs from a standing pose, while a left-to-right
 * cartwheel matches a right-to-left one (mirror) and a front view roughly matches a side view.
 *
 * A descriptor is [SIZE] floats: the pose's 36 bone components, then those of its mirror.
 * Pure Kotlin, no Android types, so it runs in JVM tests.
 */
object PoseDescriptor {
    // BlazePose landmark indices.
    const val L_SH = 11; const val R_SH = 12; const val L_EL = 13; const val R_EL = 14
    const val L_WR = 15; const val R_WR = 16; const val L_HIP = 23; const val R_HIP = 24
    const val L_KN = 25; const val R_KN = 26; const val L_AN = 27; const val R_AN = 28

    const val LANDMARKS = 33

    /** (from, to) landmark pairs, in the order of `pose_descriptor.BONES`. */
    val BONES: List<Pair<Int, Int>> = listOf(
        L_SH to L_EL, L_EL to L_WR, R_SH to R_EL, R_EL to R_WR,
        L_HIP to L_KN, L_KN to L_AN, R_HIP to R_KN, R_KN to R_AN,
        L_HIP to L_SH, R_HIP to R_SH, R_SH to L_SH, R_HIP to L_HIP,
    )

    /** Left/right landmark swap used for the mirrored pose (`pose_descriptor.SWAP`). */
    val SWAP: Map<Int, Int> = mapOf(
        L_SH to R_SH, R_SH to L_SH, L_EL to R_EL, R_EL to L_EL, L_WR to R_WR, R_WR to L_WR,
        L_HIP to R_HIP, R_HIP to L_HIP, L_KN to R_KN, R_KN to L_KN, L_AN to R_AN, R_AN to L_AN,
    )

    /** Floats per pose variant (12 bones × 3). */
    val DIM: Int = BONES.size * 3

    /** Floats per stored descriptor: the pose, then its mirror. */
    val SIZE: Int = 2 * DIM

    /**
     * Descriptor of [world] (at least 33 × 3 floats, row-major x, y, z per landmark; extra
     * landmarks are ignored). Returns [SIZE] floats: `[pose | mirrored pose]`.
     */
    fun fromWorld(world: FloatArray): FloatArray {
        require(world.size >= LANDMARKS * 3) { "Need ${LANDMARKS * 3} landmark values, got ${world.size}" }
        val out = FloatArray(SIZE)
        bonesInto(world.copyOf(LANDMARKS * 3), out, 0)
        val mirrored = FloatArray(LANDMARKS * 3)
        for (k in 0 until LANDMARKS) {
            val src = SWAP[k] ?: k
            mirrored[3 * k] = -world[3 * src]
            mirrored[3 * k + 1] = world[3 * src + 1]
            mirrored[3 * k + 2] = world[3 * src + 2]
        }
        bonesInto(mirrored, out, DIM)
        return out
    }

    /**
     * Similarity of two descriptors in [-1, 1]: the mean dot product of matching bone directions,
     * maximised over [b]'s mirrored variant. 1 means the same pose (or its mirror image).
     */
    fun similarity(a: FloatArray, b: FloatArray): Float {
        require(a.size == SIZE && b.size == SIZE) { "Descriptors must have $SIZE values" }
        var same = 0f
        var mirror = 0f
        for (i in 0 until DIM) {
            same += a[i] * b[i]
            mirror += a[i] * b[DIM + i]
        }
        return max(same, mirror) / BONES.size
    }

    /** `pose_descriptor.bones`: de-yaw, then the unit vector of each bone, into [out] at [offset]. */
    private fun bonesInto(world: FloatArray, out: FloatArray, offset: Int) {
        val w = deyaw(world)
        var o = offset
        for ((a, b) in BONES) {
            val dx = w[3 * b] - w[3 * a]
            val dy = w[3 * b + 1] - w[3 * a + 1]
            val dz = w[3 * b + 2] - w[3 * a + 2]
            val n = max(sqrt(dx * dx + dy * dy + dz * dz), 1e-6f)
            out[o++] = dx / n
            out[o++] = dy / n
            out[o++] = dz / n
        }
    }

    /** `pose_descriptor._deyaw`: rotate about y (vertical) so the hip line (L − R) is +x. */
    private fun deyaw(world: FloatArray): FloatArray {
        val hx = world[3 * L_HIP] - world[3 * R_HIP]
        val hz = world[3 * L_HIP + 2] - world[3 * R_HIP + 2]
        val ang = atan2(hz, hx)
        val c = cos(ang)
        val s = sin(ang)
        val out = FloatArray(world.size)
        for (k in 0 until world.size / 3) {
            val x = world[3 * k]
            val z = world[3 * k + 2]
            out[3 * k] = c * x + s * z
            out[3 * k + 1] = world[3 * k + 1]
            out[3 * k + 2] = -s * x + c * z
        }
        return out
    }
}
