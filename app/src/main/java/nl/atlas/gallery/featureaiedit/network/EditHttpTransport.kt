package nl.atlas.gallery.featureaiedit.network

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import kotlin.math.min

/** No Android dependency: tested against a loopback fake server through an injected connector.
 * Only an explicitly selected provider host may receive credentials or media. */
data class EditHttpConfig(
    val baseUrl: String,
    val allowedHosts: Set<String>,
    val connectTimeoutMs: Int = 5_000,
    val readTimeoutMs: Int = 15_000,
    val overallTimeoutMs: Long = 60_000L,
    val maxAttempts: Int = 3
) {
    init {
        require(connectTimeoutMs in 1..120_000 && readTimeoutMs in 1..120_000)
        require(overallTimeoutMs in 1..300_000L && maxAttempts in 1..4)
    }
}

/** PUT is never replayed unless the caller marks that request idempotent (see [EditHttpTransport.execute]). */
enum class EditHttpMethod { GET, POST, DELETE, PUT }

sealed interface EditHttpAuth {
    data object None : EditHttpAuth
    data class Bearer(val secret: String) : EditHttpAuth {
        override fun toString(): String = "Bearer([REDACTED])"
    }
    data class CustomHeader(val headerName: String, val secret: String) : EditHttpAuth {
        override fun toString(): String = "CustomHeader([REDACTED])"
    }
    /**
     * `Authorization: <scheme> <secret>` for providers with their own scheme word, for example
     * fal.ai's `Key`. The scheme is fixed in adapter code (letters only, at most 16), never typed
     * by a user; the secret is validated exactly like [Bearer]'s.
     */
    data class Scheme(val scheme: String, val secret: String) : EditHttpAuth {
        override fun toString(): String = "Scheme([REDACTED])"
    }
}

sealed interface EditMultipartPart {
    data class Text(val name: String, val value: String) : EditMultipartPart
    data class Binary(val name: String, val filename: String, val mimeType: String, val content: ByteArray) : EditMultipartPart
}

sealed interface EditHttpBody {
    data class Json(val utf8: ByteArray) : EditHttpBody
    data class Multipart(val parts: List<EditMultipartPart>) : EditHttpBody
}

data class EditHttpRequest(
    val method: EditHttpMethod,
    val path: String,
    val body: EditHttpBody? = null
)

enum class FailureCode { INVALID_REQUEST, RESPONSE_TOO_LARGE, NETWORK, TIMEOUT, AUTH, QUOTA, SERVER }

sealed interface EditHttpResult {
    data class Success(val status: Int, val bytes: ByteArray = byteArrayOf(), val file: File? = null) : EditHttpResult
    /**
     * A transport failure. [errorBody] is available only so an adapter can recognize a
     * provider's documented, structured refusal. It is always bounded by
     * [EditHttpTransport.MAX_ERROR_REPLY] and deliberately omitted from [toString].
     */
    class Failed(
        val code: FailureCode,
        val retryable: Boolean,
        val status: Int? = null,
        val errorBody: ByteArray = byteArrayOf()
    ) : EditHttpResult {
        // Preserve the pre-body value semantics used by callers and tests; the body is diagnostic
        // input to an adapter, not part of the public failure identity.
        override fun equals(other: Any?): Boolean = other is Failed && code == other.code && retryable == other.retryable
        override fun hashCode(): Int = 31 * code.hashCode() + retryable.hashCode()
        override fun toString(): String = "Failed(code=$code, retryable=$retryable, status=$status, errorBody=[REDACTED])"
    }
    data object Cancelled : EditHttpResult
}

/** Concurrent cancellation closes the active socket; never publishes a partial download. */
class EditRequestCancellation {
    private val cancelled = AtomicBoolean(false)
    @Volatile private var connection: HttpURLConnection? = null
    val isCancelled: Boolean get() = cancelled.get()
    fun cancel() {
        cancelled.set(true)
        connection?.disconnect()
    }
    internal fun attach(active: HttpURLConnection) {
        connection = active
        if (cancelled.get()) active.disconnect()
    }
    internal fun detach(active: HttpURLConnection) {
        if (connection === active) connection = null
    }
}

