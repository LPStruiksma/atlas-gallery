package nl.atlas.gallery.featureaiedit.provider.hosted.fal

import nl.atlas.gallery.featureaiedit.network.EditHttpConfig
import nl.atlas.gallery.featureaiedit.network.EditHttpResult
import nl.atlas.gallery.featureaiedit.network.EditRequestCancellation
import nl.atlas.gallery.featureaiedit.provider.Admission
import nl.atlas.gallery.featureaiedit.provider.AspectRange
import nl.atlas.gallery.featureaiedit.provider.CapabilityKey
import nl.atlas.gallery.featureaiedit.provider.CostEstimate
import nl.atlas.gallery.featureaiedit.provider.EditExecutionPlan
import nl.atlas.gallery.featureaiedit.provider.EditImage
import nl.atlas.gallery.featureaiedit.provider.EditJob
import nl.atlas.gallery.featureaiedit.provider.EditOutcome
import nl.atlas.gallery.featureaiedit.provider.FailureKind
import nl.atlas.gallery.featureaiedit.provider.ImageEditProvider
import nl.atlas.gallery.featureaiedit.provider.ImageEditRequest
import nl.atlas.gallery.featureaiedit.provider.ImageEditRequestValidator
import nl.atlas.gallery.featureaiedit.provider.ImageFormat
import nl.atlas.gallery.featureaiedit.provider.OptionSchema
import nl.atlas.gallery.featureaiedit.provider.OptionValue
import nl.atlas.gallery.featureaiedit.provider.ProviderCapabilities
import nl.atlas.gallery.featureaiedit.provider.hosted.EncodedImage
import nl.atlas.gallery.featureaiedit.provider.hosted.HostedEditJob
import nl.atlas.gallery.featureaiedit.provider.hosted.HostedJson
import nl.atlas.gallery.featureaiedit.provider.hosted.HostedResultStore
import nl.atlas.gallery.featureaiedit.provider.hosted.HostedWorkContext
import nl.atlas.gallery.featureaiedit.provider.hosted.ImageProbe
import nl.atlas.gallery.featureaiedit.provider.hosted.SourceImageLoader
import nl.atlas.gallery.featureaiedit.provider.hosted.arr
import nl.atlas.gallery.featureaiedit.provider.hosted.bool
import nl.atlas.gallery.featureaiedit.provider.hosted.hostedFailure
import nl.atlas.gallery.featureaiedit.provider.hosted.num
import nl.atlas.gallery.featureaiedit.provider.hosted.obj
import nl.atlas.gallery.featureaiedit.provider.hosted.str
import java.io.File
import java.net.URI
import java.util.Base64
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.ln

/**
 * fal.ai queue adapter: several edit models (open-weight Qwen-Image-Edit, FLUX Kontext) on one
 * prepaid key, chosen from [FalModel.CATALOG]. Never a silent fallback between models.
 *
 * Queue contract as published on 2026-11-18 (official `@fal-ai/client` 1.11.0, see
 * `docs/ai-edit/PROVIDERS.md`, "Implemented adapters"):
 * - submit: `POST https://queue.fal.run/{endpoint}` with the model input as JSON, header
 *   `Authorization: Key <key>`; reply `{request_id, status, …}`.
 * - status: `GET https://queue.fal.run/{owner}/{alias}/requests/{id}/status`
 *   → `IN_QUEUE` (with `queue_position`), `IN_PROGRESS` or `COMPLETED`.
 * - result: `GET https://queue.fal.run/{owner}/{alias}/requests/{id}`
 *   → `{images:[{url,content_type,width,height}], has_nsfw_concepts:[…], seed}`.
 * - cancel: `PUT https://queue.fal.run/{owner}/{alias}/requests/{id}/cancel`.
 * Images go up inline as base64 data URIs (no fal storage upload, no third URL). Results are
 * downloaded only from fal's media hosts, over HTTPS, without the key, with a bounded size.
 * fal result URLs are public to anyone holding them until they expire, so every submit asks fal
 * to expire generated objects after [RESULT_EXPIRY_SECONDS] (`X-Fal-Object-Lifecycle-Preference`,
 * the header `@fal-ai/client` 1.11.0 sends for `storageSettings.expiresIn`). A result URL on any
 * host outside [isFalMediaHost] fails closed with `RESULT_HOST_REJECTED`; the allowlist is never
 * widened at run time (#464, `docs/ai-edit/adapters/fal.md`).
 * The submit POST is never replayed: a lost reply may still have been queued and billed.
 */
