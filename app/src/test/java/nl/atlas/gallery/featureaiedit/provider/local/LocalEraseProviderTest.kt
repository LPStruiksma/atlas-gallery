package nl.atlas.gallery.featureaiedit.provider.local

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.sin
import nl.atlas.gallery.featureaiedit.provider.EditImage
import nl.atlas.gallery.featureaiedit.provider.EditOutcome
import nl.atlas.gallery.featureaiedit.provider.FailureKind
import nl.atlas.gallery.featureaiedit.provider.ImageEditProvider
import nl.atlas.gallery.featureaiedit.provider.ImageEditProviderContract
import nl.atlas.gallery.featureaiedit.provider.ImageEditRequest
import nl.atlas.gallery.featureaiedit.provider.ImageFormat
import nl.atlas.gallery.featureaiedit.provider.JobSnapshot
import nl.atlas.gallery.featureaiedit.provider.OutputSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Deterministic stand-in for LaMa: a fixed noisy colour field, never the source. */
private class NoiseInpainter(private val size: Int = LamaModel.SIZE) : LamaInpainter {
    var calls = 0
    var lastMask: FloatArray? = null
    var lastImage: FloatArray? = null
    override fun inpaint(image: FloatArray, mask: FloatArray, cancel: CancelSignal): FloatArray {
        calls++
        lastMask = mask.copyOf(); lastImage = image.copyOf()
        val plane = size * size
        return FloatArray(3 * plane) { i -> ((i * 2654435761L ushr 7) % 256).toFloat() }
    }
    var closed = false
    override fun close() { closed = true }
}

private fun stripes(w: Int, h: Int): IntArray = IntArray(w * h) { i ->
    val x = i % w; val y = i / w
    val r = (x / 2) % 256; val g = (128 + 60 * sin(y / 9.0)).toInt().coerceIn(0, 255); val b = ((x + y) / 4) % 256
    // Vary alpha too, so a premultiply round-trip on kept pixels would be caught.
    val a = 255 - (x * 7 + y * 3) % 64
    (a shl 24) or (r shl 16) or (g shl 8) or b
}

private fun discMask(w: Int, h: Int, cx: Int, cy: Int, radius: Int): IntArray = IntArray(w * h) { i ->
    val x = i % w; val y = i / w
    if ((x - cx) * (x - cx) + (y - cy) * (y - cy) < radius * radius) -0x1 else -0x1000000
}

class LocalEraseEngineTest {
    @Test fun keptPixelsAreBitIdenticalAndEveryMaskPixelChanges() {
        for ((w, h, cx, cy, r) in listOf(
            listOf(640, 480, 300, 240, 70), listOf(300, 200, 10, 10, 30), listOf(1500, 900, 1400, 850, 120),
        )) {
            val src = stripes(w, h)
            val mask = discMask(w, h, cx, cy, r)
            val buffer = src.copyOf()
            val regions = IntArrayRegions(buffer, w, h)
            val bits = ByteArray(w * h) { if (EraseMaskRule.isErase(mask[it])) 1 else 0 }
            val report = LocalEraseEngine(bandRows = 37).erase(regions, ByteArrayMask(bits, w, h), regions, NoiseInpainter())
            var outsideDiff = 0; var insideSame = 0; var masked = 0
            for (i in src.indices) {
                if (bits[i].toInt() == 0) { if (buffer[i] != src[i]) outsideDiff++ } else {
                    masked++
                    if (buffer[i] == src[i]) insideSame++
                    assertEquals("alpha kept", src[i] ushr 24, buffer[i] ushr 24)
                }
            }
            assertEquals("outside-mask pixels changed for ${w}x$h", 0, outsideDiff)
            assertTrue("almost every masked pixel is filled ($insideSame of $masked unchanged)", insideSame < masked / 100 + 1)
            assertEquals(masked.toLong(), report.erasedPixels)
        }
    }

    @Test fun emptyMaskRunsNoModelAndChangesNothing() {
        val w = 64; val h = 48
        val src = stripes(w, h); val buffer = src.copyOf(); val regions = IntArrayRegions(buffer, w, h)
        val model = NoiseInpainter()
        val report = LocalEraseEngine().erase(regions, ByteArrayMask(ByteArray(w * h), w, h), regions, model)
        assertEquals(0, model.calls)
        assertNull(report.window)
        assertArrayEquals(src, buffer)
    }

