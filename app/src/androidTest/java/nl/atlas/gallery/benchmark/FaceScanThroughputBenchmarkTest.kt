package nl.atlas.gallery.benchmark

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import nl.atlas.gallery.PeopleDiscoveryHost
import nl.atlas.gallery.PeoplePowerState
import nl.atlas.gallery.PeopleScanScheduling
import nl.atlas.gallery.featureface.AndroidBitmapFaceFrame
import nl.atlas.gallery.featureface.AndroidMediaFaceFrameReader
import nl.atlas.gallery.featureface.FaceBatchStop
import nl.atlas.gallery.featureface.FaceEmbeddedDetection
import nl.atlas.gallery.featureface.FaceFrameReader
import nl.atlas.gallery.featureface.FacePixelBounds
import nl.atlas.gallery.featureface.FaceSourceFrames
import nl.atlas.gallery.featureface.FaceVisibleMedia
import nl.atlas.gallery.featureface.embed.FaceEmbeddingPipeline
import nl.atlas.gallery.featureface.onnx.FaceOnnxThreads
import nl.atlas.gallery.featureface.onnx.OnnxFaceDetector
import nl.atlas.gallery.featureface.onnx.OnnxFaceEmbedder
import nl.atlas.gallery.featureface.onnx.SyntheticFace
import nl.atlas.gallery.featureface.work.PeopleScanWorkRuntime
import nl.atlas.gallery.featureface.work.PeopleScanWorkScheduler
import nl.atlas.gallery.featureface.work.runPeopleScanBatch
import nl.atlas.gallery.featurepersistence.FaceIndexStore
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #552: face scan throughput of the real pipeline on API35, per stage.
 *
 * Opt-in: `-e atlasFaceScanBench true` (not part of the default emulator-ui
 * suite). Inputs are synthetic 12 MP JPEGs this test writes into its own
 * MediaStore folder (cartoon [SyntheticFace] drawings on a noisy background)
 * and deletes again: no real photo, person or other app's media is read.
 *
 * End-to-end runs over the same photos, each on a fresh private store:
 *  - `in_process.<config>`: the worker's batch function in a loop, with
 *    per-stage timers around decode, YuNet and SFace (the rest is claim,
 *    guards and the store write): `warmup` (discarded: the first run pays
 *    cold caches), `before` (the old fixed 2 ORT threads), `after` (the
 *    production thread budget) and `before_again` (drift check);
 *  - `workmanager`: the production scheduler and setup, so the run includes
 *    the WorkManager hand-over between bounded batches.
 * Prints one JSON object on logcat `ATLAS_SCALE` (CI dumps that tag) and
 * `ATLAS_FACE scan_rate`. Timings never fail the test.
 */
@RunWith(AndroidJUnit4::class)
class FaceScanThroughputBenchmarkTest {
    companion object {
        const val ARG = "atlasFaceScanBench"
        private const val PHOTOS = 24
        private const val WIDTH = 4032
        private const val HEIGHT = 3024
        private const val FOLDER = "Pictures/ATLAS-Bench-552/"
        /** Measurement-only switch for a PR head; the merged default is opt-in. */
        private const val RUN_BY_DEFAULT = false
    }

    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val enabled = RUN_BY_DEFAULT ||
        InstrumentationRegistry.getArguments().getString(ARG) == "true"

    /** One setup: ORT intra-op threads of YuNet and SFace. */
    private data class Setup(val threads: Int)

    private val production = Setup(FaceOnnxThreads.intraOp)
    private val setups = linkedMapOf(
        "warmup" to Setup(2),
        "before" to Setup(2),
        "after" to production,
        "before_again" to Setup(2)
    )

    private class Timers {
        val decodeNs = AtomicLong()
        val yunetNs = AtomicLong()
        val sfaceNs = AtomicLong()
        val faces = AtomicLong()
        val photos = AtomicLong()
    }

    @Test fun measureFaceScanThroughput() {
        assumeTrue("opt-in: -e $ARG true", enabled)
        val rows = insertSyntheticPhotos()
        val report = JSONObject().put("bench", "face_scan_552").put("photos", rows.size)
            .put("source_px", "${WIDTH}x$HEIGHT")
        try {
            report.put("cpus", Runtime.getRuntime().availableProcessors())
                .put("intra_op_threads", FaceOnnxThreads.intraOp)
            val runs = JSONObject()
            for ((name, setup) in setups) runs.put(name, inProcess(rows, setup).put("setup", setup.toString()))
            report.put("in_process", runs)
            report.put("workmanager", workManager(rows, production))
        } finally {
            for (row in rows) target.contentResolver.delete(Uri.parse(row.uri), null, null)
        }
        val text = report.toString()
        Log.i("ATLAS_SCALE", text)
        Log.i("ATLAS_FACE", "scan_rate $text")
        println(text)
    }

