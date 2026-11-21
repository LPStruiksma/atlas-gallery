package nl.atlas.gallery.featureidentity.local

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * IDS-17 (#516): the Kotlin pre- and post-processing for HyperSwap 1a and InsightFace
 * w600k_r50 against `hyperswap_io_fixture.json`, which tools/identity_bench exported
 * from the bench's own code for one synthetic face. No weights are involved.
 */
class HyperSwapNetworkTest {
    private val fx: Map<String, Any?> = MiniJson.parse(
        javaClass.getResourceAsStream("/identity/hyperswap_io_fixture.json")!!.bufferedReader().readText()
    ) as Map<String, Any?>
    private val arc = fx.obj("arcface")
    private val hs = fx.obj("hyperswap")
    private val image = fx.obj("image")
    private val w = (image["width"] as Double).toInt()
    private val h = (image["height"] as Double).toInt()
    private val landmarks = FloatArray(10).also { out ->
        image.list("landmarks").forEachIndexed { i, p -> val xy = p as List<*>; out[2 * i] = (xy[0] as Double).toFloat(); out[2 * i + 1] = (xy[1] as Double).toFloat() }
    }

    /** The same integer rules as `synthetic_face()` in hyperswap_fixture.py; ARGB. */
    private val face: IntArray = IntArray(w * h).also { px ->
        for (y in 0 until h) for (x in 0 until w) {
            var r = 40 + (x * 120) / (w - 1)
            var g = 60 + (y * 100) / (h - 1)
            var b = 90 + ((x + y) * 60) / (w + h - 2)
            val dx = x - 100; val dy = y - 120
            if (dx * dx * 78 * 78 + dy * dy * 60 * 60 <= 60 * 60 * 78 * 78) { r = 210 - (y - 42) / 6; g = 165 - (x - 40) / 8; b = 130 }
            for (ex in intArrayOf(78, 122)) if ((x - ex) * (x - ex) + (y - 105) * (y - 105) <= 49) { r = 40; g = 30; b = 30 }
            if ((x - 100) * (x - 100) + (y - 130) * (y - 130) <= 25) { r = 180; g = 120; b = 100 }
            if (x in 82..118 && y in 152..158) { r = 150; g = 50; b = 60 }
            px[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    /** The crop exactly as LocalFaceSwapEngine builds it. */
    private fun engineCrop(template: FloatArray, size: Int): Pair<Affine, IntArray> {
        val m = FaceAlignWarp.similarity(landmarks, template)
        val rect = FaceAlignWarp.sourceRect(m, size, w, h)!!
        val region = IntArray(rect.width * rect.height)
        for (y in 0 until rect.height) for (x in 0 until rect.width) region[y * rect.width + x] = face[(rect.top + y) * w + rect.left + x]
        return m to FaceAlignWarp.warpToCrop(region, rect, m, size)
    }

    @Test fun drawsTheSameSyntheticFaceAsTheBench() {
        // Exported crop samples come from this drawing; a different drawing would move every crop statistic.
        val (_, crop) = engineCrop(HyperSwapNetwork.template(), 256)
        assertEquals(256 * 256, crop.size)
        assertEquals(listOf(78.0, 105.0), image.list("landmarks")[0])
    }

    @Test fun templatesAndAlignmentMatrixMatchTheBench() {
        assertArrayEquals(arc.floats("template"), FaceAlignWarp.ARCFACE_112, 1e-3f)
        assertArrayEquals(hs.floats("template"), HyperSwapNetwork.template(256), 1e-3f)
        assertArrayEquals(HyperSwapNetwork.SPEC.template, HyperSwapNetwork.template(256), 0f)
        assertEquals("arcface_128", hs["templateName"])
        assertArrayEquals(arc.doubles("matrix"), FaceAlignWarp.similarity(landmarks, FaceAlignWarp.ARCFACE_112).m, 1e-4)
        assertArrayEquals(hs.doubles("matrix"), FaceAlignWarp.similarity(landmarks, HyperSwapNetwork.template()).m, 1e-4)
    }

    @Test fun specMatchesTheNetworkInputsTheBenchFeeds() {
        val spec = HyperSwapNetwork.SPEC
        assertEquals(listOf("source", "target"), hs.list("inputs"))
        assertEquals(listOf("target", "source"), listOf(spec.targetInput, spec.identityInput))
        assertEquals(listOf(1.0, 512.0), hs.list("sourceShape"))
        assertEquals(512, spec.identityDim)
        assertEquals(256, spec.cropSize)
        assertEquals(PixelRange.MINUS_ONE_ONE, spec.pixelRange)
        assertEquals(false, spec.bgr)
        assertEquals("output", spec.output)
    }

    /** Puts the bench's crop pixels at the sample positions of an otherwise grey crop. */
    private fun cropWithBenchSamples(part: Map<String, Any?>, size: Int): IntArray {
        val crop = IntArray(size * size) { 0xFF808080.toInt() }
        val idx = part.list("samplePixels").map { (it as Double).toInt() }
        part.obj("crop").list("samples").forEachIndexed { j, v ->
            val c = (v as List<*>).map { (it as Double).toInt() }
            crop[idx[j]] = (0xFF shl 24) or (c[0] shl 16) or (c[1] shl 8) or c[2]
        }
        return crop
    }

    @Test fun arcFaceTensorMathsIsTheBenchMathsExactly() {
        val crop = cropWithBenchSamples(arc, 112)
        val t = ArcFacePreprocess.toTensor(crop)
        val idx = arc.list("samplePixels").map { (it as Double).toInt() }
        arc.obj("input").list("samples").forEachIndexed { j, v ->
            val expected = (v as List<*>).map { (it as Double).toFloat() }
            for (k in 0 until 3) assertEquals("pixel ${idx[j]} channel $k", expected[k], t[k * 112 * 112 + idx[j]], 2e-6f)
        }
    }

    @Test fun hyperSwapTargetTensorMathsIsTheBenchMathsExactly() {
        val crop = cropWithBenchSamples(hs, 256)
        val t = SwapTensors.toInput(crop, HyperSwapNetwork.SPEC)
        val idx = hs.list("samplePixels").map { (it as Double).toInt() }
        hs.obj("target").list("samples").forEachIndexed { j, v ->
            val expected = (v as List<*>).map { (it as Double).toFloat() }
            for (k in 0 until 3) assertEquals("pixel ${idx[j]} channel $k", expected[k], t[k * 256 * 256 + idx[j]], 2e-6f)
        }
    }

    /**
     * The engine's warp samples pixel centres (x + 0.5) while OpenCV's warpAffine maps
     * pixel corners, so the crops differ by a sub-pixel shift of (1/scale - 1)/2 source
     * pixels; both are bilinear with a replicated edge. The tensor statistics must still
     * agree closely: measured on this fixture (2026-11-19), every channel mean and spread
     * is within 0.001 of the bench's (tensor units; one grey level is 0.0078). The test
     * allows 0.003.
     */
    @Test fun alignedCropTensorsAgreeWithTheBenchStatistics() {
        val (_, crop112) = engineCrop(FaceAlignWarp.ARCFACE_112, 112)
        checkStats(ArcFacePreprocess.toTensor(crop112), 112, arc.obj("input"))
        val (_, crop256) = engineCrop(HyperSwapNetwork.template(), 256)
        checkStats(SwapTensors.toInput(crop256, HyperSwapNetwork.SPEC), 256, hs.obj("target"))
    }

    private fun checkStats(t: FloatArray, size: Int, expected: Map<String, Any?>) {
        val plane = size * size
        val means = expected.doubles("mean"); val stds = expected.doubles("std")
        for (k in 0 until 3) {
            var s = 0.0; var ss = 0.0
            for (i in 0 until plane) { val v = t[k * plane + i].toDouble(); s += v; ss += v * v }
            val mean = s / plane
            val std = sqrt(ss / plane - mean * mean)
            assertEquals("mean, channel $k", means[k], mean, MEAN_TOLERANCE)
            assertEquals("std, channel $k", stds[k], std, STD_TOLERANCE)
        }
    }

    @Test fun sourceVectorIsTheBenchNormalisation() {
        val raw = FloatArray(512) { (((it * 37) % 101 - 50) / 25.0).toFloat() }
        val v = HyperSwapNetwork.sourceVector(raw)
        val src = hs.obj("source")
        assertEquals(src["norm"] as Double, sqrt(v.sumOf { it.toDouble() * it }), 1e-5)
        assertArrayEquals(src.floats("first"), v.copyOf(8), 1e-5f)
        // The engine averages raw encoder vectors, then normalises once: same vector for one reference.
        assertArrayEquals(v, WeakSwap.meanUnit(listOf(raw)), 1e-6f)
    }

    @Test fun outputMappingIsTheBenchPostProcessing() {
        val plane = 256 * 256
        val out = FloatArray(3 * plane) { j -> val k = j / plane; val n = j % plane; ((n * 7 + k * 13) % 241).toFloat() / 110f - 1.1f }
        val px = SwapTensors.fromOutput(out, HyperSwapNetwork.SPEC)
        val r = hs.obj("result")
        val sums = LongArray(3); val zeros = IntArray(3); val fulls = IntArray(3)
        for (p in px) {
            assertEquals(0xFF, (p ushr 24) and 255)
            for (k in 0 until 3) {
                val c = (p shr (16 - 8 * k)) and 255
                sums[k] += c.toLong(); if (c == 0) zeros[k]++; if (c == 255) fulls[k]++
            }
        }
        assertArrayEquals(r.doubles("sum").map { it.toLong() }.toLongArray(), sums)
        assertArrayEquals(r.doubles("clipped0").map { it.toInt() }.toIntArray(), zeros)
        assertArrayEquals(r.doubles("clipped255").map { it.toInt() }.toIntArray(), fulls)
        val idx = hs.list("samplePixels").map { (it as Double).toInt() }
        r.list("samples").forEachIndexed { j, v ->
            val c = (v as List<*>).map { (it as Double).toInt() }
            val p = px[idx[j]]
            assertEquals(listOf((p shr 16) and 255, (p shr 8) and 255, p and 255), c)
        }
    }

    @Test fun engineConfigUsesTheManifestEntriesAndReportsNotInstalled() {
        val root = java.nio.file.Files.createTempDirectory("ids17").toFile()
        try {
            val store = IdentityModelStore(root) { _, _ -> error("no download while building the engine") }
            val config = HyperSwapNetwork.engineConfig(store)
            assertEquals(HyperSwapNetwork.SPEC, config.spec)
            assertEquals(null, config.swapModel())
            assertEquals(null, config.encoder())
            assertTrue(HyperSwapNetwork.SPEC.version.startsWith("1a-c0e98a8a03a2"))
        } finally { root.deleteRecursively() }
    }

    companion object {
        const val MEAN_TOLERANCE = 0.003
        const val STD_TOLERANCE = 0.003
    }
}

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.obj(key: String) = this[key] as Map<String, Any?>
private fun Map<String, Any?>.list(key: String) = this[key] as List<Any?>
private fun Map<String, Any?>.doubles(key: String) = list(key).map { it as Double }.toDoubleArray()
private fun Map<String, Any?>.floats(key: String) = list(key).map { (it as Double).toFloat() }.toFloatArray()

/** A minimal JSON reader for test fixtures (objects, arrays, strings, numbers, booleans, null). */
internal object MiniJson {
    fun parse(text: String): Any? = Reader(text).run { val v = value(); ws(); require(i == s.length) { "Trailing JSON" }; v }

    private class Reader(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            return when (val c = s[i]) {
                '{' -> { i++; val m = LinkedHashMap<String, Any?>(); ws()
                    if (s[i] == '}') { i++; return m }
                    while (true) { ws(); val k = string(); ws(); require(s[i++] == ':'); m[k] = value(); ws()
                        if (s[i] == ',') { i++; continue }; require(s[i++] == '}'); return m } }
                '[' -> { i++; val l = ArrayList<Any?>(); ws()
                    if (s[i] == ']') { i++; return l }
                    while (true) { l += value(); ws(); if (s[i] == ',') { i++; continue }; require(s[i++] == ']'); return l } }
                '"' -> string()
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                else -> { require(c == '-' || c.isDigit()) { "Bad JSON at $i" }
                    val start = i; while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++; s.substring(start, i).toDouble() }
            }
        }
        fun string(): String {
            require(s[i++] == '"')
            val b = StringBuilder()
            while (s[i] != '"') {
                if (s[i] == '\\') { i++; when (val e = s[i++]) { 'n' -> b.append('\n'); 't' -> b.append('\t'); 'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }; else -> b.append(e) } }
                else b.append(s[i++])
            }
            i++
            return b.toString()
        }
    }
}
