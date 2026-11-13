package nl.atlas.gallery.featuremedia

import android.content.ContentValues
import android.graphics.Color
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException
import nl.atlas.gallery.featuremedia.video.VideoKeyframePlan
import nl.atlas.gallery.featuremedia.video.VideoKeyframeResult
import nl.atlas.gallery.featuremedia.video.VideoKeyframeSampler
import nl.atlas.gallery.featuremedia.video.VideoMediaRevision
import nl.atlas.gallery.featuremedia.video.VideoSkipReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * All pixels are generated here: 2 seconds of red followed by 2 of blue.
 * The MP4 is written by MediaCodec/MediaMuxer into cache, published as an
 * app-owned MediaStore video, and deleted in finally. No real media is read.
 */
@RunWith(AndroidJUnit4::class)
class VideoKeyframeSamplerTest {
    @Test fun samplesSyntheticColourScenesWithinBudgetAndHonoursCancellation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File.createTempFile("atlas-vsv1a-", ".mp4", context.cacheDir)
        var uri: Uri? = null
        try {
            encodeSolidColourVideo(fixture)
            uri = publishSyntheticVideo(fixture)
            val source = requireNotNull(uri)
            val revision = VideoMediaRevision("synthetic-test-provider", 1L)
            val sampler = VideoKeyframeSampler(context)
            val sampled = sampler.sample(source, 4_000L, revision,
                isStillAuthorized = { true }, checkActive = {})
            assertTrue(sampled is VideoKeyframeResult.Sampled)
            val batch = (sampled as VideoKeyframeResult.Sampled).batch
            val bitmaps = batch.frames.map { it.bitmap }
            batch.use {
                assertEquals(revision, it.revision)
                assertTrue(it.frames.size in 2..VideoKeyframePlan.MAX_FRAMES_PER_VIDEO)
                assertEquals(listOf(0L, 2_000L), it.frames.map { frame -> frame.frameMs })
                val red = it.frames[0].bitmap.getPixel(
                    it.frames[0].bitmap.width / 2, it.frames[0].bitmap.height / 2)
                val blue = it.frames[1].bitmap.getPixel(
                    it.frames[1].bitmap.width / 2, it.frames[1].bitmap.height / 2)
                assertTrue(Color.red(red) > Color.blue(red))
                assertTrue(Color.blue(blue) > Color.red(blue))
                assertTrue(it.frames.all { frame ->
                    maxOf(frame.bitmap.width, frame.bitmap.height) <=
                        VideoKeyframePlan.MAX_LONG_EDGE_PX })
                assertTrue(it.frames.sumOf { frame ->
                    frame.bitmap.allocationByteCount.toLong() } <= 8L * 1024 * 1024)
            }
            assertTrue(bitmaps.all { it.isRecycled })

            assertEquals(VideoKeyframeResult.Skipped(VideoSkipReason.ZERO_OR_INVALID_DURATION),
                sampler.sample(source, 0L, revision, { true }, {}))
            assertEquals(VideoKeyframeResult.Skipped(VideoSkipReason.STALE_OR_REVOKED),
                sampler.sample(source, 4_000L, revision, { false }, {}))

            var polls = 0
            try {
                sampler.sample(source, 4_000L, revision, { true }) {
                    if (++polls == 3) throw CancellationException("synthetic stop")
                }
                fail("Cancellation was swallowed")
            } catch (_: CancellationException) {
                assertTrue(polls >= 3)
            }
        } finally {
            uri?.let { context.contentResolver.delete(it, null, null) }
            fixture.delete()
        }
    }

    private fun publishSyntheticVideo(file: File): Uri {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val entry = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,
                "atlas-synthetic-" + UUID.randomUUID() + ".mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_MOVIES + "/AtlasSyntheticTests")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), entry))
        try {
            requireNotNull(resolver.openOutputStream(uri)).use { destination ->
                file.inputStream().use { it.copyTo(destination) }
            }
            val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            check(resolver.update(uri, ready, null, null) == 1)
            return uri
        } catch (failure: Throwable) {
            resolver.delete(uri, null, null)
            throw failure
        }
    }

    private fun encodeSolidColourVideo(file: File) {
        val width = 128
        val height = 96
        val fps = 12
        val frames = 48
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 300_000)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
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
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!expectEnd) return
                    }
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!startedMuxer)
                        track = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        startedMuxer = true
                    }
                    else -> if (index >= 0) {
                        val encoded = requireNotNull(encoder.getOutputBuffer(index))
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            info.size = 0
                        }
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
            for (frame in 0 until frames) {
                var slot = encoder.dequeueInputBuffer(100_000L)
                while (slot < 0) {
                    drain(expectEnd = false)
                    slot = encoder.dequeueInputBuffer(100_000L)
                }
                val image = requireNotNull(encoder.getInputImage(slot))
                image.use {
                    fillColour(it, frame < frames / 2)
                }
                encoder.queueInputBuffer(slot, 0, width * height * 3 / 2,
                    frame.toLong() * 1_000_000L / fps, 0)
                drain(expectEnd = false)
            }
            var eos = encoder.dequeueInputBuffer(100_000L)
            while (eos < 0) {
                drain(expectEnd = false)
                eos = encoder.dequeueInputBuffer(100_000L)
            }
            encoder.queueInputBuffer(eos, 0, 0, frames.toLong() * 1_000_000L / fps,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(expectEnd = true)
        } finally {
            if (startedMuxer) muxer.stop()
            muxer.release()
            if (startedEncoder) encoder.stop()
            encoder.release()
        }
    }

    private fun fillColour(image: Image, red: Boolean) {
        val y = if (red) 76 else 29
        val u = if (red) 85 else 255
        val v = if (red) 255 else 107
        val values = listOf(y, u, v)
        image.planes.forEachIndexed { index, plane ->
            val planeWidth = if (index == 0) image.width else image.width / 2
            val planeHeight = if (index == 0) image.height else image.height / 2
            val bytes = plane.buffer
            for (row in 0 until planeHeight) {
                for (col in 0 until planeWidth) {
                    bytes.put(row * plane.rowStride + col * plane.pixelStride,
                        values[index].toByte())
                }
            }
        }
    }
}
