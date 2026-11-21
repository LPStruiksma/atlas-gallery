package nl.atlas.gallery.featureaiedit.provider.hosted.gemini

import nl.atlas.gallery.featureaiedit.network.EditHttpAuth
import nl.atlas.gallery.featureaiedit.network.EditHttpBody
import nl.atlas.gallery.featureaiedit.network.EditHttpConfig
import nl.atlas.gallery.featureaiedit.network.EditHttpMethod
import nl.atlas.gallery.featureaiedit.network.EditHttpRequest
import nl.atlas.gallery.featureaiedit.network.EditHttpResult
import nl.atlas.gallery.featureaiedit.network.EditHttpTransport
import nl.atlas.gallery.featureaiedit.provider.Admission
import nl.atlas.gallery.featureaiedit.provider.AspectRange
import nl.atlas.gallery.featureaiedit.provider.CostEstimate
import nl.atlas.gallery.featureaiedit.provider.CostScope
import nl.atlas.gallery.featureaiedit.provider.Delivery
import nl.atlas.gallery.featureaiedit.provider.SubmitJournal
import nl.atlas.gallery.featureaiedit.provider.RemoteRecovery
import nl.atlas.gallery.featureaiedit.provider.EditExecutionPlan
import nl.atlas.gallery.featureaiedit.provider.EditJob
import nl.atlas.gallery.featureaiedit.provider.EditOutcome
import nl.atlas.gallery.featureaiedit.provider.FailureKind
import nl.atlas.gallery.featureaiedit.provider.ImageEditProvider
import nl.atlas.gallery.featureaiedit.provider.ImageEditRequest
import nl.atlas.gallery.featureaiedit.provider.ImageEditRequestValidator
import nl.atlas.gallery.featureaiedit.provider.ImageFormat
import nl.atlas.gallery.featureaiedit.provider.OutputSpec
import nl.atlas.gallery.featureaiedit.provider.ProviderCapabilities
import nl.atlas.gallery.featureaiedit.provider.hosted.EncodedImage
import nl.atlas.gallery.featureaiedit.provider.hosted.HostedEditJob
import nl.atlas.gallery.featureaiedit.provider.hosted.HostedJson
import nl.atlas.gallery.featureaiedit.provider.hosted.HostedJsonFile
import nl.atlas.gallery.featureaiedit.provider.hosted.LargeJsonString
import nl.atlas.gallery.featureaiedit.provider.hosted.HostedResultStore
import nl.atlas.gallery.featureaiedit.provider.hosted.HostedWorkContext
import nl.atlas.gallery.featureaiedit.provider.hosted.SourceImageLoader
import nl.atlas.gallery.featureaiedit.provider.hosted.arr
import nl.atlas.gallery.featureaiedit.provider.hosted.bool
import nl.atlas.gallery.featureaiedit.provider.hosted.hostedFailure
import nl.atlas.gallery.featureaiedit.provider.hosted.obj
import nl.atlas.gallery.featureaiedit.provider.hosted.str
import java.io.File
import java.time.LocalDate
import java.util.Base64
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.ln

/**
 * Google Gemini API image editing ("Nano Banana" image models) through `generateContent`.
 *
 * Wire format as documented on 2026-11-18 (see `docs/ai-edit/PROVIDERS.md`, "Implemented
 * adapters"): `POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent`
 * with header `x-goog-api-key`; body `contents[].parts[]` holding `text` and
 * `inlineData{mimeType,data}`; `generationConfig.responseModalities` and
 * `generationConfig.imageConfig{aspectRatio,imageSize}`. The reply carries the image as base64
 * `inlineData` in `candidates[0].content.parts[]`, `finishReason`, `promptFeedback.blockReason`,
 * `modelVersion` and `responseId`.
 *
 * Privacy: only the request's source and explicitly attached references are uploaded, to exactly
 * the Gemini API host; the key travels in a header, never in the URL, and never appears in a
 * message, log or `toString`. One user action is one POST: it is never replayed.
 */