    /** Synthetic camera-sized JPEGs with 1-3 drawn faces each, in this app's own folder. */
    private fun insertSyntheticPhotos(): List<FaceVisibleMedia> {
        val face = SyntheticFace.bitmap(SyntheticFace.Spec(480, 480))
        val random = Random(552)
        val collection = MediaStore.Images.Media.getContentUri("external_primary")
        val rows = ArrayList<FaceVisibleMedia>(PHOTOS)
        val canvasBitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBitmap)
        val paint = Paint()
        for (i in 0 until PHOTOS) {
            canvas.drawColor(Color.rgb(60 + random.nextInt(40), 80, 110))
            // Texture, so the JPEG costs about what a real photo costs to decode.
            repeat(1500) {
                paint.color = Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
                val x = random.nextInt(WIDTH); val y = random.nextInt(HEIGHT)
                canvas.drawRect(Rect(x, y, x + 8 + random.nextInt(120), y + 8 + random.nextInt(120)), paint)
            }
            val count = 1 + i % 3
            for (f in 0 until count) {
                val size = 900 + random.nextInt(400)
                val x = 200 + f * (WIDTH - 400) / count
                canvas.drawBitmap(face, null, Rect(x, 600, x + size, 600 + size), null)
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "atlas-bench-552-$i.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = checkNotNull(target.contentResolver.insert(collection, values))
            target.contentResolver.openOutputStream(uri)!!.use {
                canvasBitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)
            }
            target.contentResolver.update(uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            val id = uri.lastPathSegment!!.toLong()
            rows += FaceVisibleMedia(
                uri = "content://media/external_primary/images/media/$id",
                volumeName = "external_primary", isVideo = false, id = id,
                providerVersion = MediaStore.getVersion(target, "external_primary"),
                generationModified = 1, sizeBytes = 1, modifiedAtSeconds = 1_700_000_000L + i,
                editorRevision = 0, widthPixels = WIDTH, heightPixels = HEIGHT,
                orientationDegrees = 0, takenAtMillis = 1_700_000_000_000L + i)
        }
        canvasBitmap.recycle()
        face.recycle()
        return rows
    }

