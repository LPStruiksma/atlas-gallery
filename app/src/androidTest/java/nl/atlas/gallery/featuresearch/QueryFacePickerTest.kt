package nl.atlas.gallery.featuresearch

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import nl.atlas.gallery.featureface.AndroidBitmapFaceFrame
import nl.atlas.gallery.featureface.FaceEmbeddedDetection
import nl.atlas.gallery.featureface.FacePixelBounds
import nl.atlas.gallery.featureface.FaceFrameReader
import nl.atlas.gallery.featureface.FaceMediaKey
import nl.atlas.gallery.featureface.embed.FaceEmbeddingPipeline
import nl.atlas.gallery.featureface.onnx.OnnxFaceDetector
import nl.atlas.gallery.featureface.onnx.OnnxFaceEmbedder
import nl.atlas.gallery.featureface.onnx.SFaceModel
import nl.atlas.gallery.featureface.onnx.SyntheticFace
import nl.atlas.gallery.featuresearch.face.QueryFace
import nl.atlas.gallery.featuresearch.face.QueryFaceCapture
import nl.atlas.gallery.featuresearch.face.QueryFaceCaptureState
import nl.atlas.gallery.featuresearch.face.QueryFacePickerScreen
import nl.atlas.gallery.featuresearch.face.QueryFaceSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * API35: YuNet + SFace on a face DRAWN IN CODE (#413 SyntheticFace).
 * The reader stands in for a currently authorized MediaStore URI; no personal
 * media, identity evidence, network, disk cache or index store is used.
 */
@RunWith(AndroidJUnit4::class)
class QueryFacePickerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun syntheticFaceProducesPreviewAndSelectableQuery() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        OnnxFaceDetector.fromAssets(assets, scoreThreshold = 0.6f).use { detector ->
            OnnxFaceEmbedder.fromAssets(assets).use { embedder ->
                // One shared reservation pipeline; never an independent query model path.
                val pipeline = FaceEmbeddingPipeline.forOnnx(detector, embedder)
                val source = QueryFaceSource(FaceMediaKey("synthetic:query", "r1"), "grant-1")
                val capture = QueryFaceCapture(
                    FaceFrameReader { lease, edge ->
                        assertEquals(source.media, lease.media)
                        assertEquals(960, edge)
                        AndroidBitmapFaceFrame(SyntheticFace.bitmap(SyntheticFace.Spec()))
                    }, pipeline, { it == source })
                runBlocking { capture.capture(source) }
                val ready = capture.state.value as QueryFaceCaptureState.Ready
                assertTrue(ready.faces.isNotEmpty())
                assertNotNull(ready.faces.first().preview)
                val found = capture.choose(0)!!
                assertEquals(SFaceModel.EMBEDDER_VERSION, found.modelVersion)
                assertEquals(SFaceModel.DIMENSION, found.embedding.size)

                capture.clear()
                var chosen: QueryFace? = null
                compose.setContent {
                    QueryFacePickerScreen(source, capture, onFaceChosen = { chosen = it },
                        onBack = {})
                }
                compose.waitUntil(45_000) {
                    capture.state.value is QueryFaceCaptureState.Ready
                }
                compose.waitForIdle()
                compose.onNodeWithTag("atlas-query-face-0").assertExists().performClick()
                compose.onNodeWithTag("atlas-query-face-confirm").assertExists().performClick()
                compose.runOnIdle { assertNotNull(chosen) }
                assertEquals(SFaceModel.EMBEDDER_VERSION, chosen!!.modelVersion)
                capture.clear()
            }
        }
    }

    @Test fun multiFaceRequiresTapAndResetsAcrossRecaptureAndSourceChange() {
        // The two detections, vectors and bitmap are synthesized in memory.
        // No private image, real detection model or new inference factory is used.
        val first = QueryFaceSource(FaceMediaKey("synthetic:multi-a", "rev-a"), "grant-a")
        val second = QueryFaceSource(FaceMediaKey("synthetic:multi-b", "rev-b"), "grant-b")
        val pipeline = FaceEmbeddingPipeline("yunet-synthetic", "sface-synthetic") {
            listOf(
                FaceEmbeddedDetection(FacePixelBounds(20, 20, 64, 64),
                    FloatArray(SFaceModel.DIMENSION) { if (it == 3) 1f else 0f }),
                FaceEmbeddedDetection(FacePixelBounds(120, 50, 164, 94),
                    FloatArray(SFaceModel.DIMENSION) { if (it == 7) 1f else 0f })
            )
        }
        val capture = QueryFaceCapture(
            FaceFrameReader { lease, edge ->
                assertTrue(lease.media == first.media || lease.media == second.media)
                assertEquals(960, edge)
                AndroidBitmapFaceFrame(Bitmap.createBitmap(240, 160, Bitmap.Config.ARGB_8888))
            }, pipeline, { it == first || it == second })
        var source by mutableStateOf(first)
        var chosen: QueryFace? = null
        compose.setContent {
            QueryFacePickerScreen(source, capture, onFaceChosen = { chosen = it }, onBack = {})
        }
        fun awaitTwoFaces(expected: QueryFaceSource) {
            compose.waitUntil(15_000) {
                (capture.state.value as? QueryFaceCaptureState.Ready)?.let {
                    it.source == expected && it.faces.size == 2
                } == true
            }
            compose.waitForIdle()
        }

        awaitTwoFaces(first)
        compose.onNodeWithTag("atlas-query-face-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("atlas-query-face-1").performClick()
        compose.onNodeWithTag("atlas-query-face-confirm").assertIsEnabled()
        compose.runOnIdle { assertNull(chosen) }

        // Re-capturing the same source must not retain the prior tile tap.
        runBlocking { capture.capture(first) }
        awaitTwoFaces(first)
        compose.onNodeWithTag("atlas-query-face-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("atlas-query-face-0").performClick()
        compose.onNodeWithTag("atlas-query-face-confirm").assertIsEnabled()

        // Switching the source invalidates selection even if it remains Ready with 2 faces.
        compose.runOnIdle { source = second }
        awaitTwoFaces(second)
        compose.onNodeWithTag("atlas-query-face-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("atlas-query-face-1").performClick()
        compose.onNodeWithTag("atlas-query-face-confirm").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertNotNull(chosen)
            assertEquals("sface-synthetic", chosen!!.modelVersion)
            assertEquals(1f, chosen!!.embedding[7], 0f)
        }
        capture.clear()
    }
}
