package nl.atlas.gallery.featureidentity.local

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.sqrt
import nl.atlas.gallery.featureaiedit.provider.CostEstimate
import nl.atlas.gallery.featureaiedit.provider.EditImage
import nl.atlas.gallery.featureaiedit.provider.ImageFormat
import nl.atlas.gallery.featureface.onnx.OnnxFaceDetector
import nl.atlas.gallery.featureface.onnx.OnnxFaceEmbedder
import nl.atlas.gallery.featureface.onnx.YuNetFace
import nl.atlas.gallery.featureidentity.engine.EngineLoadResult
import nl.atlas.gallery.featureidentity.engine.EngineRoute
import nl.atlas.gallery.featureidentity.engine.FaceBox
import nl.atlas.gallery.featureidentity.engine.IdentityCancellation
import nl.atlas.gallery.featureidentity.engine.IdentityEditEngine
import nl.atlas.gallery.featureidentity.engine.IdentityEditMode
import nl.atlas.gallery.featureidentity.engine.IdentityEditRequest
import nl.atlas.gallery.featureidentity.engine.IdentityEngineCapabilities
import nl.atlas.gallery.featureidentity.engine.IdentityEngineResult
import nl.atlas.gallery.featureidentity.engine.IdentityFailureKind
import nl.atlas.gallery.featureidentity.engine.PerceptualFingerprint
import nl.atlas.gallery.featureidentity.engine.WarmIdentityEngine

/*
 * IDS-9 (#475): the on-phone face engine behind the IDS-6 contract.
 * Pipeline per candidate: five-point alignment to the network's crop, the swap
 * network on ONNX Runtime (CPU), a guarded mask, Lab colour match, feathered
 * paste-back at full source resolution (pixels outside the mask bit-identical),
 * and an SFace weak-swap check. No network call happens anywhere in [load] or
 * [WarmIdentityEngine.generate]; models come from [IdentityModelStore] files that
 * the user installed earlier. The network itself is pluggable through
 * [SwapNetworkSpec], so a later model plugs in by manifest without code changes.
 */

/** How a swap network wants its tensors. */
enum class PixelRange { ZERO_ONE, MINUS_ONE_ONE }

data class SwapNetworkSpec(
    val id: String,
    val version: String,
    val cropSize: Int,
    /** Five-point template in crop pixels; usually [FaceAlignWarp.arcfaceTemplate]. */
    val template: FloatArray,
    val targetInput: String,
    /** Name of the identity-vector input, or null for a network that takes none. */
    val identityInput: String?,
    val identityDim: Int,
    val output: String,
    val pixelRange: PixelRange = PixelRange.ZERO_ONE,
    val bgr: Boolean = false
) {
    init {
        require(cropSize in 64..1024 && template.size == 10)
        require(identityInput == null || identityDim > 0)
    }
}

/** Turns aligned reference crops into the vector a network's identity input wants. */
interface IdentityEncoder : AutoCloseable {
    /** Crop size the encoder aligns references to, and its template. */
    val cropSize: Int
    val template: FloatArray
    fun encode(alignedArgb: IntArray): FloatArray
}

/** For networks (and the CI stub) that only need a fixed-length identity input. */
class ConstantIdentityEncoder(private val dim: Int, override val cropSize: Int = 112) : IdentityEncoder {
    override val template: FloatArray = FaceAlignWarp.arcfaceTemplate(cropSize)
    override fun encode(alignedArgb: IntArray) = FloatArray(dim) { 1f / sqrt(dim.toFloat()) }
    override fun close() = Unit
}

/** Optional face segmenter (MediaPipe multiclass, converted for ORT): face-skin probability per crop pixel. */
interface FaceSegmenter : AutoCloseable {
    fun faceSkin(cropArgb: IntArray, size: Int): FloatArray
}

/** Everything the engine needs, injected so tests and later networks plug in without code changes. */
class LocalEngineConfig(
    val spec: SwapNetworkSpec,
    /** The installed swap-network file, or null when it is not installed (the UI then offers setup). */
    val swapModel: () -> File?,
    val encoder: () -> IdentityEncoder?,
    val segmenter: () -> FaceSegmenter? = { null },
    val colourMatch: Boolean = true,
    /** Largest source the engine accepts, in pixels. */
    val maxSourcePixels: Long = 64_000_000L,
    /** Long edge references are decoded to; they never need full resolution. */
    val referenceLongEdge: Int = 1280,
    val intraOpThreads: Int = 4,
    /** Called each time the model set loads; tests use it to prove warm reuse. */
    val onModelsLoaded: () -> Unit = {}
)

