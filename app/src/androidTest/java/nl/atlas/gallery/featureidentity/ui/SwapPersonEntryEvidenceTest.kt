package nl.atlas.gallery.featureidentity.ui

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.atlas.gallery.MainActivity
import nl.atlas.gallery.S24DisplayRule
import nl.atlas.gallery.clearGalleryInventoryCacheForTest
import nl.atlas.gallery.saveUiEvidence
import nl.atlas.gallery.testkit.DisplayPrefsRule
import nl.atlas.gallery.testkit.awaitThat
import nl.atlas.gallery.testkit.exists
import nl.atlas.gallery.testkit.tap
import nl.atlas.gallery.waitForNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.File
import java.util.UUID

/*
 * IDS-11 (#478): "Swap person" entry points on the real MainActivity at S24+ size (1080×2340,
 * density 450), per design brief rev 2. Synthetic MediaStore fixtures only: plain-colour PNGs, a
 * static GIF and a short generated H.264 clip; no personal photo, no model, no network.
 * Renders are declared in scripts/ui-evidence.d/467-swap-person-entry.txt.
 */

@RunWith(AndroidJUnit4::class)
class SwapPersonEntryLight100EvidenceTest : SwapPersonEntryJourney(dark = false, fontScale = 1f)

@RunWith(AndroidJUnit4::class)
class SwapPersonEntryDark100EvidenceTest : SwapPersonEntryJourney(dark = true, fontScale = 1f)

@RunWith(AndroidJUnit4::class)
class SwapPersonEntryLight200EvidenceTest : SwapPersonEntryJourney(dark = false, fontScale = 2f)

