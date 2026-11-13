package nl.atlas.gallery.featureface.onnx

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.Executors
import java.util.Random
import nl.atlas.gallery.featureface.AndroidBitmapFaceFrame
import nl.atlas.gallery.featureface.FaceDiscoveryContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the bundled YuNet model on ONNX Runtime in the emulator. Inputs are generated
 * (flat colour and seeded noise), never real photos. Positive detections are covered by
 * the JVM decoder tests on synthetic tensors.
 */
@RunWith(AndroidJUnit4::class)
class OnnxFaceDetectorTest {
    private val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets

    private fun flat(width: Int, height: Int, color: Int) =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    @Test
    fun bundledModelLoadsFromAssetsAndFindsNoFaceInAFlatFrame() {
        OnnxFaceDetector.fromAssets(assets).use { detector ->
            assertEquals(YuNetModel.DETECTOR_VERSION, detector.detectorVersion)
            val frame = flat(960, 720, Color.GRAY)
            assertTrue(detector.detect(frame).isEmpty())
            assertTrue(detector.detectFaces(flat(540, 960, Color.WHITE)).isEmpty())
            frame.recycle()
        }
    }

    @Test
    fun noiseFrameStaysWithinTheFrameAndTheFaceCap() {
        val random = Random(402)
        val frame = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(800 * 600) { Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }
        frame.setPixels(pixels, 0, 800, 0, 0, 800, 600)
        OnnxFaceDetector.fromAssets(assets, scoreThreshold = 0.3f).use { detector ->
            val boxes = detector.detect(frame)
            assertTrue(boxes.size <= FaceDiscoveryContract.MAX_FACES_PER_IMAGE)
            boxes.forEach { assertTrue(it.left >= 0 && it.top >= 0 && it.right <= 800 && it.bottom <= 600 && it.left < it.right && it.top < it.bottom) }
        }
        frame.recycle()
    }

    @Test
    fun asyncLocatorRunsOffTheCallerAndReleasesItsSlot() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            OnnxFaceDetector.fromAssets(assets).use { detector ->
                val locator = detector.asyncLocator(executor)
                val frame = AndroidBitmapFaceFrame(flat(640, 480, Color.DKGRAY))
                frame.use {
                    val reservation = checkNotNull(locator.tryReserve())
                    reservation.use { assertTrue(it.locate(frame).isEmpty()) }
                }
                assertTrue(locator.canStart())
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun tamperedModelBytesAreRefusedBeforeTheRuntime() {
        val bytes = assets.open(YuNetModel.ASSET_PATH).use { it.readBytes() }
        bytes[100] = (bytes[100].toInt() xor 0x55).toByte()
        try {
            OnnxFaceDetector.fromModelBytes(bytes)
            fail("tampered model must not load")
        } catch (_: FaceModelIntegrityFailure) {
        }
    }
}
