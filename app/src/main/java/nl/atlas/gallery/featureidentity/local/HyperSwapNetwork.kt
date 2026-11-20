package nl.atlas.gallery.featureidentity.local

import kotlin.math.sqrt

/*
 * IDS-17 (#516): FaceFusion's HyperSwap 1a 256 behind the IDS-9 pipeline, allowed by
 * owner decision OD-1 (private use, downloaded after install from the original host).
 * The network file itself comes from [IdentityModelManifest.HYPERSWAP_1A_256] through
 * [IdentityModelStore]; nothing here touches the network.
 *
 * The pre- and post-processing is the bench's (tools/identity_bench/pipeline.py,
 * `SWAPPERS["hyperswap_1a_256"]`, `Swapper.source_vector`, `Swapper.swap_crop`):
 *  - crop: 256 px, five-point template `arcface_128` scaled to 256 (not the 112 px
 *    ArcFace template the other networks use);
 *  - input `target` [1,3,256,256]: RGB planes, (x / 255 - 0.5) / 0.5, so [-1, 1];
 *  - input `source` [1,512]: the w600k_r50 embedding of the references, averaged raw and
 *    then L2-normalised (the engine's [WeakSwap.meanUnit] does exactly that);
 *  - output `output` [1,3,256,256]: RGB planes in [-1, 1], mapped back by x * 0.5 + 0.5,
 *    rounded and clipped to 0..255. The network's second output `mask` is not used: the
 *    IDS-9 guarded mask and Lab colour match decide the paste, as in the bench.
 * `HyperSwapNetworkTest` checks this against hyperswap_io_fixture.json, exported from
 * the bench for one synthetic face.
 */
object HyperSwapNetwork {
    /** The bench's `arcface_128` five-point template, normalised to the crop side. */
    val ARCFACE_128_UNIT: FloatArray = floatArrayOf(
        0.36167656f, 0.40387734f, 0.63696719f, 0.40235469f, 0.50019687f, 0.56044219f,
        0.38710391f, 0.72160547f, 0.61507734f, 0.72034453f
    )

    const val CROP = 256
    const val IDENTITY_DIM = 512

    fun template(size: Int = CROP): FloatArray = FloatArray(10) { ARCFACE_128_UNIT[it] * size }

    val SPEC = SwapNetworkSpec(
        id = "hyperswap-1a-256",
        version = "1a-" + IdentityModelManifest.HYPERSWAP_1A_256.sha256.take(12),
        cropSize = CROP,
        template = template(CROP),
        targetInput = "target",
        identityInput = "source",
        identityDim = IDENTITY_DIM,
        output = "output",
        pixelRange = PixelRange.MINUS_ONE_ONE,
        bgr = false
    )

    /** What the bench feeds the `source` input: the raw (or averaged raw) embedding, L2-normalised. */
    fun sourceVector(raw: FloatArray): FloatArray {
        require(raw.size == IDENTITY_DIM) { "HyperSwap takes a 512-d identity" }
        var sum = 0.0
        for (v in raw) sum += v.toDouble() * v
        val norm = sqrt(sum)
        require(norm > 1e-12) { "Empty identity" }
        return FloatArray(raw.size) { (raw[it] / norm).toFloat() }
    }

    /**
     * The engine configuration for HyperSwap 1a with the InsightFace w600k_r50 identity
     * encoder, both from [store]. A model that is not installed yields null, which the
     * engine reports as `ENGINE_NOT_INSTALLED` and the studio turns into its setup sheet.
     */
    fun engineConfig(store: IdentityModelStore, intraOpThreads: Int = 4): LocalEngineConfig = LocalEngineConfig(
        spec = SPEC,
        swapModel = { store.installedFile(IdentityModelManifest.HYPERSWAP_1A_256) },
        encoder = { store.installedFile(IdentityModelManifest.INSIGHTFACE_W600K_R50)?.let { ArcFaceEmbedder(it) } },
        intraOpThreads = intraOpThreads
    )
}

/**
 * Tensor layout shared by every swap network the engine runs, driven by [SwapNetworkSpec].
 * Pure JVM, so the JVM tests check the exact maths the engine uses.
 */
object SwapTensors {
    /** ARGB crop ([spec].cropSize squared) to NCHW planes in the network's range and channel order. */
    fun toInput(crop: IntArray, spec: SwapNetworkSpec): FloatArray {
        val plane = spec.cropSize * spec.cropSize
        require(crop.size == plane) { "Crop has the wrong size" }
        val input = FloatArray(3 * plane)
        val signed = spec.pixelRange == PixelRange.MINUS_ONE_ONE
        for (i in 0 until plane) {
            val p = crop[i]
            val r = ((p shr 16) and 255) / 255f
            val g = ((p shr 8) and 255) / 255f
            val b = (p and 255) / 255f
            val c0 = if (spec.bgr) b else r
            val c2 = if (spec.bgr) r else b
            input[i] = if (signed) (c0 - 0.5f) / 0.5f else c0
            input[plane + i] = if (signed) (g - 0.5f) / 0.5f else g
            input[2 * plane + i] = if (signed) (c2 - 0.5f) / 0.5f else c2
        }
        return input
    }

    /**
     * NCHW network output back to an opaque ARGB crop: range undone, rounded, clipped.
     * Float32 throughout and round-half-to-even, as the bench's NumPy
     * `np.clip(np.rint((out * std + mean) * 255.0), 0, 255)` on float32 arrays.
     */
    fun fromOutput(out: FloatArray, spec: SwapNetworkSpec): IntArray {
        val plane = spec.cropSize * spec.cropSize
        require(out.size == 3 * plane) { "Swap output has the wrong size" }
        val signed = spec.pixelRange == PixelRange.MINUS_ONE_ONE
        fun ch(k: Int, i: Int): Int {
            val v = out[k * plane + i]
            val unit = if (signed) v * 0.5f + 0.5f else v
            val scaled = unit * 255f
            if (scaled.isNaN()) return 0
            return Math.rint(scaled.toDouble()).coerceIn(0.0, 255.0).toInt()
        }
        return IntArray(plane) { i ->
            val c0 = ch(0, i); val g = ch(1, i); val c2 = ch(2, i)
            val (r, b) = if (spec.bgr) c2 to c0 else c0 to c2
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}
