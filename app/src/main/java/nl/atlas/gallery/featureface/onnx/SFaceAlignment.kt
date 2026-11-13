package nl.atlas.gallery.featureface.onnx

import kotlin.math.floor

/**
 * Pure-Kotlin port of OpenCV `FaceRecognizerSF::alignCrop` (4.x): a 2D similarity
 * transform (rotation, uniform scale, translation; no reflection) that maps YuNet's five
 * landmarks onto the fixed 112x112 ArcFace template, then a bilinear warp.
 *
 * Landmarks must be in the coordinates of the frame being warped, i.e. the output of
 * [OnnxFaceDetector.detectFaces] (which already undoes YuNet's letterbox, see "Padding").
 *
 * Padding, so results match OpenCV:
 * - YuNet's letterbox ([YuNetLetterbox]) puts the scaled frame at the top-left of the
 *   640x640 canvas and fills the right/bottom remainder with zeros. There is no offset, so
 *   [YuNetLetterbox.faceToFrame] maps landmarks back by scale alone; alignment then runs on
 *   the original frame, never on the padded canvas.
 * - Any part of the 112x112 crop that falls outside the frame (faces cut off at an edge,
 *   landmarks YuNet placed in the padding) is black (0, 0, 0), and an edge pixel blends its
 *   in-frame neighbours with that black. This is OpenCV `warpAffine`'s default
 *   `BORDER_CONSTANT` with value 0. Alpha is ignored.
 * - OpenCV interpolates in fixed point (1/32 pixel); this port uses doubles and rounds to
 *   8-bit like OpenCV's saturate_cast. Crops differ by at most a few levels on sharp edges
 *   (mean well under one level); the JVM golden test pins this against OpenCV 4.10 output.
 */
object SFaceAlignment {
    /** ArcFace 112x112 template (OpenCV `getSimilarityTransformMatrix`). */
    val TEMPLATE: FloatArray = floatArrayOf(
        38.2946f, 51.6963f, 73.5318f, 51.5014f, 56.0252f, 71.7366f, 41.5493f, 92.3655f, 70.7299f, 92.2041f
    )
    /** OpenCV hard-codes the template mean; kept verbatim. */
    private const val TEMPLATE_MEAN_X = 56.0262
    private const val TEMPLATE_MEAN_Y = 71.9008

    /**
     * The 2x3 forward matrix `[a, -b, tx, b, a, ty]` from frame to crop pixels. Least-squares
     * similarity in closed form; equal to OpenCV's Umeyama/SVD result for every non-degenerate
     * input (rank 2 covariance), which the tests check against OpenCV's numbers.
     * Throws [IllegalArgumentException] for non-finite or collapsed landmarks.
     */
    fun similarityTransform(landmarks: FloatArray): DoubleArray {
        require(landmarks.size == 10) { "SFace alignment needs five landmarks" }
        require(landmarks.all { it.isFinite() }) { "Landmarks must be finite" }
        var mx = 0.0
        var my = 0.0
        for (i in 0 until 5) { mx += landmarks[2 * i]; my += landmarks[2 * i + 1] }
        mx /= 5; my /= 5
        var norm = 0.0
        var dot = 0.0
        var cross = 0.0
        for (i in 0 until 5) {
            val sx = landmarks[2 * i] - mx
            val sy = landmarks[2 * i + 1] - my
            val dx = TEMPLATE[2 * i] - TEMPLATE_MEAN_X
            val dy = TEMPLATE[2 * i + 1] - TEMPLATE_MEAN_Y
            norm += sx * sx + sy * sy
            dot += sx * dx + sy * dy
            cross += sx * dy - sy * dx
        }
        require(norm > 1e-6) { "Landmarks collapse to a point" }
        val a = dot / norm
        val b = cross / norm
        require(a * a + b * b > 1e-12) { "Landmarks give no usable scale" }
        return doubleArrayOf(
            a, -b, TEMPLATE_MEAN_X - (a * mx - b * my),
            b, a, TEMPLATE_MEAN_Y - (b * mx + a * my)
        )
    }

    /** Inverse of a forward similarity matrix: crop pixel to frame pixel. */
    fun invert(m: DoubleArray): DoubleArray {
        require(m.size == 6)
        val det = m[0] * m[4] - m[1] * m[3]
        require(det != 0.0 && det.isFinite()) { "Singular alignment matrix" }
        val i00 = m[4] / det
        val i01 = -m[1] / det
        val i10 = -m[3] / det
        val i11 = m[0] / det
        return doubleArrayOf(i00, i01, -(i00 * m[2] + i01 * m[5]), i10, i11, -(i10 * m[2] + i11 * m[5]))
    }

