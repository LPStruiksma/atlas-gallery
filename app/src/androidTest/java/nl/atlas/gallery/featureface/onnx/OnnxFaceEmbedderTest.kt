package nl.atlas.gallery.featureface.onnx

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the bundled YuNet and SFace models together on ONNX Runtime in the emulator, on
 * [SyntheticFace] frames drawn in code (never real photos). Covers: a positive YuNet
 * detection with sane landmarks, alignment from those landmarks, unit-length versioned
 * embeddings, and stability of the embedding when the same drawn face moves and scales.
 * It does NOT measure identity accuracy on real faces.
 */
@RunWith(AndroidJUnit4::class)
class OnnxFaceEmbedderTest {
    private val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets

    /** Below the 0.9 default on purpose: the drawn face scores ~0.91, and Android scaling differs slightly. */
    private val threshold = 0.6f

    private fun detectOne(detector: OnnxFaceDetector, spec: SyntheticFace.Spec): Pair<Bitmap, YuNetFace> {
        val frame = SyntheticFace.bitmap(spec)
        val faces = detector.detectFaces(frame)
        assertTrue("no face found in synthetic frame $spec", faces.isNotEmpty())
        return frame to faces.first()
    }

    @Test
    fun yunetFindsTheSyntheticFaceWithOrderedLandmarks() {
        OnnxFaceDetector.fromAssets(assets, scoreThreshold = threshold).use { detector ->
            val spec = SyntheticFace.Spec()
            val (frame, face) = detectOne(detector, spec)
            assertTrue(face.score >= threshold)
            assertTrue(face.left < spec.cx && spec.cx < face.right && face.top < spec.cy && spec.cy < face.bottom)
            val lm = face.landmarks
            // Right eye (image left), left eye, nose, mouth corners: left-to-right and top-to-bottom order.
            assertTrue(lm[0] < lm[4] && lm[4] < lm[2])
            assertTrue(lm[6] < lm[8])
            assertTrue(maxOf(lm[1], lm[3]) < lm[5] && lm[5] < minOf(lm[7], lm[9]))
            for (i in 0 until 5) {
                val dx = (lm[2 * i] - spec.cx) / spec.faceA
                val dy = (lm[2 * i + 1] - spec.cy) / spec.faceB
                assertTrue("landmark $i outside the drawn face", dx * dx + dy * dy < 1f)
            }
            frame.recycle()
        }
    }

    @Test
    fun embeddingIsUnitLengthVersionedAndStableAcrossPlacement() {
        OnnxFaceDetector.fromAssets(assets, scoreThreshold = threshold).use { detector ->
            OnnxFaceEmbedder.fromAssets(assets).use { embedder ->
                assertEquals(SFaceModel.EMBEDDER_VERSION, embedder.embedderVersion)
                val specs = listOf(
                    SyntheticFace.Spec(),
                    SyntheticFace.Spec(800, 600, 0.3f, 0.55f, 0.8f),
                    SyntheticFace.Spec(640, 480, 0.7f, 0.4f, 0.6f),
                    SyntheticFace.Spec(540, 960, 0.5f, 0.5f, 1f)
                )
                val embeddings = specs.map { spec ->
                    val (frame, face) = detectOne(detector, spec)
                    val e = embedder.embed(frame, face)
                    frame.recycle()
                    e
                }
                embeddings.forEach { e ->
                    assertEquals(SFaceModel.EMBEDDER_VERSION, e.embedderVersion)
                    val v = e.vector
                    assertEquals(SFaceModel.DIMENSION, v.size)
                    assertEquals(1.0, sqrt(v.sumOf { it.toDouble() * it }), 1e-4)
                }
                // Desktop ORT gives 0.90..0.96 for these placements; OpenCV's same-identity cut-off is 0.363.
                embeddings.drop(1).forEach { e ->
                    val cos = embeddings[0].cosine(e)
                    assertTrue("placement changed the embedding too much: $cos", cos >= 0.7f)
                }
                // Same frame, same face: deterministic.
                val (frame, face) = detectOne(detector, specs[0])
                assertEquals(1f, embedder.embed(frame, face).cosine(embedder.embed(frame, face)), 1e-5f)
                frame.recycle()
            }
        }
    }

    @Test
    fun faceCutOffAtTheEdgeStillEmbedsWithBlackPadding() {
        OnnxFaceEmbedder.fromAssets(assets).use { embedder ->
            val frame = Bitmap.createBitmap(200, 150, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.LTGRAY) }
            val edge = YuNetFace(140f, 40f, 70f, 90f, 0.95f,
                floatArrayOf(150f, 60f, 190f, 55f, 172f, 85f, 155f, 110f, 188f, 106f))
            val e = embedder.embed(frame, edge)
            assertEquals(1.0, sqrt(e.vector.sumOf { it.toDouble() * it }), 1e-4)
            val results = embedder.embedAll(frame, listOf(edge, YuNetFace(10f, 10f, 5f, 5f, 0.95f, FloatArray(10) { 12f })))
            assertEquals(1f, results[0]!!.cosine(e), 1e-5f)
            assertNull(results[1])
            frame.recycle()
        }
    }

    @Test
    fun tamperedOrWrongModelBytesAreRefusedBeforeTheRuntime() {
        val bytes = assets.open(SFaceModel.ASSET_PATH).use { it.readBytes() }
        bytes[1000] = (bytes[1000].toInt() xor 0x55).toByte()
        for (candidate in listOf(bytes, assets.open(YuNetModel.ASSET_PATH).use { it.readBytes() })) {
            try {
                OnnxFaceEmbedder.fromModelBytes(candidate)
                fail("wrong model must not load")
            } catch (_: FaceModelIntegrityFailure) {
            }
        }
    }

    @Test
    fun closedEmbedderRefusesWork() {
        val embedder = OnnxFaceEmbedder.fromAssets(assets)
        embedder.close()
        embedder.close()
        val frame = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        try {
            embedder.embed(frame, YuNetFace(0f, 0f, 5f, 5f, 1f, floatArrayOf(2f, 3f, 7f, 3f, 5f, 5f, 3f, 7f, 7f, 7f)))
            fail("closed embedder must refuse")
        } catch (_: IllegalStateException) {
        }
        frame.recycle()
    }
}