class GeminiImageEditProvider(
    private val model: GeminiModel = GeminiModel.DEFAULT,
    private val apiKey: () -> String?,
    private val images: SourceImageLoader,
    outputDirectory: File,
    private val transport: EditHttpTransport = EditHttpTransport(),
    private val httpConfig: EditHttpConfig = defaultHttpConfig(),
    private val pollWaitMs: Long = 1_000L,
    private val executor: Executor? = null,
    /** Bound on the spooled reply; the default allows a 4K image base64-encoded in JSON. */
    private val maxReplyBytes: Int = EditHttpTransport.MAX_REPLY_CEILING
) : ImageEditProvider {
    override val id: String = "gemini:" + model.id
    override val displayName: String = "Google Gemini · " + model.displayName
    private val results = HostedResultStore(outputDirectory)
    private val nextJob = AtomicLong(1)

    init {
        require(MODEL_ID.matches(model.id)) { "Unsupported model id" }
        require(httpConfig.allowedHosts.map { it.lowercase() }.toSet() == setOf(API_HOST)) {
            "Gemini traffic may only reach $API_HOST"
        }
    }

    override fun capabilities(): ProviderCapabilities = ProviderCapabilities(
        supportsInstructionEdit = true,
        // No documented pixel-mask input on generateContent: masks are refused, never faked.
        supportsMaskEdit = false,
        maxReferences = model.maxInputImages - 1,
        // One image per call; Gemini does not document a candidate count for image output.
        maxCandidates = 1,
        // Conversational edits need the previous turn's state; ATLAS sends one stateless call.
        supportsMultiTurn = false,
        supportsSeed = false,
        maxInputPixels = MAX_INPUT_PIXELS,
        maxOutputPixels = model.sizes.maxOf { it.maxPixels },
        aspectRange = AspectRange(model.aspectRatios.minOf { it.ratio }, model.aspectRatios.maxOf { it.ratio }),
        inputFormats = setOf(ImageFormat.PNG, ImageFormat.JPEG, ImageFormat.WEBP),
        // The Gemini API chooses the output encoding; the result reports the real format.
        outputFormats = setOf(ImageFormat.PNG),
        // poll() never blocks past pollWaitMs and cancel() closes the socket from any thread.
        supportsAsync = true,
        supportsStreaming = false,
        estimatedCostPerRequest = model.sizes.firstOrNull()?.price,
        // generateContent is one synchronous POST: there is no remote job to look up later, so a
        // request interrupted after it may have been sent can never be recovered (#431 row 6).
        remoteRecovery = RemoteRecovery.NONE
    )

    override fun estimateCost(request: ImageEditRequest): CostEstimate? {
        if (ImageEditRequestValidator.validate(request, capabilities()) !is Admission.Accepted) return null
        return sizeFor(request.output).price
    }

    override fun submit(request: ImageEditRequest): EditJob = submit(request, SubmitJournal.NONE)

    /** [SubmitJournal.beforeSend] runs right before the one POST; nothing earlier sends anything. */
    override fun submit(request: ImageEditRequest, journal: SubmitJournal): EditJob {
        val jobId = "gemini-" + nextJob.getAndIncrement()
        val admission = ImageEditRequestValidator.validate(request, capabilities())
        if (admission is Admission.Rejected) {
            return HostedEditJob(jobId, pollWaitMs, DIRECT, {
                EditOutcome.Failed(FailureKind.INVALID_REQUEST, false, admission.reason.name, Delivery.NOT_SENT)
            })
        }
        val plan = (admission as Admission.Accepted).plan
        return HostedEditJob(jobId, pollWaitMs, executor, { context -> run(jobId, request, plan, context, journal) })
    }

    private fun run(
        jobId: String,
        request: ImageEditRequest,
        plan: EditExecutionPlan,
        context: HostedWorkContext,
        journal: SubmitJournal
    ): EditOutcome {
        // Everything before beforeSend() is local: such failures are proven NOT_SENT.
        val key = apiKey()?.takeIf { it.isNotBlank() }
            ?: return EditOutcome.Failed(FailureKind.AUTH, false, "NO_API_KEY", Delivery.NOT_SENT)
        if (!results.isUsable) return EditOutcome.Failed(FailureKind.UNKNOWN, false, "NO_OUTPUT_DIRECTORY", Delivery.NOT_SENT)
        val source = images.load(request.source, plan.source)
            ?: return EditOutcome.Failed(FailureKind.INVALID_REQUEST, false, "SOURCE_UNREADABLE", Delivery.NOT_SENT)
        val references = request.references.mapIndexed { index, image ->
            images.load(image, plan.references[index])
                ?: return EditOutcome.Failed(FailureKind.INVALID_REQUEST, false, "REFERENCE_UNREADABLE", Delivery.NOT_SENT)
        }
        val size = sizeFor(request.output)
        val aspect = aspectFor(request.output)
        val body = GeminiWire.requestBody(request.instruction, listOf(source) + references, aspect.label, size.label)
            ?: return EditOutcome.Failed(FailureKind.INVALID_REQUEST, false, "UPLOAD_TOO_LARGE", Delivery.NOT_SENT)
        if (context.isCancelled) return EditOutcome.Cancelled
        val allowed = try { journal.beforeSend() } catch (_: Exception) {
            return EditOutcome.Failed(FailureKind.UNKNOWN, false, "JOURNAL_UNAVAILABLE", Delivery.NOT_SENT)
        }
        if (!allowed || context.isCancelled) return EditOutcome.Cancelled
        // The image comes back base64-encoded inside the JSON reply (about 33 MB at 4K), so the
        // reply is spooled to a hidden app-private file and the image decoded from it in a stream.
        val replyFile = results.newScratchFile(jobId)
        try {
            val reply = transport.execute(
                httpConfig,
                EditHttpRequest(EditHttpMethod.POST, "models/${model.id}:generateContent", EditHttpBody.Json(body)),
                EditHttpAuth.CustomHeader(API_KEY_HEADER, key),
                context.cancellation,
                downloadTo = replyFile,
                maxReplyBytes = maxReplyBytes
            )
            return when (reply) {
                EditHttpResult.Cancelled -> EditOutcome.Cancelled
                is EditHttpResult.Failed -> GeminiWire.parseError(reply.errorBody) ?: hostedFailure(reply)
                is EditHttpResult.Success -> interpret(jobId, HostedJsonFile.parseOrNull(replyFile), size, aspect, key, context)
            }
        } finally {
            replyFile.delete()
        }
    }

    private fun interpret(
        jobId: String, root: Any?, size: GeminiSize, aspect: GeminiAspect, key: String, context: HostedWorkContext
    ): EditOutcome {
        return when (val parsed = GeminiWire.parseReply(root)) {
            is GeminiReply.Malformed -> EditOutcome.Failed(FailureKind.UNKNOWN, false, "MALFORMED_REPLY")
            is GeminiReply.Blocked -> EditOutcome.Refused(parsed.code, parsed.description)
            // Gemini answered and finished without an image: a definite answer, not a lost request.
            is GeminiReply.NoImage -> EditOutcome.Failed(FailureKind.UNKNOWN, parsed.retryable, parsed.code, Delivery.ANSWERED)
            is GeminiReply.Image -> {
                val target = results.newDownloadTarget(jobId, 0)
                val decoded = when (val data = parsed.data) {
                    is String -> try {
                        target.writeBytes(Base64.getDecoder().decode(data)); target.length() > 0
                    } catch (_: Exception) { false }
                    is LargeJsonString -> data.decodeBase64To(target, MAX_RESULT_BYTES)
                    else -> false
                }
                if (!decoded || context.isCancelled) {
                    target.delete()
                    return if (context.isCancelled) EditOutcome.Cancelled
                        else EditOutcome.Failed(FailureKind.UNKNOWN, false, "UNREADABLE_IMAGE")
                }
                val stored = results.finalizeFile(target, MAX_RESULT_PIXELS)
                    ?: return EditOutcome.Failed(FailureKind.UNKNOWN, false, "UNREADABLE_IMAGE")
                val metadata = linkedMapOf(
                    "provider" to "gemini",
                    "aspectRatio" to aspect.label,
                    "imageSize" to size.label,
                    "declaredMimeType" to parsed.mimeType,
                    "finishReason" to (parsed.finishReason ?: "")
                )
                parsed.responseId?.let { metadata["responseId"] = it }
                // Reply text is provider-controlled: drop anything that echoes the credential.
                metadata.entries.removeAll { it.value.contains(key) }
                EditOutcome.Success(
                    images = listOf(stored),
                    providerModel = model.id,
                    providerVersion = parsed.modelVersion?.takeUnless { it.contains(key) } ?: model.id,
                    metadata = metadata
                )
            }
        }
    }

    internal fun sizeFor(output: OutputSpec): GeminiSize {
        val pixels = output.width.toLong() * output.height
        return model.sizes.firstOrNull { pixels <= it.maxPixels } ?: model.sizes.last()
    }

    internal fun aspectFor(output: OutputSpec): GeminiAspect {
        val wanted = ln(output.width.toDouble() / output.height)
        return model.aspectRatios.minBy { abs(ln(it.ratio) - wanted) }
    }

    override fun toString(): String = "GeminiImageEditProvider(${model.id})"

    companion object {
        const val API_HOST = "generativelanguage.googleapis.com"
        const val API_BASE = "https://$API_HOST/v1beta/"
        const val API_KEY_HEADER = "x-goog-api-key"
        /** Documented inline-request ceiling is 20 MB; the transport enforces the same ceiling. */
        const val MAX_INPUT_PIXELS = 4096L * 4096L
        private const val MAX_RESULT_PIXELS = 8192L * 8192L
        private const val MAX_RESULT_BYTES = 32_000_000L
        private val MODEL_ID = Regex("[a-z0-9][a-z0-9.-]{0,63}")
        private val DIRECT = Executor { it.run() }

        fun defaultHttpConfig() = EditHttpConfig(
            baseUrl = API_BASE,
            allowedHosts = setOf(API_HOST),
            connectTimeoutMs = 15_000,
            // Thinking image models can take a minute or more before the first reply byte.
            readTimeoutMs = 120_000,
            overallTimeoutMs = 180_000L,
            maxAttempts = 1
        )
    }
}