    @Test fun smallMaskRunsAtNativeResolutionAndModelSeesHoleZeroed() {
        val w = 2000; val h = 1500
        val bits = ByteArray(w * h)
        for (y in 700 until 760) for (x in 900 until 950) bits[y * w + x] = 1
        val engine = LocalEraseEngine()
        val bounds = engine.maskBounds(ByteArrayMask(bits, w, h))!!
        assertEquals(PixelRect(900, 700, 950, 760), bounds)
        val window = EraseWindow.plan(bounds, w, h)
        assertEquals(512, window.width); assertEquals(512, window.height)
        assertEquals(1.0, EraseWindow.scale(window), 0.0)
        assertTrue(window.left <= 900 && window.right >= 950 && window.top <= 700 && window.bottom >= 760)
        val buffer = IntArray(w * h) { -0x1 } // white
        val regions = IntArrayRegions(buffer, w, h)
        val model = NoiseInpainter()
        engine.erase(regions, ByteArrayMask(bits, w, h), regions, model)
        val m = model.lastMask!!; val img = model.lastImage!!
        assertEquals(50f * 60f, m.sum(), 0f)
        for (i in m.indices) if (m[i] == 1f) assertEquals(0f, img[i], 0f) else assertEquals(1f, img[i], 0f)
    }

    @Test fun windowStaysInsideImageAndCoversMaskWithContext() {
        val cases = listOf(
            Triple(PixelRect(0, 0, 10, 10), 4000, 3000), Triple(PixelRect(3990, 2990, 4000, 3000), 4000, 3000),
            Triple(PixelRect(100, 100, 3900, 2900), 4000, 3000), Triple(PixelRect(5, 5, 60, 40), 100, 80),
        )
        for ((mask, w, h) in cases) {
            val win = EraseWindow.plan(mask, w, h)
            assertTrue(win.left >= 0 && win.top >= 0 && win.right <= w && win.bottom <= h)
            assertTrue(win.left <= mask.left && win.top <= mask.top && win.right >= mask.right && win.bottom >= mask.bottom)
            assertTrue(EraseWindow.scale(win) * maxOf(win.width, win.height) <= 512.0 + 1e-9)
        }
    }

    @Test fun featherBlendsOnlyInsideTheMaskEdge() {
        val w = 600; val h = 600
        val src = IntArray(w * h) { -0x1000000 } // opaque black
        val bits = ByteArray(w * h)
        for (y in 200 until 300) for (x in 200 until 300) bits[y * w + x] = 1
        val buffer = src.copyOf(); val regions = IntArrayRegions(buffer, w, h)
        val white = object : LamaInpainter {
            override fun inpaint(image: FloatArray, mask: FloatArray, cancel: CancelSignal) = FloatArray(image.size) { 255f }
            override fun close() {}
        }
        LocalEraseEngine(featherPx = 2).erase(regions, ByteArrayMask(bits, w, h), regions, white)
        assertEquals(-0x1000000, buffer[199 * w + 250])           // outside: untouched
        assertEquals(128, (buffer[200 * w + 250] ushr 16) and 255) // edge: half blend
        assertEquals(255, (buffer[250 * w + 250] ushr 16) and 255) // deep inside: full fill
    }

    /**
     * 50 MP without allocating it: a procedural source and a recording sink prove the
     * pipeline only touches bounded bands and writes only mask pixels.
     */
    @Test fun fiftyMegapixelSourceIsStreamedInBoundedBands() {
        val w = 8192; val h = 6144
        var maxRead = 0
        val source = object : ArgbRegionReader {
            override val width = w; override val height = h
            override fun read(rect: PixelRect, out: IntArray) {
                maxRead = maxOf(maxRead, rect.width * rect.height)
                for (y in rect.top until rect.bottom) for (x in rect.left until rect.right)
                    out[(y - rect.top) * rect.width + (x - rect.left)] = (0xff shl 24) or ((x and 255) shl 16) or (y and 255)
            }
        }
        fun inMask(x: Int, y: Int) = (x - 6000) * (x - 6000) + (y - 4000) * (y - 4000) < 900 * 900
        val mask = object : MaskRegionReader {
            override val width = w; override val height = h
            override fun read(rect: PixelRect, out: ByteArray) {
                maxRead = maxOf(maxRead, rect.width * rect.height)
                for (y in rect.top until rect.bottom) for (x in rect.left until rect.right)
                    out[(y - rect.top) * rect.width + (x - rect.left)] = if (inMask(x, y)) 1 else 0
            }
        }
        var written = 0L; var wrongWrites = 0L
        val sink = object : ArgbRegionWriter {
            override fun write(rect: PixelRect, argb: IntArray, changed: BooleanArray) {
                for (y in rect.top until rect.bottom) for (x in rect.left until rect.right) {
                    if (!changed[(y - rect.top) * rect.width + (x - rect.left)]) continue
                    written++
                    if (!inMask(x, y)) wrongWrites++
                }
            }
        }
        val report = LocalEraseEngine().erase(source, mask, sink, NoiseInpainter())
        assertEquals(0, wrongWrites)
        assertEquals(report.erasedPixels, written)
        assertTrue("bands stay bounded: $maxRead", maxRead <= 8192 * 260)
        assertTrue(report.modelScale < 1.0)
    }

