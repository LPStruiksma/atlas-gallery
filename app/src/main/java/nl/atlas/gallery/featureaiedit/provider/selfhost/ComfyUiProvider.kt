package nl.atlas.gallery.featureaiedit.provider.selfhost

import nl.atlas.gallery.featureaiedit.network.*
import nl.atlas.gallery.featureaiedit.provider.*
import nl.atlas.gallery.featureaiedit.provider.hosted.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection

/**
 * Direct ComfyUI API-workflow adapter. Only a user-selected workflow and images are sent.
 * No arbitrary output URLs, WebSocket, global /interrupt, fallback provider or automatic
 * replay of queue POST. Callers must serve ComfyUI through an authenticated HTTPS ingress.
 *
 * Comfy's /view?filename=... requires a query string that EditHttpTransport intentionally
 * forbids. [downloadView] is a narrowly pinned, GET-only, bounded endpoint for that one
 * Comfy API, not a general transport bypass. Production uses HttpsURLConnection.
 */
class ComfyUiProvider(
    private val config: EditHttpConfig,
    private val auth: () -> EditHttpAuth?,
    private val workflowJson: String,
    private val images: SourceImageLoader,
    outputDirectory: File,
    private val transport: EditHttpTransport = EditHttpTransport(),
    private val viewConnectionFactory: (URL) -> HttpURLConnection = {
        it.openConnection() as HttpsURLConnection
    },
    private val pollWaitMs: Long = 1_000L,
    private val pollIntervalMs: Long = 500L,
    private val executor: Executor? = null,
    private val modelId: String = "configured-workflow"
) : ImageEditProvider {
    override val id: String = "comfyui"
    override val displayName: String = "ComfyUI · " + modelId
    private val results = HostedResultStore(outputDirectory)
    private val serial = AtomicLong()
    private val workflow = SelfHostedWire.objectReply(workflowJson.toByteArray(Charsets.UTF_8))
        ?: throw IllegalArgumentException("API workflow must be a JSON object")
    private val placeholders = mutableSetOf<String>().also { collectPlaceholders(workflow, it) }

    init {
        require(SelfHostedWire.secureBase(config.baseUrl, config.allowedHosts))
        require(workflowJson.toByteArray(Charsets.UTF_8).size in 1..1_000_000)
        require("{{image}}" in placeholders && "{{prompt}}" in placeholders)
        require(placeholders.all { it in setOf("{{image}}", "{{prompt}}", "{{mask}}", "{{seed}}") })
        require(pollWaitMs in 1..60_000 && pollIntervalMs in 1..10_000)
        require(Regex("[A-Za-z0-9._/-]{1,128}").matches(modelId))
    }

    override fun capabilities(): ProviderCapabilities = ProviderCapabilities(
        supportsInstructionEdit = true,
        supportsMaskEdit = "{{mask}}" in placeholders,
        maxReferences = 0, maxCandidates = 1, supportsMultiTurn = false,
        supportsSeed = "{{seed}}" in placeholders,
        maxInputPixels = 16_777_216, maxOutputPixels = 16_777_216,
        aspectRange = null,
        inputFormats = setOf(ImageFormat.PNG, ImageFormat.JPEG),
        outputFormats = setOf(ImageFormat.PNG, ImageFormat.JPEG),
        supportsAsync = true, supportsStreaming = false,
        advancedParameters = emptyMap()
    )

    override fun submit(request: ImageEditRequest): EditJob {
        val local = "comfy-" + serial.incrementAndGet()
        val caps = capabilities()
        val admit = ImageEditRequestValidator.validate(request, caps)
        if (admit is Admission.Rejected)
            return immediate(local, EditOutcome.Failed(FailureKind.INVALID_REQUEST, false, admit.reason.name))
        val remote = AtomicReference<String?>()
        return HostedEditJob(local, pollWaitMs, executor,
            work = { ctx -> run(local, request, (admit as Admission.Accepted).plan, caps, ctx, remote) },
            onCancel = {
                val id = remote.get()
                val key = auth()
                if (id != null && key != null) deleteQueued(id, key)
            })
    }

    private fun run(local: String, request: ImageEditRequest, plan: EditExecutionPlan,
                    caps: ProviderCapabilities, ctx: HostedWorkContext,
                    remote: AtomicReference<String?>): EditOutcome {
        if (!results.isUsable) return failed(FailureKind.SERVER, false, "NO_RESULT_DIRECTORY")
        val key = auth() ?: return failed(FailureKind.AUTH, false, "CREDENTIAL_MISSING")
        val source = SelfHostedWire.selected(images, request.source, plan.source)
            ?: return failed(FailureKind.INVALID_REQUEST, false, "SOURCE_UNREADABLE")
        // Uploaded filenames are chosen by us; the remote returned basename is checked.
        val originalName = upload(source, "source.png", key, ctx) ?: return uploadError(ctx)
        val replacements = mutableMapOf<String, Any?>(
            "{{image}}" to originalName,
            "{{prompt}}" to request.instruction,
            "{{seed}}" to (request.options[CapabilityKey("seed")] as? OptionValue.IntegerValue)?.value ?: 0L
        )
        if (request.mask != null) {
            val mask = SelfHostedWire.selected(images, request.mask, ResizePlan.Keep)
                ?: return failed(FailureKind.INVALID_REQUEST, false, "MASK_UNREADABLE")
            val uploaded = upload(mask, "mask.png", key, ctx) ?: return uploadError(ctx)
            replacements["{{mask}}"] = uploaded
        }
        val filled = bindWorkflow(workflow, replacements)
        if (filled.size != workflow.size) return failed(FailureKind.INVALID_REQUEST, false, "WORKFLOW_INVALID")
        val body = SelfHostedWire.json(mapOf(
            "prompt" to filled,
            "client_id" to UUID.randomUUID().toString().replace("-", "")
        ))
        val queued = transport.execute(config, EditHttpRequest(EditHttpMethod.POST, "prompt",
            EditHttpBody.Json(body.toByteArray(Charsets.UTF_8))), key, ctx.cancellation)
        if (queued !is EditHttpResult.Success) return httpFailure(queued)
        val remoteId = SelfHostedWire.safeId(SelfHostedWire.objectReply(queued.bytes)?.get("prompt_id"))
            ?: return failed(FailureKind.SERVER, false, "PROMPT_REPLY")
        remote.set(remoteId)
        val deadline = System.nanoTime() + 300_000_000_000L
        while (!ctx.isCancelled && System.nanoTime() < deadline) {
            val history = transport.execute(config,
                EditHttpRequest(EditHttpMethod.GET, "history/$remoteId"), key, ctx.cancellation)
            if (history !is EditHttpResult.Success) return httpFailure(history)
            val item = SelfHostedWire.map(SelfHostedWire.objectReply(history.bytes)?.get(remoteId))
            if (item != null) {
                if (item["atlas_status"] == "refused")
                    return EditOutcome.Refused(SelfHostedWire.code(item["atlas_code"]), "PROVIDER_REFUSED")
                val state = SelfHostedWire.map(item["status"])?.get("status_str")
                if (state == "error" || state == "failed")
                    return failed(FailureKind.SERVER, false, "WORKFLOW_ERROR")
                val outputs = SelfHostedWire.map(item["outputs"])
                if (outputs != null) {
                    val imageInfo = findOutput(outputs)
                        ?: return failed(FailureKind.SERVER, false, "NO_WORKFLOW_IMAGE")
                    val bytes = downloadView(imageInfo.first, imageInfo.second, key, ctx.cancellation)
                        ?: return if (ctx.isCancelled) EditOutcome.Cancelled
                        else failed(FailureKind.NETWORK, true, "VIEW_DOWNLOAD_FAILED")
                    val probe = ImageProbe.probe(bytes)
                    if (!SelfHostedWire.validResult(probe, request.output.format,
                            request.output.width, request.output.height, caps.maxOutputPixels))
                        return failed(FailureKind.SERVER, false, "INVALID_RESULT_IMAGE")
                    if (ctx.isCancelled) return EditOutcome.Cancelled
                    val image = results.store(local, 0, bytes, probe!!)
                        ?: return failed(FailureKind.SERVER, false, "RESULT_WRITE_FAILED")
                    if (ctx.isCancelled) {
                        File(image.handle).delete()
                        return EditOutcome.Cancelled
                    }
                    return EditOutcome.Success(listOf(image), modelId, "comfy-api-v1")
                }
            }
            ctx.reportProgress(null) // History has no trustworthy fractional progress.
            if (!ctx.pause(pollIntervalMs)) return EditOutcome.Cancelled
        }
        return if (ctx.isCancelled) EditOutcome.Cancelled
        else failed(FailureKind.TIMEOUT, true, "REMOTE_TIMEOUT")
    }

    private fun upload(image: EncodedImage, name: String, key: EditHttpAuth,
                       ctx: HostedWorkContext): String? {
        val reply = transport.execute(config,
            EditHttpRequest(EditHttpMethod.POST, "upload/image",
                EditHttpBody.Multipart(listOf(EditMultipartPart.Binary(
                    "image", name, image.mimeType, image.bytes
                )))), key, ctx.cancellation)
        if (reply !is EditHttpResult.Success) return null
        return (SelfHostedWire.objectReply(reply.bytes)?.get("name") as? String)
            ?.takeIf { SAFE_FILE.matches(it) && !it.startsWith(".") }
    }

    private fun findOutput(outputs: Map<String, Any?>): Pair<String, String>? {
        for (node in outputs.values) {
            val images = SelfHostedWire.list(SelfHostedWire.map(node)?.get("images")) ?: continue
            for (raw in images) {
                val obj = SelfHostedWire.map(raw) ?: continue
                val file = (obj["filename"] as? String)
                    ?.takeIf { SAFE_FILE.matches(it) && !it.startsWith(".") } ?: continue
                val folder = (obj["subfolder"] as? String) ?: ""
                if (folder.length > 128 || folder.startsWith("/") ||
                    folder.split('/').any { it == ".." || it == "." } ||
                    !Regex("[A-Za-z0-9_/-]*").matches(folder)) continue
                if (obj["type"] != "output") continue
                return file to folder
            }
        }
        return null
    }

    /** One fixed Comfy GET, with query values percent-encoded and the original host pinned. */
    private fun downloadView(filename: String, folder: String, key: EditHttpAuth,
                             cancel: EditRequestCancellation): ByteArray? {
        if (cancel.isCancelled) return null
        val base = URI(config.baseUrl)
        val query = "filename=" + URLEncoder.encode(filename, "UTF-8") +
            "&subfolder=" + URLEncoder.encode(folder, "UTF-8") + "&type=output"
        val target = base.resolve("view?$query")
        if (target.scheme != "https" || target.host != base.host ||
            target.port != base.port || !target.rawPath.startsWith(base.rawPath) ||
            target.rawUserInfo != null || target.rawFragment != null) return null
        val conn = try { viewConnectionFactory(target.toURL()) } catch (_: Exception) { return null }
        try {
            cancel.attach(conn)
            if (cancel.isCancelled) return null
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.connectTimeout = config.connectTimeoutMs
            conn.readTimeout = config.readTimeoutMs
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "image/png, image/jpeg")
            conn.setRequestProperty("Cache-Control", "no-store")
            when (key) {
                EditHttpAuth.None -> Unit
                is EditHttpAuth.Bearer -> {
                    if (!safeSecret(key.secret)) return null
                    conn.setRequestProperty("Authorization", "Bearer " + key.secret)
                }
                is EditHttpAuth.CustomHeader -> {
                    if (!Regex("[A-Za-z][A-Za-z0-9-]{0,63}").matches(key.headerName) ||
                        key.headerName.lowercase() in setOf("authorization", "host", "cookie",
                            "content-length", "content-type", "connection", "proxy-authorization") ||
                        !safeSecret(key.secret)) return null
                    conn.setRequestProperty(key.headerName, key.secret)
                }
                is EditHttpAuth.Scheme -> return null
            }
            if (conn.responseCode != 200 || conn.contentLengthLong > MAX_IMAGE) return null
            val out = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val block = ByteArray(8192)
                while (true) {
                    if (cancel.isCancelled) return null
                    val n = input.read(block)
                    if (n < 0) break
                    if (out.size().toLong() + n > MAX_IMAGE) return null
                    out.write(block, 0, n)
                }
            }
            return if (cancel.isCancelled) null else out.toByteArray()
        } catch (_: Exception) {
            return null
        } finally {
            cancel.detach(conn)
            conn.disconnect()
        }
    }

    private fun deleteQueued(id: String, key: EditHttpAuth) {
        transport.execute(config,
            EditHttpRequest(EditHttpMethod.POST, "queue",
                EditHttpBody.Json(SelfHostedWire.json(mapOf("delete" to listOf(id)))
                    .toByteArray(Charsets.UTF_8))), key)
    }

    private fun uploadError(ctx: HostedWorkContext): EditOutcome =
        if (ctx.isCancelled) EditOutcome.Cancelled
        else failed(FailureKind.NETWORK, true, "UPLOAD_FAILED")

    private fun httpFailure(reply: EditHttpResult): EditOutcome = when (reply) {
        is EditHttpResult.Failed -> hostedFailure(reply)
        EditHttpResult.Cancelled -> EditOutcome.Cancelled
        is EditHttpResult.Success -> failed(FailureKind.SERVER, false, "HTTP_STATUS")
    }

    private fun immediate(id: String, result: EditOutcome): EditJob =
        HostedEditJob(id, pollWaitMs, Executor { it.run() }, work = { result })

    private fun failed(kind: FailureKind, retryable: Boolean, code: String) =
        EditOutcome.Failed(kind, retryable, code)

    companion object {
        private const val MAX_IMAGE = 32_000_000L
        private val SAFE_FILE = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}")
        private fun safeSecret(value: String): Boolean =
            value.isNotEmpty() && value.length <= 8192 &&
                value.none { it == '\r' || it == '\n' || it == '\u0000' }

        private fun collectPlaceholders(value: Any?, set: MutableSet<String>) {
            when (value) {
                is String -> if (value.startsWith("{{") && value.endsWith("}}")) set.add(value)
                is List<*> -> value.forEach { collectPlaceholders(it, set) }
                is Map<*, *> -> value.values.forEach { collectPlaceholders(it, set) }
            }
        }

        /** Substitutes only complete string slots, never arbitrary substrings or JSON source text. */
        fun bindWorkflow(json: String, values: Map<String, Any?>): Map<String, Any?> =
            bindWorkflow(SelfHostedWire.objectReply(json.toByteArray(Charsets.UTF_8))
                ?: throw IllegalArgumentException("Invalid workflow"), values)

        private fun bindWorkflow(original: Map<String, Any?>, values: Map<String, Any?>):
            Map<String, Any?> = original.mapValues { (_, value) -> bind(value, values) }

        private fun bind(value: Any?, replacements: Map<String, Any?>): Any? = when (value) {
            is String -> replacements[value] ?: value
            is List<*> -> value.map { bind(it, replacements) }
            is Map<*, *> -> value.entries.associate { (key, item) ->
                require(key is String)
                key to bind(item, replacements)
            }
            else -> value
        }
    }
}