/** One output size tier: `imageSize` label, the largest pixel count it yields, list price. */
data class GeminiSize(val label: String, val maxPixels: Long, val price: CostEstimate?)

data class GeminiAspect(val label: String, val ratio: Double)

/**
 * The image models documented for the Gemini API on 2026-11-18. [maxInputImages] counts the
 * source plus references. Prices are list prices per output image from the Gemini pricing page
 * (PROVIDERS.md [G2], snapshot 2026-11-15) rounded up to whole US cents; null when unpublished.
 */
data class GeminiModel(
    val id: String,
    val displayName: String,
    val maxInputImages: Int,
    val sizes: List<GeminiSize>,
    val aspectRatios: List<GeminiAspect>
) {
    init {
        require(maxInputImages >= 1 && sizes.isNotEmpty() && aspectRatios.isNotEmpty())
        require(sizes.zipWithNext().all { (a, b) -> a.maxPixels < b.maxPixels })
    }

    companion object {
        private fun aspects(vararg labels: String) = labels.map { label ->
            val (w, h) = label.split(':').map { it.toDouble() }
            GeminiAspect(label, w / h)
        }
        private val COMMON = aspects("1:1", "2:3", "3:2", "3:4", "4:3", "4:5", "5:4", "9:16", "16:9", "21:9")
        private val WIDE = COMMON + aspects("1:4", "4:1", "1:8", "8:1")
        /** Gemini list prices are per output image; input is charged on top, so never a total. */
        private val PRICES_OBSERVED: LocalDate = LocalDate.of(2026, 11, 15)
        private fun usd(cents: Long, note: String) = CostEstimate("USD", cents, note,
            scope = CostScope.OUTPUT_ONLY, source = "Gemini API pricing (PROVIDERS.md [G2])", observedOn = PRICES_OBSERVED)
        private const val ONE_K = 1_048_576L
        private const val TWO_K = 4_194_304L
        private const val FOUR_K = 16_777_216L

        /** Nano Banana 2: default. Up to 14 input images, 1K to 4K, wide aspect ratios. */
        val FLASH = GeminiModel(
            id = "gemini-3.1-flash-image",
            displayName = "Nano Banana 2",
            maxInputImages = 14,
            sizes = listOf(
                GeminiSize("1K", ONE_K, usd(7, "List price ~US\$0.067 per 1K image plus input (PROVIDERS.md [G2], 2026-11-15)")),
                GeminiSize("2K", TWO_K, usd(11, "List price ~US\$0.101 per 2K image plus input (PROVIDERS.md [G2], 2026-11-15)")),
                GeminiSize("4K", FOUR_K, usd(16, "List price ~US\$0.151 per 4K image plus input (PROVIDERS.md [G2], 2026-11-15)"))
            ),
            aspectRatios = WIDE
        )

        /**
         * Nano Banana Pro: thinking model for the hardest edits. Stable (GA) id since
         * 2026-05-28; the old `gemini-3-pro-image-preview` was shut down 2026-06-25
         * (ai.google.dev/gemini-api/docs/deprecations, read 2026-11-19, #463).
         * Pro documents only the ten common aspect ratios: 1:4, 4:1, 1:8, 8:1 are Flash-only.
         */
        val PRO = GeminiModel(
            id = "gemini-3-pro-image",
            displayName = "Nano Banana Pro",
            maxInputImages = 14,
            sizes = listOf(
                GeminiSize("1K", ONE_K, usd(14, "List price ~US\$0.134 per 1K image plus input (PROVIDERS.md [G2], 2026-11-15)")),
                GeminiSize("2K", TWO_K, usd(14, "List price ~US\$0.134 per 2K image plus input (PROVIDERS.md [G2], 2026-11-15)")),
                GeminiSize("4K", FOUR_K, usd(25, "List price ~US\$0.24 per 4K image plus input (PROVIDERS.md [G2], 2026-11-15)"))
            ),
            aspectRatios = COMMON
        )

        /** Nano Banana 2 Lite: fastest, up to 3 input images, 1K only. Price not in PROVIDERS.md. */
        val LITE = GeminiModel(
            id = "gemini-3.1-flash-lite-image",
            displayName = "Nano Banana 2 Lite",
            maxInputImages = 3,
            sizes = listOf(GeminiSize("1K", ONE_K, null)),
            aspectRatios = COMMON
        )

        val DEFAULT = FLASH
        val CATALOG = listOf(FLASH, PRO, LITE)
    }
}