    @Test fun cancellationStopsBeforeInference() {
        val w = 640; val h = 480
        val bits = ByteArray(w * h).also { it[1000] = 1 }
        val regions = IntArrayRegions(stripes(w, h), w, h)
        val model = NoiseInpainter()
        try {
            LocalEraseEngine().erase(regions, ByteArrayMask(bits, w, h), regions, model, CancelSignal { true })
            fail("expected cancel")
        } catch (_: EraseCancelled) {}
        assertEquals(0, model.calls)
    }
}

class LocalEraseProviderContractTest : ImageEditProviderContract() {
    /** IO wrapper for fixtures: delays (interruptibly) or fails before any engine work. */
    private class FixtureIo(private val delayMillis: Long = 0, private val error: RuntimeException? = null) : EraseImageIo {
        private val inner = SyntheticEraseImageIo()
        override fun open(source: EditImage, mask: EditImage?, jobId: String): EraseCanvas {
            error?.let { throw it }
            if (delayMillis > 0) Thread.sleep(delayMillis)
            return inner.open(source, mask, jobId)
        }
    }

    override fun providerFor(fixture: Fixture): ImageEditProvider {
        val io = when (fixture) {
            Fixture.SUCCESS -> SyntheticEraseImageIo()
            Fixture.REFUSAL -> FixtureIo(error = EraseEngineRefusal("SYNTHETIC_REFUSAL", "fixture"))
            Fixture.FAILURE -> FixtureIo(error = EraseEngineFailure(FailureKind.NETWORK, true, "fixture"))
            Fixture.SLOW_ASYNC -> FixtureIo(delayMillis = 60_000)
            Fixture.CANCELLABLE_ASYNC -> FixtureIo(delayMillis = 150)
        }
        return LocalEraseProvider({ NoiseInpainter() }, io, pollWaitMillis = 50)
    }
}

class LocalEraseProviderTest {
    private val w = 600; private val h = 400

    private fun request(mask: EditImage?, instruction: String = "") = ImageEditRequest(
        source = EditImage("synthetic-source", w, h, ImageFormat.PNG, stripes(w, h).toList()),
        instruction = instruction, mask = mask, output = OutputSpec(w, h, ImageFormat.PNG),
    )

    private fun mask() = EditImage("synthetic-mask", w, h, ImageFormat.PNG, discMask(w, h, 300, 200, 50).toList())

    @Test fun erasesWithoutInstructionAndReportsFreeOnDevice() {
        val model = NoiseInpainter()
        val provider = LocalEraseProvider({ model }, SyntheticEraseImageIo(), pollWaitMillis = 50)
        val req = request(mask())
        assertEquals(0L, provider.estimateCost(req)!!.minorUnits)
        val outcome = provider.submit(req).await() as EditOutcome.Success
        assertEquals(1, model.calls)
        assertTrue("session released after use", model.closed)
        assertEquals("true", outcome.metadata["on_device"])
        assertEquals(LamaModel.MODEL_NAME, outcome.providerModel)
        val src = stripes(w, h); val out = outcome.images.single().pixels
        val m = discMask(w, h, 300, 200, 50)
        for (i in src.indices) if (!EraseMaskRule.isErase(m[i])) assertEquals(src[i], out[i])
        assertNotEquals(src.toList(), out)
        assertTrue(outcome.metadata["erased_pixels"]!!.toLong() > 7000)
    }

