package nl.atlas.gallery.featurevisual.onnx

/**
 * The synthetic images of `tools/visual_search_bench/export_onnx.py` (SYNTHETIC), as ARGB.
 * Integer formulas only, so Kotlin and Python produce identical pixels. Never real photos.
 * Keep in step with the androidTest copy in `featurevisual/SiglipImageEmbedderTest.kt`.
 */
object SiglipSyntheticImages {
    fun argb(name: String, w: Int, h: Int): IntArray = IntArray(w * h) { i ->
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