internal sealed interface GeminiReply {
    data object Malformed : GeminiReply
    data class Blocked(val code: String, val description: String) : GeminiReply
    data class NoImage(val code: String, val retryable: Boolean) : GeminiReply
    /** [data] is the base64 text: a String, or a [LargeJsonString] left in the reply file. */
    class Image(
        val data: Any,
        val mimeType: String,
        val finishReason: String?,
        val modelVersion: String?,
        val responseId: String?
    ) : GeminiReply
}

/** Request encoding and reply interpretation, separated so tests can pin the wire format. */
internal object GeminiWire {
    /** Upload ceiling for the JSON body (base64 grows bytes by 4/3). */
    private const val MAX_BODY_BYTES = 20_000_000

    /** Prompt-level block reasons (`promptFeedback.blockReason`). All are provider refusals. */
    private val PROMPT_BLOCKS = setOf(
        "SAFETY", "OTHER", "BLOCKLIST", "PROHIBITED_CONTENT", "IMAGE_SAFETY", "MODEL_ARMOR", "JAILBREAK"
    )

    /** Finish reasons that mean the provider declined on policy grounds. */
    private val POLICY_FINISH = setOf(
        "SAFETY", "IMAGE_SAFETY", "PROHIBITED_CONTENT", "IMAGE_PROHIBITED_CONTENT", "BLOCKLIST",
        "SPII", "RECITATION", "IMAGE_RECITATION", "ESCALATION", "PUP_LIMITED_DISABLED", "MODEL_ARMOR"
    )

