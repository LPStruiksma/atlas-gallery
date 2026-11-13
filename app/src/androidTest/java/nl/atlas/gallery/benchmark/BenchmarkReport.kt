package nl.atlas.gallery.benchmark

import android.os.Build
import android.os.Debug
import android.util.Log
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * VS-B1 (#468) result holder and process-memory probes for [VisualSearchModelBenchmarkTest].
 *
 * Holds aggregate numbers only (timings, sizes, counts, device class). It never holds pixels,
 * vectors, media keys or file paths, so the one JSON object it emits is safe to paste anywhere.
 */
class BenchmarkReport(private val variant: String) {
    private val models = JSONArray()
    private val queries = JSONArray()

    /** Latency of one model at one intra-op thread count, in milliseconds. */
    class Latency(val threads: Int, samplesMs: DoubleArray) {
        val iterations = samplesMs.size
        val p50 = percentile(samplesMs, 50.0)
        val p95 = percentile(samplesMs, 95.0)
        val min = samplesMs.minOrNull() ?: Double.NaN
        val max = samplesMs.maxOrNull() ?: Double.NaN

        fun json() = JSONObject()
            .put("threads", threads)
            .put("iterations", iterations)
            .put("p50_ms", round1(p50))
            .put("p95_ms", round1(p95))
            .put("min_ms", round1(min))
            .put("max_ms", round1(max))
    }

    fun addModel(
        name: String,
        modelBytes: Long,
        coldLoadMs: Double,
        firstRunMs: Double,
        latencies: List<Latency>,
        memory: MemoryWindow.Result,
    ) {
        models.put(
            JSONObject()
                .put("model", name)
                .put("model_mb", round1(modelBytes / 1_048_576.0))
                .put("cold_load_ms", round1(coldLoadMs))
                .put("first_run_ms", round1(firstRunMs))
                .put("latency", JSONArray().apply { latencies.forEach { put(it.json()) } })
                .put("memory", memory.json())
        )
    }

    fun addQuery(
        engine: String,
        vectors: Int,
        dimension: Int,
        setupMs: Double,
        firstQueryMs: Double,
        warm: Latency,
        hits: Int,
        planted: Int,
        plantedFound: Int,
        memory: MemoryWindow.Result,
    ) {
        queries.put(
            JSONObject()
                .put("engine", engine)
                .put("vectors", vectors)
                .put("dimension", dimension)
                .put("setup_ms", round1(setupMs))
                .put("first_query_ms", round1(firstQueryMs))
                .put("warm_p50_ms", round1(warm.p50))
                .put("warm_p95_ms", round1(warm.p95))
                .put("warm_iterations", warm.iterations)
                .put("hits", hits)
                .put("planted", planted)
                .put("planted_found", plantedFound)
                .put("memory", memory.json())
        )
    }

    fun json(): JSONObject = JSONObject()
        .put("unit", "VS-B1")
        .put("schema", 1)
        .put("variant", variant)
        .put("inputs", "synthetic, generated in code")
        .put("device", deviceJson())
        .put("models", models)
        .put("queries", queries)

    /**
     * Writes the JSON object to the test's stdout and to logcat under tag [TAG] (the API35 CI
     * job prints every `ATLAS_SCALE` line). Logcat truncates long entries, so the object is
     * split into numbered chunks that join back to exactly [json]'s text.
     */
    fun emit(): String {
        val text = json().toString()
        println("$TAG VS-B1 json $text")
        val chunks = text.chunked(CHUNK)
        chunks.forEachIndexed { i, part ->
            Log.i(TAG, "VS-B1 json ${i + 1}/${chunks.size} $part")
        }
        return text
    }

    private fun deviceJson(): JSONObject = JSONObject()
        .put("manufacturer", Build.MANUFACTURER)
        .put("model", Build.MODEL)
        .put("sdk", Build.VERSION.SDK_INT)
        .put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
        .put("cpus", Runtime.getRuntime().availableProcessors())
        .put("emulator", isEmulator())

    companion object {
        const val TAG = "ATLAS_SCALE"
        private const val CHUNK = 3_000

        fun isEmulator(): Boolean =
            Build.HARDWARE.contains("ranchu", ignoreCase = true) ||
                Build.HARDWARE.contains("goldfish", ignoreCase = true)

        /** Nearest-rank percentile; NaN for no samples. */
        fun percentile(samples: DoubleArray, p: Double): Double {
            if (samples.isEmpty()) return Double.NaN
            val sorted = samples.sortedArray()
            val rank = Math.ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
            return sorted[rank - 1]
        }

        fun round1(v: Double): Double = if (v.isNaN()) -1.0 else Math.round(v * 10.0) / 10.0
    }
}

/**
 * Memory over one benchmark step, from `/proc/self/status` and the native heap.
 *
 * Peak RSS is `VmHWM`. [open] first tries to reset it by writing `5` to `/proc/self/clear_refs`
 * (Linux 4.0+). If the kernel or SELinux refuses, `peak_reset` is false and the peak is the
 * process-wide high-water mark since start, an upper bound for this step.
 */
class MemoryWindow private constructor(
    private val rssBeforeKb: Long,
    private val nativeBeforeKb: Long,
    private val peakReset: Boolean,
) {
    class Result(
        val rssBeforeKb: Long,
        val rssAfterKb: Long,
        val peakRssKb: Long,
        val peakReset: Boolean,
        val nativeHeapDeltaKb: Long,
    ) {
        fun json() = JSONObject()
            .put("rss_before_mb", mb(rssBeforeKb))
            .put("rss_after_mb", mb(rssAfterKb))
            .put("peak_rss_mb", mb(peakRssKb))
            .put("peak_over_before_mb", mb(peakRssKb - rssBeforeKb))
            .put("peak_reset", peakReset)
            .put("native_heap_delta_mb", mb(nativeHeapDeltaKb))

        private fun mb(kb: Long) = BenchmarkReport.round1(kb / 1024.0)
    }

    /** Call while the step's sessions are still open, so RSS after includes them. */
    fun close(): Result = Result(
        rssBeforeKb = rssBeforeKb,
        rssAfterKb = statusKb("VmRSS"),
        peakRssKb = statusKb("VmHWM"),
        peakReset = peakReset,
        nativeHeapDeltaKb = Debug.getNativeHeapAllocatedSize() / 1024 - nativeBeforeKb,
    )

    companion object {
        fun open(): MemoryWindow {
            settle()
            val reset = try {
                File("/proc/self/clear_refs").writeText("5")
                true
            } catch (_: Exception) {
                false
            }
            return MemoryWindow(statusKb("VmRSS"), Debug.getNativeHeapAllocatedSize() / 1024, reset)
        }

        /** Lets the previous step's garbage go before a new window starts. */
        fun settle() {
            repeat(2) {
                Runtime.getRuntime().gc()
                System.runFinalization()
            }
        }

        /** A `/proc/self/status` field in kB, or -1 when it cannot be read. */
        fun statusKb(field: String): Long = try {
            File("/proc/self/status").readLines()
                .firstOrNull { it.startsWith("$field:") }
                ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLong() ?: -1L
        } catch (_: Exception) {
            -1L
        }
    }
}
