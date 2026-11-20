package nl.atlas.gallery.featureintents

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.platform.app.InstrumentationRegistry
import nl.atlas.gallery.S24DisplayRule
import nl.atlas.gallery.testkit.DisplayPrefsRule
import nl.atlas.gallery.testkit.awaitThat
import nl.atlas.gallery.testkit.exists
import nl.atlas.gallery.testkit.tap
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

/**
 * BENCH-2 (#573) A2: neighbours for an ordinary open of a MediaStore row ATLAS can
 * read itself, none for secure review, and a video handed over by another app.
 * Media are three generated PNGs in their own folder and one generated clip.
 */
@RunWith(Parameterized::class)
class ExternalNeighboursJourneyTest(private val dark: Boolean) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "dark={0}")
        fun themes(): List<Array<Any>> = listOf(arrayOf(false), arrayOf(true))
    }

    private val compose = createEmptyComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule())
        .around(DisplayPrefsRule(dark = dark)).around(compose)
    private val fixture = ExternalIntentFixture()
    private val folder = NeighbourFolder()
    private val theme get() = if (dark) "dark" else "light"

    @Before fun setUp() = folder.create()
    @After fun tearDown() {
        fixture.cleanUp()
        folder.delete()
    }

    @Test fun ordinaryViewSwipesThroughTheFolderAtlasCanRead() {
        val scenario = fixture.launch(ExternalIntentParser.ACTION_VIEW, folder.middle, "image/png")
        compose.awaitViewer()
        compose.awaitThat("the folder filmstrip") { compose.exists(hasTestTag("atlas-filmstrip")) }
        compose.awaitCurrent("Photo 2 of 3: ${NeighbourFolder.MIDDLE}")
        compose.capture("external-573-neighbours-$theme")
        compose.onNodeWithTag("atlas-main-pager").performTouchInput { swipeLeft() }
        compose.awaitCurrent("Photo 3 of 3: ${NeighbourFolder.OLDEST}")
        // Neighbours are still read-only: no library or write action appears.
        for (absent in listOf("atlas-viewer-favourite", "atlas-share-media", "atlas-viewer-trash",
                "atlas-edit-photo", "atlas-viewer-more", "atlas-nav-pill")) {
            assertFalse("$absent must not be shown", compose.exists(hasTestTag(absent)))
        }
        compose.tap(hasTestTag("atlas-back"))
        compose.awaitDestroyed(scenario)
    }

    @Test fun secureReviewOfTheSameRowHasNoNeighbours() {
        val scenario = fixture.launch(ExternalIntentParser.ACTION_REVIEW_SECURE, folder.middle, "image/png")
        compose.awaitViewer()
        compose.assertReadOnlyChrome(secure = true)
        assertFalse(compose.exists(hasTestTag("atlas-filmstrip")))
        scenario.close()
    }

    @Test fun aVideoFromAnotherAppPlaysInTheReadOnlyViewer() {
        val clip = folder.video()
        val scenario = fixture.launch(ExternalIntentParser.ACTION_VIEW, clip, "video/mp4")
        compose.awaitViewer()
        compose.awaitThat("the video page") {
            compose.exists(hasContentDescription("Video viewer: ${NeighbourFolder.CLIP}"))
        }
        compose.assertReadOnlyChrome(secure = false)
        compose.capture("external-573-video-$theme")
        scenario.close()
    }
}

