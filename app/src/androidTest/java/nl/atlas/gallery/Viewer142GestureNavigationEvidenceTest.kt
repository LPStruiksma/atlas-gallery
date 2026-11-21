package nl.atlas.gallery

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import nl.atlas.gallery.featuredesign.AtlasGalleryTheme
import nl.atlas.gallery.testkit.awaitScreenStill
import nl.atlas.gallery.testkit.awaitThat
import nl.atlas.gallery.testkit.awaitWindowSettled
import nl.atlas.gallery.testkit.exists
import nl.atlas.gallery.testkit.tap
import nl.atlas.gallery.testkit.testShell
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * DESIGN-8R2 (#833): gesture-navigation evidence for the CURRENT viewer, the
 * baseline the #142 preflight needs. Test/probe only; no production change.
 *
 * Isolation (the #627 lesson, docs/design/viewer-142-target/GESTURE_NAV.md):
 * this class switches the device-wide navigation overlay, so it never runs
 * inside a shared shard. It skips itself unless the instrumentation run names
 * exactly this class (`-e class`), which is how the opt-in stress lane in
 * scripts/ci_android_upgrade_emulator.sh runs each listed class on its own.
 * The overlay switch happens before the test Activity starts and is undone,
 * and verified, after it closes, also when the test fails.
 *
 * Fixtures are invented in code (flat PNG blocks and a 4 s red/blue H.264
 * clip in the app's cache dir). Nothing is read from or added to MediaStore.
 * Results go to logcat (tag ATLAS_TESTKIT, prefix G142) as JSON and base64
 * PNG chunks, because the stress lane does not pull device files; see
 * docs/design/viewer-142-gesture-proof/decode_log.py.
 */
@RunWith(AndroidJUnit4::class)
class Viewer142GestureNavigationEvidenceTest {
    private val compose = createComposeRule()
    private val gestureNav = GestureNavigationOverlayRule()

    @get:Rule val rules: RuleChain = RuleChain
        .outerRule(IsolatedRunOnlyRule())
        .around(S24DisplayRule())
        .around(gestureNav)
        .around(compose)

    @Test fun measureGestureInsetsAndEdgeProbesOnPhotoAndVideo() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val files = mutableListOf<File>()
        try {
            val photoColours = listOf(Color.rgb(240, 233, 120), Color.rgb(13, 25, 47),
                Color.rgb(120, 200, 160))
            val photos = photoColours.mapIndexed { i, colour ->
                File.createTempFile("viewer-142-g-$i-", ".png", context.cacheDir).also { file ->
                    files += file
                    Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).also { bitmap ->
                        bitmap.eraseColor(colour)
                        // Diagonal bars so a pager move is visible in a screenshot.
                        val canvas = android.graphics.Canvas(bitmap)
                        val brush = android.graphics.Paint().apply {
                            this.color = Color.rgb(22 + i * 60, 80, 150); strokeWidth = 12f
                        }
                        for (x in 0..320 step 32) canvas.drawLine(x.toFloat(), 0f,
                            (320 - x).toFloat(), 240f, brush)
                        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                }
            }
            val video = File.createTempFile("viewer-142-g-video-", ".mp4", context.cacheDir)
            files += video
            encodeRedBlueVideo(video)
            val items = listOf(
                MediaRecord(14200L, Uri.fromFile(photos[0]).toString(), "Synthetic block A",
                    "DCIM/ATLAS-SYNTHETIC-142G/", photos[0].length(), 3L, false),
                MediaRecord(14201L, Uri.fromFile(photos[1]).toString(), "Synthetic block B",
                    "DCIM/ATLAS-SYNTHETIC-142G/", photos[1].length(), 2L, false),
                MediaRecord(14202L, Uri.fromFile(video).toString(), "Synthetic red-blue clip",
                    "DCIM/ATLAS-SYNTHETIC-142G/", video.length(), 1L, true),
                MediaRecord(14203L, Uri.fromFile(photos[2]).toString(), "Synthetic block C",
                    "DCIM/ATLAS-SYNTHETIC-142G/", photos[2].length(), 0L, false)
            )
            var window: Window? = null
            var insetSample: Viewer609InsetSample? = null
            val counters = Counters()
            compose.setContent {
                val view = LocalView.current
                SideEffect { window = view.context.viewer609Activity()?.window }
                // Outer fallback: counts a Back that GalleryViewer's own
                // BackHandler did not consume (so the test Activity never
                // finishes). The viewer's onBack only counts, it does not
                // close, so the viewer stays up for the next probe.
                BackHandler { counters.systemBack++ }
                AtlasGalleryTheme(dark = true) {
                    Box(Modifier.fillMaxSize()) {
                        Viewer609InsetProbe { insetSample = it }
                        GalleryViewer(items, items[1].uri, onBack = { counters.viewerBack++ })
                    }
                }
            }
            val recorder = GestureRecorder(compose, { window }, { insetSample }, gestureNav, counters)
            compose.awaitThat("gesture-navigation side zones in the app window", 20_000L) {
                recorder.systemGestures()?.let { it.left > 0 && it.right > 0 } == true
            }
            compose.awaitWindowSettled()
            awaitCounter("2 of 4")

            // Photo, chrome shown and hidden.
            recorder.capture("photo/portrait/shown", "viewer-142-gesture-photo-portrait-shown.png")
            val photoFocus = hasTestTag("atlas-photo-surface") and hasClickAction()
            compose.onNode(photoFocus).performSemanticsAction(SemanticsActions.OnClick)
            compose.awaitThat("photo chrome hidden") { !compose.exists(hasTestTag("atlas-filmstrip")) }
            recorder.capture("photo/portrait/hidden", "viewer-142-gesture-photo-portrait-hidden.png")
            compose.onNode(photoFocus).performSemanticsAction(SemanticsActions.OnClick)
            compose.awaitThat("photo chrome shown") { compose.exists(hasTestTag("atlas-filmstrip")) }

            // Probes on the photo page, chrome shown. Every probe is a real
            // injected touch stream (`input`), so SystemUI's edge-back handler
            // sees it exactly as it sees a finger.
            val reset = { resetTo(1, "2 of 4") }
            recorder.probe("photo/left-edge-swipe-mid", reset) { b ->
                swipe(EDGE_PX, b.midY, (b.width * 0.45f).toInt(), b.midY)
            }
            recorder.probe("photo/right-edge-swipe-mid", reset) { b ->
                swipe(b.width - 1 - EDGE_PX, b.midY, (b.width * 0.55f).toInt(), b.midY)
            }
            recorder.probe("photo/inner-swipe-left-of-centre-to-right", reset) { b ->
                val start = b.gestureLeft + dp(b, 24f)
                swipe(start, b.midY, start + (b.width * 0.45f).toInt(), b.midY)
            }
            recorder.probe("photo/left-edge-swipe-on-filmstrip", reset) { b ->
                val y = recorder.centreY("atlas-filmstrip") ?: error("filmstrip not rendered")
                swipe(EDGE_PX, y, (b.width * 0.45f).toInt(), y)
            }
            recorder.probe("photo/inner-drag-on-filmstrip", reset) { b ->
                val y = recorder.centreY("atlas-filmstrip") ?: error("filmstrip not rendered")
                val start = b.gestureLeft + dp(b, 24f)
                swipe(start + (b.width * 0.4f).toInt(), y, start, y)
            }
            recorder.probe("photo/tap-centre-toggles-chrome", reset) { b ->
                tapAt(b.width / 2, b.midY)
            }
            recorder.probe("photo/tap-back-button", reset) { _ ->
                val c = recorder.centre("atlas-back") ?: error("back button not rendered")
                tapAt(c.first, c.second)
            }

            // Landscape, photo shown: one capture and one edge-back probe.
            try {
                rotateViewerEvidenceLandscape()
                compose.awaitThat("landscape viewer", 10_000L) {
                    compose.onNodeWithTag("atlas-viewer").fetchSemanticsNode().boundsInRoot
                        .let { it.width > it.height }
                }
                compose.awaitWindowSettled()
                compose.awaitThat("landscape gesture zones", 10_000L) {
                    recorder.systemGestures()?.let { it.left > 0 && it.right > 0 } == true
                }
                recorder.capture("photo/landscape/shown",
                    "viewer-142-gesture-photo-landscape-shown.png")
                recorder.probe("photo/landscape/left-edge-swipe-mid", reset) { b ->
                    swipe(EDGE_PX, b.midY, (b.width * 0.40f).toInt(), b.midY)
                }
            } finally {
                restoreViewerEvidencePortrait()
            }
            compose.awaitThat("portrait viewer", 10_000L) {
                compose.onNodeWithTag("atlas-viewer").fetchSemanticsNode().boundsInRoot
                    .let { it.height > it.width }
            }
            compose.awaitWindowSettled()

            // Video page (paused), chrome shown and hidden.
            selectThumbnail(2)
            awaitPlayer()
            awaitCounter("3 of 4")
            recorder.capture("video/portrait/shown", "viewer-142-gesture-video-portrait-shown.png")
            val videoFocus = hasTestTag("atlas-video-surface") and hasClickAction()
            compose.onNode(videoFocus).performSemanticsAction(SemanticsActions.OnClick)
            compose.awaitThat("video chrome hidden") { !compose.exists(hasTestTag("atlas-filmstrip")) }
            recorder.capture("video/portrait/hidden", "viewer-142-gesture-video-portrait-hidden.png")
            compose.onNode(videoFocus).performSemanticsAction(SemanticsActions.OnClick)
            compose.awaitThat("video chrome shown") { compose.exists(hasTestTag("atlas-filmstrip")) }

            val videoReset = { resetTo(2, "3 of 4"); awaitPlayer(); pauseIfPlaying() }
            recorder.probe("video/left-edge-swipe-on-seek", videoReset) { b ->
                val y = recorder.centreY("atlas-video-seek") ?: error("seek bar not rendered")
                swipe(EDGE_PX, y, (b.width * 0.45f).toInt(), y)
            }
            recorder.probe("video/right-edge-swipe-on-seek", videoReset) { b ->
                val y = recorder.centreY("atlas-video-seek") ?: error("seek bar not rendered")
                swipe(b.width - 1 - EDGE_PX, y, (b.width * 0.55f).toInt(), y)
            }
            recorder.probe("video/inner-drag-on-seek", videoReset) { b ->
                val r = recorder.bounds("atlas-video-seek") ?: error("seek bar not rendered")
                val y = ((r.top + r.bottom) / 2).toInt()
                val from = maxOf(r.left.toInt() + dp(b, 16f), b.gestureLeft + dp(b, 8f))
                swipe(from, y, (r.left + r.width() * 0.75f).toInt(), y)
            }
            recorder.probe("video/left-edge-swipe-mid", videoReset) { b ->
                swipe(EDGE_PX, b.midY, (b.width * 0.45f).toInt(), b.midY)
            }
            recorder.probe("video/tap-play-pause", videoReset) { _ ->
                val c = recorder.centre("atlas-video-play-pause") ?: error("play not rendered")
                tapAt(c.first, c.second)
            }

            recorder.publish(listOf(
                "Physical Galaxy S24+ (One UI) gesture zones and back sensitivity: emulator AOSP SystemUI only",
                "Home / recents swipe from the bottom zone: not injected (it would leave the app under test)",
                "Predictive-back animation frames: not captured; only the delivered Back is counted",
                "Media3 AndroidView internal touch handling beyond the Compose semantics tree",
                "Target #142 layout interactions: target not implemented, proposed/unverified"
            ))
            assertEquals("No probe may leave the viewer", 1,
                compose.onAllNodesWithTag("atlas-viewer").fetchSemanticsNodes().size)
        } finally {
            files.forEach { it.delete() }
            restoreViewerEvidencePortrait()
        }
    }

    private fun awaitCounter(text: String) {
        compose.awaitThat("viewer at $text", 10_000L) { counterText() == text }
    }

    private fun counterText(): String? =
        compose.onAllNodesWithTag("atlas-photo-counter", useUnmergedTree = true)
            .fetchSemanticsNodes(atLeastOneRootRequired = false).firstOrNull()
            ?.config?.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }

    private fun awaitPlayer() {
        compose.awaitThat("video player and play button", 20_000L) {
            compose.exists(hasTestTag("atlas-viewer-video-player")) &&
                compose.exists(hasTestTag("atlas-video-play-pause"))
        }
    }

    private fun pauseIfPlaying() {
        if (compose.exists(hasContentDescription("Pause video"))) {
            compose.tap(hasTestTag("atlas-video-play-pause"))
            compose.awaitThat("video paused") { compose.exists(hasContentDescription("Play video")) }
        }
    }

    /** Puts the viewer back on [index] with chrome shown, after a probe moved it. */
    private fun resetTo(index: Int, counter: String) {
        if (!chromeShown()) {
            val focus = (hasTestTag("atlas-photo-surface") or hasTestTag("atlas-video-surface")) and
                hasClickAction()
            compose.onNode(focus).performSemanticsAction(SemanticsActions.OnClick)
            compose.awaitThat("chrome back after probe") { chromeShown() }
        }
        settleQuietly(compose, "reset")
        if (counterText() != counter) {
            // Reset is setup, not a measured touch: use the click action.
            // A real tap on a filmstrip tile right after a page change fails
            // tap()'s stillness check (runs 44864103905 and 44865393321: the
            // tile moved 101 px while the pointer was down, also after the
            // screen held still), so it would end the probe run early.
            selectThumbnail(index)
            awaitCounter(counter)
        }
    }

    private fun chromeShown() = compose.exists(hasTestTag("atlas-filmstrip")) &&
        compose.exists(hasTestTag("atlas-back"))

    private fun selectThumbnail(index: Int) {
        compose.awaitScreenStill("filmstrip settled before selecting tile $index")
        compose.onNode(hasTestTag("atlas-thumbnail-$index") and hasClickAction())
            .performSemanticsAction(SemanticsActions.OnClick)
    }

    internal class Counters {
        @Volatile var systemBack = 0
        @Volatile var viewerBack = 0
    }

    internal class Box2(val width: Int, val height: Int, val gestureLeft: Int, val pxPerDp: Float) {
        val midY get() = height / 2
    }

    private fun dp(b: Box2, value: Float) = (value * b.pxPerDp).toInt()

    /**
     * Records geometry and probe outcomes. Labels: MEASURED (read from the
     * running system/app), COMPUTED (arithmetic on measured values), UNKNOWN.
     */
    internal class GestureRecorder(
        private val compose: ComposeTestRule,
        private val window: () -> Window?,
        private val sample: () -> Viewer609InsetSample?,
        private val nav: GestureNavigationOverlayRule,
        private val counters: Counters
    ) {
        private val frames = JSONArray()
        private val probes = JSONArray()
        private val tags = listOf(
            "atlas-viewer-top-chrome", "atlas-back", "atlas-viewer-details-open",
            "atlas-viewer-find-person", "atlas-viewer-find-similar", "atlas-viewer-more",
            "atlas-viewer-bottom-chrome", "atlas-filmstrip", "atlas-thumbnail-0",
            "atlas-thumbnail-1", "atlas-thumbnail-2", "atlas-thumbnail-3",
            "atlas-viewer-favourite", "atlas-edit-photo", "atlas-share-media",
            "atlas-viewer-trash", "atlas-video-controls", "atlas-video-play-pause",
            "atlas-video-seek", "atlas-photo-surface", "atlas-video-surface"
        )

        private fun decor(): View = requireNotNull(window()) { "No test Activity window" }.decorView

        private fun rootInsets() = ViewCompat.getRootWindowInsets(decor())

        fun systemGestures() = onMain { rootInsets()?.getInsets(WindowInsetsCompat.Type.systemGestures()) }

        fun bounds(tag: String): Rect? {
            val node = compose.onAllNodesWithTag(tag, useUnmergedTree = true)
                .fetchSemanticsNodes(atLeastOneRootRequired = false).firstOrNull() ?: return null
            val b = node.boundsInWindow
            val origin = screenOrigin()
            return Rect(b.left.toInt() + origin.first, b.top.toInt() + origin.second,
                b.right.toInt() + origin.first, b.bottom.toInt() + origin.second)
        }

        fun centre(tag: String) = bounds(tag)?.let { it.centerX() to it.centerY() }

        fun centreY(tag: String) = bounds(tag)?.centerY()

        private fun screenOrigin(): Pair<Int, Int> = onMain {
            val loc = IntArray(2); decor().getLocationOnScreen(loc); loc[0] to loc[1]
        }

        fun box(): Box2 = onMain {
            val v = decor()
            val g = rootInsets()?.getInsets(WindowInsetsCompat.Type.systemGestures())
            Box2(v.width, v.height, g?.left ?: 0, v.resources.displayMetrics.density)
        }

        private fun sides(i: androidx.core.graphics.Insets, pxPerDp: Float) = JSONObject()
            .put("px", JSONArray(listOf(i.left, i.top, i.right, i.bottom)))
            .put("dp", JSONArray(listOf(i.left, i.top, i.right, i.bottom).map { it / pxPerDp }))

        private fun rectJson(r: Rect, pxPerDp: Float) = JSONObject()
            .put("px", JSONArray(listOf(r.left, r.top, r.right, r.bottom)))
            .put("dp", JSONArray(listOf(r.left, r.top, r.right, r.bottom).map { it / pxPerDp }))

        /** Every gesture-exclusion rect any view in the window requests (MEASURED). */
        private fun exclusionRects(pxPerDp: Float): JSONArray = onMain {
            val out = JSONArray()
            fun walk(v: View) {
                if (Build.VERSION.SDK_INT >= 29) v.systemGestureExclusionRects.forEach {
                    out.put(JSONObject().put("view", v.javaClass.simpleName)
                        .put("rect", rectJson(it, pxPerDp)))
                }
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(decor())
            out
        }

        private fun environment(): JSONObject = JSONObject()
            .put("navigation_mode", testShell("settings get secure navigation_mode"))
            .put("navbar_overlays", JSONArray(nav.navbarOverlayLines()))
            .put("wm_size", testShell("wm size"))
            .put("wm_density", testShell("wm density"))
            .put("api", Build.VERSION.SDK_INT)
            .put("device_model", Build.MODEL)
            .put("emulator", testShell("getprop ro.kernel.qemu"))

        fun capture(key: String, png: String) {
            compose.awaitWindowSettled()
            compose.awaitScreenStill(key)
            val shot = screenshot(key)
            val pxPerDp = box().pxPerDp
            val measured = onMain {
                val v = decor()
                val root = rootInsets()
                val m = JSONObject()
                    .put("rotation", v.display?.rotation ?: -1)
                    .put("window_px", JSONArray(listOf(v.width, v.height)))
                    .put("pixels_per_dp", pxPerDp.toDouble())
                    .put("density_dpi", v.resources.displayMetrics.densityDpi)
                if (root != null) {
                    val types = linkedMapOf(
                        "statusBars" to WindowInsetsCompat.Type.statusBars(),
                        "navigationBars" to WindowInsetsCompat.Type.navigationBars(),
                        "displayCutout" to WindowInsetsCompat.Type.displayCutout(),
                        "systemGestures" to WindowInsetsCompat.Type.systemGestures(),
                        "mandatorySystemGestures" to WindowInsetsCompat.Type.mandatorySystemGestures(),
                        "tappableElement" to WindowInsetsCompat.Type.tappableElement()
                    )
                    val ins = JSONObject()
                    for ((name, type) in types) ins.put(name, sides(root.getInsets(type), pxPerDp))
                    for (name in listOf("statusBars", "navigationBars")) {
                        ins.put("${name}_ignoringVisibility",
                            sides(root.getInsetsIgnoringVisibility(types.getValue(name)), pxPerDp))
                    }
                    val cut = JSONArray()
                    v.rootWindowInsets?.displayCutout?.boundingRects?.forEach { cut.put(rectJson(it, pxPerDp)) }
                    ins.put("cutout_rects", cut)
                    m.put("android_insets", ins)
                } else m.put("android_insets", "UNKNOWN: no root insets")
                m
            }
            sample()?.let { s ->
                measured.put("compose_safeDrawing_px", JSONArray(s.safe.toList()))
                    .put("compose_statusBars_px", JSONArray(s.status.toList()))
                    .put("compose_navigationBars_px", JSONArray(s.navigation.toList()))
                    .put("compose_displayCutout_px", JSONArray(s.cutout.toList()))
            } ?: measured.put("compose_insets", "UNKNOWN: not sampled")
            measured.put("gesture_exclusion_rects", exclusionRects(pxPerDp))
            val regions = JSONObject()
            val computed = JSONObject()
            val b = box()
            val g = systemGestures()
            val mandatory = onMain { rootInsets()?.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures()) }
            for (tag in tags) {
                val r = bounds(tag)
                if (r == null) { regions.put(tag, "NOT_RENDERED"); continue }
                val node = compose.onAllNodesWithTag(tag, useUnmergedTree = true)
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).first()
                regions.put(tag, rectJson(r, pxPerDp)
                    .put("click", node.config.contains(SemanticsActions.OnClick)))
                if (g != null && mandatory != null) {
                    computed.put(tag, JSONObject()
                        .put("in_left_gesture_zone_px", maxOf(0, minOf(r.right, g.left) - r.left))
                        .put("in_right_gesture_zone_px", maxOf(0, r.right - maxOf(r.left, b.width - g.right)))
                        .put("in_bottom_mandatory_zone_px",
                            maxOf(0, r.bottom - maxOf(r.top, b.height - mandatory.bottom))))
                }
            }
            measured.put("regions_semantics_bounds_screen", regions)
            frames.put(JSONObject().put("key", key).put("png", png)
                .put("png_sha256", sha256(shot))
                .put("environment_MEASURED", environment())
                .put("MEASURED", measured)
                .put("COMPUTED_zone_overlap", computed))
            emit(png, shot)
            Log.i(G142_TAG, "G142 captured $key")
        }

        /**
         * Runs [inject] from a reset viewer and records what moved. Outcomes are
         * recorded, not asserted: the baseline is whatever the system does.
         */
        fun probe(name: String, reset: () -> Unit, inject: (Box2) -> Unit) {
            for (attempt in 1..PROBE_REPEATS) probeOnce("$name#$attempt", reset, inject)
        }

        private fun probeOnce(name: String, reset: () -> Unit, inject: (Box2) -> Unit) {
            reset()
            compose.awaitScreenStill("before $name")
            val before = state()
            val b = box()
            val started = SystemClock.uptimeMillis()
            inject(b)
            // A single tap is delivered only after the double-tap timeout and
            // Back after the gesture ends, so give any effect a bounded chance
            // to appear; "nothing happened" is a valid MEASURED outcome.
            val changed = runCatching {
                compose.awaitThat("any effect of $name", EFFECT_WAIT_MS) {
                    signature(state()) != signature(before)
                }
            }.isSuccess
            settleQuietly(compose, name)
            compose.awaitScreenStill("after $name")
            val after = state()
            val outcome = JSONObject().put("name", name)
                .put("label", "MEASURED")
                .put("window_px", JSONArray(listOf(b.width, b.height)))
                .put("gesture_left_px", b.gestureLeft)
                .put("before", before).put("after", after)
                .put("effect_within_ms", if (changed) EFFECT_WAIT_MS else JSONObject.NULL)
                // GalleryViewer's own BackHandler routes system Back AND its
                // toolbar back button to onBack; the outer handler only sees
                // a Back the viewer did not consume.
                .put("viewer_onBack_calls", after.getInt("viewer_back") - before.getInt("viewer_back"))
                .put("back_not_consumed_by_viewer", after.getInt("system_back") - before.getInt("system_back"))
                // The counter exists only while chrome is shown.
                .put("page_changed", if (before.isNull("counter") || after.isNull("counter"))
                    "UNKNOWN: chrome hidden" else before.opt("counter") != after.opt("counter"))
                .put("chrome_changed", before.getBoolean("chrome") != after.getBoolean("chrome"))
                .put("seek_changed", before.opt("seek_fraction") != after.opt("seek_fraction"))
                .put("playing_changed", before.getBoolean("video_playing") != after.getBoolean("video_playing"))
                .put("filmstrip_scrolled", before.opt("thumbnail0_left_px") != after.opt("thumbnail0_left_px"))
                .put("elapsed_ms", SystemClock.uptimeMillis() - started)
            probes.put(outcome)
            Log.i(G142_TAG, "G142 probe ${outcome.toString()}")
        }

        private fun signature(s: JSONObject) = listOf("system_back", "viewer_back", "counter",
            "chrome", "seek_fraction", "video_playing", "thumbnail0_left_px").map { s.opt(it) }

        private fun state(): JSONObject {
            val seek = compose.onAllNodesWithTag("atlas-video-seek", useUnmergedTree = true)
                .fetchSemanticsNodes(atLeastOneRootRequired = false).firstOrNull()
                ?.config?.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.current
            val playing = compose.exists(hasContentDescription("Pause video"))
            val strip = bounds("atlas-thumbnail-0")
            return JSONObject()
                .put("system_back", counters.systemBack)
                .put("viewer_back", counters.viewerBack)
                .put("counter", compose.onAllNodesWithTag("atlas-photo-counter", useUnmergedTree = true)
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).firstOrNull()
                    ?.config?.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
                    ?: JSONObject.NULL)
                .put("chrome", compose.exists(hasTestTag("atlas-filmstrip")) &&
                    compose.exists(hasTestTag("atlas-back")))
                .put("seek_fraction", seek?.toDouble() ?: JSONObject.NULL)
                .put("video_playing", playing)
                .put("thumbnail0_left_px", strip?.left ?: JSONObject.NULL)
                .put("viewer_present", compose.exists(hasTestTag("atlas-viewer")))
        }

        private fun screenshot(key: String): ByteArray {
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            var png: ByteArray? = null
            var problem = "no capture"
            compose.awaitThat("a whole, non-blank screenshot for $key", 10_000L) {
                val bitmap = automation.takeScreenshot() ?: return@awaitThat false
                try {
                    problem = uiEvidenceProblem(bitmap) ?: ""
                    if (problem.isEmpty()) {
                        val copy = if (bitmap.config == Bitmap.Config.HARDWARE)
                            bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
                        png = ByteArrayOutputStream().also {
                            check(copy.compress(Bitmap.CompressFormat.PNG, 100, it))
                        }.toByteArray()
                        if (copy !== bitmap) copy.recycle()
                    }
                } finally {
                    bitmap.recycle()
                }
                png != null
            }
            return requireNotNull(png) { "Screenshot for $key: $problem" }
        }

        fun publish(unknown: List<String>) {
            val report = JSONObject()
                .put("schema", "atlas-viewer-142-gesture/1")
                .put("issue", 833)
                .put("host", "GitHub-hosted API ${Build.VERSION.SDK_INT} x86_64 pixel_6 AVD with " +
                    "S24DisplayRule (1080x2340, 450 dpi); NOT a physical Galaxy S24+")
                .put("subject", "CURRENT viewer baseline (GalleryViewer on main), not the #142 target")
                .put("original_navigation", nav.originalSummary())
                .put("frames", frames)
                .put("probes", probes)
                .put("UNKNOWN", JSONArray(unknown))
            emit("viewer-142-gesture.json", (report.toString(2) + "\n").toByteArray(Charsets.UTF_8))
        }

        private fun emit(name: String, bytes: ByteArray) = emitG142File(name, bytes)
    }

    /** Edge swipe starts this many px from the screen edge. */
    private companion object {
        const val EDGE_PX = 2
        const val PROBE_REPEATS = 2
        const val EFFECT_WAIT_MS = 2_500L

        fun swipe(x1: Int, y1: Int, x2: Int, y2: Int) {
            testShell("input swipe $x1 $y1 $x2 $y2 250")
        }

        fun tapAt(x: Int, y: Int) {
            testShell("input tap $x $y")
        }
    }

    /** Same bounded local H.264 fixture as ViewerVideoChromeJourneyTest (red, then blue). */
    private fun encodeRedBlueVideo(file: File) {
        val width = 128
        val height = 96
        val fps = 12
        val frameCount = 48
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 300_000)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var encoderStarted = false
        var muxerStarted = false
        var track = -1
        val info = MediaCodec.BufferInfo()
        fun drain(expectEnd: Boolean) {
            var attempts = 0
            while (attempts++ < 500) {
                when (val index = encoder.dequeueOutputBuffer(info, 20_000L)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> if (!expectEnd) return
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!muxerStarted)
                        track = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    else -> if (index >= 0) {
                        val encoded = requireNotNull(encoder.getOutputBuffer(index))
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0) {
                            check(muxerStarted && track >= 0)
                            encoded.position(info.offset)
                            encoded.limit(info.offset + info.size)
                            muxer.writeSampleData(track, encoded, info)
                        }
                        val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        encoder.releaseOutputBuffer(index, false)
                        if (end) return
                    }
                }
            }
            error("Synthetic video encoder did not reach EOS")
        }
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            encoderStarted = true
            for (frame in 0 until frameCount) {
                var slot = encoder.dequeueInputBuffer(100_000L)
                while (slot < 0) {
                    drain(false)
                    slot = encoder.dequeueInputBuffer(100_000L)
                }
                requireNotNull(encoder.getInputImage(slot)).use { fillColour(it, frame < frameCount / 2) }
                encoder.queueInputBuffer(slot, 0, width * height * 3 / 2,
                    frame.toLong() * 1_000_000L / fps, 0)
                drain(false)
            }
            var eos = encoder.dequeueInputBuffer(100_000L)
            while (eos < 0) {
                drain(false)
                eos = encoder.dequeueInputBuffer(100_000L)
            }
            encoder.queueInputBuffer(eos, 0, 0, frameCount.toLong() * 1_000_000L / fps,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
        } finally {
            if (muxerStarted) muxer.stop()
            muxer.release()
            if (encoderStarted) encoder.stop()
            encoder.release()
        }
    }

    private fun fillColour(image: Image, red: Boolean) {
        val values = if (red) listOf(76, 85, 255) else listOf(29, 255, 107)
        image.planes.forEachIndexed { index, plane ->
            val columns = if (index == 0) image.width else image.width / 2
            val rows = if (index == 0) image.height else image.height / 2
            for (row in 0 until rows) for (column in 0 until columns) {
                plane.buffer.put(row * plane.rowStride + column * plane.pixelStride,
                    values[index].toByte())
            }
        }
    }
}

