package nl.atlas.gallery.featureface.onnx

import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SFaceModelTest {
    private fun asset(path: String): File {
        // Gradle runs unit tests from the module directory (app/).
        val candidates = listOf(File("src/main/assets/$path"), File("app/src/main/assets/$path"))
        return candidates.firstOrNull { it.isFile } ?: error("asset $path not found from ${File(".").absolutePath}")
    }

    @Test
    fun bundledModelMatchesItsPin() {
        val bytes = asset(SFaceModel.ASSET_PATH).readBytes()
        assertEquals(SFaceModel.SIZE_BYTES, bytes.size)
        assertEquals(SFaceModel.SHA256, YuNetModel.sha256Hex(bytes))
        assertSame(bytes, SFaceModel.requirePinned(bytes))
    }

    @Test
    fun bundledLicenceIsTheUpstreamApacheFile() {
        val licence = asset(SFaceModel.LICENSE_ASSET_PATH)
        assertEquals(SFaceModel.LICENSE_SHA256, YuNetModel.sha256Hex(licence.readBytes()))
        val text = licence.readText()
        assertTrue(text.contains("Apache License"))
        assertTrue(text.contains("Version 2.0, January 2004"))
        val source = asset("models/sface/SOURCE.md").readText()
        assertTrue(source.contains(SFaceModel.SHA256))
        assertTrue(source.contains(SFaceModel.LICENSE_SHA256))
        assertTrue(source.contains(SFaceModel.UPSTREAM_COMMIT))
    }

    @Test
    fun tamperedOrTruncatedModelIsRefused() {
        val bytes = asset(SFaceModel.ASSET_PATH).readBytes()
        assertRefused(bytes.copyOf().also { it[it.size / 3] = (it[it.size / 3].toInt() xor 1).toByte() })
        assertRefused(bytes.copyOf(bytes.size - 1))
        assertRefused(ByteArray(0))
        // The detector model is not an embedder.
        assertRefused(asset(YuNetModel.ASSET_PATH).readBytes())
    }

    @Test
    fun versionNamesTheModelAndDiffersFromTheDetector() {
        assertTrue(SFaceModel.EMBEDDER_VERSION.contains(SFaceModel.SHA256.take(8)))
        assertTrue(SFaceModel.EMBEDDER_VERSION != YuNetModel.DETECTOR_VERSION)
    }

    @Test
    fun normalisationGivesUnitVectorsAndRefusesBadOutput() {
        val raw = FloatArray(SFaceModel.DIMENSION) { (it - 60) * 0.25f }
        val unit = SFaceModel.l2Normalize(raw)
        assertEquals(1.0, sqrt(unit.sumOf { it.toDouble() * it }), 1e-6)
        // Direction is kept.
        val ratio = unit[127] / raw[127]
        raw.indices.forEach { assertTrue(abs(unit[it] - raw[it] * ratio) < 1e-6) }
        assertBad(FloatArray(SFaceModel.DIMENSION))
        assertBad(FloatArray(SFaceModel.DIMENSION) { if (it == 5) Float.NaN else 1f })
        assertBad(FloatArray(SFaceModel.DIMENSION) { if (it == 5) Float.POSITIVE_INFINITY else 1f })
        assertBad(FloatArray(127) { 1f })
    }

    @Test
    fun embeddingCopiesItsVectorAndComparesByCosine() {
        val raw = FloatArray(SFaceModel.DIMENSION) { if (it == 0) 3f else 0f }
        val a = SFaceEmbedding(raw, SFaceModel.EMBEDDER_VERSION)
        raw[0] = -3f
        assertEquals(1f, a.vector[0], 0f)
        a.vector[0] = 9f
        assertEquals(1f, a.vector[0], 0f)
        val b = SFaceEmbedding(FloatArray(SFaceModel.DIMENSION) { if (it == 0 || it == 1) 1f else 0f }, SFaceModel.EMBEDDER_VERSION)
        assertEquals(sqrt(0.5f), a.cosine(b), 1e-6f)
        assertEquals(1f, a.cosine(a), 1e-6f)
        try {
            a.cosine(SFaceEmbedding(raw, "other-version"))
            throw AssertionError("versions must match")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun clusterEmbeddingKeepsVersionAndDirection() {
        val e = SFaceEmbedding(FloatArray(SFaceModel.DIMENSION) { it + 1f }, SFaceModel.EMBEDDER_VERSION)
        val c = e.toClusterEmbedding(nl.atlas.gallery.featureface.NativeFaceObservationId("ab".repeat(16)))
        assertEquals(SFaceModel.EMBEDDER_VERSION, c.modelVersion)
        assertEquals(SFaceModel.DIMENSION, c.dimension)
    }

    private fun assertRefused(bytes: ByteArray) {
        try {
            SFaceModel.requirePinned(bytes)
            throw AssertionError("expected FaceModelIntegrityFailure")
        } catch (_: FaceModelIntegrityFailure) {
        }
    }

    private fun assertBad(raw: FloatArray) {
        try {
            SFaceModel.l2Normalize(raw)
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }
}