class LocalFaceSwapEngine(private val context: Context, private val config: LocalEngineConfig) : IdentityEditEngine {
    override val id: String = "local-" + config.spec.id
    override val version: String = config.spec.version

    override fun capabilities() = IdentityEngineCapabilities(
        supportedModes = setOf(IdentityEditMode.FACE),
        maxReferences = 8,
        reusesWarmState = true,
        route = EngineRoute.LOCAL,
        costPerCandidate = CostEstimate("EUR", 0, "On this phone; free"),
        makesNetworkCalls = false,
        maxSourcePixels = config.maxSourcePixels
    )

    override fun load(): EngineLoadResult {
        val model = config.swapModel() ?: return EngineLoadResult.Unavailable(IdentityFailureKind.ENGINE_NOT_INSTALLED)
        val encoder = config.encoder() ?: return EngineLoadResult.Unavailable(IdentityFailureKind.ENGINE_NOT_INSTALLED)
        return try {
            EngineLoadResult.Loaded(Warm(context.applicationContext, config, model, encoder))
        } catch (oom: OutOfMemoryError) {
            encoder.close()
            EngineLoadResult.Unavailable(IdentityFailureKind.OUT_OF_MEMORY)
        }
    }

    /**
     * Model state kept across candidates. ORT sessions load lazily and are dropped on
     * [close] or when Android reports low memory; the next candidate reloads them.
     * Neither the memory callbacks (main thread) nor [close] ever wait for a running
     * candidate: [gate] releases at once when idle, otherwise right after the candidate.
     */
    private class Warm(
        private val app: Context,
        private val config: LocalEngineConfig,
        private val modelFile: File,
        private val encoder: IdentityEncoder
    ) : WarmIdentityEngine, ComponentCallbacks2 {
        private val gate = WarmReleaseGate { releaseNow() }
        private val env = OrtEnvironment.getEnvironment()
        private var options: OrtSession.SessionOptions? = null
        private var session: OrtSession? = null
        private var detector: OnnxFaceDetector? = null
        private var embedder: OnnxFaceEmbedder? = null
        private var segmenter: FaceSegmenter? = null
        private val identityCache = HashMap<List<String>, Pair<FloatArray, FloatArray?>>()
        @Volatile private var closed = false

        init { app.registerComponentCallbacks(this) }

        private fun ensureLoaded() {
            check(!closed) { "Engine closed" }
            if (session != null) return
            val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(config.intraOpThreads) }
            try {
                session = env.createSession(modelFile.absolutePath, opts)
                options = opts
            } catch (t: Throwable) { opts.close(); throw t }
            detector = OnnxFaceDetector.fromAssets(app.assets)
            embedder = try { OnnxFaceEmbedder.fromAssets(app.assets) } catch (_: Exception) { null }
            segmenter = config.segmenter()
            config.onModelsLoaded()
        }

        private fun releaseModels() {
            session?.close(); session = null
            options?.close(); options = null
            detector?.close(); detector = null
            embedder?.close(); embedder = null
            segmenter?.close(); segmenter = null
        }

        /** Runs under [gate]: drops the models, and on close also the encoder and cached identities. */
        private fun releaseNow() {
            releaseModels()
            if (closed) { encoder.close(); identityCache.clear() }
        }

