package nl.atlas.gallery.featureeditor.render

import nl.atlas.gallery.featureeditor.engine.AdjustParam
import nl.atlas.gallery.featureeditor.engine.EditOperation
import nl.atlas.gallery.featureeditor.engine.EditRecipe
import nl.atlas.gallery.featureeditor.engine.FlipAxis
import nl.atlas.gallery.featureeditor.engine.NormRect
import nl.atlas.gallery.featureeditor.engine.Quad
import kotlin.math.abs
import kotlin.math.max

/** Synthetic pictures and recipes for renderer tests (no real photos). */
internal object RenderFixtures {
    /** Opaque premultiplied RGBA: gradients, a checkerboard and saturated colour bars. */
    fun picture(w: Int, h: Int): ByteArray {
        val d = ByteArray(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) {
            val i = (y * w + x) * 4
            val checker = ((x / 7) + (y / 7)) % 2 == 0
            val bar = (x * 6 / w)
            val r: Int; val g: Int; val b: Int
            if (y < h / 3) {
                r = x * 255 / max(1, w - 1); g = y * 255 / max(1, h - 1); b = 255 - r
            } else if (y < 2 * h / 3) {
                val v = if (checker) 230 else 25
                r = v; g = v; b = v
            } else {
                r = if (bar % 3 == 0) 255 else 20; g = if (bar % 3 == 1) 255 else 30; b = if (bar % 3 == 2) 255 else 40
            }
            d[i] = r.toByte(); d[i + 1] = g.toByte(); d[i + 2] = b.toByte(); d[i + 3] = 255.toByte()
        }
        return d
    }

    fun crop(full: ByteArray, w: Int, rect: IntRect): ByteArray {
        val out = ByteArray(rect.width * rect.height * 4)
        for (y in 0 until rect.height) {
            System.arraycopy(full, ((rect.top + y) * w + rect.left) * 4, out, y * rect.width * 4, rect.width * 4)
        }
        return out
    }

    fun maxDiff(a: ByteArray, b: ByteArray): Int {
        require(a.size == b.size)
        var m = 0
        for (i in a.indices) m = max(m, abs((a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)))
        return m
    }

    /** A recipe exercising geometry, every colour control and a filter. */
    fun everything(w: Int, h: Int): EditRecipe = EditRecipe(
        w, h,
        listOf(
            EditOperation.Rotate90("g1", 1),
            EditOperation.Flip("g2", FlipAxis.HORIZONTAL),
            EditOperation.Straighten("g3", 6.5),
            EditOperation.Perspective("g4", Quad.fromKeystone(0.3, -0.15)),
            EditOperation.Crop("g5", NormRect(0.08, 0.1, 0.92, 0.85)),
        ) + AdjustParam.entries.mapIndexed { i, p ->
            EditOperation.Adjust("a$i", p, if (p == AdjustParam.SHARPNESS) 0.6 else if (i % 2 == 0) 0.35 else -0.3)
        } + EditOperation.Filter("f1", "synthetic-warm", null, 0.7),
    )

    /** A smooth synthetic "warm" filter: lifts reds, crushes blues, adds a little S-curve. */
    val warmLut: Lut3D = Lut3D.of(17) { r, g, b, out ->
        out[0] = (r * 1.1f + 0.03f).coerceIn(0f, 1f)
        out[1] = g * g * (3f - 2f * g) * 0.9f + g * 0.1f
        out[2] = b * 0.85f
    }
}