    /** Frame pixels the warp can read, clipped to the frame; null if the crop lies fully outside. */
    fun sourceWindow(m: DoubleArray, frameWidth: Int, frameHeight: Int): PixelWindow? {
        require(frameWidth > 0 && frameHeight > 0)
        val inv = invert(m)
        val last = (SFaceModel.INPUT_SIZE - 1).toDouble()
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for ((x, y) in listOf(0.0 to 0.0, last to 0.0, 0.0 to last, last to last)) {
            val sx = inv[0] * x + inv[1] * y + inv[2]
            val sy = inv[3] * x + inv[4] * y + inv[5]
            minX = minOf(minX, sx); maxX = maxOf(maxX, sx)
            minY = minOf(minY, sy); maxY = maxOf(maxY, sy)
        }
        val left = floor(minX).coerceIn(0.0, frameWidth.toDouble()).toInt()
        val top = floor(minY).coerceIn(0.0, frameHeight.toDouble()).toInt()
        val right = (floor(maxX) + 2).coerceIn(0.0, frameWidth.toDouble()).toInt()
        val bottom = (floor(maxY) + 2).coerceIn(0.0, frameHeight.toDouble()).toInt()
        if (left >= right || top >= bottom) return null
        return PixelWindow(left, top, right - left, bottom - top)
    }

    /**
     * Warps ARGB_8888 pixels of [window] (row major, frame coordinates) into the planar
     * `[1, 3, 112, 112]` RGB tensor SFace expects. Pixels outside [window] read as black;
     * [window] must cover [sourceWindow] (or be null for a crop fully outside the frame).
     */
    fun warpToRgbPlanar(
        windowArgb: IntArray,
        window: PixelWindow?,
        m: DoubleArray,
        into: FloatArray = FloatArray(3 * SFaceModel.INPUT_SIZE * SFaceModel.INPUT_SIZE)
    ): FloatArray {
        val size = SFaceModel.INPUT_SIZE
        val plane = size * size
        require(into.size == 3 * plane)
        require(windowArgb.size == (window?.let { it.width * it.height } ?: 0)) { "pixel count does not match the window" }
        val inv = invert(m)
        val rgb = DoubleArray(3)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val sx = inv[0] * x + inv[1] * y + inv[2]
                val sy = inv[3] * x + inv[4] * y + inv[5]
                rgb.fill(0.0)
                if (window != null && sx.isFinite() && sy.isFinite()) {
                    val x0 = floor(sx).toInt()
                    val y0 = floor(sy).toInt()
                    val fx = sx - x0
                    val fy = sy - y0
                    accumulate(windowArgb, window, x0, y0, (1 - fx) * (1 - fy), rgb)
                    accumulate(windowArgb, window, x0 + 1, y0, fx * (1 - fy), rgb)
                    accumulate(windowArgb, window, x0, y0 + 1, (1 - fx) * fy, rgb)
                    accumulate(windowArgb, window, x0 + 1, y0 + 1, fx * fy, rgb)
                }
                val i = y * size + x
                into[i] = round8(rgb[0])
                into[plane + i] = round8(rgb[1])
                into[2 * plane + i] = round8(rgb[2])
            }
        }
        return into
    }

    private fun accumulate(argb: IntArray, w: PixelWindow, fx: Int, fy: Int, weight: Double, rgb: DoubleArray) {
        if (weight == 0.0) return
        val x = fx - w.left
        val y = fy - w.top
        if (x < 0 || y < 0 || x >= w.width || y >= w.height) return
        val p = argb[y * w.width + x]
        rgb[0] += weight * ((p shr 16) and 0xFF)
        rgb[1] += weight * ((p shr 8) and 0xFF)
        rgb[2] += weight * (p and 0xFF)
    }

    private fun round8(v: Double): Float = floor(v + 0.5).coerceIn(0.0, 255.0).toFloat()
}

/** A rectangle of frame pixels: [left], [top] inclusive, [width] x [height]. */
data class PixelWindow(val left: Int, val top: Int, val width: Int, val height: Int) {
    init { require(left >= 0 && top >= 0 && width > 0 && height > 0) }
}
