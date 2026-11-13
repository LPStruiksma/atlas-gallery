package nl.atlas.gallery

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import nl.atlas.gallery.featuredesign.AtlasGalleryTheme

/**
 * #645: a component harness (createComposeRule) that looks like the app, so
 * its UI-evidence screenshots can be judged against the spec. It copies what
 * MainActivity does around every route: dark ATLAS theme, the app's dark
 * system bars, and a DeepBg root Surface whose content colour is readable.
 *
 * [edgeToEdge] = false keeps content clear of the status bar, cutout and
 * navigation bar, as the app's Scaffold routes do. Full-screen routes that
 * handle insets themselves (the viewer) pass true, like MainActivity does.
 */
@Composable
internal fun AtlasEvidenceHarness(
    edgeToEdge: Boolean = false,
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    SideEffect { view.context.findActivity()?.let { applyAtlasSystemBars(it.window) } }
    AtlasGalleryTheme(dark = true) {
        Surface(Modifier.fillMaxSize(), color = DeepBg,
            contentColor = MaterialTheme.colorScheme.onBackground) {
            if (edgeToEdge) content()
            else Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                content()
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Galaxy S24+ default display, set by [S24DisplayRule]. */
internal const val UI_EVIDENCE_WIDTH = 1080
internal const val UI_EVIDENCE_HEIGHT = 2340

/**
 * #645: why [bitmap] must not be published as UI evidence, or null when it
 * may. Evidence is at the owner's S24+ size, and its content area (between
 * the status bar and the navigation bar) is not one flat colour: an all-white
 * or all-black frame is a capture taken before anything was drawn.
 */
internal fun uiEvidenceProblem(bitmap: Bitmap): String? {
    if (bitmap.width != UI_EVIDENCE_WIDTH || bitmap.height != UI_EVIDENCE_HEIGHT) {
        return "is ${bitmap.width}x${bitmap.height}, not " +
            "${UI_EVIDENCE_WIDTH}x$UI_EVIDENCE_HEIGHT: add S24DisplayRule as the outer rule"
    }
    val pixels = if (bitmap.config == Bitmap.Config.HARDWARE) {
        bitmap.copy(Bitmap.Config.ARGB_8888, false)
    } else bitmap
    try {
        val counts = HashMap<Int, Int>()
        var samples = 0
        val margin = bitmap.height * 8 / 100
        for (y in margin until bitmap.height - margin step BLANK_STRIDE) {
            for (x in 0 until bitmap.width step BLANK_STRIDE) {
                // 4 bits per channel: anti-aliasing noise is not content.
                val c = pixels.getPixel(x, y)
                val key = (c shr 4 and 0x0F0F0F)
                counts[key] = (counts[key] ?: 0) + 1
                samples++
            }
        }
        val (dominant, count) = counts.maxBy { it.value }
        val others = samples - count
        // A single short line of text covers hundreds of samples.
        return if (others * BLANK_RATIO < samples) {
            "is blank: $count of $samples content samples are one colour " +
                "(#%06X), only $others differ".format(dominant shl 4)
        } else null
    } finally {
        if (pixels !== bitmap) pixels.recycle()
    }
}

private const val BLANK_STRIDE = 6
private const val BLANK_RATIO = 2_000
