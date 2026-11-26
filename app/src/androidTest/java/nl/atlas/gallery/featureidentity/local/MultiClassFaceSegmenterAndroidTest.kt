package nl.atlas.gallery.featureidentity.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * IDS-18 (#519): the bundled MediaPipe multiclass ONNX loads from the app's assets and
 * runs on ORT Android. Synthetic input only. The model's parity against LiteRT is in
 * `assets/models/mediapipe_multiclass/SOURCE.md`.
 */
@RunWith(AndroidJUnit4::class)
class MultiClassFaceSegmenterAndroidTest {
    private val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
    private val size = MediaPipeMaskMath.SIZE

    @Test
    fun bundledModelLoadsAndReturnsABinaryMaskOfTheCropSize() {
        val segmenter = MultiClassFaceSegmenter.fromAssets(assets, intraOpThreads = 1)
        assertNotNull("bundled ONNX must load without any download", segmenter)
        segmenter!!.use { net ->
            // Flat grey: no face, so (almost) no face-skin pixels.
            val grey = IntArray(size * size) { 0xFF808080.toInt() }
            val flat = net.faceSkin(grey, size)
            assertEquals(size * size, flat.size)
            assertTrue(flat.all { it == 0f || it == 1f })
            assertTrue("flat grey should not read as face skin", flat.count { it == 1f } < size * size / 20)

            // A skin-toned oval on a dark background must give a finite mask of the same
            // size; the head variant can only add pixels (hair), never remove face skin.
            val oval = IntArray(size * size) { i ->
                val x = i % size - 128.0
                val y = i / size - 140.0
                if ((x * x) / (60.0 * 60.0) + (y * y) / (80.0 * 80.0) <= 1.0) 0xFFE0AC90.toInt()
                else 0xFF202020.toInt()
            }
            val face = net.faceSkin(oval, size)
            val head = net.headSkinAndHair(oval, size)
            assertEquals(size * size, face.size)
            assertTrue(face.indices.all { face[it] <= head[it] })
        }
    }
}
