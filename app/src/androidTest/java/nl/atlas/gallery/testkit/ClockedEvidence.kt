package nl.atlas.gallery.testkit

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import nl.atlas.gallery.uiEvidenceDisplayName
import nl.atlas.gallery.writeUiEvidenceFile

/**
 * Render the composition at a frozen Compose clock tick rather than using the
 * possibly older/newer UiAutomation display frame for motion evidence.
 *
 * Preserve the display outside the Compose host (system bars, S24 dimensions).
 * SRC overwrites the host, even at transparent pixels, so its previous display
 * state cannot leak through a fading Compose subtree. The returned bitmap is
 * the exact Compose frame, usable for pixel assertions; callers must recycle it.
 */
fun ComposeTestRule.saveClockedEvidence(filename: String): Bitmap {
    waitForIdle()
    val root = onRoot()
    val view = (root.fetchSemanticsNode().root as ViewRootForTest).view
    val xy = IntArray(2)
    runOnIdle { view.getLocationOnScreen(xy) }
    val rendered = root.captureToImage().asAndroidBitmap()
    val display = requireNotNull(
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
    ) { "Cannot obtain full-screen base for $filename" }
    try {
        check(rendered.width == view.width && rendered.height == view.height) {
            "$filename: Compose root ${rendered.width}x${rendered.height}, " +
                "host view ${view.width}x${view.height}"
        }
        check(xy[0] >= 0 && xy[1] >= 0 &&
            xy[0] + rendered.width <= display.width &&
            xy[1] + rendered.height <= display.height) {
            "$filename: Compose image at ${xy.contentToString()} does not fit " +
                "${display.width}x${display.height} display"
        }
        val evidence = display.copy(Bitmap.Config.ARGB_8888, true)
        try {
            Canvas(evidence).drawBitmap(
                rendered, xy[0].toFloat(), xy[1].toFloat(),
                Paint().apply { blendMode = BlendMode.SRC }
            )
            val png = ByteArrayOutputStream().also {
                check(evidence.compress(Bitmap.CompressFormat.PNG, 100, it)) {
                    "Cannot encode $filename"
                }
            }.toByteArray()
            writeUiEvidenceFile(uiEvidenceDisplayName(filename), png)
        } finally {
            evidence.recycle()
        }
    } catch (t: Throwable) {
        rendered.recycle()
        throw t
    } finally {
        display.recycle()
    }
    return rendered
}
