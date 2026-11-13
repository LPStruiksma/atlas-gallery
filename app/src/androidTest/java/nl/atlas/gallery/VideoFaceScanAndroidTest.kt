package nl.atlas.gallery

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
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
import nl.atlas.gallery.featureface.AndroidMediaFaceFrameReader
import nl.atlas.gallery.featureface.AuthorizedVideoFrames
import nl.atlas.gallery.featureface.FaceAuthorizedMediaDirectory
import nl.atlas.gallery.featureface.FaceCatalogPublisher
import nl.atlas.gallery.featureface.FaceScanConditions
import nl.atlas.gallery.featureface.FaceScanEngine
import nl.atlas.gallery.featureface.FaceScanStep
import nl.atlas.gallery.featureface.FaceVisibleMedia
import nl.atlas.gallery.featureface.embed.FaceEmbeddingPipeline
import nl.atlas.gallery.featureface.embed.PersistedFaceEmbeddingIndexPort
import nl.atlas.gallery.featureface.onnx.OnnxFaceDetector
import nl.atlas.gallery.featureface.onnx.OnnxFaceEmbedder
import nl.atlas.gallery.featureface.onnx.SFaceModel
import nl.atlas.gallery.featureface.onnx.SyntheticFace
import nl.atlas.gallery.featureface.search.FaceQuery
import nl.atlas.gallery.featureface.search.FaceQueryEngine
import nl.atlas.gallery.featureface.toDurableFaceKey
import nl.atlas.gallery.featuremedia.video.VideoKeyframeSampler
import nl.atlas.gallery.featurepersistence.FaceIndexStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * VS-V1b (#468) on API35: a 7 s H.264 clip ENCODED HERE with MediaCodec and
 * MediaMuxer: 1 s grey, 4 s of the #413 [SyntheticFace] drawn in code, 2 s
 * blue. It is published as an app-owned MediaStore video, catalogued, and
 * scanned by the production reader (VS-V1a keyframes) with the bundled
 * YuNet + SFace models into a real FaceIndexStore file. The face is found
 * at frame_ms 2000 and search by face returns that moment. A second,
 * garbage "video" becomes a typed skip. No real media is read; every row
 * and file is deleted in finally.
 */
@RunWith(AndroidJUnit4::class)
class VideoFaceScanAndroidTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val epoch = "vsv1b-epoch-" + UUID.randomUUID()
    private val conditions = FaceScanConditions(
        optedIn = true, photosPermissionGranted = true, permissionSnapshotCurrent = true,
        batteryNotLow = true, storageNotLow = true, charging = true,
        pausedByUser = false, backgroundMaintenance = false
    )

    private fun storeContext(file: File): Context = object : ContextWrapper(context) {
        override fun getDatabasePath(name: String): File = file
        override fun openOrCreateDatabase(
            name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?
        ): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(file, factory)
        override fun openOrCreateDatabase(
            name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
            errorHandler: DatabaseErrorHandler?
        ): SQLiteDatabase = SQLiteDatabase.openDatabase(
            file.path, factory, SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler)
    }

    @Test fun syntheticFaceInAnEncodedVideoIsFoundAtItsKeyframeTime() {
        val dir = File(context.cacheDir, "vsv1b-" + UUID.randomUUID())
        check(dir.mkdirs())
        val clip = File(dir, "face.mp4")
        val junk = File(dir, "junk.mp4")
        val published = ArrayList<Uri>()
        try {
            encodeFaceVideo(clip)
            junk.writeBytes(ByteArray(4096) { (it * 31 + 7).toByte() })
            val faceUri = publishSyntheticVideo(clip).also { published += it }
            val junkUri = publishSyntheticVideo(junk).also { published += it }
            val inventory = listOf(visible(faceUri, clip.length(), 7_000L),
                visible(junkUri, junk.length(), 3_000L))

            FaceIndexStore(storeContext(File(dir, "faces.db"))).use { store ->
                val publication = FaceCatalogPublisher(store, { it == epoch }, { true })
                    .publish(epoch, nl.atlas.gallery.featureface.onnx.YuNetModel.DETECTOR_VERSION,
                        inventory.asSequence())
                assertEquals(2, publication.sourceVideoRows)
                val directory = FaceAuthorizedMediaDirectory.build(
                    epoch, inventory, { it }, { epoch }, { true })
                assertEquals(2, directory.mediaCount)
                val assets = context.assets
                OnnxFaceDetector.fromAssets(assets, scoreThreshold = 0.6f).use { detector ->
                    OnnxFaceEmbedder.fromAssets(assets).use { embedder ->
                        val engine = FaceScanEngine(
                            scopeEpoch = { epoch },
                            currentConditions = { conditions },
                            sourceRevisionCurrent = directory::sourceRevisionCurrent,
                            index = PersistedFaceEmbeddingIndexPort(store),
                            reader = AndroidMediaFaceFrameReader(context.contentResolver,
                                { Uri.parse(directory.authorizedUri(it)) },
                                videos = AuthorizedVideoFrames(VideoKeyframeSampler(context),
                                    directory::authorizedSource)),
                            locator = FaceEmbeddingPipeline.forOnnx(detector, embedder)
                        )
                        val steps = List(3) { engine.scanOne(epoch) }
                        val byStep = steps.map { it.step }
                        assertEquals("steps $steps", FaceScanStep.NO_PENDING, byStep.last())
                        assertEquals("steps $steps", setOf(FaceScanStep.INDEXED, FaceScanStep.SKIPPED),
                            byStep.take(2).toSet())
                        val indexed = steps.first { it.step == FaceScanStep.INDEXED }
                        assertTrue("no face in the video: $indexed", indexed.observations >= 1)
                        // Kept scenes: grey (0 ms), face (2000 ms), blue (6000 ms).
                        assertTrue("keyframes ${indexed.frames}", indexed.frames in 2..4)
                    }
                }

                val faceKey = inventory[0].toDurableFaceKey()
                val faces = store.observationRecords(epoch, faceKey.canonicalMediaKey)
                assertTrue(faces.isNotEmpty())
                assertEquals(setOf(2_000L), faces.map { it.frameMs }.toSet())
                val box = faces.first().box
                assertTrue("face box ${box}", box.left < 0.5f && 0.5f < box.right &&
                    box.top < 0.5f && 0.5f < box.bottom)
                assertEquals("UNREADABLE_VIDEO",
                    store.skipReason(epoch, inventory[1].toDurableFaceKey().canonicalMediaKey))

                // Video faces join clustering, and search by face returns the moment.
                assertTrue(store.materializeClusters(epoch))
                assertNotNull(store.peopleReview(epoch))
                val corpus = requireNotNull(store.faceSearchCorpus(epoch))
                val query = store.embeddingRecords(epoch, faceKey.canonicalMediaKey).first().vector
                val hit = FaceQueryEngine.search(corpus,
                    FaceQuery(SFaceModel.EMBEDDER_VERSION, listOf(query))).hits.single()
                assertEquals(faceKey.canonicalMediaKey, hit.media.key)
                assertEquals(2_000L, hit.bestFrameMs)
            }
        } finally {
            published.forEach { context.contentResolver.delete(it, null, null) }
            dir.deleteRecursively()
        }
    }

    private fun visible(uri: Uri, size: Long, durationMs: Long): FaceVisibleMedia {
        val id = uri.lastPathSegment!!.toLong()
        return FaceVisibleMedia(
            uri = "content://media/external_primary/video/media/$id",
            volumeName = "external_primary", isVideo = true, id = id,
            providerVersion = "synthetic-test-provider", generationModified = 1L,
            sizeBytes = size, modifiedAtSeconds = 1L, editorRevision = 0L,
            widthPixels = SIDE, heightPixels = SIDE, orientationDegrees = 0,
            durationMs = durationMs
        )
    }

    private fun publishSyntheticVideo(file: File): Uri {
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

    /** BT.601 limited-range planes of one ARGB scene, chroma sampled at even pixels. */
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

    private fun flat(rgb: Int) = IntArray(SIDE * SIDE) { rgb }

    private fun encodeFaceVideo(file: File) {
        val grey = yuv(flat(0xFF808080.toInt()))
        val face = yuv(SyntheticFace.argb(SyntheticFace.Spec(SIDE, SIDE)))
        val blue = yuv(flat(0xFF1E3CC8.toInt()))
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
            val width = if (index == 0) SIDE else SIDE / 2
            val height = if (index == 0) SIDE else SIDE / 2
            val bytes = plane.buffer
            val source = sources[index]
            for (row in 0 until height) for (col in 0 until width) {
                bytes.put(row * plane.rowStride + col * plane.pixelStride, source[row * width + col])
            }
        }
    }

    private companion object {
        /** 480 = 30 x 16 macroblocks; the sampler keeps it at <= 512 px. */
        const val SIDE = 480
    }
}