        override fun onTrimMemory(level: Int) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) gate.requestRelease()
        }
        override fun onLowMemory() { gate.requestRelease() }
        override fun onConfigurationChanged(newConfig: Configuration) = Unit

        override fun close() {
            if (closed) return
            closed = true
            app.unregisterComponentCallbacks(this)
            gate.requestRelease()
        }

        override fun generate(
            request: IdentityEditRequest, workspace: File, cancellation: IdentityCancellation
        ): IdentityEngineResult = gate.exclusive { generateExclusive(request, workspace, cancellation) }

        private fun generateExclusive(
            request: IdentityEditRequest, workspace: File, cancellation: IdentityCancellation
        ): IdentityEngineResult {
            if (request.mode != IdentityEditMode.FACE) return IdentityEngineResult.Failure(IdentityFailureKind.MODE_UNAVAILABLE)
            if (request.references.references.isEmpty()) return IdentityEngineResult.Failure(IdentityFailureKind.NO_REFERENCES)
            cancellation.throwIfCancelled()
            ensureLoaded()
            val spec = config.spec

            // 1. Identity from the references (cached per reference set while warm).
            val (identity, profileSface) = identityFor(request) ?: return IdentityEngineResult.Failure(IdentityFailureKind.NO_FACE_FOUND)
            cancellation.throwIfCancelled()

            // 2. Source at full resolution, refused before allocation when too large.
            val source = decodeFull(request.source) ?: return IdentityEngineResult.Failure(IdentityFailureKind.PHOTO_TOO_LARGE)
            try {
                val landmarks = FloatArray(10).also { out ->
                    request.target.landmarks.forEachIndexed { i, p -> out[2 * i] = p.x; out[2 * i + 1] = p.y }
                }
                val toCrop = try { FaceAlignWarp.similarity(landmarks, spec.template) }
                    catch (_: IllegalArgumentException) { return IdentityEngineResult.Failure(IdentityFailureKind.NO_FACE_FOUND) }
                if (toCrop.inverse().scale * spec.cropSize < MIN_FACE_CROP_PX) {
                    return IdentityEngineResult.Failure(IdentityFailureKind.FACE_TOO_SMALL)
                }
                val rect = FaceAlignWarp.sourceRect(toCrop, spec.cropSize, source.width, source.height)
                    ?: return IdentityEngineResult.Failure(IdentityFailureKind.NO_FACE_FOUND)
                val region = IntArray(rect.width * rect.height)
                source.getPixels(region, 0, rect.width, rect.left, rect.top, rect.width, rect.height)
                val originalSface = sfaceOf(source, request.target.box, landmarks)
                val targetCrop = FaceAlignWarp.warpToCrop(region, rect, toCrop, spec.cropSize)
                cancellation.throwIfCancelled()

                // 3. Swap network.
                var swapped = runNetwork(targetCrop, identity)
                cancellation.throwIfCancelled()

                // 4. Mask with its guard, scaled by the planned blend strength.
                val decision = FaceBlendMask.decide(spec.template, spec.cropSize,
                    segmenter?.faceSkin(targetCrop, spec.cropSize))
                val strength = request.variation.blendStrength.toFloat()
                val alpha = FloatArray(decision.alpha.size) { decision.alpha[it] * strength }

                // 5. Lab colour match, then paste back.
                if (config.colourMatch) swapped = FaceColourMatch.match(swapped, targetCrop, decision.alpha)
                val paste = FaceBlendMask.pasteBack(region, rect, toCrop, swapped, alpha, spec.cropSize)
                source.setPixels(region, 0, rect.width, rect.left, rect.top, rect.width, rect.height)
                cancellation.throwIfCancelled()

                // 6. Weak-swap check: closer to the original person than to the profile.
                val resultSface = sfaceOf(source, request.target.box, landmarks)
                val weak = WeakSwap.isWeak(resultSface, profileSface, originalSface)

                val out = File(workspace, "candidate.png")
                out.outputStream().use { check(source.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                File(workspace, "candidate.provenance.json").writeText(provenance(request, decision, paste, weak))
                val fingerprint = fingerprint(source, request.target.box)
                return IdentityEngineResult.Success(
                    EditImage(out.absolutePath, source.width, source.height, ImageFormat.PNG), out, fingerprint)
            } finally {
                source.recycle()
            }
        }

        private fun identityFor(request: IdentityEditRequest): Pair<FloatArray, FloatArray?>? {
            val key = request.references.references.map { it.handle }
            identityCache[key]?.let { return it }
            val vectors = ArrayList<FloatArray>()
            val sfaces = ArrayList<FloatArray>()
            for (ref in request.references.references) {
                val bitmap = decodeReference(ref) ?: continue
                try {
                    val face = detector!!.detectFaces(bitmap).maxByOrNull { it.width * it.height } ?: continue
                    val m = FaceAlignWarp.similarity(face.landmarks, encoder.template)
                    val rect = FaceAlignWarp.sourceRect(m, encoder.cropSize, bitmap.width, bitmap.height) ?: continue
                    val px = IntArray(rect.width * rect.height)
                    bitmap.getPixels(px, 0, rect.width, rect.left, rect.top, rect.width, rect.height)
                    vectors += encoder.encode(FaceAlignWarp.warpToCrop(px, rect, m, encoder.cropSize))
                    embedder?.let { e -> sfaces += e.embed(bitmap, face).vector }
                } catch (_: IllegalArgumentException) {
                    continue
                } finally { bitmap.recycle() }
            }
            if (vectors.isEmpty()) return null
            val result = WeakSwap.meanUnit(vectors) to (if (sfaces.isEmpty()) null else WeakSwap.meanUnit(sfaces))
            identityCache[key] = result
            return result
        }

        private fun runNetwork(crop: IntArray, identity: FloatArray): IntArray {
            val spec = config.spec
            val s = spec.cropSize
            val plane = s * s
            val input = FloatArray(3 * plane)
            for (i in 0 until plane) {
                val p = crop[i]
                val r = ((p shr 16) and 255) / 255f; val g = ((p shr 8) and 255) / 255f; val b = (p and 255) / 255f
                val c = if (spec.bgr) floatArrayOf(b, g, r) else floatArrayOf(r, g, b)
                for (k in 0 until 3) input[k * plane + i] = if (spec.pixelRange == PixelRange.ZERO_ONE) c[k] else c[k] * 2 - 1
            }
            val inputs = HashMap<String, OnnxTensor>()
            try {
                inputs[spec.targetInput] = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, s.toLong(), s.toLong()))
                spec.identityInput?.let { name ->
                    val id = if (identity.size == spec.identityDim) identity else FloatArray(spec.identityDim) { identity.getOrElse(it) { 0f } }
                    inputs[name] = OnnxTensor.createTensor(env, FloatBuffer.wrap(id), longArrayOf(1, spec.identityDim.toLong()))
                }
                session!!.run(inputs).use { result ->
                    val value = result.get(spec.output).orElseThrow { IllegalStateException("Swap output missing") }
                    val buffer = (value as OnnxTensor).floatBuffer
                    val out = FloatArray(buffer.remaining()).also { buffer.get(it) }
                    require(out.size == 3 * plane) { "Swap output has the wrong size" }
                    return IntArray(plane) { i ->
                        fun ch(k: Int): Int {
                            val v = out[k * plane + i]
                            val unit = if (spec.pixelRange == PixelRange.ZERO_ONE) v else (v + 1) / 2
                            return FaceAlignWarp.round8(unit * 255.0)
                        }
                        val (r, g, b) = if (spec.bgr) Triple(ch(2), ch(1), ch(0)) else Triple(ch(0), ch(1), ch(2))
                        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
            } finally { inputs.values.forEach { it.close() } }
        }

        private fun sfaceOf(bitmap: Bitmap, box: FaceBox, landmarks: FloatArray): FloatArray? {
            val e = embedder ?: return null
            return try {
                e.embed(bitmap, YuNetFace(box.left.toFloat(), box.top.toFloat(), box.width.toFloat(),
                    box.height.toFloat(), 1f, landmarks.copyOf())).vector
            } catch (_: IllegalArgumentException) { null }
        }

        private fun open(image: EditImage): InputStream? {
            val handle = image.handle
            return if (handle.startsWith("content://") || handle.startsWith("file://"))
                app.contentResolver.openInputStream(Uri.parse(handle))
            else File(handle).takeIf { it.isFile }?.inputStream()
        }

        /** Bounds first; decodes only when the pixel count and free heap allow a full-size copy. */
        private fun decodeFull(image: EditImage): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            // A bounds-only decode always returns null by contract; only a stream that
            // fails to open or empty bounds mean failure.
            (open(image) ?: return null).use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val pixels = bounds.outWidth.toLong() * bounds.outHeight
            if (pixels > config.maxSourcePixels) return null
            val rt = Runtime.getRuntime()
            val free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
            // One ARGB copy plus room for the PNG encoder and the crop work.
            if (pixels * 4 + HEAP_MARGIN_BYTES > free) return null
            val opts = BitmapFactory.Options().apply { inMutable = true; inPreferredConfig = Bitmap.Config.ARGB_8888 }
            return try { open(image)?.use { BitmapFactory.decodeStream(it, null, opts) } }
            catch (_: OutOfMemoryError) { null }
        }

        private fun decodeReference(image: EditImage): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            // A bounds-only decode always returns null by contract; only a stream that
            // fails to open or empty bounds mean failure.
            (open(image) ?: return null).use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= config.referenceLongEdge) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
            return open(image)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }

        private fun fingerprint(bitmap: Bitmap, box: FaceBox): PerceptualFingerprint {
            val l = box.left.coerceIn(0, bitmap.width - 1); val t = box.top.coerceIn(0, bitmap.height - 1)
            val r = box.right.coerceIn(l + 1, bitmap.width); val b = box.bottom.coerceIn(t + 1, bitmap.height)
            val px = IntArray((r - l) * (b - t))
            bitmap.getPixels(px, 0, r - l, l, t, r - l, b - t)
            return PerceptualFingerprint.of(px.asList(), r - l, b - t, FaceBox(0, 0, r - l, b - t))
        }

        private fun provenance(
            request: IdentityEditRequest, mask: MaskDecision, paste: PasteResult, weak: Boolean?
        ): String = buildString {
            // Settings only: no pixels, landmarks, boxes or identity vectors.
            append('{')
            append("\"engine\":\"local-").append(config.spec.id).append("\",")
            append("\"engineVersion\":\"").append(config.spec.version).append("\",")
            append("\"mode\":\"").append(request.mode.name).append("\",")
            append("\"mask\":\"").append(mask.source.code).append("\",")
            append("\"colourMatch\":").append(config.colourMatch).append(',')
            append("\"blendStrength\":").append(request.variation.blendStrength).append(',')
            append("\"seed\":").append(request.variation.seed).append(',')
            append("\"changedPixels\":").append(paste.changedPixels).append(',')
            append("\"weakSwap\":").append(weak?.toString() ?: "null")
            append('}')
        }
    }

    companion object {
        /** A face whose aligned crop would come from fewer source pixels than this is too small. */
        const val MIN_FACE_CROP_PX = 48.0
        const val HEAP_MARGIN_BYTES = 48L * 1024 * 1024
    }
}

