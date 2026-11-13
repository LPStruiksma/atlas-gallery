package nl.atlas.gallery.featurevisual

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.File
import nl.atlas.gallery.featurevisual.onnx.SiglipImageEmbedder
import nl.atlas.gallery.featurevisual.onnx.SiglipModel
import nl.atlas.gallery.featurevisual.onnx.SiglipModelFile
import nl.atlas.gallery.featurevisual.onnx.SiglipModelIntegrityFailure
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the bundled SigLIP 2 B/16 int8 model on ONNX Runtime in the emulator, on images drawn
 * from integer formulas (never real photos), and compares each vector with the host ONNX
 * Runtime vector that `export_onnx.py --golden-kotlin` wrote for the same image ([SiglipGolden]).
 * Covers: asset parts join to the pinned file, Pillow-exact preprocessing on Android, the
 * vector contract (768-d, unit length, version) and on-device agreement with the host graph.
 */
@RunWith(AndroidJUnit4::class)
class SiglipImageEmbedderTest {
    private fun cosine(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i].toDouble() * b[i]; na += a[i].toDouble() * a[i]; nb += b[i].toDouble() * b[i] }
        return dot / Math.sqrt(na * nb)
    }

    private fun bitmap(g: SiglipGolden.Golden): Bitmap {
        val px = synthetic(g.name, g.width, g.height)
        return Bitmap.createBitmap(px, g.width, g.height, Bitmap.Config.ARGB_8888)
    }

    @Test
    fun bundledPartsJoinToThePinnedModel() {
        assertEquals(SiglipModel.SHA256, SiglipGolden.MODEL_SHA256)
        assertEquals(SiglipModel.SHA256, SiglipModelFile.sha256Hex(modelFile))
        assertEquals(SiglipModel.SIZE_BYTES, modelFile.length())
        assertTrue(SiglipModel.MODEL_VERSION.startsWith("siglip2_b16_224.int8:" + SiglipModel.SHA256.take(12)))
    }

    @Test
    fun embeddingsMatchHostGoldenVectors() {
        for (g in SiglipGolden.all) {
            val bmp = bitmap(g)
            val v = embedder.embed(bmp)
            bmp.recycle()
            assertEquals(SiglipModel.DIMENSION, v.size)
            var norm = 0.0
            for (x in v) norm += x.toDouble() * x
            assertEquals("${g.name} unit length", 1.0, norm, 1e-3)
            val cos = cosine(v, g.vector)
            assertTrue("${g.name}: cosine to host golden $cos < 0.99", cos >= 0.99)
        }
    }

    @Test
    fun distinctImagesGiveDistinctVectorsAndRepeatsAreStable() {
        val g = SiglipGolden.all
        val a = embedder.embedArgb(synthetic(g[0].name, g[0].width, g[0].height), g[0].width, g[0].height)
        val again = embedder.embedArgb(synthetic(g[0].name, g[0].width, g[0].height), g[0].width, g[0].height)
        val b = embedder.embedArgb(synthetic(g[1].name, g[1].width, g[1].height), g[1].width, g[1].height)
        assertTrue(cosine(a, again) > 0.99999)
        assertTrue("different images too close", cosine(a, b) < 0.95)
    }

    @Test
    fun corruptedPartsFailClosed() {
        val dir = File(context.cacheDir, "siglip-corrupt-test").apply { deleteRecursively() }
        val parts = SiglipModel.PART_ASSETS.mapIndexed { i, path ->
            if (i == 1) ({ ByteArrayInputStream(ByteArray(1024)) }) else ({ context.assets.open(path) })
        }
        try { SiglipModelFile.materialize(dir, parts); fail("expected integrity failure") }
        catch (_: SiglipModelIntegrityFailure) {}
        assertFalse(File(dir, SiglipModel.FILE_NAME).exists())
        dir.deleteRecursively()
    }

    @Test(expected = IllegalStateException::class)
    fun closedEmbedderRefusesWork() {
        val e = SiglipImageEmbedder.fromModelFile(modelFile, threads = 1)
        e.close()
        e.close()
        e.embedArgb(IntArray(4), 2, 2)
    }

    companion object {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private lateinit var embedder: SiglipImageEmbedder
        private lateinit var modelFile: File

        @BeforeClass @JvmStatic
        fun openOnce() {
            modelFile = SiglipImageEmbedder.materializeFromAssets(
                context.assets, File(context.noBackupFilesDir, SiglipImageEmbedder.MODEL_DIR)
            )
            embedder = SiglipImageEmbedder.open(context)
        }

        @AfterClass @JvmStatic
        fun closeOnce() {
            embedder.close()
        }

        /** Same formulas as `export_onnx.py` SYNTHETIC and the JVM `SiglipSyntheticImages`. */
        fun synthetic(name: String, w: Int, h: Int): IntArray = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            var r: Int
            var g: Int
            var b: Int
            when (name) {
                "gradient_disc" -> {
                    r = x * 255 / (w - 1); g = y * 255 / (h - 1); b = 128
                    if ((x - 200) * (x - 200) + (y - 110) * (y - 110) <= 60 * 60) { r = 240; g = 40; b = 40 }
                }
                "checker_band" -> {
                    val v = if (((x / 25) + (y / 25)) % 2 == 1) 220 else 30
                    r = v; g = v; b = v
                    if (x in 100 until 140) { r = 20; g = 120; b = 220 }
                }
                "rings" -> {
                    val d2 = (x - 128) * (x - 128) + (y - 128) * (y - 128)
                    r = (d2 / 37) % 256; g = (x * y / 97) % 256; b = (x + 2 * y) % 256
                }
                "large_stripes" -> {
                    r = (x / 7) % 2 * 200 + 20; g = (y / 11) % 2 * 180 + 40; b = ((x + y) / 13) % 256
                }
                else -> throw IllegalArgumentException(name)
            }
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}
