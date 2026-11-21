package nl.atlas.gallery

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import java.io.File
import java.util.concurrent.Executors

internal data class NativeMemoryReading(val nativeHeapBytes: Long, val pssKiB: Long,
    val capturedAtMillis: Long, val processToken: Long)

internal fun formatNativeMemorySample(sample: NativeMemoryReading, currentProcessToken: Long): String {
    val source = if (sample.processToken == currentProcessToken) "this process" else "prior process"
    return "Last native/PSS sample ($source): native_heap_mib=${sample.nativeHeapBytes / 1048576L} " +
        "pss_mib=${sample.pssKiB / 1024L} captured_ms=${sample.capturedAtMillis}"
}

/** Rate-limited/coalesced expensive read; [dispatch] pins every read and write off Main. */
internal class NativeMemorySampler(
    private val aggregate: PerformanceAggregates,
    private val dispatch: (() -> Unit) -> Unit,
    private val elapsedMillis: () -> Long,
    private val read: () -> NativeMemoryReading?,
    private val persist: (NativeMemoryReading) -> Unit = {},
    private val minimumIntervalMillis: Long = 60_000L
) {
    private var running = false
    private var lastStarted = Long.MIN_VALUE

    @Synchronized fun request(): Boolean {
        val now = elapsedMillis()
        if (running || (lastStarted != Long.MIN_VALUE && now - lastStarted < minimumIntervalMillis)) return false
        running = true
        val previousStarted = lastStarted
        lastStarted = now
        try {
            dispatch {
                try {
                    val sample = read() ?: return@dispatch
                    if (sample.nativeHeapBytes >= 0L && sample.pssKiB >= 0L &&
                        sample.capturedAtMillis >= 0L && sample.processToken >= 0L) {
                        aggregate.record("native_heap_mib", sample.nativeHeapBytes / (1024.0 * 1024.0))
                        aggregate.record("process_pss_mib", sample.pssKiB / 1024.0)
                        persist(sample)
                    }
                } catch (_: Exception) {
                    // Debug API and disk telemetry are best-effort; never kill the app.
                } finally {
                    synchronized(this) { running = false }
                }
            }
        } catch (_: Exception) {
            running = false
            lastStarted = previousStarted // a rejected dispatch did not start a sample
            return false
        }
        return true
    }
}

internal object NativeMemoryMetrics {
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "atlas-memory-sampler").apply { isDaemon = true }
    }
    private val processToken = System.currentTimeMillis()
    @Volatile private var sampler: NativeMemorySampler? = null
    @Volatile private var latest: NativeMemoryReading? = null
    private var sampleFile: File? = null

    fun install(context: Context) {
        if (sampler != null) return
        synchronized(this) {
            if (sampler != null) return
            sampleFile = File(context.applicationContext.filesDir, "performance/last-memory.txt")
            sampler = NativeMemorySampler(PerformanceSession.aggregate, { worker.execute(it) },
                SystemClock::elapsedRealtime, {
                    NativeMemoryReading(Debug.getNativeHeapAllocatedSize(), Debug.getPss().toLong(),
                        System.currentTimeMillis(), processToken)
                }, { sample -> latest = sample; write(sample) })
        }
    }

    fun request(): Boolean = sampler?.request() ?: false

    /** Numeric bounded context only; a prior sample is never attributed to a particular exit. */
    fun displaySample(): String? {
        val sample = latest ?: read(sampleFile) ?: return null
        return formatNativeMemorySample(sample, processToken)
    }

    private fun write(sample: NativeMemoryReading) {
        val destination = sampleFile ?: return
        if (!destination.parentFile.mkdirs() && !destination.parentFile.isDirectory) return
        val temporary = File(destination.parentFile, destination.name + ".tmp")
        temporary.writeText("${sample.nativeHeapBytes},${sample.pssKiB},${sample.capturedAtMillis},${sample.processToken}")
        if (!temporary.renameTo(destination)) temporary.delete()
    }

    private fun read(file: File?): NativeMemoryReading? = runCatching {
        val fields = file?.takeIf(File::isFile)?.readText()?.split(',') ?: return null
        if (fields.size != 4) return null
        NativeMemoryReading(fields[0].toLong(), fields[1].toLong(), fields[2].toLong(), fields[3].toLong())
            .takeIf { it.nativeHeapBytes >= 0L && it.pssKiB >= 0L && it.capturedAtMillis >= 0L }
    }.getOrNull()
}
