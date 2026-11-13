package nl.atlas.gallery.featurevisual.onnx

import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The VS-S0 preprocessing contract, checked against Pillow. Expected values were produced with
 * Pillow `Image.resize(size, Image.BILINEAR)` (12.3 and 13.0 give the same bytes) on the same
 * integer-formula images; see `tools/visual_search_bench/export_onnx.py`.
 */
class SiglipPreprocessTest {
    private fun small(w: Int, h: Int) = IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        (0x7F shl 24) or (((x * 37 + y * 11) % 256) shl 16) or (((x * x + 3 * y) % 256) shl 8) or ((x * y * 5 + 17) % 256)
    }

    private fun rgbBytes(argb: IntArray) = IntArray(argb.size * 3) { j ->
        val p = argb[j / 3]
        when (j % 3) { 0 -> (p shr 16) and 0xFF; 1 -> (p shr 8) and 0xFF; else -> p and 0xFF }
    }

    private fun sha256(argb: IntArray): String {
        val bytes = ByteArray(argb.size * 3)
        rgbBytes(argb).forEachIndexed { i, v -> bytes[i] = v.toByte() }
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun downscaleMatchesPillowBilinearExactly() {
        val out = SiglipPreprocess.resizeBilinear(small(61, 47), 61, 47, 13, 11)
        assertArrayEquals(PIL_61x47_TO_13x11, rgbBytes(out))
    }

    @Test
    fun upscaleMatchesPillowBilinearExactly() {
        val out = SiglipPreprocess.resizeBilinear(small(5, 4), 5, 4, 9, 7)
        assertArrayEquals(PIL_5x4_TO_9x7, rgbBytes(out))
    }

    @Test
    fun singleAxisResizeMatchesPillow() {
        val out = SiglipPreprocess.resizeBilinear(small(10, 3), 10, 3, 10, 8)
        assertArrayEquals(PIL_10x3_TO_10x8, rgbBytes(out))
    }

    @Test
    fun syntheticGoldenImagesResizeToPillowBytes() {
        // SHA-256 of Pillow's 224x224 RGB bytes, as written into the androidTest SiglipGolden.kt.
        val expected = mapOf(
            Triple("gradient_disc", 320, 240) to "3032bc3ac392f30bd98bbcef0404a36d7eb24db613d57a09cae8820e547c4d23",
            Triple("checker_band", 300, 400) to "a5ca07a165264a453b825ae89878658899df8d8daf0d23cc395a61c1277c377f",
            Triple("rings", 256, 256) to "cc23a9f573969e90957fad4770904996996be338874fbfb30a719ec31893b685",
            Triple("large_stripes", 1200, 900) to "08af2278bcaf7d7e299d17ebed6d5bf7b2751593e68ae2ea9089fd56d813937a",
        )
        for ((key, sha) in expected) {
            val (name, w, h) = key
            val out = SiglipPreprocess.resizeBilinear(SiglipSyntheticImages.argb(name, w, h), w, h, 224, 224)
            assertEquals(name, sha, sha256(out))
        }
    }

    @Test
    fun sameSizeIsIdentityAndAlphaIsDropped() {
        val src = small(6, 5)
        val out = SiglipPreprocess.resizeBilinear(src, 6, 5, 6, 5)
        for (i in src.indices) assertEquals(0xFF000000.toInt() or (src[i] and 0xFFFFFF), out[i])
    }

    @Test
    fun normalisationMapsToMinusOneToOnePlanarRgb() {
        assertEquals(-1f, SiglipPreprocess.normalize(0), 0f)
        assertEquals(1f, SiglipPreprocess.normalize(255), 1e-6f)
        assertEquals(0.003921628f, SiglipPreprocess.normalize(128), 1e-6f)
        val px = intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt())
        val out = FloatArray(6)
        SiglipPreprocess.toNchw(px, out)
        assertArrayEquals(floatArrayOf(1f, -1f, -1f, 1f, -1f, -1f), out, 1e-6f)
    }

    @Test
    fun preprocessProducesNchw224() {
        val out = SiglipPreprocess.preprocess(SiglipSyntheticImages.argb("rings", 256, 256), 256, 256)
        assertEquals(3 * 224 * 224, out.size)
        for (v in out) assert(v in -1f..1f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyImageIsRejected() {
        SiglipPreprocess.resizeBilinear(IntArray(0), 0, 4, 224, 224)
    }

    companion object {
        val PIL_61x47_TO_13x11 = intArrayOf(
            106, 14, 41, 126, 53, 83, 136, 135, 106, 119, 112, 106, 122, 134, 119, 142, 122, 96, 124, 111, 111, 116, 115, 112,
            140, 145, 126, 125, 146, 156, 119, 111, 122, 143, 138, 47, 124, 107, 83, 136, 26, 83, 119, 65, 131, 140, 147, 122,
            130, 124, 127, 113, 145, 125, 131, 134, 132, 133, 123, 132, 117, 123, 125, 136, 121, 132, 135, 149, 126, 116, 118, 151,
            126, 120, 99, 146, 119, 152, 147, 39, 106, 119, 78, 119, 120, 155, 120, 140, 118, 131, 124, 133, 134, 116, 133, 135,
            141, 134, 131, 124, 135, 125, 117, 107, 126, 141, 110, 131, 129, 122, 118, 117, 132, 116, 142, 132, 128, 142, 51, 110,
            132, 90, 131, 115, 162, 129, 130, 106, 121, 132, 132, 128, 117, 113, 133, 135, 131, 130, 138, 147, 119, 119, 111, 126,
            125, 109, 127, 136, 134, 108, 119, 134, 123, 126, 139, 129, 114, 64, 113, 139, 103, 123, 125, 168, 134, 119, 109, 115,
            144, 145, 119, 127, 121, 126, 115, 134, 127, 138, 130, 113, 129, 122, 121, 117, 122, 120, 135, 134, 131, 128, 117, 135,
            108, 138, 137, 106, 77, 116, 128, 116, 132, 133, 165, 130, 116, 100, 127, 130, 139, 132, 137, 132, 133, 120, 116, 129,
            125, 111, 126, 134, 133, 125, 118, 135, 128, 124, 121, 132, 140, 119, 153, 114, 149, 126, 122, 90, 98, 119, 129, 133,
            142, 169, 137, 128, 108, 130, 117, 121, 122, 135, 141, 131, 128, 125, 131, 118, 122, 130, 137, 145, 125, 132, 148, 126,
            118, 133, 151, 133, 123, 134, 135, 131, 122, 147, 103, 115, 118, 142, 129, 128, 158, 130, 137, 105, 120, 120, 134, 133,
            128, 122, 129, 135, 132, 124, 118, 124, 138, 122, 131, 129, 139, 150, 129, 123, 128, 132, 124, 131, 136, 148, 126, 129,
            149, 115, 115, 130, 154, 128, 119, 161, 126, 136, 115, 115, 126, 138, 121, 114, 121, 127, 138, 142, 132, 132, 132, 141,
            119, 121, 128, 135, 136, 125, 130, 121, 136, 115, 137, 134, 137, 103, 129, 124, 128, 129, 135, 167, 130, 118, 141, 128,
            127, 115, 122, 137, 115, 123, 120, 134, 125, 122, 141, 127, 138, 135, 130, 123, 110, 124, 122, 97, 126, 140, 122, 127,
            124, 122, 128, 109, 103, 125, 113, 140, 154, 139, 178, 122, 126, 128, 140, 113, 126, 121, 137, 123, 127, 132, 146, 126,
            118, 108, 129, 138, 113, 130, 130, 121, 119, 113, 105, 135, 131, 132, 110, 134, 131, 133, 106, 115, 122
        )
        val PIL_5x4_TO_9x7 = intArrayOf(
            0, 0, 17, 12, 0, 17, 33, 1, 17, 53, 2, 17, 74, 4, 17, 95, 7, 17, 115, 10, 17, 136, 14, 17,
            148, 16, 17, 4, 1, 17, 16, 1, 18, 37, 2, 18, 57, 3, 20, 78, 5, 21, 99, 8, 22, 119, 11, 23,
            140, 15, 23, 152, 17, 24, 10, 3, 17, 22, 3, 19, 43, 4, 21, 63, 5, 24, 84, 7, 26, 105, 10, 29,
            125, 13, 32, 146, 17, 34, 158, 19, 36, 17, 5, 17, 29, 5, 20, 50, 6, 24, 70, 7, 28, 91, 9, 32,
            112, 12, 37, 132, 15, 41, 153, 19, 45, 165, 21, 47, 23, 6, 17, 35, 6, 20, 56, 7, 26, 76, 8, 32,
            97, 10, 38, 118, 13, 44, 138, 16, 49, 159, 20, 55, 171, 22, 58, 29, 8, 17, 41, 8, 21, 62, 9, 29,
            82, 10, 36, 103, 12, 43, 124, 15, 51, 144, 18, 58, 165, 22, 66, 177, 24, 70, 33, 9, 17, 45, 9, 22,
            66, 10, 30, 86, 11, 39, 107, 13, 47, 128, 16, 55, 148, 19, 64, 169, 23, 72, 181, 25, 77
        )
        val PIL_10x3_TO_10x8 = intArrayOf(
            0, 0, 17, 37, 1, 17, 74, 4, 17, 111, 9, 17, 148, 16, 17, 185, 25, 17, 222, 36, 17, 3, 49, 17,
            40, 64, 17, 77, 81, 17, 1, 0, 17, 38, 1, 17, 75, 4, 18, 112, 9, 18, 149, 16, 18, 186, 25, 19,
            223, 36, 19, 4, 49, 19, 41, 64, 20, 78, 81, 20, 5, 1, 17, 42, 2, 19, 79, 5, 21, 116, 10, 24,
            153, 17, 26, 190, 26, 28, 227, 37, 30, 8, 50, 32, 45, 65, 35, 82, 82, 37, 9, 2, 17, 46, 3, 21,
            83, 6, 25, 120, 11, 29, 157, 18, 33, 194, 27, 37, 231, 38, 41, 12, 51, 45, 49, 66, 50, 86, 83, 54,
            13, 4, 17, 50, 5, 23, 87, 8, 29, 124, 13, 35, 161, 20, 41, 198, 29, 47, 235, 40, 53, 16, 53, 59,
            53, 68, 65, 90, 85, 70, 17, 5, 17, 54, 6, 25, 91, 9, 33, 128, 14, 40, 165, 21, 48, 202, 30, 56,
            239, 41, 64, 20, 54, 72, 57, 69, 80, 94, 86, 87, 21, 6, 17, 58, 7, 27, 95, 10, 36, 132, 15, 46,
            169, 22, 56, 206, 31, 65, 243, 42, 75, 24, 55, 85, 61, 70, 95, 98, 87, 104, 22, 6, 17, 59, 7, 27,
            96, 10, 37, 133, 15, 47, 170, 22, 57, 207, 31, 67, 244, 42, 77, 25, 55, 87, 62, 70, 97, 99, 87, 107
        )
    }
}