private fun androidx.compose.ui.test.junit4.ComposeTestRule.awaitCurrent(description: String) =
    awaitThat("filmstrip current item $description") {
        exists(hasContentDescription(description) and
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Currently displayed"))
    }

/** Three generated PNGs in one folder (newest first: A, B, C) and one generated clip. */
private class NeighbourFolder {
    companion object {
        const val NEWEST = "ATLAS_573_NB_A.png"
        const val MIDDLE = "ATLAS_573_NB_B.png"
        const val OLDEST = "ATLAS_573_NB_C.png"
        const val CLIP = "ATLAS_573_CLIP.mp4"
    }

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val created = mutableListOf<Uri>()
    private var middleUri: Uri? = null
    val middle: String get() = requireNotNull(middleUri).toString()

    fun create() {
        // Neighbours need ATLAS's OWN read grant, never the caller's item grant.
        for (permission in listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        }
        // Inserted oldest first, so id order and date order agree.
        png(OLDEST, AndroidColor.rgb(52, 120, 92), takenAt = 1_700_000_000_000L)
        middleUri = png(MIDDLE, AndroidColor.rgb(38, 97, 156), takenAt = 1_700_000_100_000L)
        png(NEWEST, AndroidColor.rgb(168, 84, 64), takenAt = 1_700_000_200_000L)
    }

    fun delete() {
        created.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
        created.clear()
    }

    private fun png(name: String, colour: Int, takenAt: Long): Uri {
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ATLAS-573-Neighbours/")
                put(MediaStore.Images.Media.DATE_TAKEN, takenAt)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            })).also { created += it }
        resolver.openOutputStream(uri)!!.use { out ->
            val bitmap = Bitmap.createBitmap(960, 720, Bitmap.Config.ARGB_8888)
            try {
                val canvas = android.graphics.Canvas(bitmap)
                canvas.drawColor(colour)
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                paint.color = AndroidColor.rgb(232, 226, 210)
                canvas.drawRect(0f, 480f, 960f, 720f, paint)
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
            } finally {
                bitmap.recycle()
            }
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }

    /** A 4 s generated H.264 clip (blue, then amber) published as a MediaStore video. */
    fun video(): String {
        val file = File(context.cacheDir, "atlas-573-clip.mp4")
        try {
            SyntheticClip.encode(file)
            val resolver = context.contentResolver
            val uri = requireNotNull(resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, CLIP)
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/ATLAS-573-Video/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                })).also { created += it }
            resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            check(resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
            return uri.toString()
        } finally {
            file.delete()
        }
    }
}

/** Same YUV420Flexible emulator encoder contract as the other synthetic video tests. */
private object SyntheticClip {
    private const val WIDTH = 128
    private const val HEIGHT = 96
    private const val FPS = 12
    private const val FRAMES = 48

    fun encode(file: File) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 300_000)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var startedEncoder = false
        var startedMuxer = false
        var track = -1
        val info = MediaCodec.BufferInfo()
        fun drain(expectEnd: Boolean) {
            var waits = 0
            while (waits++ < 500) {
                when (val index = encoder.dequeueOutputBuffer(info, 20_000L)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> if (!expectEnd) return
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!startedMuxer)
                        track = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        startedMuxer = true
                    }
                    else -> if (index >= 0) {
                        val encoded = requireNotNull(encoder.getOutputBuffer(index))
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0) {
                            check(startedMuxer && track >= 0)
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
            error("Synthetic encoder did not reach EOS")
        }
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            startedEncoder = true
            for (frame in 0 until FRAMES) {
                var slot = encoder.dequeueInputBuffer(100_000L)
                while (slot < 0) {
                    drain(expectEnd = false)
                    slot = encoder.dequeueInputBuffer(100_000L)
                }
                requireNotNull(encoder.getInputImage(slot)).use { fill(it, blue = frame < FRAMES / 2) }
                encoder.queueInputBuffer(slot, 0, WIDTH * HEIGHT * 3 / 2, frame.toLong() * 1_000_000L / FPS, 0)
                drain(expectEnd = false)
            }
            var eos = encoder.dequeueInputBuffer(100_000L)
            while (eos < 0) {
                drain(expectEnd = false)
                eos = encoder.dequeueInputBuffer(100_000L)
            }
            encoder.queueInputBuffer(eos, 0, 0, FRAMES.toLong() * 1_000_000L / FPS,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(expectEnd = true)
        } finally {
            if (startedMuxer) muxer.stop()
            muxer.release()
            if (startedEncoder) encoder.stop()
            encoder.release()
        }
    }

    private fun fill(image: Image, blue: Boolean) {
        // BT.601 YUV for a mid blue and a warm amber.
        val values = if (blue) listOf(70, 190, 100) else listOf(170, 70, 175)
        image.planes.forEachIndexed { index, plane ->
            val planeWidth = if (index == 0) image.width else image.width / 2
            val planeHeight = if (index == 0) image.height else image.height / 2
            val bytes = plane.buffer
            for (row in 0 until planeHeight) {
                for (col in 0 until planeWidth) {
                    bytes.put(row * plane.rowStride + col * plane.pixelStride, values[index].toByte())
                }
            }
        }
    }
}
