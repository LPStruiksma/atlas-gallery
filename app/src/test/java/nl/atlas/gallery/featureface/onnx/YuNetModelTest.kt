package nl.atlas.gallery.featureface.onnx

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class YuNetModelTest {
    private fun asset(path: String): File {
        // Gradle runs unit tests from the module directory (app/).
        val candidates = listOf(File("src/main/assets/$path"), File("app/src/main/assets/$path"))
        return candidates.firstOrNull { it.isFile } ?: error("asset $path not found from ${File(".").absolutePath}")
    }

    @Test
    fun bundledModelMatchesItsPin() {
        val bytes = asset(YuNetModel.ASSET_PATH).readBytes()
        assertEquals(YuNetModel.SIZE_BYTES, bytes.size)
        assertEquals(YuNetModel.SHA256, YuNetModel.sha256Hex(bytes))
        assertSame(bytes, YuNetModel.requirePinned(bytes))
    }

    @Test
    fun bundledLicenceIsTheUpstreamMitFile() {
        val licence = asset(YuNetModel.LICENSE_ASSET_PATH)
        assertEquals("c83b8120c50ccbd4c4f96edf53141bdd566ebb8f8e9227e415326aa1b1aba958",
            YuNetModel.sha256Hex(licence.readBytes()))
        val text = licence.readText()
        assertTrue(text.startsWith("MIT License"))
        assertTrue(text.contains("Shiqi Yu"))
        val source = asset("models/yunet/SOURCE.md").readText()
        assertTrue(source.contains(YuNetModel.SHA256))
        assertTrue(source.contains("48b10fa505aeb51fbd127fc60a5c660040e823b7"))
    }

    @Test
    fun tamperedOrTruncatedModelIsRefused() {
        val bytes = asset(YuNetModel.ASSET_PATH).readBytes()
        val flipped = bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
        assertRefused(flipped)
        assertRefused(bytes.copyOf(bytes.size - 1))
        assertRefused(ByteArray(0))
    }

    @Test
    fun outputNamesCoverEveryHeadAndStride() {
        assertEquals(12, YuNetModel.outputNames().size)
        assertTrue(YuNetModel.outputNames().containsAll(listOf("cls_8", "obj_16", "bbox_32", "kps_8")))
        assertTrue(YuNetModel.DETECTOR_VERSION.contains(YuNetModel.SHA256.take(8)))
    }

    private fun assertRefused(bytes: ByteArray) {
        try {
            YuNetModel.requirePinned(bytes)
            throw AssertionError("expected FaceModelIntegrityFailure")
        } catch (_: FaceModelIntegrityFailure) {
        }
    }
}