    private fun storeContext(dir: File): Context {
        val file = File(dir.apply { mkdirs() }, "faces.db")
        return object : ContextWrapper(target) {
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
    }

    /** The production pipeline, with stage timers; the drawn face scores ~0.91. */
    private fun timedPipeline(t: Timers, setup: Setup): FaceEmbeddingPipeline {
        val detector = OnnxFaceDetector.fromAssets(target.assets, scoreThreshold = 0.6f,
            intraOpThreads = setup.threads)
        val embedder = OnnxFaceEmbedder.fromAssets(target.assets, intraOpThreads = setup.threads)
        return FaceEmbeddingPipeline(detector.detectorVersion, embedder.embedderVersion) { frame ->
            val bitmap = (frame as AndroidBitmapFaceFrame).bitmap
            val d0 = System.nanoTime()
            val faces = detector.detectFaces(bitmap)
            val d1 = System.nanoTime()
            t.yunetNs.addAndGet(d1 - d0)
            t.faces.addAndGet(faces.size.toLong())
            faces.map { face ->
                val e0 = System.nanoTime()
                val embedding = embedder.embed(bitmap, face)
                t.sfaceNs.addAndGet(System.nanoTime() - e0)
                FaceEmbeddedDetection(FacePixelBounds(
                    floor(face.left.toDouble()).toInt(), floor(face.top.toDouble()).toInt(),
                    ceil(face.right.toDouble()).toInt(), ceil(face.bottom.toDouble()).toInt()
                ), embedding.vector)
            }
        }
    }

    private fun host(
        dir: File, t: Timers, setup: Setup, scheduler: PeopleScanScheduling
    ): Pair<PeopleDiscoveryHost, FaceIndexStore> {
        val store = FaceIndexStore(storeContext(dir))
        val host = PeopleDiscoveryHost(
            store = store,
            photoGrantCurrent = { true },
            inferenceFactory = { timedPipeline(t, setup) },
            readerFor = { directory ->
                val real = AndroidMediaFaceFrameReader(target.contentResolver,
                    { Uri.parse(directory.authorizedUri(it)) })
                object : FaceFrameReader {
                    override fun open(lease: nl.atlas.gallery.featureface.FaceScanLease,
                                      maxLongEdgePixels: Int) = real.open(lease, maxLongEdgePixels)
                    override fun openFrames(lease: nl.atlas.gallery.featureface.FaceScanLease,
                                            maxLongEdgePixels: Int): FaceSourceFrames {
                        val s = System.nanoTime()
                        return real.openFrames(lease, maxLongEdgePixels).also {
                            t.decodeNs.addAndGet(System.nanoTime() - s)
                            t.photos.incrementAndGet()
                        }
                    }
                }
            },
            power = { PeoplePowerState(charging = true, batteryNotLow = true, storageNotLow = true) },
            scheduler = scheduler
        )
        return host to store
    }

    private fun summary(t: Timers, wallMs: Double, processed: Int): JSONObject {
        val photos = t.photos.get().coerceAtLeast(1)
        fun ms(ns: Long) = Math.round(ns / 1e5) / 10.0
        val decode = ms(t.decodeNs.get()); val yunet = ms(t.yunetNs.get()); val sface = ms(t.sfaceNs.get())
        return JSONObject()
            .put("processed", processed)
            .put("faces", t.faces.get())
            .put("wall_ms", Math.round(wallMs))
            .put("photos_per_s", Math.round(processed * 10_000.0 / wallMs) / 10.0)
            .put("decode_ms_per_photo", Math.round(decode * 10 / photos) / 10.0)
            .put("yunet_ms_per_photo", Math.round(yunet * 10 / photos) / 10.0)
            .put("sface_ms_per_face", Math.round(sface * 10 / t.faces.get().coerceAtLeast(1)) / 10.0)
            .put("other_ms_per_photo",
                Math.round((wallMs - decode - yunet - sface) * 10 / photos) / 10.0)
    }

    private fun inProcess(rows: List<FaceVisibleMedia>, setup: Setup): JSONObject {
        val dir = File(target.cacheDir, "bench-552-a-" + UUID.randomUUID())
        val t = Timers()
        val (host, store) = host(dir, t, setup, object : PeopleScanScheduling {
            override fun start() = Unit
            override fun next() = Unit
            override fun later() = Unit
            override fun cancel() = Unit
        })
        try {
            host.start("bench-grant-a", rows)
            val epoch = checkNotNull(host.displayEpoch("bench-grant-a"))
            val start = SystemClock.elapsedRealtimeNanos()
            var guard = 0
            while (guard++ < 200) {
                val batch = runBlocking { runPeopleScanBatch(target, thermalAllowed = { true }) { false } }
                    ?: break
                if (batch.stop == FaceBatchStop.NO_PENDING) break
            }
            val wallMs = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
            val progress = checkNotNull(store.progress(epoch))
            return summary(t, wallMs, progress.processed)
        } finally {
            host.revoke()
            PeopleScanWorkRuntime.clear()
            store.close()
            dir.deleteRecursively()
        }
    }

    private fun workManager(rows: List<FaceVisibleMedia>, setup: Setup): JSONObject {
        val dir = File(target.cacheDir, "bench-552-b-" + UUID.randomUUID())
        val t = Timers()
        val (host, store) = host(dir, t, setup, object : PeopleScanScheduling {
            override fun start() = PeopleScanWorkScheduler.enqueueAfterVerifiedOptIn(target)
            override fun next() = PeopleScanWorkScheduler.enqueueNextBatch(target)
            override fun later() = PeopleScanWorkScheduler.enqueueNextBatch(target, delayed = true)
            override fun cancel() = PeopleScanWorkScheduler.cancel(target)
        })
        try {
            val start = SystemClock.elapsedRealtimeNanos()
            host.start("bench-grant-b", rows)
            val epoch = checkNotNull(host.displayEpoch("bench-grant-b"))
            val deadline = SystemClock.elapsedRealtime() + 240_000
            var processed = 0
            while (SystemClock.elapsedRealtime() < deadline) {
                val p = store.progress(epoch)
                processed = (p?.processed ?: 0) + (p?.failed ?: 0)
                if (processed >= rows.size) break
                Thread.sleep(50)
            }
            val wallMs = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
            return summary(t, wallMs, processed).put("timed_out", processed < rows.size)
        } finally {
            PeopleScanWorkScheduler.cancel(target)
            host.revoke()
            PeopleScanWorkRuntime.clear()
            store.close()
            dir.deleteRecursively()
        }
    }
}