private const val G142_TAG = "ATLAS_TESTKIT"
private const val G142_CLASS = "nl.atlas.gallery.Viewer142GestureNavigationEvidenceTest"
private const val GESTURAL = "com.android.internal.systemui.navbar.gestural"

private fun <T> onMain(read: () -> T): T {
    var out: Result<T>? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync { out = runCatching(read) }
    return out!!.getOrThrow()
}

/**
 * Window settle that does not end the run. With gesture navigation and the
 * viewer's chrome hidden (system bars hidden), awaitWindowSettled timed out
 * on both emulators in run 44867998376 although the screen was still; probes
 * then rely on awaitScreenStill. Captures keep the strict settle.
 */
private fun settleQuietly(compose: ComposeTestRule, label: String) {
    runCatching { compose.awaitWindowSettled(5_000L) }
        .onFailure { Log.w(G142_TAG, "G142 window not settled after $label; using screen stillness") }
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/**
 * Writes [bytes] to logcat as `G142 begin <name> <size> <sha256> <chunks>`,
 * one `G142 chunk <name> <i> <base64>` line per 3000 characters, then
 * `G142 end <name>`. decode_log.py rebuilds the file and checks size and hash.
 */
private fun emitG142File(name: String, bytes: ByteArray) {
    val text = Base64.encodeToString(bytes, Base64.NO_WRAP)
    val chunks = text.chunked(3000)
    Log.i(G142_TAG, "G142 begin $name ${bytes.size} ${sha256(bytes)} ${chunks.size}")
    chunks.forEachIndexed { i, c -> Log.i(G142_TAG, "G142 chunk $name $i $c") }
    Log.i(G142_TAG, "G142 end $name")
}

/**
 * Skips the test unless this run names only this class, as the opt-in stress
 * lane does. A Gradle connectedAndroidTest run (shared shards) passes no
 * `class` argument, so the overlay is never switched there.
 */
internal class IsolatedRunOnlyRule : TestRule {
    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            val target = InstrumentationRegistry.getArguments().getString("class").orEmpty()
            val isolated = target.isNotEmpty() &&
                target.split(',').all { it.substringBefore('#') == G142_CLASS }
            assumeTrue("Runs only as its own instrumentation run (-e class $G142_CLASS); " +
                "see docs/design/viewer-142-gesture-proof/README.md", isolated)
            base.evaluate()
        }
    }
}

