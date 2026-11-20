package nl.atlas.gallery

import android.graphics.Bitmap
import android.graphics.Color
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.view.Window
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import nl.atlas.gallery.featuredesign.AtlasGalleryTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File

/**
 * #18. Entirely invented, locally encoded red/blue MP4 and PNG. Never reads
 * personal media or adds video to MediaStore. Captures Samsung-size frames
 * from both themes, including the frame shown while a seek thumb is held.
 */
@RunWith(AndroidJUnit4::class)
class ViewerVideoChromeJourneyTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(compose)

    @Test fun photoAndVideoShareFilmstripAndVideoHasPlayPauseSeekPreview() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val video = File.createTempFile("viewer-video-18-", ".mp4", context.cacheDir)
        val photo = File.createTempFile("viewer-photo-18-", ".png", context.cacheDir)
        try {
            encodeSolidColourVideo(video)
            Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888).also { bitmap ->
                bitmap.eraseColor(Color.CYAN)
                photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            val items = listOf(
                MediaRecord(18L, Uri.fromFile(video).toString(), "Invented red-blue clip",
                    "DCIM/ATLAS-SYNTHETIC/", video.length(), 0L, true),
                MediaRecord(19L, Uri.fromFile(photo).toString(), "Invented cyan still",
                    "DCIM/ATLAS-SYNTHETIC/", photo.length(), 1L, false)
            )
            val dark = mutableStateOf(true)
            var window: Window? = null
            var insetSample: Viewer609InsetSample? = null
            val recorder = Viewer609Recorder(compose) { window }
            compose.setContent {
                val view = LocalView.current
                SideEffect { window = view.context.testActivityForVideoEvidence()?.window }
                AtlasGalleryTheme(dark = dark.value) {
                    Box(Modifier.fillMaxSize()) {
                        Viewer609InsetProbe { insetSample = it }
                        GalleryViewer(items, items[0].uri, onBack = {})
                    }
                }
            }
            awaitPlayer()
            compose.waitUntil(20_000L) {
                runCatching {
                    compose.onNodeWithTag("atlas-video-seek").assertIsEnabled()
                }.isSuccess
            }
            val originalStripY = compose.onNodeWithTag("atlas-filmstrip")
                .fetchSemanticsNode().boundsInRoot.center.y
            for (theme in listOf("dark", "light")) {
                compose.runOnIdle { dark.value = theme == "dark" }
                compose.onNodeWithTag("atlas-video-play-pause")
                    .assertHasClickAction()
                    .assert(hasContentDescription("Play video"))
                compose.onNodeWithTag("atlas-video-seek").assertIsDisplayed()
                saveUiEvidence("viewer-video-$theme-still.png")
                recorder.capture("video/short/$theme/portrait/still",
                    "viewer-video-$theme-still.png", insetSample)
                val videoFocus = hasTestTag("atlas-video-surface") and hasClickAction()
                compose.onNode(videoFocus).performSemanticsAction(
                    androidx.compose.ui.semantics.SemanticsActions.OnClick)
                compose.onNodeWithTag("atlas-filmstrip").assertDoesNotExist()
                saveUiEvidence("viewer-609-video-$theme-portrait-hidden.png")
                recorder.capture("video/short/$theme/portrait/hidden",
                    "viewer-609-video-$theme-portrait-hidden.png", insetSample)
                compose.onNode(videoFocus).performSemanticsAction(
                    androidx.compose.ui.semantics.SemanticsActions.OnClick)
                compose.onNodeWithTag("atlas-filmstrip").assertExists()

                try {
                    rotateViewerEvidenceLandscape()
                    compose.waitUntil(10_000L) {
                        compose.onNodeWithTag("atlas-viewer").fetchSemanticsNode().boundsInRoot
                            .let { it.width > it.height }
                    }
                    assertVideoControlsClearLandscapeCutout(requireNotNull(window))
                    saveUiEvidence("viewer-142-video-landscape-cutout-$theme.png")
                    recorder.capture("video/short/$theme/landscape/shown",
                        "viewer-142-video-landscape-cutout-$theme.png", insetSample)
                } finally {
                    restoreViewerEvidencePortrait()
                }
                compose.waitUntil(10_000L) {
                    compose.onNodeWithTag("atlas-viewer").fetchSemanticsNode().boundsInRoot
                        .let { it.height > it.width }
                }

                compose.onNodeWithTag("atlas-video-play-pause").performClick()
                compose.waitUntil(10_000L) {
                    compose.onAllNodes(hasContentDescription("Pause video"))
                        .fetchSemanticsNodes().isNotEmpty()
                }
                saveUiEvidence("viewer-video-$theme-playing.png")
                recorder.capture("video/short/$theme/portrait/playing",
                    "viewer-video-$theme-playing.png", insetSample)

                compose.onNodeWithTag("atlas-video-play-pause").performClick()
                compose.waitUntil(10_000L) {
                    compose.onAllNodes(hasContentDescription("Play video"))
                        .fetchSemanticsNodes().isNotEmpty()
                }
                saveUiEvidence("viewer-video-$theme-paused.png")
                recorder.capture("video/short/$theme/portrait/paused",
                    "viewer-video-$theme-paused.png", insetSample)

                // Hold the slider thumb long enough for an actual extracted
                // video frame, not a generic image or the photo filmstrip.
                compose.onNodeWithTag("atlas-video-seek").performTouchInput {
                    down(Offset(width * 0.20f, height * 0.5f))
                    moveTo(Offset(width * 0.78f, height * 0.5f))
                }
                compose.waitUntil(15_000L) {
                    // Wait for extracted pixels, not just the preview shell.
                    compose.onAllNodesWithTag("atlas-video-seek-frame")
                        .fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag("atlas-video-seek-preview").assertIsDisplayed()
                compose.onNodeWithTag("atlas-video-seek-frame").assertIsDisplayed()
                saveUiEvidence("viewer-video-$theme-seek.png")
                recorder.capture("video/short/$theme/portrait/seek-preview",
                    "viewer-video-$theme-seek.png", insetSample)
                compose.onNodeWithTag("atlas-video-seek").performTouchInput { up() }
                compose.waitUntil(10_000L) {
                    compose.onAllNodes(hasContentDescription("Play video"))
                        .fetchSemanticsNodes().isNotEmpty()
                }
                // Actual Material3 slider accessibility actions, not a mock
                // progress bar. Capture endpoints even if the renderer cannot
                // provide a distinct frame at exactly 0 or duration.
                for ((endpoint, fraction) in listOf("start" to 0f, "end" to 1f)) {
                    compose.onNodeWithTag("atlas-video-seek")
                        .performSemanticsAction(
                            androidx.compose.ui.semantics.SemanticsActions.SetProgress) {
                            it(fraction)
                        }
                    compose.waitForIdle()
                    val file = "viewer-609-video-$theme-seek-$endpoint.png"
                    saveUiEvidence(file)
                    recorder.capture("video/short/$theme/portrait/seek-$endpoint",
                        file, insetSample)
                }
                // A Material3 SetProgress action can leave the synthetic player
                // seeking or positioned at the end of this four-second clip.
                // Remount between themes to isolate the second theme's playback
                // checks from the first theme's endpoint evidence.
                if (theme == "dark") {
                    compose.onNodeWithTag("atlas-thumbnail-1").performClick()
                    compose.waitUntil(10_000L) {
                        compose.onAllNodesWithTag("atlas-viewer-video-player")
                            .fetchSemanticsNodes().isEmpty()
                    }
                    compose.onNodeWithTag("atlas-thumbnail-0").performClick()
                    awaitPlayer()
                    compose.waitUntil(20_000L) {
                        runCatching {
                            compose.onNodeWithTag("atlas-video-seek").assertIsEnabled()
                        }.isSuccess
                    }
                }
            }

            // Delayed mount and teardown still work when the pager changes.
            compose.onNodeWithTag("atlas-thumbnail-1").performClick()
            compose.waitUntil(10_000L) {
                compose.onAllNodesWithTag("atlas-viewer-video-player")
                    .fetchSemanticsNodes().isEmpty()
            }
            val photoStripY = compose.onNodeWithTag("atlas-filmstrip")
                .fetchSemanticsNode().boundsInRoot.center.y
            assertTrue("Filmstrip moved between photo/video", kotlin.math.abs(originalStripY - photoStripY) < 4f)
            compose.onNodeWithTag("atlas-thumbnail-0").performClick()
            awaitPlayer()
            compose.waitUntil(10_000L) {
                compose.onAllNodes(hasContentDescription("Play video"))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            recorder.publish("viewer-609-video.json", listOf(
                "Long-duration video not produced by this four-second H.264 fixture: NOT_CAPTURED",
                "Codec unavailable/error video UI: NOT_CAPTURED (requires an independent codec fixture)",
                "Media3 AndroidView gesture hit rectangles: not exposed by Compose semantics",
                "System TalkBack spoken focus order: NOT_CAPTURED; descriptions are measured"
            ))
        } finally {
            video.delete()
            photo.delete()
        }
    }

    private fun cutoutInsets(window: Window) = ViewCompat.getRootWindowInsets(window.decorView)
        ?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.displayCutout())

    private fun assertVideoControlsClearLandscapeCutout(window: Window) {
        // The window turns landscape before the rotated insets are dispatched: a single
        // read right after width > height saw Insets{0,0,0,0} (run 44287993296). Wait
        // for the side cutout, then for Compose to lay out with it.
        runCatching {
            compose.waitUntil(10_000L) {
                cutoutInsets(window)?.let { it.left > 0 || it.right > 0 } == true
            }
        }
        compose.waitForIdle()
        val insets = cutoutInsets(window)
            ?: error("Landscape display-cutout insets unavailable")
        assertTrue("Pixel emulator profile must expose a landscape cutout: $insets",
            insets.left > 0 || insets.right > 0)
        val controls = compose.onNodeWithTag("atlas-video-controls")
            .fetchSemanticsNode().boundsInWindow
        assertTrue("Video controls overlap left cutout: $controls / $insets",
            controls.left >= insets.left)
        assertTrue("Video controls overlap right cutout: $controls / $insets",
            controls.right <= window.decorView.width - insets.right)
    }

    @Test fun disposingPlayerClearsLiveReaderAndCheckpointsPosition() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val video = File.createTempFile("viewer-release-18-", ".mp4", context.cacheDir)
        try {
            encodeSolidColourVideo(video)
            val visible = mutableStateOf(true)
            var live: (() -> Long)? = null
            var checkpoint: Long? = null
            compose.setContent {
                if (visible.value) VideoPage(
                    uri = Uri.fromFile(video).toString(),
                    onLivePosition = { live = it },
                    onSavePosition = { checkpoint = it }
                )
            }
            compose.runOnIdle { assertNotNull("player mounted", live) }
            compose.runOnIdle { visible.value = false }
            compose.runOnIdle {
                assertNull("reader must be removed on disposal", live)
                assertNotNull("checkpoint must be stored before release", checkpoint)
                assertTrue("checkpoint is nonnegative", checkpoint!! >= 0L)
            }
        } finally {
            video.delete()
        }
    }

    private fun awaitPlayer() {
        compose.waitUntil(20_000L) {
            compose.onAllNodesWithTag("atlas-viewer-video-player")
                .fetchSemanticsNodes().isNotEmpty() &&
            compose.onAllNodesWithTag("atlas-video-play-pause")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Same bounded local H.264 fixture strategy as VideoKeyframeSamplerTest. */
    private fun encodeSolidColourVideo(file: File) {
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

private tailrec fun android.content.Context.testActivityForVideoEvidence(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.testActivityForVideoEvidence()
    else -> null
}
