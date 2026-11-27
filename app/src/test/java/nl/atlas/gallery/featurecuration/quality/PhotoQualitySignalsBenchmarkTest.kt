package nl.atlas.gallery.featurecuration.quality

import java.io.File
import java.lang.reflect.Method
import java.util.Locale
import kotlin.math.ceil
import org.junit.Assert.*
import org.junit.Test

/** Host JVM only: no image decoding, Android device, or wall-clock pass/fail budget. */
class PhotoQualitySignalsBenchmarkTest {
    companion object {
        private const val WARMUPS = 20
        private const val SAMPLES = 50
    }

    @Test fun benchmarkPredecodedArgb() {
        val evidenceLines = mutableListOf<String>()
        for ((width, height) in listOf(512 to 384, 1024 to 768, 2048 to 1536)) {
            val pixels = fixture(width, height)
            val reference = PhotoQualitySignals.analyseArgb8888(pixels, width, height)
            assertInvariants(reference, width, height)
            repeat(WARMUPS) {
                assertEquals(reference, PhotoQualitySignals.analyseArgb8888(pixels, width, height))
            }

            val counter = allocationCounter()
            val threadId = Thread.currentThread().id
            val elapsed = LongArray(SAMPLES)
            var allocationSum = 0.0
            var allocationAvailable = counter != null
            repeat(SAMPLES) { sample ->
                val beforeBytes = allocatedBytes(counter, threadId)
                val start = System.nanoTime()
                val result = PhotoQualitySignals.analyseArgb8888(pixels, width, height)
                elapsed[sample] = System.nanoTime() - start
                val afterBytes = allocatedBytes(counter, threadId)
                if (beforeBytes < 0 || afterBytes < 0 || afterBytes < beforeBytes) {
                    allocationAvailable = false
                } else {
                    allocationSum += (afterBytes - beforeBytes).toDouble()
                }
                // Outside the timed and allocation-counted region. The result is consumed.
                assertInvariants(result, width, height)
                assertEquals(reference, result)
            }
            elapsed.sort()
            val meanBytes = if (allocationAvailable) {
                String.format(Locale.ROOT, "%.1f", allocationSum / SAMPLES)
            } else {
                "NOT MEASURED"
            }
            val evidenceLine = "QUALITY_EVAL_1 {" +
                    "\"environment\":\"host-JVM\"," +
                    "\"os\":${jsonString(System.getProperty("os.name", "unknown"))}," +
                    "\"os_version\":${jsonString(System.getProperty("os.version", "unknown"))}," +
                    "\"arch\":${jsonString(System.getProperty("os.arch", "unknown"))}," +
                    "\"jvm\":${jsonString(System.getProperty("java.vm.name", "unknown"))}," +
                    "\"java_version\":${jsonString(System.getProperty("java.version", "unknown"))}," +
                    "\"width\":$width,\"height\":$height,\"warmups\":$WARMUPS,\"samples\":$SAMPLES," +
                    "\"p50_ms\":${milliseconds(percentile(elapsed, 0.50))}," +
                    "\"p95_ms\":${milliseconds(percentile(elapsed, 0.95))}," +
                    "\"mean_allocated_bytes_per_call\":${if (allocationAvailable) meanBytes else jsonString(meanBytes)}}"
            println(evidenceLine)
            evidenceLines.add(evidenceLine)
        }
        // Gradle may suppress successful-test stdout; the GitHub step summary is independent.
        System.getenv("GITHUB_STEP_SUMMARY")?.takeIf { it.isNotBlank() }?.let { path ->
            File(path).appendText(evidenceLines.joinToString("\n", prefix = "\n```text\n", postfix = "\n```\n"))
        }
    }

    // Fixed seed; varied RGB and occasional masked alpha, independent of personal images.
    private fun fixture(width: Int, height: Int): IntArray {
        var state = 0x13579BDF
        return IntArray(width * height) {
            state = state * 1664525 + 1013904223
            val rgb = (state ushr 8) and 0x00FFFFFF
            val alpha = if ((state ushr 24) == 0) 0 else 255
            (alpha shl 24) or rgb
        }
    }

    private fun assertInvariants(result: WholePhotoQuality, width: Int, height: Int) {
        val scale = (maxOf(width, height) + PhotoQualitySignals.MAX_EDGE - 1) /
            PhotoQualitySignals.MAX_EDGE
        assertEquals((width + scale - 1) / scale, result.sampledWidth)
        assertEquals((height + scale - 1) / scale, result.sampledHeight)
        assertTrue(result.validSamples in 0..(result.sampledWidth * result.sampledHeight))
        result.blur?.let {
            // Normalized luminance gives a Laplacian in [-4, 4], variance <= 16.
            assertTrue(it.laplacianVariance.isFinite() && it.laplacianVariance in 0.0..16.000001)
        }
        result.exposure?.let {
            assertTrue(it.clippedDarkFraction in 0.0..1.0)
            assertTrue(it.clippedLightFraction in 0.0..1.0)
            assertTrue(it.clippedDarkFraction + it.clippedLightFraction <= 1.000001)
            assertTrue(it.p05 in 0.0..1.0 && it.p95 in it.p05..1.0)
            assertTrue(it.spread in 0.0..1.0)
        }
        // These dense, valid fixtures must actually exercise both signal calculations.
        assertNotNull(result.blur)
        assertNotNull(result.exposure)
    }

    private data class AllocationCounter(val bean: Any, val readBytes: Method)

    // Android's compile classpath lacks these host-JVM management APIs.
    // Resolve the public extension interface at runtime, not an internal JVM implementation.
    private fun allocationCounter(): AllocationCounter? = try {
        val factory = Class.forName("java.lang.management.ManagementFactory")
        val extension = Class.forName("com.sun.management.ThreadMXBean")
        val bean = factory.getMethod("getThreadMXBean").invoke(null)
        if (bean != null && extension.isInstance(bean) &&
            extension.getMethod("isThreadAllocatedMemorySupported").invoke(bean) == true &&
            extension.getMethod("isThreadAllocatedMemoryEnabled").invoke(bean) == true
        ) {
            AllocationCounter(
                bean,
                extension.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType!!)
            )
        } else {
            null
        }
    } catch (e: ReflectiveOperationException) {
        null
    } catch (e: SecurityException) {
        null
    } catch (e: LinkageError) {
        null
    }

    private fun allocatedBytes(counter: AllocationCounter?, threadId: Long): Long = try {
        if (counter == null) -1L
        else (counter.readBytes.invoke(counter.bean, threadId) as? Long) ?: -1L
    } catch (e: ReflectiveOperationException) {
        -1L
    } catch (e: SecurityException) {
        -1L
    } catch (e: LinkageError) {
        -1L
    }

    // Nearest rank over the sorted sample array, no interpolation.
    private fun percentile(sorted: LongArray, fraction: Double): Long =
        sorted[(ceil(sorted.size * fraction).toInt() - 1).coerceIn(sorted.indices)]

    private fun milliseconds(nanos: Long): String =
        String.format(Locale.ROOT, "%.6f", nanos / 1_000_000.0)

    private fun jsonString(value: String): String = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                else -> if (c.code < 32) append(String.format(Locale.ROOT, "\\u%04x", c.code)) else append(c)
            }
        }
        append('"')
    }
}