    @Test fun rejectsResizedOutputReferencesAndJpegOutput() {
        val provider = LocalEraseProvider({ NoiseInpainter() }, SyntheticEraseImageIo(), pollWaitMillis = 50)
        val resized = request(mask()).copy(output = OutputSpec(w / 2, h / 2, ImageFormat.PNG))
        assertEquals(FailureKind.INVALID_REQUEST, (provider.submit(resized).await() as EditOutcome.Failed).kind)
        val jpeg = request(mask()).copy(output = OutputSpec(w, h, ImageFormat.JPEG))
        assertEquals(FailureKind.INVALID_REQUEST, (provider.submit(jpeg).await() as EditOutcome.Failed).kind)
        val withRef = request(mask()).copy(references = listOf(EditImage("r", 4, 4, ImageFormat.PNG)))
        assertEquals(FailureKind.INVALID_REQUEST, (provider.submit(withRef).await() as EditOutcome.Failed).kind)
    }

    @Test fun cancelDuringInferenceTerminatesAndReportsCancelled() {
        val started = CountDownLatch(1)
        val released = CountDownLatch(1)
        var terminated = false
        val blocking = object : LamaInpainter {
            override fun inpaint(image: FloatArray, mask: FloatArray, cancel: CancelSignal): FloatArray {
                started.countDown()
                released.await(10, TimeUnit.SECONDS)
                if (cancel.isCancelled()) throw EraseCancelled()
                return FloatArray(image.size)
            }
            override fun terminate() { terminated = true; released.countDown() }
            override fun close() {}
        }
        val provider = LocalEraseProvider({ blocking }, SyntheticEraseImageIo(), pollWaitMillis = 20)
        val job = provider.submit(request(mask()))
        assertTrue(started.await(10, TimeUnit.SECONDS))
        assertTrue(job.poll() is JobSnapshot.Pending)
        job.cancel()
        assertTrue(terminated)
        assertEquals(EditOutcome.Cancelled, (job.poll() as JobSnapshot.Completed).outcome)
    }

    @Test fun engineCrashBecomesTypedFailureNotACrash() {
        val broken = object : LamaInpainter {
            override fun inpaint(image: FloatArray, mask: FloatArray, cancel: CancelSignal): FloatArray = FloatArray(3)
            override fun close() {}
        }
        val provider = LocalEraseProvider({ broken }, SyntheticEraseImageIo(), pollWaitMillis = 50)
        val outcome = provider.submit(request(mask())).await() as EditOutcome.Failed
        assertEquals(FailureKind.UNKNOWN, outcome.kind)
        assertFalse(outcome.retryable)
    }

    @Test fun maskRuleIsWhiteOpaqueOnly() {
        assertTrue(EraseMaskRule.isErase(-0x1))
        assertFalse(EraseMaskRule.isErase(-0x1000000))     // opaque black
        assertFalse(EraseMaskRule.isErase(0x00ffffff))     // transparent white
        assertTrue(EraseMaskRule.isErase(0x80ff0000.toInt()))
    }
}

class LamaModelFileTest {
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun joinsVerifiesAndRefusesTamperedParts() {
        val dir = Files.createTempDirectory("lama").toFile()
        try {
            val a = ByteArray(1000) { it.toByte() }; val b = ByteArray(337) { (it * 3).toByte() }
            val whole = a + b
            val parts = listOf({ ByteArrayInputStream(a) }, { ByteArrayInputStream(b) })
            val file = LamaModelFile.materialize(dir, parts, sha(whole), whole.size.toLong(), "m.onnx")
            assertArrayEquals(whole, file.readBytes())
            // Second open re-verifies the existing file without the parts.
            assertEquals(file, LamaModelFile.materialize(dir, emptyList(), sha(whole), whole.size.toLong(), "m.onnx"))
            file.writeBytes(whole.copyOf().also { it[5] = 99 })
            val bad = listOf({ ByteArrayInputStream(a) }, { ByteArrayInputStream(b.copyOf().also { it[0] = 1 }) })
            try {
                LamaModelFile.materialize(dir, bad, sha(whole), whole.size.toLong(), "m.onnx"); fail()
            } catch (_: LamaModelIntegrityFailure) {}
            assertFalse(File(dir, "m.onnx").exists())
            assertFalse(File(dir, "m.onnx.tmp").exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun pinsMatchTheShippedSourceRecord() {
        assertEquals(LamaModel.SIZE_BYTES, 60_000_000L + 32_591_623L)
        assertEquals(64, LamaModel.SHA256.length)
        assertTrue(LamaModel.SHA256.startsWith(LamaModel.MODEL_VERSION))
        assertTrue(abs(LamaModel.SIZE - 512) == 0)
    }
}
