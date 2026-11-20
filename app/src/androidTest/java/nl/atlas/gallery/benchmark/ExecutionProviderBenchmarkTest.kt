package nl.atlas.gallery.benchmark

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtProvider
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.FloatBuffer
import java.util.EnumSet
import java.util.Locale
import java.util.TreeMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import nl.atlas.gallery.featureface.onnx.FaceOnnxThreads
import nl.atlas.gallery.featureface.onnx.SFaceModel
import nl.atlas.gallery.featureface.onnx.SyntheticFace
import nl.atlas.gallery.featureface.onnx.YuNetDecoder
import nl.atlas.gallery.featureface.onnx.YuNetFace
import nl.atlas.gallery.featureface.onnx.YuNetLetterbox
import nl.atlas.gallery.featureface.onnx.YuNetModel
import nl.atlas.gallery.featurevisual.onnx.SiglipImageEmbedder
import nl.atlas.gallery.featurevisual.onnx.SiglipModel
import nl.atlas.gallery.featurevisual.pose.PoseModel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PERF-EP (#581): ONNX Runtime execution providers for the bundled face, visual and pose graphs.
 *
 * Benchmark only: no production session changes. It first asks the packaged
 * `onnxruntime-android` which execution providers it exposes. For every exposed provider in
 * [Ep] it then runs YuNet, SFace int8, SigLIP 2 image tower int8 and BlazePose (detector, then
 * landmarks) on fixed synthetic inputs and records:
 * - session creation time (model bytes are read and pin-checked before the clock starts);
 * - first run, then warm p50/p95 of one pass over the model's graphs;
 * - peak RSS (`VmHWM`, reset per window where the kernel allows) and native heap growth;
 * - node placement from ORT's own profiler for the first run: kernels per execution provider,
 *   and the op types that stayed on the CPU provider. A provider with no kernels is labelled
 *   `fallback`, never accelerated;
 * - output equivalence against the CPU run on the same inputs: cosine distance for the SFace
 *   and SigLIP embeddings, decoded box and score deltas for YuNet, raw maximum absolute delta
 *   for every float output.
 *
 * "cpu" is what the app runs today: CPU provider, intra-op threads as in production
 * ([FaceOnnxThreads] for faces, 2 for SigLIP, 1 for pose). "xnnpack" gives XNNPACK the same
 * thread count and ORT's own pool one thread with spinning off, as ORT recommends.
 * "nnapi" uses default NNAPI flags (NNAPI may still choose its CPU reference device);
 * "nnapi_no_cpu" sets CPU_DISABLED so only a real accelerator can take nodes.
 *
 * Privacy: inputs are generated in code ([SyntheticFace] drawings and seeded noise). It reads
 * no MediaStore, no photos and no ATLAS index; it writes one JSON object of aggregate numbers
 * (no vectors, paths or pixels) to stdout and logcat under `ATLAS_SCALE`, plus one Markdown
 * table row per (model, provider). Timings and deltas never fail the test; only a broken CPU
 * baseline does. Emulator numbers show relative behaviour and correctness only, never S24+ speed.
 *
 * `-e atlasBenchFull true` runs more warm iterations.
 */
@RunWith(AndroidJUnit4::class)
class ExecutionProviderBenchmarkTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val full = InstrumentationRegistry.getArguments().getString(FULL_ARG) == "true"
    private val warmup = if (full) 3 else 1
    private val iterations = if (full) 20 else 5

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private lateinit var workDir: File

    /** One execution-provider configuration under test. */
    enum class Ep(val label: String, val provider: OrtProvider, val ortName: String) {
        CPU("cpu", OrtProvider.CPU, "CPUExecutionProvider"),
        XNNPACK("xnnpack", OrtProvider.XNNPACK, "XnnpackExecutionProvider"),
        NNAPI("nnapi", OrtProvider.NNAPI, "NnapiExecutionProvider"),
        NNAPI_NO_CPU("nnapi_no_cpu", OrtProvider.NNAPI, "NnapiExecutionProvider"),
    }

    /** How a model's outputs are compared with the CPU run. */
    private enum class Compare { EMBEDDING, YUNET, RAW }

    /** One ONNX graph: how to create its session, its input shape and its synthetic inputs. */
    private class Graph(
        val name: String,
        val create: (OrtSession.SessionOptions) -> OrtSession,
        val shape: LongArray,
        val inputs: List<FloatArray>,
    )

    private class Model(
        val name: String,
        val int8: Boolean,
        val threads: Int,
        val compare: Compare,
        val embeddingOutput: String?,
        val graphs: List<Graph>,
        /**
         * Configurations whose session creation aborts the whole process inside ORT (a native
         * `std::terminate`, which no Java catch can stop). They are reported, never run.
         */
        val abortsProcess: Set<Ep> = emptySet(),
    )

    /** Outputs of one provider run: per graph, per evaluated input, float outputs by name. */
    private class Outputs(val byGraph: Map<String, List<Map<String, FloatArray>>>)

    @Test
    fun measureExecutionProviders() {
        workDir = File(context.cacheDir, "perf-ep-bench").apply { deleteRecursively(); mkdirs() }
        val available = OrtEnvironment.getAvailableProviders()
        val root = JSONObject()
            .put("unit", "PERF-EP")
            .put("schema", 1)
            .put("variant", if (full) "full" else "short")
            .put("ort_version", runCatching { env.version }.getOrDefault("unknown"))
            .put("inputs", "synthetic, generated in code")
            .put("device", deviceJson())
            .put("available_providers", JSONArray().apply { available.map { it.name }.sorted().forEach { put(it) } })
            .put("tolerance", JSONObject()
                .put("embedding_max_cosine_distance", EMBEDDING_TOLERANCE)
                .put("embedding_similarity_shift_bound", round4(sqrt(2.0 * EMBEDDING_TOLERANCE)))
                .put("yunet_max_box_delta_px", BOX_TOLERANCE_PX)
                .put("yunet_max_score_delta", SCORE_TOLERANCE))
        val rows = ArrayList<String>()
        val models = JSONArray()
        try {
            assertTrue("ORT exposes no CPU provider", OrtProvider.CPU in available)
            for (model in models()) {
                models.put(benchModel(model, available, rows))
            }
        } finally {
            workDir.deleteRecursively()
        }
        root.put("models", models)
        emit(root, rows)

        assertEquals(4, models.length())
        for (i in 0 until models.length()) {
            val m = models.getJSONObject(i)
            val cpu = m.getJSONArray("providers").getJSONObject(0)
            assertEquals("${m.getString("model")} CPU baseline", "ok", cpu.getString("status"))
        }
    }

    // ---------------------------------------------------------------- models and inputs

    private fun asset(path: String): ByteArray = context.assets.open(path).use { it.readBytes() }

    private fun models(): List<Model> {
        val yunet = YuNetModel.requirePinned(asset(YuNetModel.ASSET_PATH))
        val sface = SFaceModel.requirePinned(asset(SFaceModel.ASSET_PATH))
        val siglip = SiglipImageEmbedder.materializeFromAssets(context.assets, workDir)
        val det = PoseModel.requirePinned(asset(PoseModel.DETECTOR_ASSET),
            PoseModel.DETECTOR_SHA256, PoseModel.DETECTOR_SIZE_BYTES, "person detector")
        val pose = PoseModel.requirePinned(asset(PoseModel.LANDMARK_ASSET),
            PoseModel.LANDMARK_SHA256, PoseModel.LANDMARK_SIZE_BYTES, "pose landmark model")
        val y = YuNetModel.INPUT_SIZE
        val s = SFaceModel.INPUT_SIZE
        return listOf(
            Model("yunet_2023mar", int8 = false, threads = FaceOnnxThreads.intraOp, compare = Compare.YUNET,
                embeddingOutput = null, graphs = listOf(Graph("yunet", { env.createSession(yunet, it) },
                    longArrayOf(1, 3, y.toLong(), y.toLong()), listOf(
                        bgr(SyntheticFace.Spec(y, y), y),
                        bgr(SyntheticFace.Spec(y, y, centerX = 0.3f, centerY = 0.4f, size = 0.5f), y),
                        noise(3 * y * y, seed = 11, lo = 0f, hi = 255f),
                    )))),
            Model("sface_2021dec_int8", int8 = true, threads = FaceOnnxThreads.intraOp, compare = Compare.EMBEDDING,
                embeddingOutput = SFaceModel.OUTPUT_NAME, graphs = listOf(Graph("sface", { env.createSession(sface, it) },
                    longArrayOf(1, 3, s.toLong(), s.toLong()), listOf(
                        bgr(SyntheticFace.Spec(s, s), s),
                        bgr(SyntheticFace.Spec(s, s, centerX = 0.46f, centerY = 0.54f, size = 1.1f), s),
                        noise(3 * s * s, seed = 21, lo = 0f, hi = 255f),
                    )))),
            Model("siglip2_b16_224_int8", int8 = true, threads = 2, compare = Compare.EMBEDDING,
                embeddingOutput = SiglipModel.OUTPUT_NAME, graphs = listOf(Graph("siglip_image",
                    { env.createSession(siglip.absolutePath, it) }, longArrayOf(1, 3, 224, 224), listOf(
                        rgbPlanarSigned(SyntheticFace.Spec(224, 224)),
                        rgbPlanarSigned(SyntheticFace.Spec(224, 224, centerX = 0.4f, size = 0.7f)),
                        noise(3 * 224 * 224, seed = 31, lo = -1f, hi = 1f),
                    )))),
            Model("blazepose_2023mar_detector_plus_landmarks", int8 = false, threads = 1, compare = Compare.RAW,
                embeddingOutput = null, graphs = listOf(
                    Graph("pose_detector", { env.createSession(det, it) }, longArrayOf(1, 3, 224, 224), listOf(
                        rgbPlanarSigned(SyntheticFace.Spec(224, 224)),
                        noise(3 * 224 * 224, seed = 41, lo = -1f, hi = 1f),
                    )),
                    Graph("pose_landmarks", { env.createSession(pose, it) }, longArrayOf(1, 256, 256, 3), listOf(
                        rgbInterleavedUnit(SyntheticFace.Spec(256, 256)),
                        noise(256 * 256 * 3, seed = 42, lo = 0f, hi = 1f),
                    )),
                ),
                // API 35 x86_64 emulator, ORT 1.23.2: creating the BlazePose sessions ended in
                // libc++abi "terminating" (SIGABRT) inside OrtSession.createSession with XNNPACK
                // (PR #691 run 44679387896) and with NNAPI (run 44681678879), while all three other
                // models ran under every provider. Both aborts happen during graph partitioning,
                // so NNAPI with CPU disabled is not run either.
                abortsProcess = setOf(Ep.XNNPACK, Ep.NNAPI, Ep.NNAPI_NO_CPU)),
        )
    }

    /** YuNet/SFace layout: planar B, G, R, values 0..255 (as the app packs it). */
    private fun bgr(spec: SyntheticFace.Spec, size: Int): FloatArray =
        YuNetLetterbox(spec.width, spec.height, inputSize = size).packBgrPlanar(SyntheticFace.argb(spec))

    /** SigLIP / pose detector layout: planar R, G, B in [-1, 1]. */
    private fun rgbPlanarSigned(spec: SyntheticFace.Spec): FloatArray {
        val px = SyntheticFace.argb(spec)
        val plane = px.size
        return FloatArray(3 * plane) { i ->
            val c = i / plane
            ((px[i % plane] shr (16 - 8 * c)) and 0xFF) / 127.5f - 1f
        }
    }

    /** Pose landmark layout: NHWC R, G, B in [0, 1]. */
    private fun rgbInterleavedUnit(spec: SyntheticFace.Spec): FloatArray {
        val px = SyntheticFace.argb(spec)
        return FloatArray(3 * px.size) { i ->
            val c = i % 3
            ((px[i / 3] shr (16 - 8 * c)) and 0xFF) / 255f
        }
    }

    private fun noise(n: Int, seed: Int, lo: Float, hi: Float): FloatArray {
        val unit = VisualSearchModelBenchmarkTest.syntheticInput(n, seed)
        return FloatArray(n) { lo + (hi - lo) * unit[it] }
    }

    // ---------------------------------------------------------------- measuring

    private fun options(ep: Ep, threads: Int, profilePrefix: String): OrtSession.SessionOptions =
        OrtSession.SessionOptions().apply {
            setInterOpNumThreads(1)
            setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR)
            when (ep) {
                Ep.CPU -> setIntraOpNumThreads(threads)
                Ep.XNNPACK -> {
                    setIntraOpNumThreads(1)
                    addConfigEntry("session.intra_op.allow_spinning", "0")
                    addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
                }
                Ep.NNAPI -> {
                    setIntraOpNumThreads(threads)
                    addNnapi()
                }
                Ep.NNAPI_NO_CPU -> {
                    setIntraOpNumThreads(threads)
                    addNnapi(EnumSet.of(NNAPIFlags.CPU_DISABLED))
                }
            }
            // Profiles only the first run: endProfiling() is called right after it.
            enableProfiling(profilePrefix)
        }

    private fun benchModel(model: Model, available: Set<OrtProvider>, rows: MutableList<String>): JSONObject {
        val results = JSONArray()
        var cpuOutputs: Outputs? = null
        for (ep in Ep.values()) {
            val json = JSONObject().put("ep", ep.label)
            if (ep.provider !in available) {
                json.put("status", "absent")
                results.put(json)
                rows += row(model, json)
                continue
            }
            if (ep in model.abortsProcess) {
                json.put("status", "aborts_process")
                    .put("note", "ORT aborted the process (std::terminate) creating these sessions with a non-CPU provider; not run")
                results.put(json)
                rows += row(model, json)
                continue
            }
            // Names the configuration in logcat first, so a native abort still says where it was.
            Log.i(BenchmarkReport.TAG, "PERF-EP start ${model.name} ${ep.label}")
            val outputs = try {
                measure(model, ep, json)
            } catch (e: Exception) {
                json.put("status", "failed").put("error", clean(e))
                null
            }
            if (outputs != null) {
                if (ep == Ep.CPU) cpuOutputs = outputs
                else cpuOutputs?.let { json.put("vs_cpu", compare(model, it, outputs)) }
            }
            results.put(json)
            rows += row(model, json)
        }
        return JSONObject()
            .put("model", model.name)
            .put("int8", model.int8)
            .put("threads", model.threads)
            .put("graphs", model.graphs.size)
            .put("providers", results)
    }

    /** Runs one model under one provider; fills [json] and returns the outputs for comparison. */
    private fun measure(model: Model, ep: Ep, json: JSONObject): Outputs {
        val memory = MemoryWindow.open()
        val sessions = ArrayList<OrtSession>()
        val allOptions = ArrayList<OrtSession.SessionOptions>()
        val tensors = ArrayList<List<OnnxTensor>>()
        try {
            val createStart = System.nanoTime()
            for ((g, graph) in model.graphs.withIndex()) {
                val opts = options(ep, model.threads, File(workDir, "prof-${ep.label}-$g").absolutePath)
                allOptions += opts
                sessions += graph.create(opts)
            }
            val createMs = elapsedMs(createStart)
            model.graphs.forEach { graph ->
                tensors += graph.inputs.map { OnnxTensor.createTensor(env, FloatBuffer.wrap(it), graph.shape) }
            }
            val names = sessions.map { it.inputNames.single() }
            val outputs = model.graphs.map { ArrayList<Map<String, FloatArray>>() }

            // First run (profiled), on input 0 of every graph.
            val firstStart = System.nanoTime()
            for (i in sessions.indices) outputs[i] += run(sessions[i], names[i], tensors[i][0])
            val firstMs = elapsedMs(firstStart)
            val placement = placement(sessions.map { it.endProfiling() }, ep)

            val tooSlow = firstMs > FIRST_RUN_BUDGET_MS
            if (!tooSlow) {
                fun pass() {
                    for (i in sessions.indices) sessions[i].run(mapOf(names[i] to tensors[i][0])).use { }
                }
                repeat(warmup) { pass() }
                val samples = DoubleArray(iterations) {
                    val t = System.nanoTime()
                    pass()
                    elapsedMs(t)
                }
                json.put("warm_iterations", iterations)
                    .put("warm_p50_ms", BenchmarkReport.round1(BenchmarkReport.percentile(samples, 50.0)))
                    .put("warm_p95_ms", BenchmarkReport.round1(BenchmarkReport.percentile(samples, 95.0)))
                // Remaining equivalence inputs.
                for (i in sessions.indices) {
                    for (k in 1 until tensors[i].size) outputs[i] += run(sessions[i], names[i], tensors[i][k])
                }
            }
            json.put("status", if (tooSlow) "too_slow" else "ok")
                .put("session_create_ms", BenchmarkReport.round1(createMs))
                .put("first_run_ms", BenchmarkReport.round1(firstMs))
                .put("placement", placement)
                .put("memory", memory.close().json())
            return Outputs(model.graphs.indices.associate { model.graphs[it].name to outputs[it] })
        } finally {
            tensors.flatten().forEach { it.close() }
            sessions.forEach { runCatching { it.close() } }
            allOptions.forEach { runCatching { it.close() } }
        }
    }

    private fun run(session: OrtSession, inputName: String, tensor: OnnxTensor): Map<String, FloatArray> {
        session.run(mapOf(inputName to tensor)).use { result ->
            val out = LinkedHashMap<String, FloatArray>()
            for ((name, value) in result) {
                val t = value as? OnnxTensor ?: continue
                if (t.info.type != OnnxJavaType.FLOAT) continue
                val buffer = t.floatBuffer
                out[name] = FloatArray(buffer.remaining()).also { buffer.get(it) }
            }
            assertTrue("graph produced no float output", out.isNotEmpty())
            return out
        }
    }

    /**
     * Kernels per execution provider in the profiled first run, from ORT's own profile JSON
     * (`cat` = Node, `name` ending `_kernel_time`, `args.provider`). A fused NNAPI or XNNPACK
     * partition counts as one kernel. The profile files are deleted after reading.
     */
    private fun placement(files: List<String>, ep: Ep): JSONObject {
        val byProvider = TreeMap<String, Int>()
        val cpuOps = TreeMap<String, Int>()
        var readable = true
        for (path in files) {
            val file = File(path).let { if (it.isAbsolute) it else File(workDir, path) }
            try {
                val events = JSONArray(file.readText())
                for (i in 0 until events.length()) {
                    val e = events.optJSONObject(i) ?: continue
                    if (e.optString("cat") != "Node" || !e.optString("name").endsWith("_kernel_time")) continue
                    val args = e.optJSONObject("args") ?: continue
                    val provider = args.optString("provider").ifEmpty { "unknown" }
                    byProvider[provider] = (byProvider[provider] ?: 0) + 1
                    if (provider == Ep.CPU.ortName) {
                        val op = args.optString("op_name").ifEmpty { "unknown" }
                        cpuOps[op] = (cpuOps[op] ?: 0) + 1
                    }
                }
            } catch (_: Exception) {
                readable = false
            } finally {
                file.delete()
            }
        }
        val total = byProvider.values.sum()
        val onEp = if (ep == Ep.CPU) 0 else byProvider[ep.ortName] ?: 0
        val label = when {
            !readable || total == 0 -> "unknown"
            ep == Ep.CPU -> "cpu"
            onEp == 0 -> "fallback"
            onEp == total -> "full"
            else -> "partial"
        }
        return JSONObject()
            .put("label", label)
            .put("kernels_total", total)
            .put("kernels_on_ep", onEp)
            .put("kernels_by_provider", JSONObject(byProvider.toMap()))
            .put("cpu_op_types", if (ep == Ep.CPU) JSONObject() else JSONObject(cpuOps.toMap()))
    }

    // ---------------------------------------------------------------- equivalence

    private fun compare(model: Model, cpu: Outputs, other: Outputs): JSONObject {
        var maxAbs = 0.0
        var maxRef = 0.0
        var compared = 0
        for ((graph, cpuRuns) in cpu.byGraph) {
            val runs = other.byGraph[graph] ?: continue
            for (k in 0 until minOf(cpuRuns.size, runs.size)) {
                compared++
                for ((name, ref) in cpuRuns[k]) {
                    val v = runs[k][name] ?: continue
                    if (v.size != ref.size) { maxAbs = Double.POSITIVE_INFINITY; continue }
                    for (j in ref.indices) {
                        maxAbs = max(maxAbs, abs(ref[j].toDouble() - v[j]))
                        maxRef = max(maxRef, abs(ref[j].toDouble()))
                    }
                }
            }
        }
        val json = JSONObject()
            .put("inputs_compared", compared)
            .put("max_abs_delta", sci(maxAbs))
            .put("max_abs_cpu_value", sci(maxRef))
        when (model.compare) {
            Compare.EMBEDDING -> {
                val out = checkNotNull(model.embeddingOutput)
                val graph = model.graphs.single().name
                var worst = 0.0
                val cpuRuns = cpu.byGraph.getValue(graph)
                val runs = other.byGraph.getValue(graph)
                for (k in 0 until minOf(cpuRuns.size, runs.size)) {
                    worst = max(worst, cosineDistance(cpuRuns[k].getValue(out), runs[k].getValue(out)))
                }
                json.put("max_cosine_distance", sci(worst))
                    .put("similarity_shift_bound", round4(sqrt(2.0 * worst)))
                    .put("within_tolerance", worst <= EMBEDDING_TOLERANCE)
            }
            Compare.YUNET -> {
                val cpuRuns = cpu.byGraph.getValue("yunet")
                val runs = other.byGraph.getValue("yunet")
                var facesCpu = 0
                var facesEp = 0
                var countMismatch = 0
                var boxDelta = 0.0
                var scoreDelta = 0.0
                for (k in 0 until minOf(cpuRuns.size, runs.size)) {
                    val a = decode(cpuRuns[k])
                    val b = decode(runs[k])
                    facesCpu += a.size
                    facesEp += b.size
                    if (a.size != b.size) countMismatch++
                    for (i in 0 until minOf(a.size, b.size)) {
                        boxDelta = max(boxDelta, maxOf(abs(a[i].left - b[i].left), abs(a[i].top - b[i].top),
                            abs(a[i].width - b[i].width), abs(a[i].height - b[i].height)).toDouble())
                        scoreDelta = max(scoreDelta, abs(a[i].score - b[i].score).toDouble())
                    }
                }
                json.put("decode_score_threshold", YUNET_DECODE_THRESHOLD)
                    .put("faces_cpu", facesCpu)
                    .put("faces_ep", facesEp)
                    .put("inputs_with_face_count_mismatch", countMismatch)
                    .put("max_box_delta_px", round4(boxDelta))
                    .put("max_score_delta", sci(scoreDelta))
                    .put("within_tolerance", countMismatch == 0 && boxDelta <= BOX_TOLERANCE_PX &&
                        scoreDelta <= SCORE_TOLERANCE)
            }
            Compare.RAW -> Unit
        }
        return json
    }

    /** Faces sorted by score, so equal detections line up across providers. */
    private fun decode(outputs: Map<String, FloatArray>): List<YuNetFace> =
        YuNetDecoder.decode(outputs, scoreThreshold = YUNET_DECODE_THRESHOLD).sortedByDescending { it.score }

    private fun cosineDistance(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size)
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i]
            na += a[i].toDouble() * a[i]
            nb += b[i].toDouble() * b[i]
        }
        return 1.0 - dot / (sqrt(na) * sqrt(nb))
    }

    // ---------------------------------------------------------------- report

    private fun row(model: Model, json: JSONObject): String {
        val p = json.optJSONObject("placement")
        val v = json.optJSONObject("vs_cpu")
        val mem = json.optJSONObject("memory")
        val delta = when {
            v == null -> if (json.optString("ep") == "cpu") "reference" else "-"
            v.has("max_cosine_distance") -> "cos dist ${v.get("max_cosine_distance")}"
            v.has("max_box_delta_px") -> "box ${v.get("max_box_delta_px")} px, score ${v.get("max_score_delta")}, faces ${v.get("faces_cpu")}/${v.get("faces_ep")}"
            else -> "max abs ${v.get("max_abs_delta")}"
        }
        val placement = p?.let { "${it.getString("label")} ${it.getInt("kernels_on_ep")}/${it.getInt("kernels_total")}" } ?: "-"
        return listOf(
            model.name, json.optString("ep"), json.optString("status"), placement,
            json.opt("session_create_ms") ?: "-", json.opt("first_run_ms") ?: "-",
            json.opt("warm_p50_ms") ?: "-", json.opt("warm_p95_ms") ?: "-",
            mem?.opt("peak_over_before_mb") ?: "-", delta,
        ).joinToString(" | ", prefix = "| ", postfix = " |")
    }

    private fun emit(root: JSONObject, rows: List<String>) {
        val text = root.toString()
        println("${BenchmarkReport.TAG} PERF-EP json $text")
        val chunks = text.chunked(CHUNK)
        chunks.forEachIndexed { i, part -> Log.i(BenchmarkReport.TAG, "PERF-EP json ${i + 1}/${chunks.size} $part") }
        val header = "| model | ep | status | placement (on ep/total kernels) | create ms | first ms | warm p50 ms | warm p95 ms | peak RSS over before MB | vs CPU |"
        for (line in listOf(header, "|---|---|---|---|---|---|---|---|---|---|") + rows) {
            println("${BenchmarkReport.TAG} PERF-EP row $line")
            Log.i(BenchmarkReport.TAG, "PERF-EP row $line")
        }
    }

    private fun deviceJson(): JSONObject = JSONObject()
        .put("manufacturer", Build.MANUFACTURER)
        .put("model", Build.MODEL)
        .put("soc", if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "unknown")
        .put("sdk", Build.VERSION.SDK_INT)
        .put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
        .put("cpus", Runtime.getRuntime().availableProcessors())
        .put("emulator", BenchmarkReport.isEmulator())

    /** An exception as one short line, with cache paths removed. */
    private fun clean(e: Exception): String =
        "${e.javaClass.simpleName}: ${e.message.orEmpty()}"
            .replace(context.cacheDir.absolutePath, "<cache>")
            .replace(context.filesDir.absolutePath, "<files>")
            .lineSequence().first().take(240)

    companion object {
        const val FULL_ARG = "atlasBenchFull"

        /**
         * Unit embeddings u, v with cosine distance d satisfy |q·u − q·v| ≤ ‖u − v‖ = √(2d) for
         * any unit query q, so 1e-5 moves any People or visual-search similarity by at most
         * 0.0045: well inside the gap between the People floors (0.38 to 0.45) and the
         * visual-search bands, so groups and ranks hold except for ties closer than that.
         */
        const val EMBEDDING_TOLERANCE = 1e-5
        const val BOX_TOLERANCE_PX = 1.0
        const val SCORE_TOLERANCE = 0.01
        const val YUNET_DECODE_THRESHOLD = 0.5f

        /** A provider whose first pass takes longer than this skips warm timing and extra inputs. */
        const val FIRST_RUN_BUDGET_MS = 20_000.0
        private const val CHUNK = 3_000

        private fun elapsedMs(start: Long) = (System.nanoTime() - start) / 1e6
        private fun round4(v: Double): Double = if (v.isNaN()) -1.0 else Math.round(v * 10_000.0) / 10_000.0
        private fun sci(v: Double): String = if (v.isInfinite()) "shape_mismatch" else String.format(Locale.ROOT, "%.3e", v)
    }
}
