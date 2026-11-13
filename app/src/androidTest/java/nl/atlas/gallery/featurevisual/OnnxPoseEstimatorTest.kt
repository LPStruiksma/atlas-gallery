package nl.atlas.gallery.featurevisual

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.atlas.gallery.featurevisual.pose.OnnxPoseEstimator
import nl.atlas.gallery.featurevisual.pose.PoseDescriptor
import nl.atlas.gallery.featurevisual.pose.PoseModel
import nl.atlas.gallery.featurevisual.pose.PoseModelIntegrityFailure
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the bundled person detector + BlazePose on ONNX Runtime in the emulator, on figure images
 * drawn from integer formulas (never real photos), and compares each descriptor with the host
 * ONNX Runtime + OpenCV result that `pose_golden.py --android` wrote for the same image ([PoseGolden]).
 */
@RunWith(AndroidJUnit4::class)
class OnnxPoseEstimatorTest {
    private fun figure(name: String): Bitmap {
        val px = figureArgb(name)
        return Bitmap.createBitmap(px, W, H, Bitmap.Config.ARGB_8888)
    }

    @Test
    fun bundledModelsMatchTheirPins() {
        assertEquals(PoseModel.DETECTOR_SHA256, PoseGolden.DETECTOR_SHA256)
        assertEquals(PoseModel.LANDMARK_SHA256, PoseGolden.LANDMARK_SHA256)
        val det = context.assets.open(PoseModel.DETECTOR_ASSET).use { it.readBytes() }
        val pose = context.assets.open(PoseModel.LANDMARK_ASSET).use { it.readBytes() }
        assertEquals(PoseModel.DETECTOR_SHA256, PoseModel.sha256Hex(det))
        assertEquals(PoseModel.LANDMARK_SHA256, PoseModel.sha256Hex(pose))
        context.assets.open(PoseModel.LICENSE_ASSET_PATH).use { assertTrue(it.readBytes().size > 10_000) }
    }

    @Test
    fun descriptorsMatchHostGolden() {
        for (g in PoseGolden.all) {
            val bmp = figure(g.name)
            val d = estimator.describe(bmp)
            bmp.recycle()
            if (g.descriptor == null) {
                assertNull("${g.name}: host found no body", d)
                continue
            }
            assertNotNull("${g.name}: host found a body (detector ${g.detScore})", d)
            assertEquals(PoseDescriptor.SIZE, d!!.size)
            val sim = PoseDescriptor.similarity(d, g.descriptor)
            assertTrue("${g.name}: similarity to host descriptor $sim < 0.95", sim >= 0.95f)
        }
    }

    @Test
    fun mirroredFigureMatchesAndUpsideDownDoesNot() {
        val standing = estimator.describeArgb(figureArgb("standing"), W, H)!!
        val mirrored = estimator.describeArgb(figureArgb("mirrored"), W, H)!!
        val upsideDown = estimator.describeArgb(figureArgb("upside_down"), W, H)!!
        assertTrue(PoseDescriptor.similarity(standing, mirrored) > 0.9f)
        assertTrue(PoseDescriptor.similarity(standing, upsideDown) < 0f)
    }

    @Test
    fun blankFrameHasNoBody() {
        assertNull(estimator.describeArgb(figureArgb("blank"), W, H))
        assertNull(estimator.describeArgb(IntArray(16) { 0xFF000000.toInt() }, 4, 4))
    }

    @Test
    fun tamperedModelFailsClosed() {
        val det = context.assets.open(PoseModel.DETECTOR_ASSET).use { it.readBytes() }
        det[det.size / 2] = (det[det.size / 2] + 1).toByte()
        try {
            PoseModel.requirePinned(det, PoseModel.DETECTOR_SHA256, PoseModel.DETECTOR_SIZE_BYTES, "person detector")
            throw AssertionError("expected integrity failure")
        } catch (_: PoseModelIntegrityFailure) {}
    }

    @Test(expected = IllegalStateException::class)
    fun closedEstimatorRefusesWork() {
        val e = OnnxPoseEstimator.open(context)
        e.close()
        e.close()
        e.describeArgb(IntArray(4), 2, 2)
    }

    companion object {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private lateinit var estimator: OnnxPoseEstimator
        const val W = 480
        const val H = 640

        @BeforeClass @JvmStatic
        fun openOnce() { estimator = OnnxPoseEstimator.open(context) }

        @AfterClass @JvmStatic
        fun closeOnce() { estimator.close() }

        private const val BG = 0xF0F0F0
        private const val SKIN = 0xDCAA8C
        private const val SHIRT = 0x2850A0
        private const val PANTS = 0x32323C
        private const val DARK = 0x1E1E1E
        private const val HAIR = 0x3C2814

        private class Shape(val kind: Char, val a: Int, val b: Int, val c: Int, val d: Int, val r: Int, val rgb: Int)

        /** Same shapes, order and integer tests as `pose_golden.SHAPES` / `inside`. */
        private val SHAPES = listOf(
            Shape('c', 226, 336, 218, 396, 10, PANTS), Shape('c', 218, 396, 214, 452, 9, PANTS),
            Shape('c', 254, 336, 262, 396, 10, PANTS), Shape('c', 262, 396, 266, 452, 9, PANTS),
            Shape('r', 213, 248, 267, 340, 0, SHIRT),
            Shape('c', 213, 254, 184, 296, 6, SHIRT), Shape('c', 184, 296, 176, 340, 5, SKIN),
            Shape('c', 267, 254, 296, 296, 6, SHIRT), Shape('c', 296, 296, 304, 340, 5, SKIN),
            Shape('c', 240, 240, 240, 250, 6, SKIN),
            Shape('e', 240, 218, 19, 24, 0, SKIN),
            Shape('e', 233, 213, 2, 2, 0, DARK), Shape('e', 247, 213, 2, 2, 0, DARK),
            Shape('e', 240, 200, 21, 11, 0, HAIR),
        )

        private fun inside(s: Shape, x: Int, y: Int): Boolean {
            val a = s.a.toLong(); val b = s.b.toLong(); val c = s.c.toLong(); val d = s.d.toLong(); val r = s.r.toLong()
            val px = x.toLong(); val py = y.toLong()
            return when (s.kind) {
                'r' -> px in a..c && py in b..d
                'e' -> (px - a) * (px - a) * d * d + (py - b) * (py - b) * c * c <= c * c * d * d
                else -> {
                    val vx = c - a; val vy = d - b; val wx = px - a; val wy = py - b
                    val ll = vx * vx + vy * vy; val t = wx * vx + wy * vy
                    when {
                        t <= 0 -> wx * wx + wy * wy <= r * r
                        t >= ll -> (px - c) * (px - c) + (py - d) * (py - d) <= r * r
                        else -> { val cr = wx * vy - wy * vx; cr * cr <= r * r * ll }
                    }
                }
            }
        }

        /** `pose_golden.figure(name)` as ARGB. */
        fun figureArgb(name: String): IntArray {
            val upright = IntArray(W * H) { i ->
                val x = i % W; val y = i / W
                var rgb = BG
                if (name != "blank") {
                    for (s in SHAPES.asReversed()) if (inside(s, x, y)) { rgb = s.rgb; break }
                }
                (0xFF shl 24) or rgb
            }
            return when (name) {
                "mirrored" -> IntArray(W * H) { i -> upright[(i / W) * W + (W - 1 - i % W)] }
                "upside_down" -> IntArray(W * H) { i -> upright[W * H - 1 - i] }
                "standing", "blank" -> upright
                else -> throw IllegalArgumentException(name)
            }
        }
    }
}
