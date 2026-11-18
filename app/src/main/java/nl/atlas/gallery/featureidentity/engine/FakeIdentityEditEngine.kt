package nl.atlas.gallery.featureidentity.engine

import nl.atlas.gallery.featureaiedit.provider.CostEstimate
import nl.atlas.gallery.featureaiedit.provider.EditImage
import nl.atlas.gallery.featureaiedit.provider.ImageFormat
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Knobs for [FakeIdentityEditEngine]. Defaults behave like a healthy local engine.
 * Call numbers count [WarmIdentityEngine.generate] calls from 0 across the engine's life.
 */
data class FakeIdentityEngineConfig(
    val installed: Boolean = true,
    val route: EngineRoute = EngineRoute.LOCAL,
    val makesNetworkCalls: Boolean = false,
    val supportedModes: Set<IdentityEditMode> = setOf(IdentityEditMode.FACE, IdentityEditMode.HEAD),
    val maxReferences: Int = 4,
    val reusesWarmState: Boolean = true,
    val maxSourcePixels: Long = 4_194_304L,
    val stepsPerCandidate: Int = 4,
    val minFaceEdge: Int = 24,
    val failOnCall: Map<Int, IdentityFailureKind> = emptyMap(),
    val outOfMemoryOnCall: Int? = null,
    /** These calls return exactly the previous call's pixels, to exercise duplicate rejection. */
    val duplicateOfPreviousOnCalls: Set<Int> = emptySet(),
    val writePng: Boolean = true,
    /** Called between steps with (call, step); tests use it to cancel mid-candidate. */
    val onStep: ((call: Int, step: Int) -> Unit)? = null
) {
    init {
        require(stepsPerCandidate in 1..32)
        require(minFaceEdge >= 1)
        require(maxSourcePixels in 1L..16_777_216L) { "Synthetic output is intentionally bounded" }
    }
}

/**
 * Deterministic synthetic identity engine for JVM tests and for building the studio UI and save
 * flow before the real engine lands. No model, decoder, network, clock or randomness: the same
 * request always gives the same pixels. Pixels outside the edit region are copied unchanged, as
 * the goal requires of real engines. Output is a real PNG so Android can decode it.
 */