abstract class SwapPersonEntryJourney(private val dark: Boolean, private val fontScale: Float) {
    private val fixtures = SwapEntryFixtures()
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule())
        .around(DisplayPrefsRule(dark, fontScale))
        .around(fixtures)
        .around(compose)

    private val suffix get() = "${if (dark) "dark" else "light"}-${(fontScale * 100).toInt()}"

    private fun shot(name: String) {
        compose.waitForIdle()
        saveUiEvidence("ids11-$name-$suffix.png")
    }

    private fun tile(uri: Uri) = hasTestTag("atlas-timeline-media-${ContentUris.parseId(uri)}")

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun select(vararg uris: Uri) {
        compose.waitForNode(tile(uris[0]))
        compose.onNode(tile(uris[0])).performTouchInput { longClick() }
        uris.drop(1).forEach { compose.tap(tile(it)) }
        compose.waitForNode(hasTestTag("atlas-selection-count") and hasText("${uris.size} selected"))
    }

    private fun openMore() {
        compose.tap(hasTestTag("atlas-selection-more"))
        // The menu is a popup window that attaches a moment later (rule 5).
        compose.waitForNode(hasTestTag("atlas-selection-more-menu"))
    }

    private fun clearSelection() {
        // First Back closes the popup, the second ends selection.
        back()
        compose.awaitThat("the More menu to close") { !compose.exists(hasTestTag("atlas-selection-more-menu")) }
        if (compose.exists(hasTestTag("atlas-selection-count"))) back()
        compose.waitForNode(hasTestTag("atlas-nav-photos"))
    }

    private fun assertAbsent(tag: String) {
        compose.waitForIdle()
        assertTrue("$tag must be absent", !compose.exists(hasTestTag(tag)))
    }

    /**
     * The shared guard itself says no for this item, so its row can never appear: the UI starts
     * hidden and only shows the row on a yes. No waiting for a time.
     */
    private fun assertGuardRefuses(uri: Uri) {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val header = resolver.openInputStream(uri)!!.use {
            SwapPersonMediaCheck.readUpTo(it, SwapPersonEligibility.HEADER_BYTES)
        }
        val video = resolver.getType(uri)?.startsWith("video/") == true
        assertFalse("the guard must refuse this item",
            SwapPersonEligibility.eligible(video, uri.scheme, uri.authority, resolver.getType(uri), header))
    }

    private fun node(tag: String): SemanticsNode = compose.onNodeWithTag(tag).fetchSemanticsNode()

    /** Screen-space rows: inside the measured safe insets, at least 48 dp tall, in this order. */
    private fun assertRows(vararg tags: String) {
        val activity = compose.activity
        val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)!!
            .getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val metrics = activity.resources.displayMetrics
        val minRow = 48f * metrics.density - 1f
        var lastTop = -1f
        for (tag in tags) {
            val n = node(tag)
            val top = n.positionOnScreen.y
            val bottom = top + n.size.height
            val left = n.positionOnScreen.x
            val right = left + n.size.width
            assertTrue("$tag below the status inset", top >= insets.top - 1f)
            assertTrue("$tag above the navigation inset", bottom <= metrics.heightPixels - insets.bottom + 1f)
            assertTrue("$tag inside the side insets", left >= insets.left - 1f && right <= metrics.widthPixels - insets.right + 1f)
            assertTrue("$tag at least 48 dp tall", n.size.height >= minRow)
            assertTrue("$tag must come after the row above it", top > lastTop)
            lastTop = top
        }
    }

    private fun counter(): String? = compose.onAllNodes(hasTestTag("atlas-photo-counter")).fetchSemanticsNodes()
        .firstOrNull()?.config?.getOrNull(SemanticsProperties.Text)?.joinToString()

    @Test fun swapPersonEntryPoints() {
        compose.waitForNode(hasTestTag("atlas-nav-photos"))

        // Selection More, one supported still photo: Favourite, AI Edit, Swap person, Copy, Move.
        select(fixtures.photos[0])
        openMore()
        compose.waitForNode(hasTestTag("atlas-selection-swap-person"), 20_000L)
        assertRows("atlas-selection-more-favourite", "atlas-selection-ai-edit", "atlas-selection-swap-person",
            "atlas-selection-copy-to-album", "atlas-selection-move-to-album")
        shot("selection-more")
        clearSelection()

        // Two photos: absent, not greyed out (AI Edit is one-photo only too).
        select(fixtures.photos[0], fixtures.photos[1])
        openMore()
        compose.waitForNode(hasTestTag("atlas-selection-copy-to-album"))
        assertAbsent("atlas-selection-ai-edit")
        assertAbsent("atlas-selection-swap-person")
        shot("selection-two")
        clearSelection()

        if (fontScale == 1f) {
            // A GIF keeps AI Edit (its own guard is unchanged) but never offers Swap person.
            select(fixtures.gif)
            openMore()
            compose.waitForNode(hasTestTag("atlas-selection-ai-edit"))
            assertGuardRefuses(fixtures.gif)
            assertAbsent("atlas-selection-swap-person")
            shot("selection-gif")
            clearSelection()

            select(fixtures.video)
            openMore()
            compose.waitForNode(hasTestTag("atlas-selection-copy-to-album"))
            assertGuardRefuses(fixtures.video)
            assertAbsent("atlas-selection-swap-person")
            assertAbsent("atlas-selection-ai-edit")
            shot("selection-video")
            clearSelection()
        }

        // Viewer ⋮: AI Edit, then Swap person, in the existing overflow.
        compose.tap(tile(fixtures.photos[0]))
        compose.waitForNode(hasTestTag("atlas-viewer-more"))
        val before = counter()
        assertTrue("viewer shows its N of M position", before != null)
        compose.tap(hasTestTag("atlas-viewer-more"))
        compose.waitForNode(hasTestTag("atlas-viewer-swap-person"), 20_000L)
        assertRows("atlas-viewer-ai-edit", "atlas-viewer-swap-person")
        shot("viewer-menu")

        if (fontScale == 1f) {
            // Swap person opens the IDS-8 flow over the photo (S1; no People in the fixture set).
            compose.tap(hasTestTag("atlas-viewer-swap-person"))
            compose.waitForNode(hasTestTag("swap-person-flow"), 30_000L)
            compose.waitForNode(hasTestTag("swap-no-people"), 30_000L)
            shot("flow-from-viewer")
            // System Back closes the flow and returns to the same viewer photo.
            back()
            compose.waitForNode(hasTestTag("atlas-viewer-more"), 30_000L)
            assertAbsent("swap-person-flow")
            assertEquals("same viewer position after closing", before, counter())
        }
    }
}

