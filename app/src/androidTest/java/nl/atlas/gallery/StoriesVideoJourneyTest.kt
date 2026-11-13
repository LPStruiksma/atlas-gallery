package nl.atlas.gallery

import android.content.ContentValues
import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import nl.atlas.gallery.featuredesign.AtlasGalleryTheme
import nl.atlas.gallery.featurestories.AtlasStoriesScreen
import nl.atlas.gallery.featurestories.StoryCard
import nl.atlas.gallery.featurestories.StoryDeck
import nl.atlas.gallery.featurestories.StoryKind
import nl.atlas.gallery.featurestories.StoryMedia
import nl.atlas.gallery.featurestories.StoryPlaybackState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * #21: real Media3 playback of app-owned synthetic photo → H.264 video → photo.
 * No personal media, network source or exported fixture. Every generated row is
 * removed in @After. Real EOS, not a test-clock simulation, advances the deck.
 */
@RunWith(AndroidJUnit4::class)
class StoriesVideoJourneyTest {
    private val compose = createComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule()).around(compose)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val created = mutableListOf<Uri>()
    private val clip = File(context.cacheDir, "stories-21-" + UUID.randomUUID() + ".mp4")
    private lateinit var first: Uri
    private lateinit var video: Uri
    private lateinit var last: Uri
    private var grant: String? by mutableStateOf(GRANT)
    private val playback = StoryPlaybackState()
    private var opened: String? = null
    private var backs = 0

    @Before fun createMedia() {
        first = publishPhoto("stories-first.png", 0xff24b6c9.toInt())
        encodeVideo(clip)
        video = publishVideo(clip)
        last = publishPhoto("stories-last.png", 0xfff9b24b.toInt())
    }

    @After fun removeMedia() {
        created.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
        clip.delete()
    }

    @Test fun lightPhotoVideoPhotoPlaysInlineAndCompletes() = journey("light", false)

    @Test fun darkPhotoVideoPhotoPlaysInlineAndCompletes() = journey("dark", true)

    private fun journey(mode: String, dark: Boolean) {
        mount(dark)
        compose.onNodeWithTag("atlas-story-manual").performClick()
        compose.onNodeWithTag("atlas-story-pause").performClick()
        compose.onNodeWithContentDescription("Moment 1 of 3").assertExists()
        saveUiEvidence("stories-21-photo-" + mode + ".png")

        compose.onNodeWithTag("atlas-story-next").performClick()
        await(hasTestTag("atlas-story-video-first-frame"))
        compose.onNodeWithContentDescription("Resume video").assertExists()
        compose.onNodeWithTag("atlas-story-player").assertExists()
        saveUiEvidence("stories-21-poster-" + mode + ".png")

        compose.onNodeWithTag("atlas-story-pause").performClick()
        await(hasContentDescription("Pause video"))
        compose.onNodeWithTag("atlas-story-video-surface").assertExists()
        saveUiEvidence("stories-21-playing-" + mode + ".png")

        // Decoder EOS is an actual asynchronous Media3 event, not a fake callback.
        await(hasContentDescription("Moment 3 of 3"), timeout = 20_000L)
        compose.onNodeWithTag("atlas-story-video-surface").assertDoesNotExist()
        saveUiEvidence("stories-21-ended-" + mode + ".png")
    }

    @Test fun navigationAndGrantLossRemoveTheVideoStage() {
        mount(dark = false)
        compose.onNodeWithTag("atlas-story-manual").performClick()
        compose.onNodeWithTag("atlas-story-pause").performClick()
        compose.onNodeWithTag("atlas-story-next").performClick()
        await(hasTestTag("atlas-story-video-surface"))

        // Back one moment never jumps out to a separate Viewer.
        compose.onNodeWithTag("atlas-story-previous").performClick()
        compose.onNodeWithContentDescription("Moment 1 of 3").assertExists()
        compose.onNodeWithTag("atlas-story-next").performClick()
        await(hasTestTag("atlas-story-video-surface"))

        // Optional explicit Viewer handoff remains, but pauses the story.
        compose.onNodeWithTag("atlas-story-open-gallery").performClick()
        assertEquals(video.toString(), opened)
        compose.onNodeWithContentDescription("Resume video").assertExists()

        // A close removes the player and returns to the same story grid.
        compose.onNodeWithTag("atlas-story-close").performClick()
        compose.onNodeWithTag("atlas-stories-grid").assertExists()
        compose.onNodeWithTag("atlas-story-video-surface").assertDoesNotExist()

        compose.onNodeWithTag("atlas-story-manual").performClick()
        compose.onNodeWithTag("atlas-story-pause").performClick()
        compose.onNodeWithTag("atlas-story-next").performClick()
        await(hasTestTag("atlas-story-video-surface"))
        compose.runOnIdle { grant = null } // simulated revoked permission/publication
        compose.onNodeWithTag("atlas-story-video-surface").assertDoesNotExist()
        compose.onNodeWithTag("atlas-stories-empty").assertExists()
        compose.onNodeWithTag("atlas-stories-back").performClick()
        assertEquals(1, backs)
    }

    private fun mount(dark: Boolean) {
        val deck = StoryDeck(GRANT, REVISION, listOf(StoryCard(
            key = "manual:video21", title = "Autumn moments", caption = "Synthetic recap",
            kind = StoryKind.MANUAL,
            items = listOf(
                StoryMedia(first.toString(), 1L),
                StoryMedia(video.toString(), 2L, isVideo = true),
                StoryMedia(last.toString(), 3L)
            )
        )))
        compose.setContent {
            AtlasGalleryTheme(dark = dark) {
                AtlasStoriesScreen(
                    deck = deck, currentGrantEpoch = grant,
                    currentCatalogRevision = REVISION,
                    playback = playback,
                    onBack = { backs++ },
                    onOpenMedia = { opened = it }
                )
            }
        }
    }

    private fun await(matcher: androidx.compose.ui.test.SemanticsMatcher, timeout: Long = 10_000L) {
        compose.waitUntil(timeout) {
            compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun publishPhoto(name: String, color: Int): Uri {
        val uri = requireNotNull(context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/ATLAS-Stories21/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }))
        created += uri
        val bitmap = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            requireNotNull(context.contentResolver.openOutputStream(uri)).use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
        publish(uri)
        return uri
    }

    private fun publishVideo(file: File): Uri {
        val uri = requireNotNull(context.contentResolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "stories-21-synthetic.mp4")
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/ATLAS-Stories21/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }))
        created += uri
        requireNotNull(context.contentResolver.openOutputStream(uri)).use { out ->
            file.inputStream().use { it.copyTo(out) }
        }
        publish(uri)
        return uri
    }

    private fun publish(uri: Uri) {
        check(context.contentResolver.update(uri, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }, null, null) == 1)
    }

    /**
     * 3 s offline H.264 clip at 10 fps; first half cyan, second half magenta.
     * Uses the same YUV420Flexible emulator encoder contract as VS-V2 tests.
     */
    private fun encodeVideo(file: File) {
        val frames = 30
        val fps = 10
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, SIDE, SIDE)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 400_000)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        var encoderStarted = false
        var track = -1
        val info = MediaCodec.BufferInfo()
        fun drain(ending: Boolean) {
            repeat(1_000) {
                val output = encoder.dequeueOutputBuffer(info, 20_000L)
                if (output == MediaCodec.INFO_TRY_AGAIN_LATER && !ending) return
                if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    check(!muxerStarted)
                    track = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    muxerStarted = true
                } else if (output >= 0) {
                    val buffer = requireNotNull(encoder.getOutputBuffer(output))
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0) {
                        check(muxerStarted)
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buffer, info)
                    }
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    encoder.releaseOutputBuffer(output, false)
                    if (eos) return
                }
            }
            error("Synthetic video encoder did not reach EOS")
        }
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            encoderStarted = true
            for (frame in 0 until frames) {
                var input = encoder.dequeueInputBuffer(100_000L)
                while (input < 0) {
                    drain(false)
                    input = encoder.dequeueInputBuffer(100_000L)
                }
                requireNotNull(encoder.getInputImage(input)).use { fillYuv(it, frame < frames / 2) }
                encoder.queueInputBuffer(input, 0, SIDE * SIDE * 3 / 2,
                    frame.toLong() * 1_000_000L / fps, 0)
                drain(false)
            }
            var input = encoder.dequeueInputBuffer(100_000L)
            while (input < 0) {
                drain(false)
                input = encoder.dequeueInputBuffer(100_000L)
            }
            encoder.queueInputBuffer(input, 0, 0, frames.toLong() * 1_000_000L / fps,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
        } finally {
            if (muxerStarted) muxer.stop()
            muxer.release()
            if (encoderStarted) encoder.stop()
            encoder.release()
        }
    }

    private fun fillYuv(image: Image, cyan: Boolean) {
        val value = if (cyan) byteArrayOf(170.toByte(), 128.toByte(), 40.toByte())
            else byteArrayOf(106.toByte(), 202.toByte(), 222.toByte())
        image.planes.forEachIndexed { index, plane ->
            val side = if (index == 0) SIDE else SIDE / 2
            for (row in 0 until side) for (col in 0 until side) {
                plane.buffer.put(row * plane.rowStride + col * plane.pixelStride, value[index])
            }
        }
    }

    companion object {
        private const val GRANT = "grant21"
        private const val REVISION = "revision21"
        private const val SIDE = 240
    }
}
