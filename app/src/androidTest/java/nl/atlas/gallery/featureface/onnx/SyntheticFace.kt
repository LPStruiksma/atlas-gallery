package nl.atlas.gallery.featureface.onnx

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * A cartoon face drawn from ellipses in code: no photo, no person, no third-party asset
 * (written for this repository, same licence). YuNet 2023mar scores it about 0.91 in
 * desktop ONNX Runtime 1.29. It proves the detector and embedder pipeline runs end to
 * end; it cannot prove that real faces of different people are told apart.
 */
object SyntheticFace {
    data class Spec(
        val width: Int = 480,
        val height: Int = 480,
        val centerX: Float = 0.5f,
        val centerY: Float = 0.5f,
        val size: Float = 1f
    ) {
        val faceA: Float get() = min(width, height) * 0.22f * size
        val faceB: Float get() = min(width, height) * 0.30f * size
        val cx: Float get() = width * centerX
        val cy: Float get() = height * centerY
    }

    fun bitmap(spec: Spec): Bitmap {
        val pixels = argb(spec)
        return Bitmap.createBitmap(spec.width, spec.height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, spec.width, 0, 0, spec.width, spec.height)
        }
    }

    fun argb(spec: Spec): IntArray {
        val a = spec.faceA
        val b = spec.faceB
        val cx = spec.cx
        val cy = spec.cy
        val ex = a * 0.42f
        val ey = cy - b * 0.12f
        val blobs = ArrayList<Blob>()
        for (s in intArrayOf(-1, 1)) {
            blobs += Blob(cx + s * ex, ey - b * 0.17f, a * 0.28f, b * 0.04f, floatArrayOf(60f, 40f, 30f), 0.5f)
            blobs += Blob(cx + s * ex, ey, a * 0.22f, b * 0.075f, floatArrayOf(245f, 245f, 240f), 0.3f)
            blobs += Blob(cx + s * ex, ey, a * 0.09f, b * 0.07f, floatArrayOf(50f, 35f, 25f), 0.4f)
        }
        blobs += Blob(cx, cy + b * 0.12f, a * 0.12f, b * 0.08f, floatArrayOf(190f, 130f, 105f), 1f)
        blobs += Blob(cx, cy + b * 0.42f, a * 0.32f, b * 0.06f, floatArrayOf(160f, 70f, 70f), 0.5f)
        val out = IntArray(spec.width * spec.height)
        val c = FloatArray(3)
        for (y in 0 until spec.height) for (x in 0 until spec.width) {
            val dx = (x - cx) / a
            val dy = (y - cy) / b
            val d = dx * dx + dy * dy
            if (d <= 1f) {
                val shade = (1.05f - 0.35f * d).coerceIn(0.5f, 1.1f)
                c[0] = 224f * shade; c[1] = 172f * shade; c[2] = 140f * shade
            } else {
                c[0] = 70f; c[1] = 90f; c[2] = 120f
            }
            if (d <= 1.15f && y < cy - b * 0.55f) { c[0] = 40f; c[1] = 28f; c[2] = 20f }
            for (blob in blobs) blob.paint(x.toFloat(), y.toFloat(), c)
            val r = c[0].coerceIn(0f, 255f).toInt()
            val g = c[1].coerceIn(0f, 255f).toInt()
            val bl = c[2].coerceIn(0f, 255f).toInt()
            out[y * spec.width + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
        }
        return out
    }

    private class Blob(val x: Float, val y: Float, val rx: Float, val ry: Float, val rgb: FloatArray, val soft: Float) {
        fun paint(px: Float, py: Float, c: FloatArray) {
            val e = ((px - x) / rx).pow(2) + ((py - y) / ry).pow(2)
            val w = max(0f, 1f - e).coerceAtMost(1f).pow(soft)
            if (w <= 0f) return
            for (k in 0 until 3) c[k] = c[k] * (1 - w) + rgb[k] * w
        }
    }
}