class FalImageEditProvider(
    private val model: FalModel = FalModel.DEFAULT,
    private val apiKey: () -> String?,
    private val images: SourceImageLoader,
    outputDirectory: File,
    private val exchange: FalExchange = TransportFalExchange(),
    private val timing: FalTiming = FalTiming(),
    private val resultHosts: (String) -> Boolean = ::isFalMediaHost,
    private val executor: Executor? = null
) : ImageEditProvider {
    override val id: String = "fal:" + model.endpoint
    override val displayName: String = "fal.ai · " + model.displayName
    private val results = HostedResultStore(outputDirectory)
    private val nextJob = AtomicLong(1)

    init {
        require(ENDPOINT.matches(model.endpoint) && model.endpoint.split('/').size >= 2) { "Unsupported endpoint" }
    }

    override fun capabilities(): ProviderCapabilities = ProviderCapabilities(
        supportsInstructionEdit = true,
        supportsMaskEdit = false,
        maxReferences = model.maxInputImages - 1,
        maxCandidates = model.maxImages,
        supportsMultiTurn = false,
        supportsSeed = true,
        maxInputPixels = MAX_INPUT_PIXELS,
        maxOutputPixels = model.maxOutputPixels,
        aspectRange = model.aspectRatios?.let { list -> AspectRange(list.minOf { it.second }, list.maxOf { it.second }) },
        inputFormats = setOf(ImageFormat.PNG, ImageFormat.JPEG, ImageFormat.WEBP),
        outputFormats = model.outputFormats,
        supportsAsync = true,
        supportsStreaming = false,
        estimatedCostPerRequest = model.price,
        advancedParameters = model.options.mapValues { it.value.schema }
    )

    override fun submit(request: ImageEditRequest): EditJob {
        val jobId = "fal-" + nextJob.getAndIncrement()
        val admission = ImageEditRequestValidator.validate(request, capabilities())
        if (admission is Admission.Rejected) {
            return HostedEditJob(jobId, timing.pollWaitMs, DIRECT, {
                EditOutcome.Failed(FailureKind.INVALID_REQUEST, false, admission.reason.name)
            })
        }
        val plan = (admission as Admission.Accepted).plan
        val requestId = AtomicReference<String?>(null)
        val key = AtomicReference<String?>(null)
        return HostedEditJob(
            jobId, timing.pollWaitMs, executor,
            work = { context -> run(jobId, request, plan, context, requestId, key) },
            onCancel = { cancelRemote(requestId.get(), key.get()) }
        )
    }

    private fun queueConfig() = EditHttpConfig(
        baseUrl = QUEUE_BASE, allowedHosts = setOf(QUEUE_HOST),
        connectTimeoutMs = timing.connectTimeoutMs, readTimeoutMs = timing.readTimeoutMs,
        overallTimeoutMs = timing.callTimeoutMs, maxAttempts = 3
    )

    private fun requestPath(requestId: String) = "${model.appPath}/requests/$requestId"

    private fun run(
        jobId: String,
        request: ImageEditRequest,
        plan: EditExecutionPlan,
        context: HostedWorkContext,
        requestIdRef: AtomicReference<String?>,
        keyRef: AtomicReference<String?>
    ): EditOutcome {
        val key = apiKey()?.takeIf { it.isNotBlank() }
            ?: return EditOutcome.Failed(FailureKind.AUTH, false, "NO_API_KEY")
        if (!results.isUsable) return EditOutcome.Failed(FailureKind.UNKNOWN, false, "NO_OUTPUT_DIRECTORY")
        keyRef.set(key)
        val source = images.load(request.source, plan.source)
            ?: return EditOutcome.Failed(FailureKind.INVALID_REQUEST, false, "SOURCE_UNREADABLE")
        val references = request.references.mapIndexed { index, image ->
            images.load(image, plan.references[index])
                ?: return EditOutcome.Failed(FailureKind.INVALID_REQUEST, false, "REFERENCE_UNREADABLE")
        }
        val input = model.input(request, listOf(source) + references)
            ?: return EditOutcome.Failed(FailureKind.INVALID_REQUEST, false, "UPLOAD_TOO_LARGE")
        if (context.isCancelled) return EditOutcome.Cancelled

        // 1. Submit once.
        val submitted = exchange.execute(
            FalCall(FalMethod.POST, queueConfig().copy(maxAttempts = 1), model.endpoint, input, withKey = true,
                headers = SUBMIT_HEADERS),
            key, context.cancellation
        )
        val requestId = when (submitted) {
            EditHttpResult.Cancelled -> return EditOutcome.Cancelled
            is EditHttpResult.Failed -> return falError(submitted)
            is EditHttpResult.Success -> HostedJson.parseOrNull(submitted.bytes).obj()?.get("request_id").str()
                ?.takeIf { REQUEST_ID.matches(it) }
                ?: return EditOutcome.Failed(FailureKind.UNKNOWN, false, "MALFORMED_SUBMIT_REPLY")
        }
        requestIdRef.set(requestId)
        if (context.isCancelled) return EditOutcome.Cancelled

        // 2. Poll status until COMPLETED, cancel, or the job deadline.
        val deadline = System.nanoTime() + timing.jobTimeoutMs * 1_000_000L
        var queuePosition: Long? = null
        while (true) {
            if (System.nanoTime() >= deadline) {
                cancelRemote(requestId, key)
                return EditOutcome.Failed(FailureKind.TIMEOUT, true, "QUEUE_TIMEOUT")
            }
            val status = exchange.execute(
                FalCall(FalMethod.GET, queueConfig(), requestPath(requestId) + "/status", withKey = true),
                key, context.cancellation
            )
            val body = when (status) {
                EditHttpResult.Cancelled -> return EditOutcome.Cancelled
                is EditHttpResult.Failed -> return falError(status)
                is EditHttpResult.Success -> HostedJson.parseOrNull(status.bytes).obj()
                    ?: return EditOutcome.Failed(FailureKind.UNKNOWN, false, "MALFORMED_STATUS")
            }
            when (body["status"].str()) {
                "COMPLETED" -> break
                "IN_QUEUE" -> {
                    queuePosition = body["queue_position"].num()?.toLong()
                    context.reportProgress(0.0)
                }
                "IN_PROGRESS" -> context.reportProgress(0.5)
                else -> return EditOutcome.Failed(FailureKind.UNKNOWN, false, "MALFORMED_STATUS")
            }
            if (!context.pause(timing.statusIntervalMs)) return EditOutcome.Cancelled
        }

        // 3. Fetch the result description.
        val resultReply = exchange.execute(
            FalCall(FalMethod.GET, queueConfig(), requestPath(requestId), withKey = true),
            key, context.cancellation
        )
        val result = when (resultReply) {
            EditHttpResult.Cancelled -> return EditOutcome.Cancelled
            is EditHttpResult.Failed -> return when (val mapped = falError(resultReply)) {
                is EditOutcome.Failed -> if (mapped.kind == FailureKind.INVALID_REQUEST)
                    mapped.copy(message = "RESULT_REJECTED") else mapped
                else -> mapped
            }
            is EditHttpResult.Success -> HostedJson.parseOrNull(resultReply.bytes).obj()
                ?: return EditOutcome.Failed(FailureKind.UNKNOWN, false, "MALFORMED_RESULT")
        }
        val described = result["images"].arr()?.mapNotNull { it.obj() }.orEmpty()
        if (described.isEmpty()) return EditOutcome.Failed(FailureKind.UNKNOWN, false, "NO_IMAGE")
        val flagged = result["has_nsfw_concepts"].arr()
        // fal's safety checker returns flagged images as black frames: a refusal, never a success.
        if (flagged != null && described.indices.any { flagged.getOrNull(it).bool() == true })
            return EditOutcome.Refused("fal:has_nsfw_concepts", "fal.ai's safety checker withheld the edited image.")

        // 4. Download each image from an allowlisted media host, without the key.
        val stored = ArrayList<EditImage>()
        for ((index, item) in described.take(request.output.candidates).withIndex()) {
            val image = download(jobId, index, item["url"].str(), key, context)
            when (image) {
                is Downloaded.Ok -> stored.add(image.image)
                is Downloaded.Error -> {
                    stored.forEach { File(it.handle).delete() }
                    return image.outcome
                }
            }
        }
        val metadata = linkedMapOf("provider" to "fal", "endpoint" to model.endpoint, "requestId" to requestId)
        result["seed"].num()?.let { metadata["seed"] = it.toLong().toString() }
        queuePosition?.let { metadata["queuePositionAtLastCheck"] = it.toString() }
        return EditOutcome.Success(stored, providerModel = model.endpoint, providerVersion = model.endpoint, metadata = metadata)
    }

    /** fal documents `detail.type=content_policy_violation` for its structured policy error. */
    private fun falError(failure: EditHttpResult.Failed): EditOutcome {
        val root = HostedJson.parseOrNull(failure.errorBody).obj()
        val details = when (val detail = root?.get("detail")) {
            is List<*> -> detail
            is Map<*, *> -> listOf(detail)
            else -> emptyList<Any?>()
        }
        val policy = details.mapNotNull { it.obj()?.get("type").str() }
            .firstOrNull { it == "content_policy_violation" }
        return if (policy != null) EditOutcome.Refused("fal:$policy", "fal.ai declined the request ($policy).")
        else hostedFailure(failure)
    }

    private sealed interface Downloaded {
        class Ok(val image: EditImage) : Downloaded
        class Error(val outcome: EditOutcome) : Downloaded
    }

    private fun download(jobId: String, index: Int, url: String?, key: String, context: HostedWorkContext): Downloaded {
        val target = parseResultUrl(url) ?: return Downloaded.Error(
            EditOutcome.Failed(FailureKind.UNKNOWN, false, "RESULT_HOST_REJECTED"))
        val file = results.newDownloadTarget(jobId, index)
        val config = EditHttpConfig(
            baseUrl = "https://${target.first}/", allowedHosts = setOf(target.first),
            connectTimeoutMs = timing.connectTimeoutMs, readTimeoutMs = timing.readTimeoutMs,
            overallTimeoutMs = timing.callTimeoutMs, maxAttempts = 3
        )
        val reply = exchange.execute(
            FalCall(FalMethod.GET, config, target.second, withKey = false, downloadTo = file), key, context.cancellation
        )
        return when (reply) {
            EditHttpResult.Cancelled -> { file.delete(); Downloaded.Error(EditOutcome.Cancelled) }
            is EditHttpResult.Failed -> { file.delete(); Downloaded.Error(hostedFailure(reply)) }
            is EditHttpResult.Success -> {
                val bytes = if (file.isFile && file.length() in 1..MAX_RESULT_BYTES) file.readBytes() else null
                val probe = bytes?.let { ImageProbe.probe(it) }
                if (probe == null || probe.width.toLong() * probe.height > MAX_RESULT_PIXELS) {
                    file.delete()
                    Downloaded.Error(EditOutcome.Failed(FailureKind.UNKNOWN, false, "UNREADABLE_IMAGE"))
                } else {
                    val ext = when (probe.format) { ImageFormat.PNG -> "png"; ImageFormat.JPEG -> "jpg"; ImageFormat.WEBP -> "webp" }
                    val final = File(file.parentFile, file.name.removeSuffix(".download") + "." + ext)
                    if (!file.renameTo(final)) {
                        file.delete()
                        Downloaded.Error(EditOutcome.Failed(FailureKind.UNKNOWN, false, "STORE_FAILED"))
                    } else Downloaded.Ok(results.adopt(final, probe))
                }
            }
        }
    }

    /** Returns (host, relative path) for an allowlisted HTTPS result URL, else null. */
    private fun parseResultUrl(url: String?): Pair<String, String>? = try {
        val uri = URI(url ?: return null)
        val host = uri.host?.lowercase(Locale.ROOT)
        val path = uri.rawPath
        if (uri.scheme != "https" || host == null || !resultHosts(host) || uri.rawUserInfo != null ||
            uri.rawQuery != null || uri.rawFragment != null || (uri.port != -1 && uri.port != 443) ||
            path.isNullOrBlank() || path == "/" || path.contains("..")
        ) null else host to path.removePrefix("/")
    } catch (_: Exception) {
        null
    }

    private fun cancelRemote(requestId: String?, key: String?) {
        if (requestId == null || key == null) return
        runCatching {
            exchange.execute(
                FalCall(FalMethod.PUT, queueConfig().copy(maxAttempts = 1), requestPath(requestId) + "/cancel", withKey = true),
                key, EditRequestCancellation()
            )
        }
    }

    override fun toString(): String = "FalImageEditProvider(${model.endpoint})"

    companion object {
        const val QUEUE_HOST = "queue.fal.run"
        const val QUEUE_BASE = "https://$QUEUE_HOST/"
        const val MAX_INPUT_PIXELS = 4096L * 4096L
        private const val MAX_RESULT_BYTES = 32_000_000L
        private const val MAX_RESULT_PIXELS = 8192L * 8192L
        private val ENDPOINT = Regex("[a-z0-9][a-z0-9._-]*(/[a-z0-9][a-z0-9._-]*){1,4}")
        private val REQUEST_ID = Regex("[A-Za-z0-9-]{1,128}")
        private val DIRECT = Executor { it.run() }

        /**
         * Lifetime fal is asked to give generated result objects (#464): one hour, the client's
         * `"1h"` preset. Results are downloaded seconds after `COMPLETED`, so this only bounds
         * how long a public result URL stays retrievable afterwards.
         */
        const val RESULT_EXPIRY_SECONDS = 3_600
        const val LIFECYCLE_HEADER = "X-Fal-Object-Lifecycle-Preference"
        internal val SUBMIT_HEADERS = mapOf(
            LIFECYCLE_HEADER to "{\"expiration_duration_seconds\":$RESULT_EXPIRY_SECONDS}"
        )

        /** fal's media CDN hosts (for example `v3b.fal.media` in the official client). */
        fun isFalMediaHost(host: String): Boolean =
            host == "fal.media" || (host.endsWith(".fal.media") && host.removeSuffix(".fal.media")
                .split('.').all { it.matches(Regex("[a-z0-9-]{1,63}")) })
    }
}