    /**
     * Google API errors use the documented google.rpc.Status envelope. Only a structured
     * ErrorInfo `reason` that is also a documented Gemini policy reason is a refusal; messages
     * are untrusted prose and are never copied into state.
     */
    fun parseError(bytes: ByteArray): EditOutcome.Refused? {
        if (bytes.isEmpty()) return null
        val error = HostedJson.parseOrNull(bytes).obj()?.get("error").obj() ?: return null
        val reason = error["details"].arr().orEmpty().mapNotNull { detail ->
            detail.obj()?.get("reason").str()
        }.firstOrNull { it in POLICY_FINISH || it in PROMPT_BLOCKS } ?: return null
        return EditOutcome.Refused("error:$reason", "Gemini declined the request ($reason).")
    }

    /** Every finish reason documented on 2026-11-18; anything else is reported as UNRECOGNIZED. */
    private val KNOWN_FINISH = POLICY_FINISH + setOf(
        "FINISH_REASON_UNSPECIFIED", "STOP", "MAX_TOKENS", "LANGUAGE", "OTHER", "MALFORMED_FUNCTION_CALL",
        "IMAGE_OTHER", "NO_IMAGE", "UNEXPECTED_TOOL_CALL", "TOO_MANY_TOOL_CALLS", "MISSING_THOUGHT_SIGNATURE",
        "MALFORMED_RESPONSE", "CONTINUATION"
    )

