package nl.atlas.gallery

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.Color
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import nl.atlas.gallery.featureface.AndroidMediaFaceFrameReader
import nl.atlas.gallery.featureface.AuthorizedVideoFrames
import nl.atlas.gallery.featureface.FaceBatchStop
import nl.atlas.gallery.featureface.FaceCatalogPublisher
import nl.atlas.gallery.featureface.FaceVisibleMedia
import nl.atlas.gallery.featureface.embed.FaceEmbeddingPipeline
import nl.atlas.gallery.featureface.onnx.OnnxFaceDetector
import nl.atlas.gallery.featureface.onnx.OnnxFaceEmbedder
import nl.atlas.gallery.featureface.onnx.SyntheticFace
import nl.atlas.gallery.featureface.work.PeopleScanWorkRuntime
import nl.atlas.gallery.featureface.work.runPeopleScanBatch
import nl.atlas.gallery.featuremedia.video.VideoKeyframeSampler
import nl.atlas.gallery.featurepersistence.FaceIndexStore
import nl.atlas.gallery.featurepersistence.IndexedFaceBox
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * VS-V1c (#468) on API35. (1) A video face gets a review crop from its
 * keyframe at frame_ms: a 7 s H.264 clip ENCODED HERE (1 s grey, 4 s of the
 * #413 [SyntheticFace] drawn in code, 2 s blue) is published as an app-owned
 * MediaStore video and scanned through the real host with the production
 * reader, the bundled YuNet + SFace models and the production
 * [MediaStoreFaceCropDecoder]; the crop is the face (not grey or blue) and
 * renders on the real review screen. A garbage "video" and a missing frame
 * time fail closed to the neutral icon. (2) With a real FaceIndexStore, more
 * videos than the video budget still publish every photo. No real media is
 * read; every row and file is deleted afterwards.
 */
@RunWith(AndroidJUnit4::class)
class VideoFaceCropAndroidTest {
    @get:Rule val compose = createComposeRule()

    private val target: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(target.cacheDir, "vsv1c-" + UUID.randomUUID()).apply { mkdirs() }
    private val published = ArrayList<Uri>()
    private var store: FaceIndexStore? = null

    @After fun cleanUp() {
        PeopleScanWorkRuntime.clear()
        store?.close()
        published.forEach { target.contentResolver.delete(it, null, null) }
        dir.deleteRecursively()
    }

    private fun storeAt(file: File): FaceIndexStore = FaceIndexStore(object : ContextWrapper(target) {
        override fun getDatabasePath(name: String): File = file
        override fun openOrCreateDatabase(
            name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?
        ): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(file, factory)
        override fun openOrCreateDatabase(
            name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
            errorHandler: DatabaseErrorHandler?
        ): SQLiteDatabase = SQLiteDatabase.openDatabase(
            file.path, factory, SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler)
    }).also { store = it }

    private fun host(faces: FaceIndexStore) = PeopleDiscoveryHost(
        store = faces,
        photoGrantCurrent = { true },
        inferenceFactory = {
            FaceEmbeddingPipeline.forOnnx(
                OnnxFaceDetector.fromAssets(target.assets, scoreThreshold = 0.6f),
                OnnxFaceEmbedder.fromAssets(target.assets))
        },
        readerFor = { directory ->
            AndroidMediaFaceFrameReader(target.contentResolver,
                { Uri.parse(directory.authorizedUri(it)) },
                videos = AuthorizedVideoFrames(VideoKeyframeSampler(target),
                    directory::authorizedSource))
        },
        power = { PeoplePowerState(true, batteryNotLow = true, storageNotLow = true) },
        scheduler = object : PeopleScanScheduling {
            override fun start() = Unit
            override fun next() = Unit
            override fun later() = Unit
            override fun cancel() = Unit
        },
        faceCropDecoder = MediaStoreFaceCropDecoder(target.contentResolver)
    )

    private fun waitFor(label: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(30_000, condition)
        } catch (e: Throwable) {
            val tree = runCatching { compose.onRoot().printToString() }.getOrDefault("?")
            throw AssertionError("Timed out waiting for $label. Screen:\n$tree", e)
        }
    }

    @Test fun videoFaceCropComesFromItsKeyframeAndRendersInReview() {
        val clip = File(dir, "face.mp4").also(::encodeFaceVideo)
        val junk = File(dir, "junk.mp4").apply { writeBytes(ByteArray(4096) { (it * 31 + 7).toByte() }) }
        val faceUri = publishSyntheticVideo(clip)
        val junkUri = publishSyntheticVideo(junk)
        val inventory = listOf(visible(faceUri, clip.length(), 7_000L))
        val faces = storeAt(File(dir, "faces.db"))
        val host = host(faces)

        host.start(GRANT, inventory)
        var guard = 0
        while (runBlocking { runPeopleScanBatch(target) { false } }!!.stop !=
            FaceBatchStop.NO_PENDING && guard++ < 20) Unit
        val epoch = requireNotNull(host.displayEpoch(GRANT))
        val candidate = requireNotNull(host.reviewPage(GRANT)).queue.candidates.first()
        val uid = candidate.key.nativeFaceUid
        val location = requireNotNull(faces.faceLocation(epoch, uid))
        assertEquals("face found in the face scene", 2_000L, location.frameMs)

        // Host level: a bounded crop of the face, read at frame_ms.
        val crop = host.faceCrop(epoch, uid)
        assertNotNull("a crop for a video face", crop)
        crop!!
        assertTrue("crop ${crop.width}x${crop.height} within the target edge",
            maxOf(crop.width, crop.height) <= FaceCropGeometry.TARGET_EDGE + 2)
        val centre = crop.getPixel(crop.width / 2, crop.height / 2)
        // The drawn face is skin-toned; the grey (0 ms) and blue (6000 ms)
        // scenes are not, so this proves the crop came from the right frame.
        assertTrue("crop centre ${Integer.toHexString(centre)} is the face",
            Color.red(centre) > Color.blue(centre) + 40)

        // Fail closed: no frame time for a video, a frame time for a photo,
        // and an undecodable video all give the neutral icon.
        val decoder = MediaStoreFaceCropDecoder(target.contentResolver)
        val box = location.box
        assertNull(decoder.decodeAt(faceUri.toVolumeUri(), box, null))
        assertNull(decoder.decodeAt("content://media/external_primary/images/media/1", box, 2_000L))
        assertNull(decoder.decodeAt(junkUri.toVolumeUri(), box, 0L))
        assertNull(decoder.decodeAt(faceUri.toVolumeUri(), IndexedFaceBox(0f, 0f, 1f, 1f), -1L))

        // The real review screen renders the video face's crop.
        compose.setContent { PeopleReviewRoute(host, GRANT, onClose = {}) }
        waitFor("a video face crop in review") {
            compose.onAllNodesWithTag("atlas-face-crop", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun moreVideosThanTheBudgetStillPublishEveryPhoto() {
        val epoch = "vsv1c-epoch-" + UUID.randomUUID()
        val faces = storeAt(File(dir, "budget.db"))
        val photos = (1L..2L).map { invented(it, video = false) }
        val videos = (11L..13L).map { invented(it, video = true) }
        val publication = FaceCatalogPublisher(faces, { it == epoch }, { true },
            maxVideoRows = 1).publish(epoch, "detector-1", (videos + photos).asSequence())
        assertEquals(2, publication.sourcePhotoRows)
        assertEquals(1, publication.sourceVideoRows)
        assertEquals(2, publication.deferredVideoRows)
        assertEquals(0, publication.ignoredVideoRows)
    }

    private fun invented(id: Long, video: Boolean) = FaceVisibleMedia(
        uri = "content://media/external_primary/${if (video) "video" else "images"}/media/$id",
        volumeName = "external_primary", isVideo = video, id = id,
        providerVersion = "synthetic-provider-1", generationModified = 10 + id,
        sizeBytes = 1000 + id, modifiedAtSeconds = 1_700_000_000L, editorRevision = 0,
        widthPixels = 480, heightPixels = 480, orientationDegrees = 0,
        durationMs = if (video) 4_000L else 0L
    )

    private fun Uri.toVolumeUri() = "content://media/external_primary/video/media/$lastPathSegment"

    private fun visible(uri: Uri, size: Long, durationMs: Long): FaceVisibleMedia {
        val id = uri.lastPathSegment!!.toLong()
        return FaceVisibleMedia(
            uri = uri.toVolumeUri(),
            volumeName = "external_primary", isVideo = true, id = id,
            providerVersion = "synthetic-test-provider", generationModified = 1L,
            sizeBytes = size, modifiedAtSeconds = 1L, editorRevision = 0L,
            widthPixels = SIDE, heightPixels = SIDE, orientationDegrees = 0,
            durationMs = durationMs
        )
    }

    private fun publishSyntheticVideo(file: File): Uri {
        val resolver = target.contentResolver
        val entry = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "atlas-synthetic-" + UUID.randomUUID() + ".mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_MOVIES + "/AtlasSyntheticTests")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), entry))
        published += uri
        requireNotNull(resolver.openOutputStream(uri)).use { out ->
            file.inputStream().use { it.copyTo(out) }
        }
        check(resolver.update(uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
        return uri
    }

    // ---- Synthetic clip, the same recipe as VideoFaceScanAndroidTest -------

    private class Yuv(val y: ByteArray, val u: ByteArray, val v: ByteArray)

    private fun yuv(argb: IntArray): Yuv {
        val half = SIDE / 2
        val y = ByteArray(SIDE * SIDE)
        val u = ByteArray(half * half)
        val v = ByteArray(half * half)
        for (row in 0 until SIDE) for (col in 0 until SIDE) {
            val p = argb[row * SIDE + col]
            val r = (p shr 16) and 255
            val g = (p shr 8) and 255
            val b = p and 255
            y[row * SIDE + col] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte()
            if (row % 2 == 0 && col % 2 == 0) {
                val c = (row / 2) * half + col / 2
                u[c] = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).toByte()
                v[c] = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).toByte()
            }
        }
        return Yuv(y, u, v)
    }

    private fun encodeFaceVideo(file: File) {
        val grey = yuv(IntArray(SIDE * SIDE) { 0xFF808080.toInt() })
        val face = yuv(SyntheticFace.argb(SyntheticFace.Spec(SIDE, SIDE)))
        val blue = yuv(IntArray(SIDE * SIDE) { 0xFF1E3CC8.toInt() })
        val fps = 10
        val frames = 70 // 7 s: [0,1) grey, [1,5) face, [5,7) blue
        fun scene(frame: Int) = when {
            frame < 1 * fps -> grey
            frame < 5 * fps -> face
            else -> blue
        }
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, SIDE, SIDE)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
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
            while (waits++ < 1000) {
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
            for (frame in 0 until frames) {
                var slot = encoder.dequeueInputBuffer(100_000L)
                while (slot < 0) {
                    drain(expectEnd = false)
                    slot = encoder.dequeueInputBuffer(100_000L)
                }
                requireNotNull(encoder.getInputImage(slot)).use { fill(it, scene(frame)) }
                encoder.queueInputBuffer(slot, 0, SIDE * SIDE * 3 / 2,
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

    private fun fill(image: Image, scene: Yuv) {
        val sources = listOf(scene.y, scene.u, scene.v)
        image.planes.forEachIndexed { index, plane ->
            val side = if (index == 0) SIDE else SIDE / 2
            val bytes = plane.buffer
            val source = sources[index]
            for (row in 0 until side) for (col in 0 until side) {
                bytes.put(row * plane.rowStride + col * plane.pixelStride, source[row * side + col])
            }
        }
    }

    private companion object {
        const val GRANT = "gallery-vsv1c"
        /** 480 = 30 x 16 macroblocks; the sampler keeps it at <= 512 px. */
        const val SIDE = 480
    }
}
