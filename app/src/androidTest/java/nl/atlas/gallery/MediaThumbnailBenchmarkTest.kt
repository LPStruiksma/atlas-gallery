package nl.atlas.gallery

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Debug
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Manual, emulator-only, synthetic A/B/C measurement. NEVER run on an owner
 * phone or existing media. Not wired into unit CI or the production image path.
 * No launch/claim of benchmark measurements is implicit in this source file.
 */
@RunWith(AndroidJUnit4::class)
class MediaThumbnailBenchmarkTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private val tier = Size(400, 400)

    @Test fun compareCurrentCoilVsProviderVsBoundedPrivateDerivative() {
        // Default Android emulator acceptance MUST NOT create fixtures or
        // spend extra runner time. This optional experiment requires an
        // explicit instrument argument in addition to an emulator fingerprint.
        assumeTrue("Explicit atlas_media_benchmark_opt_in=true required",
            InstrumentationRegistry.getArguments()
                .getString("atlas_media_benchmark_opt_in") == "true")
        // An explicit physical-device hard gate; avoid even inventory queries
        // when an instrumented test APK is mistakenly started on a real phone.
        assumeTrue("Synthetic thumbnail benchmarking is emulator-only",
            (Build.HARDWARE.contains("ranchu", ignoreCase = true) ||
                Build.HARDWARE.contains("goldfish", ignoreCase = true)) &&
                (Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
                    Build.PRODUCT.contains("sdk", ignoreCase = true)))

        // Fresh per-run directory: a process crash must not make a later A/B
        // silently reuse a derivative belonging to a different synthetic URI.
        val testDir = File(context.cacheDir, "atlas-m-bench-" + System.nanoTime())
            .apply { mkdirs() }
        val disposable = ArrayList<android.net.Uri>()
        try {
            for ((formatIndex, format) in listOf("jpeg", "png").withIndex()) {
                // Fair comparison requires one independent synthetic URI per
                // path: warming Coil's provider cache must not warm the other
                // two paths on the SAME synthetic image.
                val paths = listOf("coil", "provider", "private_8MiB")
                val uris = paths.associateWith {
                    createFictionalImage(format).also(disposable::add)
                }
                val loader = SingletonImageLoader.get(context)
                val derivative = File(testDir, "fictional_" + format + ".jpg")
                // Counterbalance pipeline order for the two source formats;
                // does not assert truly OS-cold equivalence or phone parity.
                val sequence = if (formatIndex % 2 == 0) paths else paths.reversed()
                val times = LinkedHashMap<String, List<Long>>()
                for (path in sequence) {
                    val uri = checkNotNull(uris[path])
                    times[path] = when (path) {
                        "coil" -> {
                            val request = ImageRequest.Builder(context).data(uri)
                                .size(tier.width, tier.height).build()
                            measure {
                                runBlocking {
                                    val result = loader.execute(request)
                                    // Coil returns ErrorResult as a value. Do NOT
                                    // misreport a fast failed decode as a PASS.
                                    assertTrue("Coil synthetic image did not decode",
                                        result is SuccessResult)
                                }
                            }
                        }
                        "provider" -> measure {
                            val bitmap = resolver.loadThumbnail(uri, tier, null)
                            bitmap.recycle()
                        }
                        "private_8MiB" -> measure {
                            if (!derivative.exists()) {
                                resolver.loadThumbnail(uri, tier, null).useBitmap { bitmap ->
                                    derivative.outputStream().use { output ->
                                        assertTrue(bitmap.compress(
                                            Bitmap.CompressFormat.JPEG, 85, output))
                                    }
                                }
                                enforceBudget(testDir, 8L * 1024L * 1024L)
                                assertTrue("Private derivative evicted immediately",
                                    derivative.isFile)
                            } else {
                                val bitmap = BitmapFactory.decodeFile(
                                    derivative.absolutePath)
                                assertNotNull("Private synthetic derivative failed decode", bitmap)
                                bitmap.recycle()
                            }
                        }
                        else -> error("Unexpected synthetic path")
                    }
                }
                val occupancy = testDir.listFiles()?.sumOf { it.length() } ?: 0L
                val appHeapBytes = Debug.getNativeHeapAllocatedSize()
                for ((name, samples) in times) {
                    // First acquisition vs subsequent same-URI reuse, not a
                    // cold-cache or thermal-controlled performance comparison.
                    val stats = summarizeThumbnailBenchmark(samples)
                    Log.i("ATLAS_M_BENCH",
                        "format=" + format + " path=" + name +
                            " firstMs=" + nsMs(stats.firstNs) +
                            " warmCount=" + stats.warmCount +
                            " warmP50Ms=" + nsMs(stats.warmP50Ns) +
                            " warmP95Ms=" + nsMs(stats.warmP95Ns) +
                            " privateBytes=" + occupancy +
                            " nativeHeapBytes=" + appHeapBytes)
                }
                // Comparison only, no arbitrary speed thresholds.
                assertTrue(occupancy <= 8L * 1024L * 1024L)
            }
        } finally {
            disposable.forEach { uri -> runCatching { resolver.delete(uri, null, null) } }
            testDir.listFiles()?.forEach { it.delete() }
            testDir.delete()
        }
    }

    // 1 fresh acquisition + 20 repeated operations per independent fixture.
    private fun measure(block: () -> Unit): List<Long> = (0 until 21).map {
        val before = System.nanoTime()
        block()
        System.nanoTime() - before
    }

    private fun nsMs(nanos: Long): Double = nanos / 1_000_000.0

    private fun Bitmap.useBitmap(block: (Bitmap) -> Unit) {
        try { block(this) } finally { recycle() }
    }

    private fun createFictionalImage(format: String): android.net.Uri {
        val mime = if (format == "png") "image/png" else "image/jpeg"
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "ATLAS_M_FICTION_" +
                System.nanoTime() + "." + format)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/ATLAS_M_DISPOSABLE/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: error("Cannot insert synthetic fixture")
        try {
            val bitmap = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888)
            try {
                // Synthetic pixels only. No read of the phone's existing media.
                val w = bitmap.width
                val h = bitmap.height
                val pixels = IntArray(w * h) { p ->
                    val x = p % w
                    val y = p / w
                    val noise = ((x * 73856093) xor (y * 19349663)) and 63
                    (0xff shl 24) or (((x + noise) and 255) shl 16) or
                        (((y + noise) and 255) shl 8) or ((x + y) and 255)
                }
                bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
                resolver.openOutputStream(uri)?.use { out ->
                    val compression = if (format == "png") Bitmap.CompressFormat.PNG
                        else Bitmap.CompressFormat.JPEG
                    check(bitmap.compress(compression, 92, out))
                } ?: error("Cannot write fictional fixture")
            } finally { bitmap.recycle() }
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
            return uri
        } catch (e: Throwable) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private fun enforceBudget(dir: File, budget: Long) {
        val entries = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = entries.sumOf { it.length() }
        for (file in entries) {
            if (total <= budget) break
            val bytes = file.length()
            if (file.delete()) total -= bytes
        }
    }
}
