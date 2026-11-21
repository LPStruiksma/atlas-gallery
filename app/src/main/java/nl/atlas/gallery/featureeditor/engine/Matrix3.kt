package nl.atlas.gallery.featureeditor.engine

import kotlin.math.abs

/** A 2D point in pixel or normalized coordinates (y grows downwards, like Android bitmaps). */
data class Point2(val x: Double, val y: Double)

/**
 * Row-major 3x3 projective matrix acting on column vectors `(x, y, 1)`.
 *
 * Every geometry step of the editor (perspective, straighten, orientation, crop, output scale)
 * composes into one of these, so the GPU renderer (EDIT-2), the saver (EDIT-3) and tap mapping
 * all use exactly the same transform. Values are doubles for deterministic JVM maths; renderers
 * may narrow to floats when uploading.
 */
class Matrix3(values: DoubleArray) {
    private val m: DoubleArray = values.copyOf()

    init {
        require(m.size == 9) { "Matrix3 needs 9 values" }
        require(m.all { it.isFinite() }) { "Matrix3 values must be finite" }
    }

    operator fun get(row: Int, col: Int): Double = m[row * 3 + col]

    fun values(): DoubleArray = m.copyOf()

    /** `this * other`: applies [other] first, then this. */
    operator fun times(other: Matrix3): Matrix3 {
        val r = DoubleArray(9)
        for (i in 0..2) for (j in 0..2) {
            var s = 0.0
            for (k in 0..2) s += this[i, k] * other[k, j]
            r[i * 3 + j] = s
        }
        return Matrix3(r)
    }

    fun map(p: Point2): Point2 = map(p.x, p.y)

    fun map(x: Double, y: Double): Point2 {
        val w = m[6] * x + m[7] * y + m[8]
        require(abs(w) > 1e-12) { "Point maps to infinity" }
        return Point2((m[0] * x + m[1] * y + m[2]) / w, (m[3] * x + m[4] * y + m[5]) / w)
    }

    fun determinant(): Double =
        m[0] * (m[4] * m[8] - m[5] * m[7]) -
            m[1] * (m[3] * m[8] - m[5] * m[6]) +
            m[2] * (m[3] * m[7] - m[4] * m[6])

    fun inverse(): Matrix3 {
        val det = determinant()
        require(abs(det) > 1e-18) { "Matrix is not invertible" }
        val inv = DoubleArray(9)
        inv[0] = (m[4] * m[8] - m[5] * m[7]) / det
        inv[1] = (m[2] * m[7] - m[1] * m[8]) / det
        inv[2] = (m[1] * m[5] - m[2] * m[4]) / det
        inv[3] = (m[5] * m[6] - m[3] * m[8]) / det
        inv[4] = (m[0] * m[8] - m[2] * m[6]) / det
        inv[5] = (m[2] * m[3] - m[0] * m[5]) / det
        inv[6] = (m[3] * m[7] - m[4] * m[6]) / det
        inv[7] = (m[1] * m[6] - m[0] * m[7]) / det
        inv[8] = (m[0] * m[4] - m[1] * m[3]) / det
        return Matrix3(inv)
    }

    /** Divides by the bottom-right entry so equal transforms compare equal. */
    fun normalized(): Matrix3 {
        val w = m[8]
        if (abs(w) < 1e-15 || w == 1.0) return this
        return Matrix3(DoubleArray(9) { m[it] / w })
    }

    fun isIdentity(tolerance: Double = 1e-12): Boolean {
        val n = normalized()
        return (0..8).all { abs(n.m[it] - IDENTITY.m[it]) <= tolerance }
    }

    override fun equals(other: Any?): Boolean = other is Matrix3 && m.contentEquals(other.m)

    override fun hashCode(): Int = m.contentHashCode()

    override fun toString(): String = m.joinToString(prefix = "Matrix3[", postfix = "]")

    companion object {
        val IDENTITY = Matrix3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))

        fun translate(dx: Double, dy: Double) =
            Matrix3(doubleArrayOf(1.0, 0.0, dx, 0.0, 1.0, dy, 0.0, 0.0, 1.0))

        fun scale(sx: Double, sy: Double) =
            Matrix3(doubleArrayOf(sx, 0.0, 0.0, 0.0, sy, 0.0, 0.0, 0.0, 1.0))

        /** Rotation by [radians] about the origin; positive is clockwise on screen (y down). */
        fun rotate(radians: Double): Matrix3 {
            val c = kotlin.math.cos(radians)
            val s = kotlin.math.sin(radians)
            return Matrix3(doubleArrayOf(c, -s, 0.0, s, c, 0.0, 0.0, 0.0, 1.0))
        }

        /**
         * The projective transform that sends `from[i]` to `to[i]` for four point pairs.
         * Solved as the usual 8x8 linear system with h33 = 1 (partial-pivot Gauss).
         */
        fun homography(from: List<Point2>, to: List<Point2>): Matrix3 {
            require(from.size == 4 && to.size == 4) { "Homography needs four point pairs" }
            val a = Array(8) { DoubleArray(9) }
            for (i in 0..3) {
                val (x, y) = from[i]
                val (u, v) = to[i]
                a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
                a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
            }
            for (col in 0..7) {
                var pivot = col
                for (r in col + 1..7) if (abs(a[r][col]) > abs(a[pivot][col])) pivot = r
                require(abs(a[pivot][col]) > 1e-12) { "Degenerate quadrilateral" }
                val tmp = a[col]; a[col] = a[pivot]; a[pivot] = tmp
                for (r in 0..7) {
                    if (r == col) continue
                    val f = a[r][col] / a[col][col]
                    if (f == 0.0) continue
                    for (c in col..8) a[r][c] -= f * a[col][c]
                }
            }
            val h = DoubleArray(9) { if (it < 8) a[it][8] / a[it][it] else 1.0 }
            return Matrix3(h)
        }
    }
}