/** Synthetic MediaStore rows, newest first: three PNG photos, a static GIF and a short clip. */
private class SwapEntryFixtures : TestRule {
    lateinit var photos: List<Uri>
    lateinit var gif: Uri
    lateinit var video: Uri
    private val created = mutableListOf<Uri>()

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO).forEach {
                instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
            }
            clearGalleryInventoryCacheForTest(context)
            try {
                val now = System.currentTimeMillis()
                photos = List(3) { i -> png(context, i, now - (i + 1) * 60_000L) }
                gif = insert(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "ids11-still.gif", "image/gif",
                    "Pictures/ATLAS-Swap-Person-478/", now - 4 * 60_000L) { it.write(STATIC_GIF) }
                val clip = File(context.cacheDir, "ids11-" + UUID.randomUUID() + ".mp4")
                try {
                    encodeVideo(clip)
                    video = insert(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "ids11-clip.mp4", "video/mp4",
                        "Movies/ATLAS-Swap-Person-478/", now - 5 * 60_000L) { out -> clip.inputStream().use { it.copyTo(out) } }
                } finally { clip.delete() }
                base.evaluate()
            } finally {
                created.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
            }
        }
    }

    private fun png(context: Context, i: Int, taken: Long): Uri =
        insert(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "ids11-photo-$i.png", "image/png",
            "Pictures/ATLAS-Swap-Person-478/", taken) { out ->
            val bitmap = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.HSVToColor(floatArrayOf(200f + i * 40f, .45f, .85f)))
            try { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) } finally { bitmap.recycle() }
        }

    private fun insert(context: Context, collection: Uri, name: String, mime: String, path: String, taken: Long,
                       write: (java.io.OutputStream) -> Unit): Uri {
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, path)
            put(MediaStore.MediaColumns.DATE_TAKEN, taken)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        })) { "Could not create synthetic fixture" }
        created += uri
        requireNotNull(resolver.openOutputStream(uri)).use(write)
        resolver.update(uri, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
            put(MediaStore.MediaColumns.DATE_TAKEN, taken)
        }, null, null)
        return uri
    }

    /** 2 s offline H.264 clip at 10 fps (same encoder contract as the Stories video test). */
    private fun encodeVideo(file: File) {
        val frames = 20
        val fps = 10
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, SIDE, SIDE)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
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
                while (input < 0) { drain(false); input = encoder.dequeueInputBuffer(100_000L) }
                requireNotNull(encoder.getInputImage(input)).use { fillYuv(it) }
                encoder.queueInputBuffer(input, 0, SIDE * SIDE * 3 / 2, frame.toLong() * 1_000_000L / fps, 0)
                drain(false)
            }
            var input = encoder.dequeueInputBuffer(100_000L)
            while (input < 0) { drain(false); input = encoder.dequeueInputBuffer(100_000L) }
            encoder.queueInputBuffer(input, 0, 0, frames.toLong() * 1_000_000L / fps, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
        } finally {
            if (muxerStarted) muxer.stop()
            muxer.release()
            if (encoderStarted) encoder.stop()
            encoder.release()
        }
    }

    private fun fillYuv(image: Image) {
        val value = byteArrayOf(150.toByte(), 110.toByte(), 140.toByte())
        image.planes.forEachIndexed { index, plane ->
            val side = if (index == 0) SIDE else SIDE / 2
            for (row in 0 until side) for (col in 0 until side) {
                plane.buffer.put(row * plane.rowStride + col * plane.pixelStride, value[index])
            }
        }
    }

    companion object {
        private const val SIDE = 240

        /** A valid 1×1 static GIF89a (one frame, no animation extension). */
        private val STATIC_GIF = byteArrayOf(
            0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00, 0x80.toByte(), 0x00, 0x00,
            0x00, 0x00, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0x2C, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
            0x02, 0x02, 0x44, 0x01, 0x00, 0x3B
        )
    }
}