/**
 * Serialises candidates and lets a release request (low memory, close) arrive from any
 * thread without waiting: it runs [release] now when no candidate is running, otherwise
 * once the running candidate finishes. Pure JVM.
 */
class WarmReleaseGate(private val release: () -> Unit) {
    private val lock = java.util.concurrent.locks.ReentrantLock()
    @Volatile private var pending = false

    fun <T> exclusive(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            try { if (pending) { pending = false; release() } } finally {
                lock.unlock()
                // A request may race with the pending check just before unlock.
                // Retry without waiting so a failed tryLock cannot strand it.
                if (pending) requestRelease()
            }
        }
    }

    /** Never blocks. True when the release ran now; false when it waits for the running candidate. */
    fun requestRelease(): Boolean {
        pending = true
        // Re-check after setting the flag, so a candidate that just finished cannot strand it.
        if (!lock.tryLock()) return false
        try {
            if (pending) { pending = false; release() }
            return true
        } finally { lock.unlock() }
    }
}

/** Pure helpers for the weak-swap check (SFace similarity is a check, not a gate: BENCH.md). */
object WeakSwap {
    fun meanUnit(vectors: List<FloatArray>): FloatArray {
        require(vectors.isNotEmpty())
        val dim = vectors.first().size
        require(vectors.all { it.size == dim })
        val mean = FloatArray(dim) { i -> vectors.sumOf { it[i].toDouble() }.toFloat() / vectors.size }
        val norm = sqrt(mean.sumOf { (it * it).toDouble() }).toFloat()
        return if (norm < 1e-12f) mean else FloatArray(dim) { mean[it] / norm }
    }

    fun cosine(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size)
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return if (na == 0.0 || nb == 0.0) 0.0 else dot / sqrt(na * nb)
    }

    /** True when the result looks more like the original person than the profile; null if unknown. */
    fun isWeak(result: FloatArray?, profile: FloatArray?, original: FloatArray?): Boolean? {
        if (result == null || profile == null || original == null) return null
        return cosine(result, profile) < cosine(result, original)
    }
}
