package nl.atlas.gallery

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Isolated Apache-2.0 Telephoto 0.19.0 / Coil3 test-only prototype.
 * This is deliberately NOT wired into the shipped GalleryViewer or its APK.
 * Everything is synthetic, local, offline, and limited to an emulator test.
 */
@RunWith(AndroidJUnit4::class)
class TelephotoPrototypeTest {
    @get:Rule val rule = createComposeRule()

    private fun image(index: Int): Uri {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "telephoto-synthetic-$index.jpg")
        if (!file.exists()) {
            val bitmap = Bitmap.createBitmap(1600, 1000, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                canvas.drawColor(if (index == 0) Color.rgb(40, 130, 140) else Color.rgb(160, 95, 50))
                paint.color = Color.WHITE
                paint.textSize = 200f
                canvas.drawText(index.toString(), 500f, 500f, paint)
                FileOutputStream(file).use { out ->
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out))
                }
            } finally {
                bitmap.recycle()
            }
        }
        return Uri.fromFile(file)
    }

    @Test fun coil3TelephotoZoomResetsAcrossLocalSyntheticImages() {
        val images = listOf(image(0), image(1))
        rule.setContent {
            var index by remember { mutableIntStateOf(0) }
            var loaded by remember { mutableStateOf(false) }
            var zoomed by remember { mutableStateOf(false) }
            MaterialTheme {
                Column(Modifier.fillMaxSize()) {
                    Row {
                        Text("Synthetic image ${index + 1} of 2",
                            modifier = Modifier.testTag("telephoto-count"))
                        Button(onClick = { index = 1 - index },
                            modifier = Modifier.testTag("telephoto-switch")) {
                            Text("Switch image")
                        }
                    }
                    Text(if (loaded) "Loaded" else "Loading",
                        modifier = Modifier.testTag("telephoto-loaded"))
                    Text(if (zoomed) "Zoomed" else "Fit",
                        modifier = Modifier.testTag("telephoto-zoom"))
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        // Lazy-page semantics: zoom state belongs to one photo.
                        // Never accidentally carry a prior person's zoom or
                        // pan position over to a different image.
                        key(index) {
                            val state = rememberZoomableState()
                            val imageState = rememberZoomableImageState(state)
                            LaunchedEffect(index, imageState.isImageDisplayed, state.zoomFraction) {
                                loaded = imageState.isImageDisplayed
                                zoomed = (state.zoomFraction ?: 0f) > 0.1f
                            }
                            ZoomableAsyncImage(
                                model = images[index],
                                state = imageState,
                                contentDescription = "Synthetic local image",
                                modifier = Modifier.fillMaxSize()
                                    .testTag("telephoto-image")
                            )
                        }
                    }
                }
            }
        }
        rule.waitUntil(15_000) {
            rule.onAllNodesWithTag("telephoto-loaded")
                .fetchSemanticsNodes().any { node ->
                    node.config[androidx.compose.ui.semantics.SemanticsProperties.Text]
                        .joinToString("") { it.text } == "Loaded"
                }
        }
        rule.onNodeWithTag("telephoto-count").assertTextEquals("Synthetic image 1 of 2")
        rule.onNodeWithTag("telephoto-image").performTouchInput { doubleClick(center) }
        rule.waitUntil(15_000) {
            rule.onAllNodesWithTag("telephoto-zoom")
                .fetchSemanticsNodes().any { node ->
                    node.config[androidx.compose.ui.semantics.SemanticsProperties.Text]
                        .joinToString("") { it.text } == "Zoomed"
                }
        }
        rule.onNodeWithTag("telephoto-switch").performClick()
        rule.onNodeWithTag("telephoto-count").assertTextEquals("Synthetic image 2 of 2")
        rule.waitForIdle()
        rule.onNodeWithTag("telephoto-zoom").assertTextEquals("Fit")
        rule.onNodeWithTag("telephoto-switch").performClick()
        rule.onNodeWithTag("telephoto-count").assertTextEquals("Synthetic image 1 of 2")
        rule.onNodeWithTag("telephoto-zoom").assertTextEquals("Fit")
    }
}