/**
 * Switches the device to gesture navigation before the Activity starts and
 * puts back the exact navbar overlay set it found, then verifies that the
 * navigation mode and overlay set match the originals. A failed restore fails
 * the test (added to any earlier failure), so a leak is never silent.
 */
internal class GestureNavigationOverlayRule : TestRule {
    private var originalMode = ""
    private var originalOverlays: List<String> = emptyList()

    fun navbarOverlayLines(): List<String> =
        testShell("cmd overlay list").lines().map { it.trim() }.filter { "navbar" in it }

    private fun enabledNavbar(lines: List<String> = navbarOverlayLines()) =
        lines.filter { it.startsWith("[x]") }.map { it.removePrefix("[x]").trim() }

    fun originalSummary(): JSONObject = JSONObject().put("navigation_mode", originalMode)
        .put("enabled_navbar_overlays", JSONArray(originalOverlays))

    private fun awaitMode(expected: String, label: String) {
        val deadline = SystemClock.uptimeMillis() + 20_000L
        var mode = ""
        while (SystemClock.uptimeMillis() < deadline) {
            mode = testShell("settings get secure navigation_mode")
            if (mode == expected) return
        }
        throw AssertionError("$label: navigation_mode stayed '$mode', wanted '$expected'; " +
            "navbar overlays ${navbarOverlayLines()}")
    }

    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            originalMode = testShell("settings get secure navigation_mode")
            originalOverlays = enabledNavbar()
            Log.i(G142_TAG, "G142 original navigation_mode=$originalMode overlays=$originalOverlays")
            var failure: Throwable? = null
            try {
                testShell("cmd overlay enable-exclusive --category $GESTURAL")
                awaitMode("2", "Switch to gesture navigation")
                base.evaluate()
            } catch (t: Throwable) {
                failure = t
            } finally {
                val restore = runCatching {
                    if (originalOverlays.isEmpty()) testShell("cmd overlay disable $GESTURAL")
                    else originalOverlays.forEach { testShell("cmd overlay enable-exclusive --category $it") }
                    awaitMode(originalMode, "Restore navigation mode")
                    val now = enabledNavbar()
                    check(now.toSet() == originalOverlays.toSet()) {
                        "Navbar overlays not restored: now $now, originally $originalOverlays"
                    }
                    Log.i(G142_TAG, "G142 restored navigation_mode=$originalMode overlays=$now")
                }.exceptionOrNull()
                if (restore != null) {
                    Log.e(G142_TAG, "G142 RESTORE FAILED ${restore.message}")
                    if (failure != null) failure.addSuppressed(restore) else failure = restore
                }
            }
            failure?.let { throw it }
        }
    }
}