private class ResponseTooLargeException : IOException()

class EditHttpTransport(
    private val connectionFactory: (URL) -> HttpURLConnection = {
        it.openConnection() as HttpsURLConnection
    },
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) }
) {
    /**
     * Rejections have fixed code-only messages. Never expose server response bodies, exception
     * messages, URLs, tokens or headers in logs, diagnostics, failures, or usage exports.
     * POST is never replayed: the server may have accepted a job before losing the response.
     * PUT is replayed only when [idempotent] is true.
     *
     * Reply size: by default a JSON reply is held in memory up to 1 MB and a [downloadTo] file up
     * to 32 MB. [maxReplyBytes] raises (or lowers) that bound for this one request, up to
     * [MAX_REPLY_CEILING]; a reply above it fails with `SERVER`, never retried. With [downloadTo]
     * the reply (of any method but DELETE) is spooled to that app-private file through an 8 KB
     * buffer, so even a 40 MB reply costs about 8 KB of heap. Without it the reply is buffered in
     * memory (peak about 3x its size while growing and copying), so an in-memory cap may not
     * exceed [MAX_IN_MEMORY_REPLY].
     */
    fun execute(
        config: EditHttpConfig,
        request: EditHttpRequest,
        auth: EditHttpAuth = EditHttpAuth.None,
        cancellation: EditRequestCancellation = EditRequestCancellation(),
        downloadTo: File? = null,
        maxReplyBytes: Int? = null,
        idempotent: Boolean = false
    ): EditHttpResult {
        if (cancellation.isCancelled) return EditHttpResult.Cancelled
        val url = verifiedUrl(config, request.path)
            ?: return EditHttpResult.Failed(FailureCode.INVALID_REQUEST, false)
        val header = when (auth) {
            EditHttpAuth.None -> null
            is EditHttpAuth.Bearer -> {
                if (!safeSecret(auth.secret)) return EditHttpResult.Failed(FailureCode.INVALID_REQUEST, false)
                "Authorization" to ("Bearer " + auth.secret)
            }
            is EditHttpAuth.CustomHeader -> {
                if (!safeHeader(auth.headerName) || !safeSecret(auth.secret))
                    return EditHttpResult.Failed(FailureCode.INVALID_REQUEST, false)
                auth.headerName to auth.secret
            }
            is EditHttpAuth.Scheme -> {
                if (!SCHEME.matches(auth.scheme) || auth.scheme.equals("Basic", ignoreCase = true) ||
                    !safeSecret(auth.secret) || auth.secret.any { it == ' ' })
                    return EditHttpResult.Failed(FailureCode.INVALID_REQUEST, false)
                "Authorization" to (auth.scheme + " " + auth.secret)
            }
        }
        val bodyAllowed = request.method == EditHttpMethod.POST || request.method == EditHttpMethod.PUT
        if ((request.method == EditHttpMethod.POST && request.body == null) ||
            (!bodyAllowed && request.body != null) ||
            (downloadTo != null && request.method == EditHttpMethod.DELETE))
            return EditHttpResult.Failed(FailureCode.INVALID_REQUEST, false)
        if (maxReplyBytes != null && (maxReplyBytes !in 1..MAX_REPLY_CEILING ||
                (downloadTo == null && maxReplyBytes > MAX_IN_MEMORY_REPLY)))
            return EditHttpResult.Failed(FailureCode.INVALID_REQUEST, false)
        val payload = request.body?.let { buildBody(it) }
        if (request.body != null && payload == null)
            return EditHttpResult.Failed(FailureCode.INVALID_REQUEST, false)
        val deadline = clockMs() + config.overallTimeoutMs
        var attempt = 0
        while (true) {
            if (cancellation.isCancelled) return EditHttpResult.Cancelled
            if (clockMs() >= deadline) return EditHttpResult.Failed(FailureCode.TIMEOUT, true)
            attempt++
            val result = once(url, request.method, payload, header, config,
                deadline, cancellation, downloadTo, maxReplyBytes)
            if (cancellation.isCancelled) return EditHttpResult.Cancelled
            if (result !is EditHttpResult.Failed || !result.retryable ||
                request.method == EditHttpMethod.POST ||
                (request.method == EditHttpMethod.PUT && !idempotent) ||
                attempt >= config.maxAttempts) return result
            val wait = min(250L shl (attempt - 1), (deadline - clockMs()).coerceAtLeast(0L))
            if (wait == 0L) return EditHttpResult.Failed(FailureCode.TIMEOUT, true)
            try { sleeper(wait) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return EditHttpResult.Cancelled
            }
        }
    }

    private fun verifiedUrl(config: EditHttpConfig, path: String): URL? = try {
        val base = URI(config.baseUrl)
        val target = base.resolve(path)
        val host = base.host?.lowercase(Locale.ROOT)
        val allowed = config.allowedHosts.map { it.lowercase(Locale.ROOT) }.toSet()
        if (base.scheme != "https" || target.scheme != "https" || host.isNullOrBlank() ||
            host !in allowed || target.host?.lowercase(Locale.ROOT) != host ||
            base.rawUserInfo != null || target.rawUserInfo != null ||
            base.rawFragment != null || target.rawFragment != null ||
            base.rawQuery != null || target.rawQuery != null ||
            base.port != target.port || base.port !in -1..65535 ||
            base.rawPath.isNullOrBlank() || !base.rawPath.endsWith("/") ||
            !target.rawPath.startsWith(base.rawPath) ||
            target.rawPath.contains("..") || target.rawPath.contains("%2e", true) ||
            target.rawPath.contains("%2f", true) ||
            path.isBlank() || path.contains('\\') || target.normalize() != target)
            null else target.toURL()
    } catch (_: Exception) { null }

    private fun safeSecret(secret: String): Boolean =
        secret.isNotEmpty() && secret.length <= 8192 && secret.none { it == '\r' || it == '\n' || it == '\u0000' }

    private fun safeHeader(name: String): Boolean {
        val valid = Regex("[A-Za-z][A-Za-z0-9-]{0,63}")
        return valid.matches(name) && name.lowercase(Locale.ROOT) !in
            setOf("host", "cookie", "set-cookie", "content-type", "content-length", "authorization",
                "connection", "transfer-encoding", "proxy-authorization")
    }

    private data class Payload(val bytes: ByteArray, val type: String)
    private fun buildBody(body: EditHttpBody): Payload? {
        return try {
        when (body) {
            is EditHttpBody.Json -> if (body.utf8.size > MAX_UPLOAD) null
                else Payload(body.utf8, "application/json; charset=utf-8")
            is EditHttpBody.Multipart -> {
                if (body.parts.isEmpty() || body.parts.size > 12) return null
                val boundary = "atlas-" + UUID.randomUUID().toString().replace("-", "")
                val stream = ByteArrayOutputStream()
                val token = Regex("[A-Za-z0-9_-]{1,64}")
                val fileName = Regex("[A-Za-z0-9_.-]{1,128}")
                for (part in body.parts) {
                    val name = when (part) {
                        is EditMultipartPart.Text -> part.name
                        is EditMultipartPart.Binary -> part.name
                    }
                    if (!token.matches(name)) return null
                    stream.write(("--" + boundary + "\r\n").toByteArray(Charsets.US_ASCII))
                    when (part) {
                        is EditMultipartPart.Text -> {
                            stream.write(("Content-Disposition: form-data; name=\"" + name +
                                "\"\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n").toByteArray(Charsets.US_ASCII))
                            stream.write(part.value.toByteArray(Charsets.UTF_8))
                        }
                        is EditMultipartPart.Binary -> {
                            if (!fileName.matches(part.filename) ||
                                part.mimeType !in setOf("image/png", "image/jpeg")) return null
                            stream.write(("Content-Disposition: form-data; name=\"" + name +
                                "\"; filename=\"" + part.filename + "\"\r\nContent-Type: " +
                                part.mimeType + "\r\n\r\n").toByteArray(Charsets.US_ASCII))
                            stream.write(part.content)
                        }
                    }
                    stream.write("\r\n".toByteArray(Charsets.US_ASCII))
                    if (stream.size() > MAX_UPLOAD) return null
                }
                stream.write(("--" + boundary + "--\r\n").toByteArray(Charsets.US_ASCII))
                if (stream.size() > MAX_UPLOAD) null
                else Payload(stream.toByteArray(), "multipart/form-data; boundary=" + boundary)
            }
        }
    } catch (_: Exception) { null }
    }

    private fun once(
        url: URL, method: EditHttpMethod, payload: Payload?,
        header: Pair<String, String>?, config: EditHttpConfig, deadline: Long,
        cancel: EditRequestCancellation, destination: File?,
        replyCap: Int? = null
    ): EditHttpResult {
        var connection: HttpURLConnection? = null
        var temp: File? = null
        try {
            val remaining = (deadline - clockMs()).coerceAtLeast(0L)
            if (remaining == 0L) return EditHttpResult.Failed(FailureCode.TIMEOUT, true)
            connection = connectionFactory(url)
            cancel.attach(connection)
            if (cancel.isCancelled) return EditHttpResult.Cancelled
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = min(config.connectTimeoutMs.toLong(), remaining).toInt().coerceAtLeast(1)
            connection.readTimeout = min(config.readTimeoutMs.toLong(), remaining).toInt().coerceAtLeast(1)
            connection.requestMethod = method.name
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-store")
            if (header != null) connection.setRequestProperty(header.first, header.second)
            if (payload != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", payload.type)
                connection.setFixedLengthStreamingMode(payload.bytes.size)
                connection.outputStream.use { it.write(payload.bytes) }
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                val body = if (status in 400..599) readErrorBody(connection, deadline, cancel)
                    else byteArrayOf()
                val mapped = when (status) {
                    401, 403 -> FailureCode.AUTH to false
                    408 -> FailureCode.TIMEOUT to true
                    429 -> FailureCode.QUOTA to false
                    400, 404, 405, 411, 413, 415, 422 -> FailureCode.INVALID_REQUEST to false
                    in 500..599 -> FailureCode.SERVER to true
                    else -> FailureCode.SERVER to false // includes ALL redirects
                }
                return EditHttpResult.Failed(mapped.first, mapped.second, status, body)
            }
            val stream = connection.inputStream
            if (replyCap != null && connection.contentLengthLong > replyCap) throw ReplyLimitExceeded()
            if (destination != null) {
                if (destination.exists() || destination.parentFile?.isDirectory != true)
                    return EditHttpResult.Failed(FailureCode.INVALID_REQUEST, false)
                temp = File.createTempFile(".atlas-edit-", ".part", destination.parentFile)
                FileOutputStream(temp).use { output ->
                    stream.use { input ->
                        if (replyCap == null) copyBounded(input, output, deadline, cancel)
                        else copyBounded(input, CappedOutput(output, replyCap), deadline, cancel, replyCap + BLOCK)
                    }
                    output.fd.sync()
                }
                if (cancel.isCancelled) return EditHttpResult.Cancelled
                if (!temp.renameTo(destination)) return EditHttpResult.Failed(FailureCode.NETWORK, false)
                temp = null
                return EditHttpResult.Success(status, file = destination)
            }
            val buffer = ByteArrayOutputStream()
            stream.use {
                if (replyCap == null) copyBounded(it, buffer, deadline, cancel, MAX_JSON)
                else copyBounded(it, CappedOutput(buffer, replyCap), deadline, cancel, replyCap + BLOCK)
            }
            if (cancel.isCancelled) return EditHttpResult.Cancelled
            return EditHttpResult.Success(status, buffer.toByteArray())
        } catch (_: ResponseTooLargeException) {
            return if (cancel.isCancelled) EditHttpResult.Cancelled
                else EditHttpResult.Failed(FailureCode.RESPONSE_TOO_LARGE, false)
        } catch (_: SocketTimeoutException) {
            return if (cancel.isCancelled) EditHttpResult.Cancelled
                else EditHttpResult.Failed(FailureCode.TIMEOUT, true)
        } catch (_: SSLException) {
            return if (cancel.isCancelled) EditHttpResult.Cancelled
                else EditHttpResult.Failed(FailureCode.NETWORK, false)
        } catch (_: IOException) {
            return if (cancel.isCancelled) EditHttpResult.Cancelled
                else EditHttpResult.Failed(FailureCode.NETWORK, true)
        } catch (_: ReplyLimitExceeded) {
            // Over a caller-chosen cap: same code as the default caps, and the same request would get the same reply, so no retry.
            return if (cancel.isCancelled) EditHttpResult.Cancelled
                else EditHttpResult.Failed(FailureCode.RESPONSE_TOO_LARGE, false)
        } catch (_: Exception) {
            return if (cancel.isCancelled) EditHttpResult.Cancelled
                else EditHttpResult.Failed(FailureCode.INVALID_REQUEST, false)
        } finally {
            temp?.delete()
            connection?.let {
                cancel.detach(it)
                it.disconnect()
            }
        }
    }

    private fun readErrorBody(
        connection: HttpURLConnection,
        deadline: Long,
        cancel: EditRequestCancellation
    ): ByteArray {
        if (connection.contentLengthLong > MAX_ERROR_REPLY) return byteArrayOf()
        val input = connection.errorStream ?: return byteArrayOf()
        val output = ByteArrayOutputStream(minOf(connection.contentLength.coerceAtLeast(0), MAX_ERROR_REPLY))
        val block = ByteArray(8192)
        return try {
            input.use {
                while (output.size() < MAX_ERROR_REPLY) {
                    if (cancel.isCancelled || clockMs() >= deadline) return byteArrayOf()
                    val count = it.read(block, 0, minOf(block.size, MAX_ERROR_REPLY - output.size()))
                    if (count < 0) return output.toByteArray()
                    output.write(block, 0, count)
                }
                // Exactly 64 KiB is allowed. One additional byte means the body is not retained.
                if (it.read() < 0) output.toByteArray() else byteArrayOf()
            }
        } catch (_: IOException) { byteArrayOf() }
    }

    private fun copyBounded(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        deadline: Long,
        cancel: EditRequestCancellation,
        max: Int = MAX_DOWNLOAD
    ) {
        val block = ByteArray(8192)
        var total = 0L
        while (true) {
            if (cancel.isCancelled) throw IOException("cancelled")
            if (clockMs() >= deadline) throw SocketTimeoutException()
            val count = input.read(block)
            if (count < 0) return
            total += count
            if (total > max) throw ResponseTooLargeException()
            output.write(block, 0, count)
        }
    }

    /** Counts bytes on their way to [target] and stops at [cap]; copyBounded's own cap sits one block higher. */
    private class CappedOutput(private val target: java.io.OutputStream, private val cap: Int) : java.io.OutputStream() {
        private var written = 0L
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            written += len
            if (written > cap) throw ReplyLimitExceeded()
            target.write(b, off, len)
        }
        override fun flush() = target.flush()
    }

    /** Not an IOException on purpose: it must not take the retryable network-failure path. */
    private class ReplyLimitExceeded : RuntimeException()

    companion object {
        const val MAX_ERROR_REPLY = 64 * 1024
        /**
         * Largest reply a caller may allow for one request. The largest documented hosted output
         * is a 4096 x 4096 image (Gemini `imageSize` 4K): about 25 MB as a photographic PNG, about
         * 33 MB once base64-encoded inside a JSON reply, so 40 MB leaves headroom for the envelope.
         */
        const val MAX_REPLY_CEILING = 40_000_000
        /** Larger raised caps must spool to a file: 8 MB in memory peaks near 24 MB of heap. */
        const val MAX_IN_MEMORY_REPLY = 8_000_000
        private const val BLOCK = 8192
        private val SCHEME = Regex("[A-Za-z]{1,16}")
        private const val MAX_UPLOAD = 20_000_000
        private const val MAX_JSON = 1_000_000
        private const val MAX_DOWNLOAD = 32_000_000
    }
}
