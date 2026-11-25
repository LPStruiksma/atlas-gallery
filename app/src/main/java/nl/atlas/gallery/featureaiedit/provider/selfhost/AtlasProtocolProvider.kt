package nl.atlas.gallery.featureaiedit.provider.selfhost

import nl.atlas.gallery.featureaiedit.network.*
import nl.atlas.gallery.featureaiedit.provider.*
import nl.atlas.gallery.featureaiedit.provider.hosted.*
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Client for docs/ai-edit/PROTOCOL.md v1.0. No server URL, credential, source handle,
 * prompt or provider response is included in exceptions or user-visible error messages.
 * Always negotiates with the selected server; an offline/unknown version is fail closed.
 * Production callers provide their own selected-image loader and Keystore-backed auth.
 */
class AtlasProtocolProvider(
    private val config: EditHttpConfig,
    private val auth: () -> EditHttpAuth?,
    private val images: SourceImageLoader,
    outputDirectory: File,
    private val transport: EditHttpTransport = EditHttpTransport(),
    private val pollWaitMs: Long = 1_000L,
    private val pollIntervalMs: Long = 500L,
    private val executor: Executor? = null
) : ImageEditProvider {
    override val id = "atlas-protocol"
    override val displayName = "Custom ATLAS server"
    private val results = HostedResultStore(outputDirectory)
    private val serial = AtomicLong()

    init {
        require(SelfHostedWire.secureBase(config.baseUrl, config.allowedHosts))
        require(pollWaitMs in 1..60_000 && pollIntervalMs in 1..10_000)
    }

    override fun capabilities(): ProviderCapabilities {
        val credential = auth() ?: return unavailable
        val reply = transport.execute(config, EditHttpRequest(EditHttpMethod.GET, "capabilities"), credential)
        return if (reply is EditHttpResult.Success && reply.status == 200)
            decodeCapabilities(SelfHostedWire.objectReply(reply.bytes)) ?: unavailable
        else unavailable
    }

    override fun submit(request: ImageEditRequest): EditJob {
        val local = "atlas-" + serial.incrementAndGet()
        val caps = capabilities()
        val admission = ImageEditRequestValidator.validate(request, caps)
        if (admission is Admission.Rejected) return immediate(local,
            EditOutcome.Failed(FailureKind.INVALID_REQUEST, false, admission.reason.name))
        val remote = AtomicReference<String?>()
        return HostedEditJob(local, pollWaitMs, executor,
            work = { ctx -> execute(local, request, (admission as Admission.Accepted).plan, caps, ctx, remote) },
            onCancel = {
                val key = auth()
                val remoteId = remote.get()
                if (key != null && remoteId != null)
                    transport.execute(config, EditHttpRequest(EditHttpMethod.DELETE, "edits/$remoteId"), key)
            }
        )
    }

    private fun execute(local: String, request: ImageEditRequest, plan: EditExecutionPlan,
                        caps: ProviderCapabilities, ctx: HostedWorkContext,
                        remote: AtomicReference<String?>): EditOutcome {
        if (!results.isUsable) return failure(FailureKind.SERVER, false, "NO_RESULT_DIRECTORY")
        val key = auth() ?: return failure(FailureKind.AUTH, false, "CREDENTIAL_MISSING")
        val source = SelfHostedWire.selected(images, request.source, plan.source)
            ?: return failure(FailureKind.INVALID_REQUEST, false, "SOURCE_UNREADABLE")
        val parts = mutableListOf<EditMultipartPart>(
            EditMultipartPart.Binary("image", "source.png".takeIf { source.format == ImageFormat.PNG }
                ?: "source.jpg", source.mimeType, source.bytes),
            EditMultipartPart.Text("instruction", request.instruction)
        )
        if (request.mask != null) {
            val mask = SelfHostedWire.selected(images, request.mask, ResizePlan.Keep)
                ?: return failure(FailureKind.INVALID_REQUEST, false, "MASK_UNREADABLE")
            parts.add(EditMultipartPart.Binary("mask", "mask.png", mask.mimeType, mask.bytes))
        }
        for ((index, reference) in request.references.withIndex()) {
            val encoded = SelfHostedWire.selected(images, reference, plan.references[index])
                ?: return failure(FailureKind.INVALID_REQUEST, false, "REFERENCE_UNREADABLE")
            parts.add(EditMultipartPart.Binary("reference",
                if (encoded.format == ImageFormat.PNG) "ref$index.png" else "ref$index.jpg",
                encoded.mimeType, encoded.bytes))
        }
        if (request.options.isNotEmpty()) {
            val options = linkedMapOf<String, Any?>()
            for ((keyName, option) in request.options) {
                options[keyName.name] = when (option) {
                    is OptionValue.IntegerValue -> option.value
                    is OptionValue.DecimalValue -> option.value
                    is OptionValue.BooleanValue -> option.value
                    is OptionValue.TextValue -> option.value
                }
            }
            parts.add(EditMultipartPart.Text("options", SelfHostedWire.json(options)))
        }
        if (parts.size > 12) return failure(FailureKind.INVALID_REQUEST, false, "TOO_MANY_PARTS")
        // Non-idempotent POST is made exactly once. A lost response cannot safely be retried.
        val submitted = transport.execute(config,
            EditHttpRequest(EditHttpMethod.POST, "edits", EditHttpBody.Multipart(parts)),
            key, ctx.cancellation)
        if (submitted !is EditHttpResult.Success) return httpFailure(submitted)
        if (submitted.status != 202) return failure(FailureKind.SERVER, false, "SUBMIT_STATUS")
        val created = SelfHostedWire.objectReply(submitted.bytes)
            ?: return failure(FailureKind.SERVER, false, "SUBMIT_REPLY")
        val remoteId = (created["id"] as? String)?.takeIf { REMOTE_ID.matches(it) }
            ?: return failure(FailureKind.SERVER, false, "REMOTE_ID")
        remote.set(remoteId)
        val limit = System.nanoTime() + 300_000_000_000L
        while (!ctx.isCancelled && System.nanoTime() < limit) {
            val reply = transport.execute(config,
                EditHttpRequest(EditHttpMethod.GET, "edits/$remoteId"), key, ctx.cancellation)
            if (reply !is EditHttpResult.Success) return httpFailure(reply)
            val snapshot = SelfHostedWire.objectReply(reply.bytes)
                ?: return failure(FailureKind.SERVER, false, "STATUS_REPLY")
            when (snapshot["status"]) {
                "queued", "running" -> {
                    val progress = SelfHostedWire.decimal(snapshot["progress"])
                    ctx.reportProgress(progress?.takeIf { it in 0.0..100.0 }?.div(100.0))
                    if (!ctx.pause(pollIntervalMs)) return EditOutcome.Cancelled
                }
                "succeeded" -> {
                    val target = results.newDownloadTarget(local, 0)
                    val download = transport.execute(config, EditHttpRequest(EditHttpMethod.GET,
                        "edits/$remoteId/result"), key, ctx.cancellation, downloadTo = target)
                    if (download !is EditHttpResult.Success) {
                        target.delete()
                        return httpFailure(download)
                    }
                    val image = results.finalizeFile(target, caps.maxOutputPixels)
                        ?: return failure(FailureKind.SERVER, false, "INVALID_RESULT_IMAGE")
                    if (!SelfHostedWire.validResult(
                            ImageProbe.Probe(image.format, image.width, image.height),
                            request.output.format, request.output.width, request.output.height,
                            caps.maxOutputPixels)) {
                        File(image.handle).delete()
                        return failure(FailureKind.SERVER, false, "RESULT_DIMENSIONS")
                    }
                    if (ctx.isCancelled) {
                        File(image.handle).delete()
                        return EditOutcome.Cancelled
                    }
                    return EditOutcome.Success(listOf(image), id, "1.0")
                }
                "refused" -> return EditOutcome.Refused(
                    SelfHostedWire.code(SelfHostedWire.map(snapshot["refusal"])?.get("code")),
                    "PROVIDER_REFUSED")
                "cancelled" -> return EditOutcome.Cancelled
                "error" -> {
                    val details = SelfHostedWire.map(snapshot["error"])
                    val type = when (details?.get("kind")) {
                        "timeout" -> FailureKind.TIMEOUT
                        "auth" -> FailureKind.AUTH
                        "quota" -> FailureKind.QUOTA
                        "invalid_request" -> FailureKind.INVALID_REQUEST
                        else -> FailureKind.SERVER
                    }
                    return failure(type, details?.get("retryable") == true, "REMOTE_ERROR")
                }
                else -> return failure(FailureKind.SERVER, false, "UNRECOGNIZED_STATUS")
            }
        }
        return if (ctx.isCancelled) EditOutcome.Cancelled
        else failure(FailureKind.TIMEOUT, true, "REMOTE_TIMEOUT")
    }

    private fun immediate(id: String, outcome: EditOutcome): EditJob =
        HostedEditJob(id, pollWaitMs, Executor { it.run() }, work = { outcome })

    private fun failure(kind: FailureKind, retryable: Boolean, code: String) =
        EditOutcome.Failed(kind, retryable, code)

    private fun httpFailure(result: EditHttpResult): EditOutcome = when (result) {
        is EditHttpResult.Failed -> hostedFailure(result)
        EditHttpResult.Cancelled -> EditOutcome.Cancelled
        is EditHttpResult.Success -> failure(FailureKind.SERVER, false, "HTTP_STATUS")
    }

    private fun decodeCapabilities(json: Map<String, Any?>?): ProviderCapabilities? {
        if (json == null || SelfHostedWire.int(json["schema_version"], 1, 1) != 1 ||
            json["protocol_version"] != "1.0") return null
        val f = SelfHostedWire.map(json["features"]) ?: return null
        fun flag(name: String) = f[name] as? Boolean
        fun formats(name: String): Set<ImageFormat>? =
            SelfHostedWire.list(f[name])?.map { value ->
                when (value) {
                    "image/png" -> ImageFormat.PNG
                    "image/jpeg" -> ImageFormat.JPEG
                    else -> return null
                }
            }?.toSet()?.takeIf { it.isNotEmpty() }
        val async = flag("async_jobs") ?: return null
        val streaming = flag("streaming") ?: return null
        val maxReferences = SelfHostedWire.int(SelfHostedWire.map(f["reference_images"])?.get("max_count"), 0, 10)
            ?: return null
        val maxCandidates = SelfHostedWire.int(SelfHostedWire.map(f["candidates"])?.get("max_count"), 1, 10)
            ?: return null
        val input = SelfHostedWire.long(f["max_input_pixels"], 1, 100_000_000) ?: return null
        val output = SelfHostedWire.long(f["max_output_pixels"], 1, 100_000_000) ?: return null
        val aspect = if (f["aspect_constraints"] == null) null else {
            val obj = SelfHostedWire.map(f["aspect_constraints"]) ?: return null
            val min = SelfHostedWire.decimal(obj["min"]) ?: return null
            val max = SelfHostedWire.decimal(obj["max"]) ?: return null
            try { AspectRange(min, max) } catch (_: IllegalArgumentException) { return null }
        }
        val advanced = linkedMapOf<CapabilityKey, OptionSchema>()
        for (raw in SelfHostedWire.list(json["advanced_parameters"]) ?: return null) {
            val p = SelfHostedWire.map(raw) ?: return null
            val name = (p["name"] as? String)?.takeIf { OPTION.matches(it) } ?: return null
            val schema = when (p["type"]) {
                "integer" -> {
                    val min = SelfHostedWire.long(p["minimum"], 0, Long.MAX_VALUE) ?: 0L
                    val max = SelfHostedWire.long(p["maximum"], min, Long.MAX_VALUE) ?: Long.MAX_VALUE
                    OptionSchema.IntegerRange(min, max)
                }
                "number" -> {
                    val min = SelfHostedWire.decimal(p["minimum"]) ?: 0.0
                    val max = SelfHostedWire.decimal(p["maximum"]) ?: 1_000_000.0
                    if (min > max) return null
                    OptionSchema.DecimalRange(min, max)
                }
                "enum" -> {
                    val values = SelfHostedWire.list(p["enum"])?.map {
                        (it as? String)?.takeIf { choice -> choice.length <= 128 } ?: return null
                    }?.toSet()?.takeIf { it.isNotEmpty() } ?: return null
                    OptionSchema.Choices(values)
                }
                "boolean" -> OptionSchema.BooleanFlag
                else -> return null
            }
            if (advanced.put(CapabilityKey(name), schema) != null) return null
        }
        return ProviderCapabilities(
            supportsInstructionEdit = flag("instruction_edit") ?: return null,
            supportsMaskEdit = flag("mask_edit") ?: return null,
            maxReferences = maxReferences, maxCandidates = maxCandidates,
            supportsMultiTurn = flag("multi_turn") ?: return null,
            supportsSeed = flag("seed") ?: return null,
            maxInputPixels = input, maxOutputPixels = output,
            aspectRange = aspect, inputFormats = formats("input_formats") ?: return null,
            outputFormats = formats("output_formats") ?: return null,
            supportsAsync = async, supportsStreaming = streaming, advancedParameters = advanced
        ).takeIf { it.supportsAsync && !it.supportsStreaming && it.supportsInstructionEdit }
    }

    private companion object {
        val REMOTE_ID = Regex("[0-9a-f]{32}")
        val OPTION = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")
        val unavailable = ProviderCapabilities(
            false, false, 0, 1, false, false, 1, 1, null,
            setOf(ImageFormat.PNG), setOf(ImageFormat.PNG), false, false
        )
    }
}
