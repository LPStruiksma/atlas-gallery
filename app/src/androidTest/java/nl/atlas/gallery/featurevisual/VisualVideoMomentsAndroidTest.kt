package nl.atlas.gallery.featurevisual

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Color
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import nl.atlas.gallery.PeopleDiscoveryHost
import nl.atlas.gallery.PeoplePowerState
import nl.atlas.gallery.PeopleScanScheduling
import nl.atlas.gallery.featureface.AndroidBitmapFaceFrame
import nl.atlas.gallery.featureface.FaceFrameReader
import nl.atlas.gallery.featureface.FaceMediaKey
import nl.atlas.gallery.featureface.FaceVisibleMedia
import nl.atlas.gallery.featureface.embed.FaceEmbeddingPipeline
import nl.atlas.gallery.featureface.onnx.OnnxFaceDetector
import nl.atlas.gallery.featureface.onnx.OnnxFaceEmbedder
import nl.atlas.gallery.featureface.toDurableFaceKey
import nl.atlas.gallery.featureface.work.PeopleScanWorkRuntime
import nl.atlas.gallery.featureface.work.runPeopleScanBatch
import nl.atlas.gallery.featuremedia.video.VideoKeyframeSampler
import nl.atlas.gallery.featurepersistence.FaceIndexStore
import nl.atlas.gallery.featurevisual.onnx.SiglipImageEmbedder
import nl.atlas.gallery.featurevisual.onnx.SiglipModel
import nl.atlas.gallery.featurevisual.search.StoreVisualVectorSource
import nl.atlas.gallery.featurevisual.search.VisualHit
import nl.atlas.gallery.featurevisual.search.VisualMatchBand
import nl.atlas.gallery.featurevisual.search.VisualQuery
import nl.atlas.gallery.featurevisual.search.VisualQueryBands
import nl.atlas.gallery.featurevisual.search.VisualQueryEngine
import nl.atlas.gallery.featurevisual.search.VisualQueryResult
import nl.atlas.gallery.featurevisual.search.VisualQueryStatus
import nl.atlas.gallery.featurevisual.search.VisualReference
import nl.atlas.gallery.featurevisual.search.VisualSearchResultsScreen
import nl.atlas.gallery.featurevisual.search.VisualSearchUiState
import nl.atlas.gallery.featurevisual.store.VisualIndexStore
import nl.atlas.gallery.featurevisual.store.VisualIndexedVector
import nl.atlas.gallery.featurevisual.store.VisualMedia
import nl.atlas.gallery.sampleVisualVideo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * VS-V2 (#468) on API35: semantic video moments through the real People worker
 * host. A 9 s H.264 clip of three solid scenes ENCODED HERE ([0,3) s red disc on
 * white, [3,6) s blue square on yellow, [6,9) s green stripes) is published as
 * an app-owned MediaStore video and indexed with the production sampler
 * adapter ([sampleVisualVideo] over VS-V1a's [VideoKeyframeSampler]) into a real
 * [VisualIndexStore]. With the bundled SigLIP 2 model, a reference image of
 * each scene ranks that scene's moment first. Photos go before videos, a junk
 * video is a typed skip, a revision change swaps all moments at once, and an
 * item that always fails becomes a skip after three tries. No real media is
 * read; every row and file is deleted afterwards.
 */
@RunWith(AndroidJUnit4::class)
class VisualVideoMomentsAndroidTest {
    @get:Rule val compose = createComposeRule()

    private val target: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(target.cacheDir, "vsv2-" + UUID.randomUUID()).apply { mkdirs() }
    private val published = ArrayList<Uri>()
    private val stores = ArrayList<AutoCloseable>()
    private lateinit var visual: VisualIndexStore
    private val nexts = AtomicInteger()
    private val embeds = AtomicInteger()
    private val failedEmbeds = AtomicInteger()

    @After fun cleanUp() {
        PeopleScanWorkRuntime.clear()
        stores.forEach { runCatching { it.close() } }
        published.forEach { target.contentResolver.delete(it, null, null) }
        dir.deleteRecursively()
    }

    private fun dbContext(file: File): Context = object : ContextWrapper(target) {
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

    /** Deterministic 8-d colour vector; magenta (an "always failing" item) throws like OrtException. */
    private inner class FakeEmbedder : ImageEmbedder {
        override val modelVersion = FAKE_MODEL
        override val dimension = 8
        override fun embed(bitmap: Bitmap): FloatArray {
            embeds.incrementAndGet()
            val centre = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
            if (centre == MAGENTA) {
                failedEmbeds.incrementAndGet()
                throw Exception("synthetic runtime failure")
            }
            val v = FloatArray(8) { i ->
                val p = bitmap.getPixel(bitmap.width * i / 8, bitmap.height / 3)
                ((p ushr (i % 3) * 8) and 255) + 1f
            }
            val norm = Math.sqrt(v.sumOf { it.toDouble() * it }).toFloat()
            return FloatArray(8) { v[it] / norm }
        }
        override fun close() = Unit
    }

    private fun host(realSiglip: Boolean = false): PeopleDiscoveryHost {
        val faces = FaceIndexStore(dbContext(File(dir, "faces.db"))).also { stores += it }
        visual = VisualIndexStore(dbContext(File(dir, "visual.db"))).also { stores += it }
        val sampler = VideoKeyframeSampler(target)
        return PeopleDiscoveryHost(
            store = faces,
            photoGrantCurrent = { true },
            inferenceFactory = {
                FaceEmbeddingPipeline.forOnnx(
                    OnnxFaceDetector.fromAssets(target.assets, scoreThreshold = 0.6f),
                    OnnxFaceEmbedder.fromAssets(target.assets))
            },
            // Faces are not under test: every source is one blank still for the face stage.
            readerFor = { _ ->
                FaceFrameReader { _, _ ->
                    AndroidBitmapFaceFrame(Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888))
                }
            },
            power = { PeoplePowerState(true, batteryNotLow = true, storageNotLow = true) },
            scheduler = object : PeopleScanScheduling {
                override fun start() = Unit
                override fun next() { nexts.incrementAndGet() }
                override fun later() { nexts.incrementAndGet() }
                override fun cancel() = Unit
            },
            visualStore = visual,
            visualEmbedderFactory = {
                if (realSiglip) SiglipImageEmbedder.open(target) else FakeEmbedder()
            },
            visualDecoderFor = { directory ->
                { media ->
                    val uri = directory.authorizedUri(FaceMediaKey(media.key, media.revision))
                    photoBitmap(uri.substringAfterLast('/').toLong())
                }
            },
            visualVideoFramesFor = { directory ->
                { media -> sampleVisualVideo(sampler, directory::authorizedSource, media) }
            },
            visualModelVersion = if (realSiglip) SiglipModel.MODEL_VERSION else FAKE_MODEL,
            visualDimension = if (realSiglip) SiglipModel.DIMENSION else 8
        )
    }

    private fun batch() = runBlocking { runPeopleScanBatch(target, thermalAllowed = { true }) { false } }

    /** Runs worker batches until neither stage asks for another. */
    private fun drain(max: Int = 40) {
        var n = 0
        while (n++ < max) {
            val before = nexts.get()
            batch()
            if (nexts.get() == before) return
        }
        error("The visual stage did not drain in $max batches")
    }

    private fun epoch(): String = visual.readableDatabase.rawQuery(
        "SELECT active_epoch FROM control", null).use { it.moveToFirst(); it.getString(0) }

    private fun count(table: String): Int = visual.readableDatabase
        .rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getInt(0) }

    private fun moments(model: String): List<VisualIndexedVector> =
        visual.loadActiveVectors(epoch(), model).filter { it.media.frameMs != null }

    @Test fun eachSceneReferenceRanksItsOwnMomentFirst() {
        val video = publishedVideo(encodeScenes(File(dir, "scenes.mp4")))
        val host = host(realSiglip = true)
        host.start(GRANT, listOf(video))
        assertTrue(host.setVisualIndex(true))
        drain()
        val found = moments(SiglipModel.MODEL_VERSION)
        assertEquals("one moment per scene", SCENE_MOMENTS, found.map { it.media.frameMs })
        assertEquals(1, visual.progress(epoch())!!.indexed)
        val videoKey = video.toDurableFaceKey().canonicalMediaKey
        val engine = VisualQueryEngine(StoreVisualVectorSource(visual, epoch(), SiglipModel.MODEL_VERSION))
        SiglipImageEmbedder.open(target).use { embedder ->
            for ((scene, expected) in SCENE_MOMENTS.withIndex()) {
                val reference = sceneBitmap(scene)
                val probe = try { embedder.embed(reference) } finally { reference.recycle() }
                val cosines = found.associate { it.media.frameMs!! to dot(probe, it.vector) }
                assertEquals("scene $scene: $cosines", expected, cosines.maxBy { it.value }.key)
                val result = engine.search(VisualQuery(SiglipModel.MODEL_VERSION,
                    VisualReference(embed = { probe })))
                assertEquals(VisualQueryStatus.OK, result.status)
                if (cosines.getValue(expected) >= VisualQueryBands.NEAR_DUPLICATE) {
                    // The same scene is a copy of the reference: counted, never a result.
                    assertTrue(result.nearDuplicates >= 1)
                } else {
                    val hit = result.hits.single { it.media.key == videoKey }
                    assertEquals("scene $scene ranks its own moment first", expected, hit.frameMs)
                }
                assertTrue("one hit per video", result.hits.count { it.media.key == videoKey } <= 1)
            }
        }
        host.setVisualIndex(false)
    }

    @Test fun photosGoFirstAndTheVideoIsOneItemWithItsMoments() {
        val video = publishedVideo(encodeScenes(File(dir, "scenes.mp4")))
        val inventory = listOf(video, photo(1), photo(2))
        val host = host()
        host.start(GRANT, inventory)
        assertTrue(host.setVisualIndex(true))
        assertEquals(3, visual.progress(epoch())!!.total)
        batch()
        assertEquals("the first step indexed the photos only", 2, count("vectors"))
        assertEquals(0, count("moments"))
        drain()
        val progress = visual.progress(epoch())!!
        assertEquals(3, progress.indexed)
        assertEquals(0, progress.queued + progress.running)
        assertEquals(SCENE_MOMENTS, moments(FAKE_MODEL).map { it.media.frameMs })
        // Photos and moments page together, in (key, frame) order.
        val all = visual.loadActiveVectors(epoch(), FAKE_MODEL)
        assertEquals(5, all.size)
        assertEquals(all.sortedWith(compareBy({ it.media.key }, { it.media.frameMs ?: -1L })), all)
    }

    @Test fun aJunkVideoIsATypedSkipAndPhotosStillIndex() {
        val junk = File(dir, "junk.mp4").apply { writeBytes(ByteArray(4096) { (it * 31 + 7).toByte() }) }
        val inventory = listOf(publishedVideo(junk, durationMs = 4_000L), photo(1))
        val host = host()
        host.start(GRANT, inventory)
        assertTrue(host.setVisualIndex(true))
        drain()
        val progress = visual.progress(epoch())!!
        assertEquals(1, progress.indexed)
        assertEquals(1, progress.skipped)
        assertEquals(0, count("moments"))
        assertEquals(1, count("skips"))
    }

    @Test fun aRevisionChangeReplacesAllMomentsAtOnce() {
        val file = encodeScenes(File(dir, "scenes.mp4"))
        val video = publishedVideo(file)
        val host = host()
        host.start(GRANT, listOf(video))
        assertTrue(host.setVisualIndex(true))
        drain()
        val before = moments(FAKE_MODEL)
        assertEquals(3, before.size)
        // The same MediaStore row, edited: a new generation is a new revision.
        val edited = video.copy(generationModified = video.generationModified + 1)
        host.onGalleryPublication("$GRANT-2") { listOf(edited) }
        assertEquals("the old revision's moments went in the publication commit", 0, count("moments"))
        assertEquals(1, visual.progress(epoch())!!.queued)
        drain()
        val after = moments(FAKE_MODEL)
        assertEquals(SCENE_MOMENTS, after.map { it.media.frameMs })
        val revision = edited.toDurableFaceKey().sourceRevision
        assertTrue(after.all { it.media.revision == revision })
        assertTrue(before.all { it.media.revision != revision })
    }

    @Test fun anItemThatAlwaysFailsIsSkippedAfterThreeTries() {
        val inventory = listOf(photo(1), photo(MAGENTA_PHOTO), photo(3))
        val host = host()
        host.start(GRANT, inventory)
        assertTrue(host.setVisualIndex(true))
        drain()
        val progress = visual.progress(epoch())!!
        assertEquals(2, progress.indexed)
        assertEquals(1, progress.skipped)
        assertEquals(VisualScanStage.MAX_ITEM_FAILURES, failedEmbeds.get())
        assertEquals("a skip clears the count", 0, count("failures"))
    }

    @Test fun aVideoHitShowsItsMomentAndOpensAtFrameMs() {
        val opened = ArrayList<VisualMedia>()
        val moment = VisualMedia("content://synthetic/video/1", "r1", 4_000L)
        val hit = VisualHit(moment, 0.9f, VisualMatchBand.STRONG, secondary = listOf(
            VisualHit(moment.copy(frameMs = 0L), 0.8f, VisualMatchBand.RELATED)))
        val photo = VisualHit(VisualMedia("content://synthetic/image/2", "r1"), 0.8f,
            VisualMatchBand.RELATED)
        compose.setContent {
            VisualSearchResultsScreen(
                state = VisualSearchUiState.Results(VisualQueryResult(VisualQueryStatus.OK,
                    listOf(hit, photo), 0, 4, false, false)),
                poseEnabled = false, onPoseToggle = {}, onOpen = { opened += it }, onBack = {},
                thumbnail = { sceneBitmap(1).asImageBitmap() })
        }
        compose.onNodeWithTag("atlas-visual-hit-0-video", useUnmergedTree = true)
            .assertTextEquals("▶ 0:04 · +1")
        compose.onNodeWithTag("atlas-visual-hit-1-video", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("atlas-visual-hit-0").performClick()
        compose.waitForIdle()
        assertEquals(listOf(4_000L), opened.map { it.frameMs })
    }

    /** Review #486 note 1: keyset windows after a cursor, and moments split across small pages. */
    @Test fun storeWindowsAfterACursorAndPagesMomentsInOrder() {
        val store = VisualIndexStore(dbContext(File(dir, "paging.db"))).also { stores += it }
        val epoch = "vsv2-paging-" + UUID.randomUUID()
        val items = (0 until 6).map { VisualMedia("content://synthetic/item/$it", "r1") }
        store.beginSnapshot(epoch)
        store.stageBatch(epoch, items)
        store.commitSnapshot(epoch, FAKE_MODEL, 8)
        assertEquals(items.subList(3, 5), store.queuedCandidates(epoch, 2, after = items[2]))
        assertTrue(store.queuedCandidates(epoch, 2, after = items[5])!!.isEmpty())
        // Items 0, 2, 4 are photos; 1, 3, 5 are videos with three moments each.
        val leases = store.claimAuthorized(epoch, FAKE_MODEL, items, items.size)
        fun unit(seed: Int) = FloatArray(8) { if (it == seed % 8) 1f else 0f }
        val photos = leases.filterIndexed { i, _ -> i % 2 == 0 }
            .map { nl.atlas.gallery.featurevisual.store.VisualVectorWrite(it, unit(1)) }
        val videos = leases.filterIndexed { i, _ -> i % 2 == 1 }.map { lease ->
            nl.atlas.gallery.featurevisual.store.VisualMomentsWrite(lease, listOf(0L, 2_000L, 4_000L)
                .map { nl.atlas.gallery.featurevisual.store.VisualMoment(it, unit(2)) })
        }
        assertTrue(store.completeBatch(photos, emptyList(), videos))
        assertEquals(6, store.progress(epoch)!!.indexed)
        val one = store.loadActiveVectors(epoch, FAKE_MODEL, limit = 512)
        assertEquals(12, one.size)
        for (limit in listOf(1, 2, 5)) {
            val paged = ArrayList<VisualIndexedVector>()
            var after: nl.atlas.gallery.featurevisual.store.VisualVectorCursor? = null
            while (true) {
                val page = store.loadActiveVectors(epoch, FAKE_MODEL, after, limit)
                assertTrue(page.size <= limit)
                paged += page
                if (page.size < limit) break
                after = page.last().cursor
            }
            assertEquals("limit $limit", one.map { it.media }, paged.map { it.media })
        }
        // A video's frames stay in time order right after its source key.
        assertEquals(listOf(null, 0L, 2_000L, 4_000L),
            one.filter { it.media.key.endsWith("/1") || it.media.key.endsWith("/0") }
                .map { it.media.frameMs })
    }

    // ---- Synthetic media ----------------------------------------------------

    private fun photo(id: Long) = FaceVisibleMedia(
        uri = "content://media/external_primary/images/media/$id",
        volumeName = "external_primary", isVideo = false, id = id,
        providerVersion = "synthetic-provider-1", generationModified = 10 + id,
        sizeBytes = 1000 + id, modifiedAtSeconds = 1_700_000_000L, editorRevision = 0,
        widthPixels = SIDE, heightPixels = SIDE, orientationDegrees = 0
    )

    private fun photoBitmap(id: Long): Bitmap =
        if (id == MAGENTA_PHOTO) Bitmap.createBitmap(IntArray(SIDE * SIDE) { MAGENTA }, SIDE, SIDE,
            Bitmap.Config.ARGB_8888)
        else sceneBitmap((id % 3).toInt())

    private fun sceneBitmap(scene: Int): Bitmap =
        Bitmap.createBitmap(sceneArgb(scene), SIDE, SIDE, Bitmap.Config.ARGB_8888)

    /** 0: red disc on white; 1: blue square on yellow; 2: green and black stripes. */
    private fun sceneArgb(scene: Int): IntArray = IntArray(SIDE * SIDE) { i ->
        val x = i % SIDE
        val y = i / SIDE
        val c = SIDE / 2
        when (scene) {
            0 -> if ((x - c) * (x - c) + (y - c) * (y - c) < 150 * 150) Color.rgb(220, 30, 30)
                else Color.WHITE
            1 -> if (x in 120 until 360 && y in 120 until 360) Color.rgb(30, 60, 220)
                else Color.rgb(250, 220, 40)
            else -> if ((x / 40) % 2 == 0) Color.rgb(30, 180, 60) else Color.BLACK
        }
    }

    private fun publishedVideo(file: File, durationMs: Long = 9_000L): FaceVisibleMedia {
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
        val id = uri.lastPathSegment!!.toLong()
        return FaceVisibleMedia(
            uri = "content://media/external_primary/video/media/$id",
            volumeName = "external_primary", isVideo = true, id = id,
            providerVersion = "synthetic-test-provider", generationModified = 1L,
            sizeBytes = file.length(), modifiedAtSeconds = 1L, editorRevision = 0L,
            widthPixels = SIDE, heightPixels = SIDE, orientationDegrees = 0,
            durationMs = durationMs
        )
    }

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

    /** 9 s at 10 fps, a sync frame every second: scene n fills [3n, 3n + 3) s. */
    private fun encodeScenes(file: File): File {
        val scenes = (0 until 3).map { yuv(sceneArgb(it)) }
        val fps = 10
        val frames = 90
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
                requireNotNull(encoder.getInputImage(slot)).use { fill(it, scenes[frame / (3 * fps)]) }
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
        return file
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

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

    private companion object {
        const val GRANT = "gallery-vsv2"
        const val FAKE_MODEL = "synthetic-visual-v2"
        /** 480 = 30 x 16 macroblocks. */
        const val SIDE = 480
        const val MAGENTA_PHOTO = 7L
        val MAGENTA = Color.rgb(255, 0, 255)
        /** Candidates every 2 s (0..8 s); scene changes keep 0, 4 and 6 s. */
        val SCENE_MOMENTS = listOf(0L, 4_000L, 6_000L)
    }
}