/** Timing knobs; tests shrink them, production keeps the defaults. */
data class FalTiming(
    val pollWaitMs: Long = 1_000L,
    val statusIntervalMs: Long = 1_500L,
    val connectTimeoutMs: Int = 15_000,
    val readTimeoutMs: Int = 60_000,
    val callTimeoutMs: Long = 120_000L,
    val jobTimeoutMs: Long = 600_000L
)

/** One typed, documented model option sent as a top-level input field. */
class FalOption(val schema: OptionSchema, val field: String)

/**
 * One fal edit endpoint. Fields and limits come from the endpoint's published input schema
 * (`@fal-ai/client` 1.11.0 `types/endpoints.d.ts`, 2026-11-18); [price] is null where no fal
 * price was verified (PROVIDERS.md has none for these endpoints).
 */
class FalModel(
    val endpoint: String,
    val displayName: String,
    val openWeights: Boolean,
    val maxInputImages: Int,
    val maxImages: Int,
    val maxOutputPixels: Long,
    val outputFormats: Set<ImageFormat>,
    /** Allowed `aspect_ratio` values; null when the endpoint takes explicit pixel sizes. */
    val aspectRatios: List<Pair<String, Double>>?,
    val price: CostEstimate?,
    val options: Map<CapabilityKey, FalOption> = emptyMap(),
    private val multiImageField: Boolean
) {
    /** `{owner}/{alias}`: status, result and cancel live under this path, not the full id. */
    val appPath: String get() = endpoint.split('/').take(2).joinToString("/")

    internal fun input(request: ImageEditRequest, images: List<EncodedImage>): ByteArray? {
        val encoder = Base64.getEncoder()
        val uris = images.map { HostedJson.quote("data:${it.mimeType};base64," + encoder.encodeToString(it.bytes)) }
        val fields = ArrayList<String>()
        fields += "\"prompt\":" + HostedJson.quote(request.instruction)
        fields += if (multiImageField) "\"image_urls\":[" + uris.joinToString(",") + "]"
            else "\"image_url\":" + uris.single()
        fields += "\"num_images\":" + request.output.candidates
        fields += "\"output_format\":" + HostedJson.quote(formatName(request.output.format))
        if (aspectRatios != null) {
            val wanted = ln(request.output.width.toDouble() / request.output.height)
            fields += "\"aspect_ratio\":" + HostedJson.quote(aspectRatios.minBy { abs(ln(it.second) - wanted) }.first)
        } else {
            fields += "\"image_size\":{\"width\":${request.output.width},\"height\":${request.output.height}}"
        }
        (request.options[CapabilityKey("seed")] as? OptionValue.IntegerValue)?.let { fields += "\"seed\":${it.value}" }
        for ((key, option) in options) {
            when (val value = request.options[key]) {
                is OptionValue.TextValue -> fields += HostedJson.quote(option.field) + ":" + HostedJson.quote(value.value)
                is OptionValue.IntegerValue -> fields += HostedJson.quote(option.field) + ":" + value.value
                is OptionValue.BooleanValue -> fields += HostedJson.quote(option.field) + ":" + value.value
                is OptionValue.DecimalValue -> fields += HostedJson.quote(option.field) + ":" + value.value
                null -> Unit
            }
        }
        val bytes = fields.joinToString(",", "{", "}").toByteArray(Charsets.UTF_8)
        return if (bytes.size > MAX_BODY_BYTES) null else bytes
    }

    private fun formatName(format: ImageFormat) = when (format) {
        ImageFormat.PNG -> "png"
        ImageFormat.JPEG -> "jpeg"
        ImageFormat.WEBP -> "webp"
    }

    override fun toString(): String = "FalModel($endpoint)"

    companion object {
        private const val MAX_BODY_BYTES = 20_000_000
        private fun ratios(vararg labels: String) = labels.map { label ->
            val (w, h) = label.split(':').map { it.toDouble() }
            label to w / h
        }
        private val KONTEXT_RATIOS = ratios("21:9", "16:9", "4:3", "3:2", "1:1", "2:3", "3:4", "9:16", "9:21")
        private val SAFETY_TOLERANCE = mapOf(
            CapabilityKey("safety_tolerance") to FalOption(OptionSchema.Choices(setOf("1", "2", "3", "4", "5", "6")), "safety_tolerance")
        )

        /** Qwen-Image-Edit-2511: Apache-2.0 weights; up to 4 input images; png/jpeg/webp. */
        val QWEN_EDIT = FalModel(
            endpoint = "fal-ai/qwen-image-edit-2511",
            displayName = "Qwen Image Edit 2511 (open weights)",
            openWeights = true,
            maxInputImages = 4,
            maxImages = 4,
            // Explicit width/height are accepted; ATLAS bounds them at 4 MP until measured.
            maxOutputPixels = 2048L * 2048L,
            outputFormats = setOf(ImageFormat.PNG, ImageFormat.JPEG, ImageFormat.WEBP),
            aspectRatios = null,
            price = null,
            multiImageField = true
        )

        /** FLUX.1 Kontext [pro]: one source image, aspect-ratio presets, safety tolerance 1–6. */
        val KONTEXT_PRO = FalModel(
            endpoint = "fal-ai/flux-pro/kontext",
            displayName = "FLUX.1 Kontext [pro]",
            openWeights = false,
            maxInputImages = 1,
            maxImages = 4,
            maxOutputPixels = 2048L * 2048L,
            outputFormats = setOf(ImageFormat.PNG, ImageFormat.JPEG),
            aspectRatios = KONTEXT_RATIOS,
            price = null,
            options = SAFETY_TOLERANCE,
            multiImageField = false
        )

        /** FLUX.1 Kontext [max]: same contract as [pro], stronger model. */
        val KONTEXT_MAX = FalModel(
            endpoint = "fal-ai/flux-pro/kontext/max",
            displayName = "FLUX.1 Kontext [max]",
            openWeights = false,
            maxInputImages = 1,
            maxImages = 4,
            maxOutputPixels = 2048L * 2048L,
            outputFormats = setOf(ImageFormat.PNG, ImageFormat.JPEG),
            aspectRatios = KONTEXT_RATIOS,
            price = null,
            options = SAFETY_TOLERANCE,
            multiImageField = false
        )

        val DEFAULT = QWEN_EDIT
        val CATALOG = listOf(QWEN_EDIT, KONTEXT_PRO, KONTEXT_MAX)
    }
}
