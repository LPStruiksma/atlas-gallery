package nl.atlas.gallery.featureeditor.video

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * EDIT-13 (#731) smoke test: proves media3-transformer and media3-effect are on the
 * app classpath and work on the emulator. Fully synthetic: a generated PNG becomes a
 * 2 s clip, which is then trimmed to 1 s and rotated by 90 degrees.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class Media3TransformerSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun transcodesSyntheticClipWithTrimAndRotate() {
        val dir = File(context.cacheDir, "media3-smoke").apply { deleteRecursively(); mkdirs() }
        val png = File(dir, "frame.png")
        writeSyntheticFrame(png, width = 320, height = 240)

        // 1. Synthetic 2 s source clip from a still image.
        val source = File(dir, "source.mp4")
        val sourceItem = EditedMediaItem.Builder(
            MediaItem.Builder()
                .setUri(Uri.fromFile(png))
                .setMimeType(MimeTypes.IMAGE_PNG)
                .setImageDurationMs(SOURCE_MS)
                .build(),
        ).setFrameRate(30).build()
        export(sourceItem, source)
        val (srcW, srcH, srcMs) = displayedSizeAndDuration(source)
        assertEquals(320, srcW)
        assertEquals(240, srcH)
        assertTrue("source duration $srcMs ms", abs(srcMs - SOURCE_MS) <= TOLERANCE_MS)

        // 2. Trim 0.5 s .. 1.5 s and rotate 90 degrees.
        val edited = File(dir, "edited.mp4")
        val editedItem = EditedMediaItem.Builder(
            MediaItem.Builder()
                .setUri(Uri.fromFile(source))
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(500)
                        .setEndPositionMs(1_500)
                        .build(),
                )
                .build(),
        ).setEffects(
            Effects(
                emptyList(),
                listOf(ScaleAndRotateTransformation.Builder().setRotationDegrees(90f).build()),
            ),
        ).build()
        export(editedItem, edited)
        val (outW, outH, outMs) = displayedSizeAndDuration(edited)
        assertEquals("rotated width", 240, outW)
        assertEquals("rotated height", 320, outH)
        assertTrue("trimmed duration $outMs ms", abs(outMs - 1_000L) <= TOLERANCE_MS)

        dir.deleteRecursively()
    }

    private fun export(item: EditedMediaItem, output: File) {
        val done = CountDownLatch(1)
        var error: ExportException? = null
        instrumentation.runOnMainSync {
            Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        done.countDown()
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        error = exportException
                        done.countDown()
                    }
                })
                .build()
                .start(item, output.absolutePath)
        }
        assertTrue("export of ${output.name} timed out", done.await(TIMEOUT_S, TimeUnit.SECONDS))
        assertNull("export of ${output.name} failed: $error", error)
        assertTrue("${output.name} is empty", output.length() > 0)
    }

    private fun writeSyntheticFrame(file: File, width: Int, height: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(31, 64, 160))
        canvas.drawRect(0f, 0f, width / 4f, height / 4f, Paint().apply { color = Color.YELLOW })
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    /** Width and height as displayed (rotation metadata applied) and duration in ms. */
    private fun displayedSizeAndDuration(file: File): Triple<Int, Int, Long> {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            fun meta(key: Int) = requireNotNull(retriever.extractMetadata(key)) { "missing metadata $key" }
            val w = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH).toInt()
            val h = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT).toInt()
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            val ms = meta(MediaMetadataRetriever.METADATA_KEY_DURATION).toLong()
            return if (rotation % 180 != 0) Triple(h, w, ms) else Triple(w, h, ms)
        } finally {
            retriever.release()
        }
    }

    private companion object {
        const val SOURCE_MS = 2_000L
        const val TOLERANCE_MS = 200L
        const val TIMEOUT_S = 60L
    }
}
