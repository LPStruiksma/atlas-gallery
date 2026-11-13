package nl.atlas.gallery.benchmark

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.sqrt
import kotlin.random.Random
import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featureface.onnx.SFaceModel
import nl.atlas.gallery.featureface.onnx.YuNetModel
import nl.atlas.gallery.featureface.search.FaceMatchTier
import nl.atlas.gallery.featureface.search.FaceQuery
import nl.atlas.gallery.featureface.search.FaceQueryEngine
import nl.atlas.gallery.featureface.search.FaceQueryStatus
import nl.atlas.gallery.featureface.search.FaceSearchCorpus
import nl.atlas.gallery.featurepeople.cluster.ClusterReviewFacts
import nl.atlas.gallery.featurepersistence.IndexedFaceBox
import nl.atlas.gallery.featurepersistence.IndexedMedia
import nl.atlas.gallery.featurevisual.onnx.SiglipImageEmbedder
import nl.atlas.gallery.featurevisual.onnx.SiglipModel
import nl.atlas.gallery.featurevisual.onnx.SiglipModelFile
import nl.atlas.gallery.featurevisual.pose.PoseModel
import nl.atlas.gallery.featurevisual.search.VisualQuery
import nl.atlas.gallery.featurevisual.search.VisualQueryBands
import nl.atlas.gallery.featurevisual.search.VisualQueryEngine
import nl.atlas.gallery.featurevisual.search.VisualQueryStatus
import nl.atlas.gallery.featurevisual.search.VisualReference
import nl.atlas.gallery.featurevisual.search.VisualVectorSource
import nl.atlas.gallery.featurevisual.store.VisualIndexedVector
import nl.atlas.gallery.featurevisual.store.VisualMedia
import nl.atlas.gallery.featurevisual.store.VisualVectorCursor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * VS-B1 (#468): on-device model and retrieval benchmark.
 *
 * Per bundled model (YuNet, SFace, SigLIP 2 B/16 int8, BlazePose detector + landmarks) it
 * measures cold load (pinned bytes read and verified, ORT session created), the first run, and
 * warm p50/p95 of `OrtSession.run` at 1 and 4 intra-op threads, plus peak RSS. It then times
 * exact brute-force queries in [FaceQueryEngine] (300k synthetic 128-d faces) and
 * [VisualQueryEngine] (150k synthetic 768-d vectors), and checks that planted matches are found.
 *
 * Privacy: every input is generated in code from a fixed seed. It reads no MediaStore, no
 * photos and no ATLAS index or database; the only files it touches are the bundled model
 * assets and a benchmark-only copy of the SigLIP model in the cache, deleted at the end.
 * It writes one JSON object of aggregate numbers to stdout and logcat (`ATLAS_SCALE`).
 *
 * Normal CI runs the short variant. `-e atlasBenchFull true` runs the full one
 * (see docs/visual-search/VS_B_BENCHMARKS.md). Timings never fail the test; a broken model
 * contract or a missed planted match does.
 */
@RunWith(AndroidJUnit4::class)
class VisualSearchModelBenchmarkTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val full = InstrumentationRegistry.getArguments().getString(FULL_ARG) == "true"

    /** Warm-up and measured runs per (model, thread count), and warm query repeats. */
    private val warmup = if (full) 5 else 1
    private val iterations = if (full) 30 else 5
    private val queryRuns = if (full) 10 else 3

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    /** One ONNX graph of a model, with the fixed input shape the app feeds it. */
    private class Graph(val load: () -> OrtSession, val shape: LongArray)

    @Test
    fun measureModelsAndBruteForceQueries() {
        val report = BenchmarkReport(if (full) "full" else "short")
        val benchDir = File(context.cacheDir, "vs-b1-bench").apply { deleteRecursively(); mkdirs() }
        try {
            benchYuNet(report)
            benchSFace(report)
            benchSiglip(report, benchDir)
            benchBlazePose(report)
            benchFaceQuery(report)
            benchVisualQuery(report)
        } finally {
            benchDir.deleteRecursively()
        }
        val text = report.emit()
        val parsed = JSONObject(text)
        assertEquals(4, parsed.getJSONArray("models").length())
        assertEquals(2, parsed.getJSONArray("queries").length())
    }

    private fun options(threads: Int) = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setInterOpNumThreads(1)
        setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR)
    }

    private fun asset(path: String): ByteArray = context.assets.open(path).use { it.readBytes() }

    private fun benchYuNet(report: BenchmarkReport) {
        var size = 0L
        benchModel(report, "yunet_2023mar", { size }) { threads ->
            val bytes = asset(YuNetModel.ASSET_PATH).also { YuNetModel.requirePinned(it); size = it.size.toLong() }
            val s = YuNetModel.INPUT_SIZE.toLong()
            listOf(Graph({ env.createSession(bytes, options(threads)) }, longArrayOf(1, 3, s, s)))
        }
    }

    private fun benchSFace(report: BenchmarkReport) {
        var size = 0L
        benchModel(report, "sface_2021dec_int8", { size }) { threads ->
            val bytes = asset(SFaceModel.ASSET_PATH).also { SFaceModel.requirePinned(it); size = it.size.toLong() }
            val s = SFaceModel.INPUT_SIZE.toLong()
            listOf(Graph({ env.createSession(bytes, options(threads)) }, longArrayOf(1, 3, s, s)))
        }
    }

    private fun benchSiglip(report: BenchmarkReport, dir: File) {
        // Joining the asset parts happens once per install in the app; it is not part of a
        // load. Join a benchmark-only copy first, then time what every app open does:
        // re-hash the joined file against its pin and create the session from the path.
        val file = SiglipImageEmbedder.materializeFromAssets(context.assets, dir)
        benchModel(report, "siglip2_b16_224_int8", { file.length() }) { threads ->
            // Verifies size and SHA-256 of the existing file (no parts are opened when it does).
            check(SiglipModelFile.materialize(dir, emptyList()) == file)
            listOf(Graph({ env.createSession(file.absolutePath, options(threads)) }, longArrayOf(1, 3, 224, 224)))
        }
    }

    private fun benchBlazePose(report: BenchmarkReport) {
        var size = 0L
        benchModel(report, "blazepose_2023mar_detector_plus_landmarks", { size }) { threads ->
            val det = PoseModel.requirePinned(asset(PoseModel.DETECTOR_ASSET),
                PoseModel.DETECTOR_SHA256, PoseModel.DETECTOR_SIZE_BYTES, "person detector")
            val pose = PoseModel.requirePinned(asset(PoseModel.LANDMARK_ASSET),
                PoseModel.LANDMARK_SHA256, PoseModel.LANDMARK_SIZE_BYTES, "pose landmark model")
            size = det.size.toLong() + pose.size
            listOf(
                Graph({ env.createSession(det, options(threads)) }, longArrayOf(1, 3, 224, 224)),
                Graph({ env.createSession(pose, options(threads)) }, longArrayOf(1, 256, 256, 3)),
            )
        }
    }

    /**
     * Times one model. [prepare] reads and verifies the model bytes and returns its graphs for a
     * thread count; it is part of the cold load the first time. Every warm iteration runs all
     * graphs in order (for BlazePose: detector, then landmarks), one `run` each.
     */
    private fun benchModel(
        report: BenchmarkReport,
        name: String,
        modelBytes: () -> Long,
        prepare: (threads: Int) -> List<Graph>,
    ) {
        val memory = MemoryWindow.open()
        val latencies = ArrayList<BenchmarkReport.Latency>()
        var coldLoadMs = 0.0
        var firstRunMs = 0.0
        var memoryResult: MemoryWindow.Result? = null
        for ((pass, threads) in THREADS.withIndex()) {
            val loadStart = System.nanoTime()
            val graphs = prepare(threads)
            val sessions = ArrayList<OrtSession>()
            try {
                graphs.forEach { sessions += it.load() }
                val loadMs = elapsedMs(loadStart)
                val inputs = graphs.mapIndexed { i, g ->
                    val n = g.shape.fold(1L) { a, b -> a * b }.toInt()
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(syntheticInput(n, seed = i + 1)), g.shape)
                }
                try {
                    val names = sessions.map { it.inputNames.single() }
                    fun once() {
                        for (i in sessions.indices) {
                            sessions[i].run(mapOf(names[i] to inputs[i])).use { result ->
                                assertTrue("$name produced no output", result.size() > 0)
                            }
                        }
                    }
                    val firstStart = System.nanoTime()
                    once()
                    if (pass == 0) {
                        coldLoadMs = loadMs
                        firstRunMs = elapsedMs(firstStart)
                    }
                    repeat(warmup) { once() }
                    val samples = DoubleArray(iterations) {
                        val t = System.nanoTime()
                        once()
                        elapsedMs(t)
                    }
                    latencies += BenchmarkReport.Latency(threads, samples)
                    if (pass == THREADS.lastIndex) memoryResult = memory.close()
                } finally {
                    inputs.forEach { it.close() }
                }
            } finally {
                sessions.forEach { it.close() }
            }
        }
        report.addModel(name, modelBytes(), coldLoadMs, firstRunMs, latencies, checkNotNull(memoryResult))
    }

    private fun benchFaceQuery(report: BenchmarkReport) {
        val dim = SFaceModel.DIMENSION
        val model = SFaceModel.EMBEDDER_VERSION
        val rng = Random(300_000)
        val target = unit(FloatArray(dim) { gaussian(rng) })
        val pool = Array(POOL) { unit(FloatArray(dim) { gaussian(rng) }) }
        val box = IndexedFaceBox(0.2f, 0.2f, 0.5f, 0.6f)
        val planted = HashSet<NativeFaceObservationId>()

        val memory = MemoryWindow.open()
        val buildStart = System.nanoTime()
        val builder = FaceSearchCorpus.Builder(model, FACES)
        for (i in 0 until FACES) {
            val id = NativeFaceObservationId(hex32(i + 1))
            // About two faces per photo: 150k media for 300k faces.
            val media = IndexedMedia("content://synthetic/media/${i / 2}", "r")
            val plant = i % (FACES / PLANTED) == 7
            val vector = if (plant) near(target, 0.55f + 0.4f * rng.nextFloat(), rng) else pool[i % POOL]
            if (plant) planted += id
            builder.add(id, media, box, null, vector)
        }
        val corpus = builder.build(ClusterReviewFacts.NONE, clustersPublished = true)
        val buildMs = elapsedMs(buildStart)
        assertEquals(FACES, corpus.faceCount)

        val query = FaceQuery(model, listOf(target))
        val firstStart = System.nanoTime()
        var result = FaceQueryEngine.search(corpus, query)
        val firstMs = elapsedMs(firstStart)
        val samples = DoubleArray(queryRuns) {
            val t = System.nanoTime()
            result = FaceQueryEngine.search(corpus, query)
            elapsedMs(t)
        }
        val memoryResult = memory.close()
        assertEquals(FaceQueryStatus.OK, result.status)
        val same = result.hits.flatMap { it.faces }
            .filter { it.tier == FaceMatchTier.SAME_PERSON }.map { it.face }.toSet()
        val found = planted.count { it in same }
        assertEquals("every planted face found as SAME_PERSON", planted.size, found)
        report.addQuery("FaceQueryEngine", FACES, dim, buildMs, firstMs,
            BenchmarkReport.Latency(1, samples), result.hits.size, planted.size, found, memoryResult)
    }

    private fun benchVisualQuery(report: BenchmarkReport) {
        val dim = SiglipModel.DIMENSION
        val model = "vs-b1-synthetic:1"
        val rng = Random(150_000)
        val target = unit(FloatArray(dim) { gaussian(rng) })

        val memory = MemoryWindow.open()
        val setupStart = System.nanoTime()
        val source = SyntheticVisualSource(model, target, rng)
        val setupMs = elapsedMs(setupStart)
        val engine = VisualQueryEngine(source)
        val query = VisualQuery(model, VisualReference { target })
        val firstStart = System.nanoTime()
        var result = engine.search(query)
        val firstMs = elapsedMs(firstStart)
        val samples = DoubleArray(queryRuns) {
            val t = System.nanoTime()
            result = engine.search(query)
            elapsedMs(t)
        }
        val memoryResult = memory.close()
        assertEquals(VisualQueryStatus.OK, result.status)
        assertEquals(VISUALS, result.scanned)
        val hitRows = result.hits.map { source.rowOf(it.media.key) }.toSet()
        val found = source.planted.count { it in hitRows }
        assertEquals("every planted vector found", source.planted.size, found)
        report.addQuery("VisualQueryEngine", VISUALS, dim, setupMs, firstMs,
            BenchmarkReport.Latency(1, samples), result.hits.size, source.planted.size, found, memoryResult)
    }

    /**
     * 150k rows in keyset pages, like the store: rows reuse a pool of random unit vectors,
     * except the planted rows, which sit inside the shown bands (FLOOR to NEAR_DUPLICATE). Keys are made
     * once, so paging costs object creation but not string formatting.
     */
    private class SyntheticVisualSource(
        override val modelVersion: String,
        target: FloatArray,
        rng: Random,
    ) : VisualVectorSource {
        private val keys = Array(VISUALS) { "content://synthetic/v/" + it.toString().padStart(6, '0') }
        private val pool = Array(POOL) { unit(FloatArray(SiglipModel.DIMENSION) { gaussian(rng) }) }
        private val plantedVectors = (0 until VISUALS step VISUALS / PLANTED)
            .associateWith {
                // Inside the shown bands whatever their calibration (VS-S2c).
                near(target, VisualQueryBands.FLOOR + 0.01f +
                    (VisualQueryBands.NEAR_DUPLICATE - VisualQueryBands.FLOOR - 0.02f) * rng.nextFloat(), rng)
            }
        val planted: Set<Int> get() = plantedVectors.keys

        fun rowOf(key: String): Int = key.takeLast(6).toInt()

        override fun page(after: VisualVectorCursor?, limit: Int): List<VisualIndexedVector> {
            val start = if (after == null) 0 else rowOf(after.mediaKey) + 1
            return (start until minOf(VISUALS, start + limit)).map { i ->
                val media = VisualMedia(keys[i], "r1")
                VisualIndexedVector(media, modelVersion, plantedVectors[i] ?: pool[i % POOL],
                    VisualVectorCursor("e1", modelVersion, media.key, null))
            }
        }

        override fun lookup(media: VisualMedia): FloatArray? = null
    }

    companion object {
        /** Instrumentation argument: `-e atlasBenchFull true` runs the full variant. */
        const val FULL_ARG = "atlasBenchFull"
        val THREADS = listOf(1, 4)
        const val FACES = 300_000
        const val VISUALS = 150_000
        const val PLANTED = 40
        private const val POOL = 4_096

        private fun elapsedMs(start: Long) = (System.nanoTime() - start) / 1e6

        /** Deterministic values in [0, 1); content does not change these graphs' cost. */
        fun syntheticInput(n: Int, seed: Int): FloatArray {
            var x = 0x9E3779B9.toInt() * seed
            return FloatArray(n) {
                x = x * 1_664_525 + 1_013_904_223
                (x ushr 8) / 16_777_216f
            }
        }

        fun hex32(i: Int): String = i.toString(16).padStart(32, '0')

        fun gaussian(rng: Random): Float {
            val u1 = rng.nextDouble().coerceAtLeast(1e-12)
            val u2 = rng.nextDouble()
            return (sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2 * Math.PI * u2)).toFloat()
        }

        fun unit(v: FloatArray): FloatArray {
            var s = 0.0
            for (x in v) s += x.toDouble() * x
            val n = sqrt(s).toFloat()
            return FloatArray(v.size) { v[it] / n }
        }

        /** A unit vector at cosine [c] to the unit vector [base]. */
        fun near(base: FloatArray, c: Float, rng: Random): FloatArray {
            val r = unit(FloatArray(base.size) { gaussian(rng) })
            var p = 0f
            for (k in base.indices) p += r[k] * base[k]
            val orth = unit(FloatArray(base.size) { r[it] - p * base[it] })
            val s = sqrt(1f - c * c)
            return unit(FloatArray(base.size) { c * base[it] + s * orth[it] })
        }
    }
}
