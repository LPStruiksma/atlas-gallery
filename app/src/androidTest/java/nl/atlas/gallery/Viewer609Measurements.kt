package nl.atlas.gallery

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Point
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.Window
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject

/**
 * DESIGN-8M #609. Test-only sampled Compose insets, NOT constants from a design sketch.
 * SideEffect refreshes the sample after configuration/rotation/inset changes.
 */
internal data class Viewer609InsetSample(
    val pixelsPerDp: Float,
    val safe: IntArray,
    val status: IntArray,
    val navigation: IntArray,
    val cutout: IntArray
)

@Composable
internal fun Viewer609InsetProbe(onSample: (Viewer609InsetSample) -> Unit) {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    fun read(insets: WindowInsets): IntArray = intArrayOf(
        insets.getLeft(density, direction),
        insets.getTop(density),
        insets.getRight(density, direction),
        insets.getBottom(density)
    )
    val sample = Viewer609InsetSample(
        density.density, read(WindowInsets.safeDrawing), read(WindowInsets.statusBars),
        read(WindowInsets.navigationBars), read(WindowInsets.displayCutout)
    )
    SideEffect { onSample(sample) }
}

internal fun Context.viewer609Activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.viewer609Activity()
    else -> null
}

/**
 * A measurement log, not a parity assertion. Rectangles are Compose *semantics*
 * hit areas in window pixels; visual glyph and Media3 platform touch geometry
 * are not measurable from the Compose semantics tree and are identified as such.
 * No real MediaStore URI, filename, image content or user data is serialized.
 */