class FakeIdentityEditEngine(private val config: FakeIdentityEngineConfig = FakeIdentityEngineConfig()) :
    IdentityEditEngine {

    override val id = "atlas-fake-identity"
    override val version = "1"

    val loads = AtomicInteger()
    val releases = AtomicInteger()
    val calls = AtomicInteger()
    @Volatile private var previousPixels: List<Int>? = null

    override fun capabilities() = IdentityEngineCapabilities(
        supportedModes = config.supportedModes,
        maxReferences = config.maxReferences,
        reusesWarmState = config.reusesWarmState,
        route = config.route,
        costPerCandidate = CostEstimate("EUR", 0, "Synthetic engine; no charge"),
        makesNetworkCalls = config.makesNetworkCalls,
        maxSourcePixels = config.maxSourcePixels
    )

    override fun load(): EngineLoadResult {
        if (!config.installed) return EngineLoadResult.Unavailable(IdentityFailureKind.ENGINE_NOT_INSTALLED)
        loads.incrementAndGet()
        return EngineLoadResult.Loaded(Warm())
    }

    private inner class Warm : WarmIdentityEngine {
        @Volatile private var closed = false

        override fun close() {
            if (!closed) { closed = true; releases.incrementAndGet() }
        }

        override fun generate(
            request: IdentityEditRequest,
            workspace: File,
            cancellation: IdentityCancellation
        ): IdentityEngineResult {
            check(!closed) { "Engine was released" }
            val call = calls.getAndIncrement()
            val box = request.target.box
            if (box.width < config.minFaceEdge || box.height < config.minFaceEdge)
                return IdentityEngineResult.Failure(IdentityFailureKind.FACE_TOO_SMALL)
            val source = request.source
            val region = editRegion(request)
            for (step in 0 until config.stepsPerCandidate) {
                cancellation.throwIfCancelled()
                // Stand-in for intermediate tensors a real engine may spill to disk.
                File(workspace, "step-$step.partial").writeBytes(ByteArray(64) { (call + step + it).toByte() })
                config.onStep?.invoke(call, step)
                if (config.outOfMemoryOnCall == call && step == config.stepsPerCandidate - 1)
                    throw OutOfMemoryError("synthetic")
            }
            cancellation.throwIfCancelled()
            config.failOnCall[call]?.let { return IdentityEngineResult.Failure(it) }

            val pixels = previousPixels.takeIf { call in config.duplicateOfPreviousOnCalls && it != null }
                ?: render(request, region)
            previousPixels = pixels
            workspace.listFiles { f -> f.name.endsWith(".partial") }?.forEach { it.delete() }
            val file = if (config.writePng) File(workspace, "candidate.png").also {
                it.writeBytes(encodePng(pixels, source.width, source.height))
            } else null
            return IdentityEngineResult.Success(
                image = EditImage(
                    handle = file?.path ?: "synthetic://identity/$call",
                    width = source.width,
                    height = source.height,
                    format = ImageFormat.PNG,
                    pixels = pixels
                ),
                file = file,
                fingerprint = PerceptualFingerprint.of(pixels, source.width, source.height, region)
            )
        }
    }

    companion object {
        /** The region a request may change: the face box, or a grown box for whole-head mode. */
        fun editRegion(request: IdentityEditRequest): FaceBox =
            if (request.mode == IdentityEditMode.HEAD)
                request.target.box.expanded(1.6, request.source.width, request.source.height)
            else request.target.box

        private fun basePixel(source: EditImage, x: Int, y: Int): Int =
            if (source.pixels.isNotEmpty()) source.pixels[y * source.width + x]
            else {
                val v = (x * 31 + y * 17 + source.handle.hashCode()) and 255
                (0xff000000L or (v.toLong() shl 16) or (((v * 3) and 255).toLong() shl 8) or
                    ((v * 7) and 255).toLong()).toInt()
            }

        private fun render(request: IdentityEditRequest, region: FaceBox): List<Int> {
            val source = request.source
            val v = request.variation
            var key = IdentityVariationPolicy.mix(v.seed)
            key = IdentityVariationPolicy.mix(key xor request.mode.ordinal.toLong())
            request.references.references.forEach { key = IdentityVariationPolicy.mix(key xor it.handle.hashCode().toLong()) }
            val prefs = request.preferences
            // Ordinals, not hashCode: enum hash codes differ between runs.
            key = IdentityVariationPolicy.mix(key xor (prefs.hair.ordinal * 4 +
                (if (prefs.keepExpression) 2 else 0) + (if (prefs.moreNatural) 1 else 0)).toLong())
            // An 8x8 grid of synthetic "identity" colours, blended in at the planned strength.
            val grid = IntArray(64) { cell -> (IdentityVariationPolicy.mix(key + cell) ushr 40).toInt() and 0xffffff }
            val blend = v.blendStrength
            return List(source.width * source.height) { index ->
                val x = index % source.width
                val y = index / source.width
                val base = basePixel(source, x, y)
                if (x < region.left || x >= region.right || y < region.top || y >= region.bottom) base
                else {
                    val cx = (x - region.left) * 8 / region.width
                    val cy = (y - region.top) * 8 / region.height
                    val overlay = grid[cy * 8 + cx]
                    fun ch(shift: Int) = (((overlay ushr shift) and 255) * blend +
                        ((base ushr shift) and 255) * (1 - blend)).toInt().coerceIn(0, 255)
                    (0xff shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
                }
            }
        }

        /** Minimal RGBA PNG encoder (no filter) so synthetic candidates are real image files. */
        internal fun encodePng(pixels: List<Int>, width: Int, height: Int): ByteArray {
            val raw = ByteArray(height * (1 + width * 4))
            var o = 0
            for (y in 0 until height) {
                raw[o++] = 0
                for (x in 0 until width) {
                    val p = pixels[y * width + x]
                    raw[o++] = (p ushr 16).toByte(); raw[o++] = (p ushr 8).toByte()
                    raw[o++] = p.toByte(); raw[o++] = (p ushr 24).toByte()
                }
            }
            val deflater = Deflater(Deflater.BEST_SPEED)
            val compressed = ByteArrayOutputStream()
            try {
                deflater.setInput(raw); deflater.finish()
                val buffer = ByteArray(8192)
                while (!deflater.finished()) compressed.write(buffer, 0, deflater.deflate(buffer))
            } finally { deflater.end() }
            val out = ByteArrayOutputStream()
            val data = DataOutputStream(out)
            data.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
            fun chunk(type: String, body: ByteArray) {
                data.writeInt(body.size)
                val typeBytes = type.toByteArray(Charsets.US_ASCII)
                data.write(typeBytes); data.write(body)
                data.writeInt(CRC32().apply { update(typeBytes); update(body) }.value.toInt())
            }
            val header = ByteArrayOutputStream().also { h ->
                DataOutputStream(h).apply { writeInt(width); writeInt(height); write(byteArrayOf(8, 6, 0, 0, 0)) }
            }.toByteArray()
            chunk("IHDR", header)
            chunk("IDAT", compressed.toByteArray())
            chunk("IEND", ByteArray(0))
            return out.toByteArray()
        }
    }
}
