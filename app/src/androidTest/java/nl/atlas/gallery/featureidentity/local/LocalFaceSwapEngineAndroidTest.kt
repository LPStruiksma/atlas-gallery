package nl.atlas.gallery.featureidentity.local

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.StrictMode
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import nl.atlas.gallery.featureaiedit.provider.EditImage
import nl.atlas.gallery.featureaiedit.provider.ImageFormat
import nl.atlas.gallery.featureface.onnx.OnnxFaceDetector
import nl.atlas.gallery.featureface.onnx.SyntheticFace
import nl.atlas.gallery.featureidentity.engine.EngineLoadResult
import nl.atlas.gallery.featureidentity.engine.FaceBox
import nl.atlas.gallery.featureidentity.engine.FacePoint
import nl.atlas.gallery.featureidentity.engine.IdentityCancellation
import nl.atlas.gallery.featureidentity.engine.IdentityCancelledException
import nl.atlas.gallery.featureidentity.engine.IdentityEditMode
import nl.atlas.gallery.featureidentity.engine.IdentityEditRequest
import nl.atlas.gallery.featureidentity.engine.IdentityEngineResult
import nl.atlas.gallery.featureidentity.engine.IdentityFailureKind
import nl.atlas.gallery.featureidentity.engine.IdentityPreferences
import nl.atlas.gallery.featureidentity.engine.IdentityReferenceSet
import nl.atlas.gallery.featureidentity.engine.IdentityVariation
import nl.atlas.gallery.featureidentity.engine.TargetFace
import nl.atlas.gallery.featureidentity.engine.WarmIdentityEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * IDS-9 (#475): the full local pipeline on the emulator with the synthetic stub network
 * (`androidTest/assets/identity/stub_swapper.onnx`, 323 bytes: output = 0.6 x input + 0.2).
 * Synthetic cartoon faces only; no third-party weights, no photos of people.
 */
@RunWith(AndroidJUnit4::class)
class LocalFaceSwapEngineAndroidTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
    private lateinit var dir: File
    private lateinit var stub: File
    private var loads = 0

    private val spec = SwapNetworkSpec(
        id = "stub", version = "stub-1", cropSize = 256, template = FaceAlignWarp.arcfaceTemplate(256),
        targetInput = "target", identityInput = "source", identityDim = 512, output = "output")

    @Before fun setUp() {
        dir = File(target.cacheDir, "ids9-" + UUID.randomUUID()).also { check(it.mkdirs()) }
        stub = File(dir, "stub_swapper.onnx")
        testAssets.open("identity/stub_swapper.onnx").use { input -> stub.outputStream().use { input.copyTo(it) } }
    }

    @After fun tearDown() { dir.deleteRecursively() }

    private fun engine(model: File? = stub) = LocalFaceSwapEngine(target, LocalEngineConfig(
        spec = spec, swapModel = { model }, encoder = { ConstantIdentityEncoder(512) },
        onModelsLoaded = { loads++ }))

    private fun png(name: String, bitmap: Bitmap): File =
        File(dir, name).also { f -> f.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }

    private fun image(f: File, w: Int, h: Int) = EditImage(f.absolutePath, w, h, ImageFormat.PNG)

    private data class Scene(val source: File, val bitmap: Bitmap, val face: TargetFace, val refs: List<EditImage>)

    private fun scene(): Scene {
        val spec = SyntheticFace.Spec(width = 1024, height = 768, centerX = 0.45f, centerY = 0.5f, size = 1f)
        val bitmap = SyntheticFace.bitmap(spec)
        val found = OnnxFaceDetector.fromAssets(target.assets).use { it.detectFaces(bitmap) }.first()
        val face = TargetFace(
            FaceBox(found.left.toInt(), found.top.toInt(), (found.right).toInt(), (found.bottom).toInt()),
            (0 until 5).map { FacePoint(found.landmarks[2 * it], found.landmarks[2 * it + 1]) })
        // Use the exact synthetic face that YuNet detected above. The former
        // 480/640px variants did not reliably pass reference detection in emulator
        // CI, so all three pipeline tests exited at step 1 with NO_FACE_FOUND.
        // Distinct files still exercise multi-reference loading and averaging.
        val source = png("source.png", bitmap)
        val refs = (0..1).map { i ->
            val file = File(dir, "ref$i.png")
            source.copyTo(file)
            image(file, bitmap.width, bitmap.height)
        }
        OnnxFaceDetector.fromAssets(target.assets).use { detector ->
            refs.forEachIndexed { i, ref ->
                val decoded = checkNotNull(BitmapFactory.decodeFile(ref.handle)) {
                    "Reference $i did not decode after PNG round-trip"
                }
                try {
                    check(detector.detectFaces(decoded).isNotEmpty()) {
                        "Reference $i is not detectable after PNG round-trip"
                    }
                } finally {
                    decoded.recycle()
                }
            }
        }
        return Scene(source, bitmap, face, refs)
    }

    private fun request(scene: Scene, seed: Long = 1, mode: IdentityEditMode = IdentityEditMode.FACE, source: EditImage? = null) =
        IdentityEditRequest(
            source = source ?: image(scene.source, scene.bitmap.width, scene.bitmap.height),
            target = scene.face,
            references = IdentityReferenceSet("synthetic-profile", null, scene.refs),
            mode = mode, preferences = IdentityPreferences(),
            variation = IdentityVariation(seed, scene.refs.indices.toList(), 0.85))

    private fun loaded(e: LocalFaceSwapEngine): WarmIdentityEngine =
        (e.load() as? EngineLoadResult.Loaded)?.engine ?: error("Engine did not load")

    private fun workspace(n: Int) = File(dir, "attempt-$n").also { it.mkdirs() }

    private val never = IdentityCancellation { false }

    private fun openSockets(): Int =
        File("/proc/self/fd").listFiles()?.count { runCatching { it.canonicalPath.contains("socket:") }.getOrDefault(false) } ?: 0

    @Test fun fullPipelineChangesOnlyTheMaskedFaceWithoutTouchingTheNetwork() {
        val scene = scene()
        val warm = loaded(engine())
        try {
            val socketsBefore = openSockets()
            val old = StrictMode.getThreadPolicy()
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectNetwork().penaltyDeath().build())
            val started = SystemClock.elapsedRealtime()
            val result = try { warm.generate(request(scene), workspace(1), never) } finally { StrictMode.setThreadPolicy(old) }
            val firstMs = SystemClock.elapsedRealtime() - started
            assertTrue("no socket opened while generating", openSockets() <= socketsBefore)
            val success = result as? IdentityEngineResult.Success ?: error("Expected success, got $result")
            val out = BitmapFactory.decodeFile(success.file!!.absolutePath)
            assertEquals(scene.bitmap.width, out.width); assertEquals(scene.bitmap.height, out.height)

            // Recompute the mask exactly as the engine does and check every pixel.
            val landmarks = FloatArray(10).also { o -> scene.face.landmarks.forEachIndexed { i, p -> o[2 * i] = p.x; o[2 * i + 1] = p.y } }
            val toCrop = FaceAlignWarp.similarity(landmarks, spec.template)
            val mask = FaceBlendMask.decide(spec.template, spec.cropSize, null).alpha
            val w = out.width; val h = out.height
            val a = IntArray(w * h).also { scene.bitmap.getPixels(it, 0, w, 0, 0, w, h) }
            val b = IntArray(w * h).also { out.getPixels(it, 0, w, 0, 0, w, h) }
            var outside = 0; var inside = 0
            for (y in 0 until h) for (x in 0 until w) {
                if (a[y * w + x] == b[y * w + x]) continue
                val cx = toCrop.mapX(x + 0.5, y + 0.5) - 0.5
                val cy = toCrop.mapY(x + 0.5, y + 0.5) - 0.5
                if (maskNear(mask, spec.cropSize, cx, cy) > 0f) inside++ else outside++
            }
            assertEquals("pixels outside the mask must be bit-identical", 0, outside)
            assertTrue("the face changed ($inside px)", inside > 1000)
            val provenance = File(workspace(1), "candidate.provenance.json").readText()
            assertTrue(provenance, provenance.contains("\"mask\":\"fallback\""))
            assertFalse("no landmarks or boxes in provenance", provenance.contains("landmark") || provenance.contains("box"))

            // Warm reuse: more candidates, one model load.
            val warmStart = SystemClock.elapsedRealtime()
            assertTrue(warm.generate(request(scene, seed = 2), workspace(2), never) is IdentityEngineResult.Success)
            assertTrue(warm.generate(request(scene, seed = 3), workspace(3), never) is IdentityEngineResult.Success)
            val warmMs = (SystemClock.elapsedRealtime() - warmStart) / 2
            assertEquals("one load serves every candidate", 1, loads)
            Log.i("IDS9_TIMING", "stub first=${firstMs}ms warm=${warmMs}ms source=${w}x$h")
        } finally { warm.close() }
    }

    @Test fun cancelStopsBetweenStepsAndWritesNoCandidate() {
        val scene = scene()
        val warm = loaded(engine())
        try {
            var checks = 0
            val ws = workspace(9)
            try {
                warm.generate(request(scene), ws, IdentityCancellation { ++checks > 2 })
                fail("Expected cancellation")
            } catch (_: IdentityCancelledException) { }
            assertFalse(File(ws, "candidate.png").exists())
            // The engine still works after a cancel.
            assertTrue(warm.generate(request(scene), workspace(10), never) is IdentityEngineResult.Success)
        } finally { warm.close() }
        assertEquals(1, loads)
    }

    @Test fun notInstalledAndUnsupportedModeAreTypedFailures() {
        assertEquals(EngineLoadResult.Unavailable(IdentityFailureKind.ENGINE_NOT_INSTALLED), engine(model = null).load())
        val scene = scene()
        val warm = loaded(engine())
        try {
            assertEquals(IdentityEngineResult.Failure(IdentityFailureKind.MODE_UNAVAILABLE),
                warm.generate(request(scene, mode = IdentityEditMode.HEAD), workspace(1), never))
            val noRefs = request(scene).copy(references = IdentityReferenceSet("p", null, emptyList()))
            assertEquals(IdentityEngineResult.Failure(IdentityFailureKind.NO_REFERENCES), warm.generate(noRefs, workspace(2), never))
            assertTrue(engine().capabilities().let { !it.makesNetworkCalls && it.costPerCandidate.minorUnits == 0L })
        } finally { warm.close() }
    }

    @Test fun largeSourcesUseBoundedDecode() {
        val scene = scene()
        val warm = loaded(engine())
        try {
            // Over the pixel limit: refused from the header alone, nothing decoded.
            val huge = File(dir, "huge.png").also { writeHeaderOnlyPng(it, 9000, 8000) }
            assertEquals(IdentityEngineResult.Failure(IdentityFailureKind.PHOTO_TOO_LARGE),
                warm.generate(request(scene, source = image(huge, 9000, 8000)), workspace(1), never))

            // 50 MP: either processed at full size, or refused before allocation. Never an OOM crash.
            val big = File(dir, "big.png").also { writeFlatPng(it, 8000, 6250) }
            val started = SystemClock.elapsedRealtime()
            val result = warm.generate(request(scene, source = image(big, 8000, 6250)), workspace(2), never)
            Log.i("IDS9_TIMING", "stub 50MP=${SystemClock.elapsedRealtime() - started}ms result=${result::class.simpleName}")
            when (result) {
                is IdentityEngineResult.Success -> {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(result.file!!.absolutePath, bounds)
                    assertEquals(8000, bounds.outWidth); assertEquals(6250, bounds.outHeight)
                }
                is IdentityEngineResult.Failure -> assertEquals(IdentityFailureKind.PHOTO_TOO_LARGE, result.kind)
            }
        } finally { warm.close() }
    }

    private fun maskNear(mask: FloatArray, size: Int, cx: Double, cy: Double): Float {
        if (cx < -0.5 || cy < -0.5 || cx > size - 0.5 || cy > size - 0.5) return 0f
        val x0 = kotlin.math.floor(cx).toInt(); val y0 = kotlin.math.floor(cy).toInt()
        var m = 0f
        for (y in y0..y0 + 1) for (x in x0..x0 + 1) m = maxOf(m, mask[y.coerceIn(0, size - 1) * size + x.coerceIn(0, size - 1)])
        return m
    }

    // ---- Minimal PNG writers (synthetic, streamed; no 50 MP bitmap in the test itself) ----

    private fun chunk(out: DataOutputStream, type: String, data: ByteArray) {
        out.writeInt(data.size)
        val t = type.toByteArray(Charsets.US_ASCII)
        out.write(t); out.write(data)
        out.writeInt(CRC32().apply { update(t); update(data) }.value.toInt())
    }

    private fun ihdr(w: Int, h: Int) = ByteArrayOutputStream().also { b ->
        DataOutputStream(b).apply { writeInt(w); writeInt(h); write(8); write(2); write(0); write(0); write(0) }
    }.toByteArray()

    private val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    private fun writeHeaderOnlyPng(f: File, w: Int, h: Int) = DataOutputStream(f.outputStream().buffered()).use { out ->
        out.write(signature); chunk(out, "IHDR", ihdr(w, h)); chunk(out, "IEND", ByteArray(0))
    }

    /** A flat grey RGB PNG, deflated row by row so the test never holds the image in memory. */
    private fun writeFlatPng(f: File, w: Int, h: Int) {
        val idat = File(dir, "idat.bin")
        DeflaterOutputStream(idat.outputStream().buffered(), Deflater(1)).use { z ->
            val row = ByteArray(1 + 3 * w) { if (it == 0) 0 else 0x70 }
            repeat(h) { z.write(row) }
        }
        DataOutputStream(f.outputStream().buffered()).use { out ->
            out.write(signature); chunk(out, "IHDR", ihdr(w, h)); chunk(out, "IDAT", idat.readBytes()); chunk(out, "IEND", ByteArray(0))
        }
        idat.delete()
    }
}