internal class Viewer609Recorder(
    private val rule: ComposeTestRule,
    private val window: () -> Window?
) {
    private val frames = JSONArray()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val tags = listOf(
        "atlas-viewer", "atlas-viewer-top-chrome", "atlas-back",
        "atlas-viewer-details-open", "atlas-viewer-find-person",
        "atlas-viewer-find-similar", "atlas-viewer-more",
        "atlas-viewer-bottom-chrome", "atlas-filmstrip",
        "atlas-thumbnail-0", "atlas-thumbnail-1",
        "atlas-viewer-favourite", "atlas-edit-photo",
        "atlas-share-media", "atlas-viewer-trash",
        "atlas-video-controls", "atlas-video-play-pause",
        "atlas-video-seek", "atlas-video-seek-preview",
        "atlas-video-seek-frame", "atlas-photo-surface", "atlas-video-surface"
    )

    private fun rect(left: Float, top: Float, right: Float, bottom: Float) =
        JSONObject().put("left", left.toDouble()).put("top", top.toDouble())
            .put("right", right.toDouble()).put("bottom", bottom.toDouble())
            .put("width", (right - left).toDouble()).put("height", (bottom - top).toDouble())

    private fun sides(v: IntArray, pxPerDp: Float) =
        JSONObject().put("px", JSONArray(v.toList()))
            .put("dp", JSONArray(v.map { it / pxPerDp }))

    private fun shell(command: String): String = runCatching {
        val fd = instrumentation.uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(fd).use {
            it.readBytes().decodeToString().trim().take(256)
        }
    }.getOrElse { "UNAVAILABLE:${it.javaClass.simpleName}" }

    fun capture(key: String, screenshot: String?, sample: Viewer609InsetSample?) {
        rule.waitForIdle()
        val w = requireNotNull(window()) { "Measurement requires a real test Activity window" }
        val view = w.decorView
        val pxPerDp = sample?.pixelsPerDp ?: view.resources.displayMetrics.density
        val frame = JSONObject()
            .put("key", key)
            .put("capture", screenshot?.let { "ui-evidence/$it" } ?: JSONObject.NULL)
            .put("capture_status", if (screenshot == null) "NOT_CAPTURED" else "CAPTURED_BY_TEST")
            .put("geometry_source", "Compose semantics boundsInWindow; not painted glyph bounds")
            .put("pixels_per_dp", pxPerDp.toDouble())
            .put("density_dpi", view.resources.displayMetrics.densityDpi)
            .put("api", Build.VERSION.SDK_INT)
            .put("device_manufacturer", Build.MANUFACTURER)
            .put("device_model", Build.MODEL)
            .put("emulator", shell("getprop ro.kernel.qemu"))
            .put("wm_size", shell("wm size"))
            .put("wm_density", shell("wm density"))
            .put("navigation_mode", shell("settings get secure navigation_mode"))
            .put("rotation", view.display?.rotation ?: -1)
            .put("orientation", view.resources.configuration.orientation)
            .put("window_px", JSONObject().put("width", view.width).put("height", view.height))
        val actualSize = Point()
        view.display?.getRealSize(actualSize)
        frame.put("display_real_px", JSONObject().put("width", actualSize.x)
            .put("height", actualSize.y))
        val insetJson = JSONObject()
        if (sample != null) {
            insetJson.put("compose_safeDrawing", sides(sample.safe, pxPerDp))
                .put("compose_statusBars", sides(sample.status, pxPerDp))
                .put("compose_navigationBars", sides(sample.navigation, pxPerDp))
                .put("compose_displayCutout", sides(sample.cutout, pxPerDp))
        } else insetJson.put("compose", "NOT_SAMPLED")
        val root = ViewCompat.getRootWindowInsets(view)
        if (root != null) {
            fun androidInsets(type: Int, ignoreVisibility: Boolean): JSONObject {
                val ins = if (ignoreVisibility) root.getInsetsIgnoringVisibility(type)
                    else root.getInsets(type)
                return sides(intArrayOf(ins.left, ins.top, ins.right, ins.bottom), pxPerDp)
            }
            insetJson.put("android_status_visible", androidInsets(WindowInsetsCompat.Type.statusBars(), false))
                .put("android_status_ignoringVisibility",
                    androidInsets(WindowInsetsCompat.Type.statusBars(), true))
                .put("android_navigation_visible", androidInsets(WindowInsetsCompat.Type.navigationBars(), false))
                .put("android_navigation_ignoringVisibility",
                    androidInsets(WindowInsetsCompat.Type.navigationBars(), true))
                .put("android_displayCutout",
                    androidInsets(WindowInsetsCompat.Type.displayCutout(), true))
                // #627: gesture zones decide edge-swipe vs strip/seek drags and where
                // the home gesture is mandatory; recorded in every mode, never assumed.
                .put("android_systemGestures",
                    androidInsets(WindowInsetsCompat.Type.systemGestures(), false))
                .put("android_mandatorySystemGestures",
                    androidInsets(WindowInsetsCompat.Type.mandatorySystemGestures(), false))
                .put("android_tappableElement",
                    androidInsets(WindowInsetsCompat.Type.tappableElement(), false))
            val boxes = JSONArray()
            view.rootWindowInsets?.displayCutout?.boundingRects?.forEach {
                boxes.put(rect(it.left.toFloat(), it.top.toFloat(),
                    it.right.toFloat(), it.bottom.toFloat()))
            }
            insetJson.put("cutout_rectangles_px", boxes)
                .put("cutout_supported", boxes.length() > 0)
        } else insetJson.put("android", "NOT_AVAILABLE")
        frame.put("insets", insetJson)
        val regions = JSONObject()
        for (tag in tags) {
            val nodes = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
            if (nodes.isEmpty()) {
                regions.put(tag, JSONObject().put("status", "NOT_RENDERED"))
                continue
            }
            val node = nodes.first()
            val b = node.boundsInWindow
            val click = node.config.contains(SemanticsActions.OnClick)
            val target = JSONObject().put("status", "MEASURED")
                .put("bounds_px", rect(b.left, b.top, b.right, b.bottom))
                .put("bounds_dp", rect(b.left / pxPerDp, b.top / pxPerDp,
                    b.right / pxPerDp, b.bottom / pxPerDp))
                .put("semantic_click_action", click)
                .put("content_description",
                    node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" | ")
                        ?: JSONObject.NULL)
                .put("state_description",
                    node.config.getOrNull(SemanticsProperties.StateDescription) ?: JSONObject.NULL)
            if (click) target.put("at_least_44dp",
                b.width / pxPerDp >= 44f && b.height / pxPerDp >= 44f)
            if (sample != null) {
                target.put("safe_edge_offsets_px", JSONObject()
                    .put("left", b.left - sample.safe[0])
                    .put("top", b.top - sample.safe[1])
                    .put("right", view.width - sample.safe[2] - b.right)
                    .put("bottom", view.height - sample.safe[3] - b.bottom))
            }
            regions.put(tag, target)
        }
        frame.put("regions", regions)
        val controls = regions.optJSONObject("atlas-video-controls")?.optJSONObject("bounds_px")
        val strip = regions.optJSONObject("atlas-filmstrip")?.optJSONObject("bounds_px")
        if (controls != null && strip != null) {
            val gap = strip.getDouble("top") - controls.getDouble("bottom")
            frame.put("video_controls_to_filmstrip_gap_px", gap)
                .put("video_controls_to_filmstrip_gap_dp", gap / pxPerDp)
                .put("proposed_374_gap_at_least_8dp", gap / pxPerDp >= 8.0)
        } else frame.put("video_controls_to_filmstrip_gap_dp", "NOT_MEASURABLE")
        frames.put(frame)
        Log.i("ATLAS_VIEWER_609", "Measured $key; regions=${regions.length()}")
    }

    fun publish(filename: String, unobservedStates: List<String>, issue: Int = 609) {
        val report = JSONObject()
            .put("schema", "atlas-viewer-design-8m/1")
            .put("issue", issue)
            .put("host", "GitHub API35 x86_64 pixel_6 with S24DisplayRule override; NOT a physical S24+")
            .put("geometry_caveat", "Semantics node bounds do not prove rendered pill artwork or AndroidView touch zones")
            .put("comparison_only", "Corrected #374 design proposal: videoControlsBottom <= filmstripTop - 8dp; this is NOT a baseline assertion")
            .put("frames", frames)
            .put("not_captured", JSONArray(unobservedStates))
        writeUiEvidenceFile(filename, (report.toString(2) + "\n").toByteArray(Charsets.UTF_8))
        Log.i("ATLAS_VIEWER_609", "Manifest $filename with ${frames.length()} measured states")
    }
}
