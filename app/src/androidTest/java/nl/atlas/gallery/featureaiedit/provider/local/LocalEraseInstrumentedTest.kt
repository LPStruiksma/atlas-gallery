package nl.atlas.gallery.featureaiedit.provider.local

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.log10
import kotlin.math.sin
import nl.atlas.gallery.featureaiedit.provider.EditImage
import nl.atlas.gallery.featureaiedit.provider.EditOutcome
import nl.atlas.gallery.featureaiedit.provider.ImageEditRequest
import nl.atlas.gallery.featureaiedit.provider.ImageFormat
import nl.atlas.gallery.featureaiedit.provider.OutputSpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * AIE-22 (#434) acceptance on the CI emulator: the bundled LaMa model runs a real inference
 * through ONNX Runtime on synthetic images (integer formulas, never real photos), kept
 * pixels stay bit-identical at full resolution, the fill is close to the hidden ground
 * truth, and a 50 MP source completes without crashing. Measurements go to logcat tag
 * `ATLAS_SCALE` as one JSON line per test (no pixels, no paths).
 */
@RunWith(AndroidJUnit4::class)
class LocalEraseInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var work: File

    @Before fun setUp() {
        work = File(context.cacheDir, "local-erase-test").apply { deleteRecursively(); mkdirs() }
    }

    @After fun tearDown() {
        work.deleteRecursively()
    }

    private fun stripesRgb(x: Int, y: Int): Int {
        val r = (x / 2) % 256
        val g = (128 + 60 * sin(y / 9.0)).toInt().coerceIn(0, 255)
        val b = ((x + y) / 4) % 256
        return Color.rgb(r, g, b)
    }

    private fun writePng(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private fun peakRssKb(): Long = File("/proc/self/status").readLines()
        .firstOrNull { it.startsWith("VmHWM:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull() ?: -1

    // Bounded, synchronous diagnostics for the 50 MP synthetic test only. Do not log
    // paths, pixels or raw proc lines. The separate tag is not an ATLAS_SCALE result.
    private fun erasePhase(startMs: Long, phase: String, bandY: Int? = null) {
        val elapsedMs = SystemClock.elapsedRealtime() - startMs
        var rss: Long? = null
        var swap: Long? = null
        var hwm: Long? = null
        try {
            File("/proc/self/status").bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    when {
                        line.startsWith("VmRSS:") -> rss = statusKb(line)
                        line.startsWith("VmSwap:") -> swap = statusKb(line)
                        line.startsWith("VmHWM:") -> hwm = statusKb(line)
                    }
                    if (rss != null && swap != null && hwm != null) break
                }
            }
        } catch (_: IOException) {
            // A missing sample must not mask an acceptance failure.
        } catch (_: SecurityException) {
            // Some environments may deny proc access.
        }
        Log.i("ATLAS_ERASE_PHASE", "test=aie22_erase_50mp phase=$phase " +
            "pid=${android.os.Process.myPid()} elapsed_ms=$elapsedMs " +
            "rss_kb=${rss ?: "unknown"} swap_kb=${swap ?: "unknown"} hwm_kb=${hwm ?: "unknown"}" +
            (bandY?.let { " band_y=$it" } ?: ""))
    }

    private fun statusKb(line: String): Long? =
        line.substringAfter(':').trimStart().takeWhile { it.isDigit() }.toLongOrNull()

    private fun decodeExact(file: File): Bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.ARGB_8888; inPremultiplied = false; inScaled = false
    })!!

    @Test
    fun bundledPartsJoinToThePinnedModelAndCorruptionFailsClosed() {
        val dir = File(work, "model")
        val file = OnnxLamaInpainter.materializeFromAssets(context.assets, dir)
        assertEquals(LamaModel.SIZE_BYTES, file.length())
        assertEquals(LamaModel.SHA256, LamaModelFile.sha256Hex(file))
        context.assets.open(LamaModel.LICENSE_ASSET_PATH).use { assertTrue(it.readBytes().size > 10_000) }
        val bad = File(work, "bad")
        val parts = LamaModel.PART_ASSETS.mapIndexed { i, path ->
            if (i == 1) ({ ByteArrayInputStream(ByteArray(1024)) }) else ({ context.assets.open(path) })
        }
        try { LamaModelFile.materialize(bad, parts); fail("expected integrity failure") } catch (_: LamaModelIntegrityFailure) {}
        assertFalse(File(bad, LamaModel.FILE_NAME).exists())
    }

    @Test
    fun realInferenceFillsTheHoleAndKeepsEveryOtherPixel() {
        val w = 1024; val h = 768; val cx = 330; val cy = 380; val radius = 70
        val truth = IntArray(w * h) { stripesRgb(it % w, it / w) }
        val inHole = BooleanArray(w * h) { val x = it % w; val y = it / w; (x - cx) * (x - cx) + (y - cy) * (y - cy) < radius * radius }
        // The "object" to erase: a magenta disc painted over the stripes.
        val before = IntArray(w * h) { if (inHole[it]) Color.MAGENTA else truth[it] }
        val sourceFile = File(work, "source.png")
        val maskFile = File(work, "mask.png")
        Bitmap.createBitmap(before, w, h, Bitmap.Config.ARGB_8888).also { writePng(it, sourceFile); it.recycle() }
        Bitmap.createBitmap(IntArray(w * h) { if (inHole[it]) Color.WHITE else Color.BLACK }, w, h, Bitmap.Config.ARGB_8888)
            .also { writePng(it, maskFile); it.recycle() }

        val modelDir = File(context.noBackupFilesDir, OnnxLamaInpainter.MODEL_DIR)
        val t0 = SystemClock.elapsedRealtime()
        OnnxLamaInpainter.materializeFromAssets(context.assets, modelDir)
        val materializeMs = SystemClock.elapsedRealtime() - t0
        var openMs = 0L
        val provider = LocalEraseProvider(
            inpainterFactory = {
                val s = SystemClock.elapsedRealtime()
                OnnxLamaInpainter.open(context).also { openMs = SystemClock.elapsedRealtime() - s }
            },
            io = AndroidEraseImageIo(File(work, "out")),
        )
        val request = ImageEditRequest(
            source = EditImage(sourceFile.absolutePath, w, h, ImageFormat.PNG),
            instruction = "",
            mask = EditImage(maskFile.absolutePath, w, h, ImageFormat.PNG),
            output = OutputSpec(w, h, ImageFormat.PNG),
        )
        val t1 = SystemClock.elapsedRealtime()
        val outcome = provider.submit(request).await(maxPolls = 600)
        val totalMs = SystemClock.elapsedRealtime() - t1
        assertTrue("erase failed: $outcome", outcome is EditOutcome.Success)
        outcome as EditOutcome.Success
        val outFile = File(outcome.images.single().handle)
        val out = decodeExact(outFile)
        assertEquals(w, out.width); assertEquals(h, out.height)
        val pixels = IntArray(w * h).also { out.getPixels(it, 0, w, 0, 0, w, h) }
        out.recycle()
        var outsideDiff = 0; var se = 0.0; var holes = 0; var magentaLeft = 0
        for (i in pixels.indices) {
            if (!inHole[i]) { if (pixels[i] != before[i]) outsideDiff++; continue }
            holes++
            val p = pixels[i]; val t = truth[i]
            for (shift in intArrayOf(16, 8, 0)) {
                val d = ((p ushr shift) and 255) - ((t ushr shift) and 255)
                se += (d * d).toDouble()
            }
            val x = i % w; val y = i / w
            val deep = (x - cx) * (x - cx) + (y - cy) * (y - cy) < (radius - 3) * (radius - 3)
            if (deep && Color.red(p) > 200 && Color.green(p) < 60 && Color.blue(p) > 200) magentaLeft++
        }
        val psnr = 10 * log10(255.0 * 255.0 / (se / (3.0 * holes)))
        Log.i("ATLAS_SCALE", "{\"test\":\"aie22_erase_1024x768\",\"materialize_ms\":$materializeMs,\"session_open_ms\":$openMs," +
            "\"inference_ms\":${outcome.metadata["inference_ms"]},\"total_ms\":$totalMs,\"hole_psnr_db\":${"%.2f".format(java.util.Locale.ROOT, psnr)}," +
            "\"peak_rss_kb\":${peakRssKb()},\"cores\":${Runtime.getRuntime().availableProcessors()},\"threads\":${OnnxLamaInpainter.defaultThreads}}")
        assertEquals("pixels outside the mask changed", 0, outsideDiff)
        assertEquals("magenta object pixels left", 0, magentaLeft)
        // Host ONNX Runtime gives 31.5 dB on this scene; allow for CPU math differences.
        assertTrue("fill too far from ground truth: $psnr dB", psnr >= 24.0)
    }

    @Test
    fun fiftyMegapixelSourceCompletesWithoutCrashing() {
        val started = SystemClock.elapsedRealtime()
        fun phase(name: String, bandY: Int? = null) = erasePhase(started, name, bandY)
        phase("entry")
        val w = 8192; val h = 6144 // 50.3 MP
        val sourceFile = File(work, "big.jpg")
        val maskFile = File(work, "big-mask.png")
        phase("source_allocate_before")
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
            phase("source_allocate_after")
            Canvas(bmp).drawPaint(Paint().apply {
                shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), Color.rgb(30, 90, 160), Color.rgb(220, 180, 60), Shader.TileMode.CLAMP)
            })
            Canvas(bmp).drawCircle(6000f, 4000f, 300f, Paint().apply { color = Color.MAGENTA })
            phase("source_encode_before")
            FileOutputStream(sourceFile).use { assertTrue(bmp.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            phase("source_encode_after")
            phase("source_recycle_before")
            bmp.recycle()
            phase("source_recycle_after")
        }
        phase("mask_allocate_before")
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
            phase("mask_allocate_after")
            bmp.eraseColor(Color.BLACK)
            Canvas(bmp).drawCircle(6000f, 4000f, 340f, Paint().apply { color = Color.WHITE; isAntiAlias = false })
            phase("mask_encode_before")
            writePng(bmp, maskFile)
            phase("mask_encode_after")
            phase("mask_recycle_before")
            bmp.recycle()
            phase("mask_recycle_after")
        }
        phase("provider_construct_before")
        // Same production wiring as localEraseProvider; only the existing factory injection
        // is instrumented. Delegation preserves termination and session close.
        val provider = LocalEraseProvider(
            inpainterFactory = {
                phase("model_open_before")
                val real = OnnxLamaInpainter.open(context.applicationContext)
                phase("model_open_after")
                object : LamaInpainter by real {
                    override fun inpaint(image: FloatArray, mask: FloatArray, cancel: CancelSignal): FloatArray {
                        phase("inference_before")
                        return real.inpaint(image, mask, cancel).also { phase("inference_after") }
                    }
                }
            },
            io = AndroidEraseImageIo(File(work, "out")),
        )
        phase("provider_construct_after")
        phase("submit_before")
        val t = SystemClock.elapsedRealtime()
        val job = provider.submit(ImageEditRequest(
            source = EditImage(sourceFile.absolutePath, w, h, ImageFormat.JPEG),
            instruction = "",
            mask = EditImage(maskFile.absolutePath, w, h, ImageFormat.PNG),
            output = OutputSpec(w, h, ImageFormat.PNG),
        ))
        phase("submit_after")
        phase("await_before")
        val outcome = job.await(maxPolls = 1200)
        val totalMs = SystemClock.elapsedRealtime() - t
        phase("await_after")
        assertTrue("50 MP erase failed: $outcome", outcome is EditOutcome.Success)
        outcome as EditOutcome.Success
        val out = File(outcome.images.single().handle)

        // Compare against the decoded source in bands; the output is decoded once.
        phase("output_decode_before")
        val result = decodeExact(out)
        phase("output_decode_after")
        assertEquals(w, result.width); assertEquals(h, result.height)
        phase("verification_decoders_before")
        @Suppress("DEPRECATION")
        val sourceDecoder = BitmapRegionDecoder.newInstance(sourceFile.path, false)
        @Suppress("DEPRECATION")
        val maskDecoder = BitmapRegionDecoder.newInstance(maskFile.path, false)
        phase("verification_decoders_after")
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888; inPremultiplied = false }
        var outsideDiff = 0L; var changedInside = 0L
        val band = 512
        phase("verification_buffers_before")
        val a = IntArray(w * band); val b = IntArray(w * band); val m = IntArray(w * band)
        phase("verification_buffers_after")
        var y = 0
        while (y < h) {
            phase("verification_band_before", y)
            val rows = minOf(band, h - y)
            val r = Rect(0, y, w, y + rows)
            sourceDecoder.decodeRegion(r, opts).also { it.getPixels(a, 0, w, 0, 0, w, rows); it.recycle() }
            maskDecoder.decodeRegion(r, opts).also { it.getPixels(m, 0, w, 0, 0, w, rows); it.recycle() }
            result.getPixels(b, 0, w, 0, y, w, rows)
            for (i in 0 until w * rows) {
                if (EraseMaskRule.isErase(m[i])) { if (a[i] != b[i]) changedInside++ } else if (a[i] != b[i]) outsideDiff++
            }
            phase("verification_band_after", y)
            y += rows
        }
        phase("verification_recycle_before")
        sourceDecoder.recycle(); maskDecoder.recycle(); result.recycle()
        phase("verification_recycle_after")
        Log.i("ATLAS_SCALE", "{\"test\":\"aie22_erase_50mp\",\"inference_ms\":${outcome.metadata["inference_ms"]}," +
            "\"model_scale\":${outcome.metadata["model_scale"]},\"total_ms\":$totalMs,\"peak_rss_kb\":${peakRssKb()}}")
        assertEquals("pixels outside the mask changed", 0L, outsideDiff)
        assertTrue("the masked disc was filled", changedInside > 300_000)
        phase("complete")
    }
}
