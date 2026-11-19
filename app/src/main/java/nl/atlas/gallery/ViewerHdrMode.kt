package nl.atlas.gallery

import android.content.pm.ActivityInfo
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.BitmapImage
import coil3.Image

/**
 * BENCH-1 (#572): Ultra HDR photos (JPEG + gain map, the S24+ camera default)
 * show in HDR while they are the focused viewer photo, like Samsung Gallery
 * and Google Photos. Android's guidance: enable window HDR only while an
 * Ultra HDR image is on screen, never in the manifest or app-wide.
 * https://developer.android.com/media/grow/ultra-hdr/display
 */
internal const val GAINMAP_MIN_SDK = 34

/**
 * The window colour mode the viewer wants: HDR only on API 34+, while the
 * activity is in the foreground and the focused photo's decoded bitmap has a
 * gain map. Every other case is the mode the window had before the viewer.
 */
internal fun viewerWindowColorMode(
    sdkInt: Int, focusedHasGainmap: Boolean, foreground: Boolean, priorMode: Int
): Int =
    if (sdkInt >= GAINMAP_MIN_SDK && focusedHasGainmap && foreground) ActivityInfo.COLOR_MODE_HDR
    else priorMode

/** True when Coil decoded [image] to a bitmap that kept its Ultra HDR gain map. */
internal fun viewerImageHasGainmap(image: Image): Boolean {
    if (Build.VERSION.SDK_INT < GAINMAP_MIN_SDK) return false
    val bitmap = (image as? BitmapImage)?.bitmap ?: return false
    return bitmap.hasGainmap()
}

/**
 * Applies [viewerWindowColorMode] to the host window for the viewer's
 * lifetime and restores the window's prior mode when the viewer leaves.
 * Below API 34 it does nothing at all.
 */
@Composable
internal fun ViewerHdrWindowMode(focusedHasGainmap: Boolean) {
    if (Build.VERSION.SDK_INT < GAINMAP_MIN_SDK) return
    val view = LocalView.current
    val window = view.context.viewerHostActivity()?.window ?: return
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val foreground = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    val prior = remember(window) { window.colorMode }
    DisposableEffect(window) {
        onDispose { window.colorMode = prior }
    }
    LaunchedEffect(window, focusedHasGainmap, foreground) {
        val wanted = viewerWindowColorMode(Build.VERSION.SDK_INT, focusedHasGainmap, foreground, prior)
        if (window.colorMode != wanted) window.colorMode = wanted
    }
}