    /** Finish reasons with no image where trying again may succeed. */
    private val RETRYABLE_NO_IMAGE = setOf("NO_IMAGE", "IMAGE_OTHER", "OTHER", "MALFORMED_RESPONSE", "STOP")

    fun requestBody(instruction: String, images: List<EncodedImage>, aspect: String, size: String): ByteArray? {
        val encodedSize = images.sumOf { (it.bytes.size.toLong() + 2) / 3 * 4 }
        if (encodedSize + instruction.length * 6L + 1_024 > MAX_BODY_BYTES) return null
        val out = StringBuilder((encodedSize + instruction.length * 2 + 512).toInt())
        out.append("{\"contents\":[{\"role\":\"user\",\"parts\":[")
        out.append("{\"text\":").append(HostedJson.quote(instruction)).append('}')
        val encoder = Base64.getEncoder()
        for (image in images) {
            out.append(",{\"inlineData\":{\"mimeType\":").append(HostedJson.quote(image.mimeType))
            out.append(",\"data\":\"").append(encoder.encodeToString(image.bytes)).append("\"}}")
        }
        out.append("]}],\"generationConfig\":{\"responseModalities\":[\"IMAGE\"],")
        out.append("\"imageConfig\":{\"aspectRatio\":").append(HostedJson.quote(aspect))
        out.append(",\"imageSize\":").append(HostedJson.quote(size)).append("}}}")
        val bytes = out.toString().toByteArray(Charsets.UTF_8)
        return if (bytes.size > MAX_BODY_BYTES) null else bytes
    }

    fun parseReply(parsed: Any?): GeminiReply {
        val root = parsed.obj() ?: return GeminiReply.Malformed
        val blockReason = root["promptFeedback"].obj()?.get("blockReason").str()
        if (blockReason != null && blockReason != "BLOCKED_REASON_UNSPECIFIED") {
            val code = if (blockReason in PROMPT_BLOCKS) blockReason else "UNRECOGNIZED"
            return GeminiReply.Blocked("prompt:$code", "Gemini declined the request ($code).")
        }
        val candidates = root["candidates"].arr() ?: return GeminiReply.NoImage("NO_CANDIDATES", false)
        val first = candidates.firstOrNull().obj() ?: return GeminiReply.NoImage("NO_CANDIDATES", false)
        val finish = first["finishReason"].str()?.let { if (it in KNOWN_FINISH) it else "UNRECOGNIZED" }
        val parts = first["content"].obj()?.get("parts").arr().orEmpty()
        // Thinking models may emit interim "thought" images; only the final, non-thought image counts.
        val imagePart = parts.mapNotNull { it.obj() }
            .filter { it["thought"].bool() != true }
            .mapNotNull { (it["inlineData"] ?: it["inline_data"]).obj() }
            .lastOrNull { (it["mimeType"] ?: it["mime_type"]).str()?.startsWith("image/") == true }
        if (finish != null && finish in POLICY_FINISH) {
            return GeminiReply.Blocked("finish:$finish", "Gemini declined to return this edit ($finish).")
        }
        if (imagePart == null) {
            val code = "NO_IMAGE" + (finish?.let { ":$it" } ?: "")
            return GeminiReply.NoImage(code, finish != null && finish in RETRYABLE_NO_IMAGE)
        }
        val data = imagePart["data"]?.takeIf { it is String || it is LargeJsonString } ?: return GeminiReply.Malformed
        return GeminiReply.Image(
            data = data,
            mimeType = sanitizeCode((imagePart["mimeType"] ?: imagePart["mime_type"]).str() ?: ""),
            finishReason = finish,
            modelVersion = root["modelVersion"].str()?.let(::sanitizeCode),
            responseId = root["responseId"].str()?.let(::sanitizeCode)
        )
    }

    /** Provider codes go into user-visible state: keep them short and plain. */
    private fun sanitizeCode(value: String): String =
        value.filter { it.isLetterOrDigit() || it in "_-./:" }.take(64)
}
