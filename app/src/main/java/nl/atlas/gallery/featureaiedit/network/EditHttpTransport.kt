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

/** No Android dependency: tested against JDK's local HttpServer through an injected fake connector.
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

enum class EditHttpMethod { GET, POST, DELETE }

sealed interface EditHttpAuth {
    data object None : EditHttpAuth
    data class Bearer(val secret: String) : EditHttpAuth {
        override fun toString(): String = "Bearer([REDACTED])"
    }
    data class CustomHeader(val headerName: String, val secret: String) : EditHttpAuth {
        override fun toString(): String = "CustomHeader([REDACTED])"
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

enum class FailureCode { INVALID_REQUEST, NETWORK, TIMEOUT, AUTH, QUOTA, SERVER }

sealed interface EditHttpResult {
    data class Success(val status: Int, val bytes: ByteArray = byteArrayOf(), val file: File? = null) : EditHttpResult
    data class Failed(val code: FailureCode, val retryable: Boolean) : EditHttpResult
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
     */
    fun execute(
        config: EditHttpConfig,
        request: EditHttpRequest,
        auth: EditHttpAuth = EditHttpAuth.None,
        cancellation: EditRequestCancellation = EditRequestCancellation(),
        downloadTo: File? = null
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
        }
        if ((request.method == EditHttpMethod.POST) != (request.body != null) ||
            (downloadTo != null && request.method != EditHttpMethod.GET))
            return EditHttpResult.Failed(FailureCode.INVALID_REQUEST, false)
        val payload = if (request.body == null) null else buildBody(request.body)
            ?: return EditHttpResult.Failed(FailureCode.INVALID_REQUEST, false)
        val deadline = clockMs() + config.overallTimeoutMs
        var attempt = 0
        while (true) {
            if (cancellation.isCancelled) return EditHttpResult.Cancelled
            if (clockMs() >= deadline) return EditHttpResult.Failed(FailureCode.TIMEOUT, true)
            attempt++
            val result = once(url, request.method, payload, header, config,
                deadline, cancellation, downloadTo)
            if (cancellation.isCancelled) return EditHttpResult.Cancelled
            if (result !is EditHttpResult.Failed || !result.retryable ||
                request.method == EditHttpMethod.POST || attempt >= config.maxAttempts) return result
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
        cancel: EditRequestCancellation, destination: File?
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
                return when (status) {
                    401, 403 -> EditHttpResult.Failed(FailureCode.AUTH, false)
                    408 -> EditHttpResult.Failed(FailureCode.TIMEOUT, true)
                    429 -> EditHttpResult.Failed(FailureCode.QUOTA, false)
                    400, 404, 405, 411, 413, 415, 422 -> EditHttpResult.Failed(FailureCode.INVALID_REQUEST, false)
                    in 500..599 -> EditHttpResult.Failed(FailureCode.SERVER, true)
                    else -> EditHttpResult.Failed(FailureCode.SERVER, false) // includes ALL redirects
                }
            }
            val stream = connection.inputStream
            if (destination != null) {
                if (destination.exists() || destination.parentFile?.isDirectory != true)
                    return EditHttpResult.Failed(FailureCode.INVALID_REQUEST, false)
                temp = File.createTempFile(".atlas-edit-", ".part", destination.parentFile)
                FileOutputStream(temp).use { output ->
                    stream.use { input ->
                        copyBounded(input, output, deadline, cancel)
                    }
                    output.fd.sync()
                }
                if (cancel.isCancelled) return EditHttpResult.Cancelled
                if (!temp.renameTo(destination)) return EditHttpResult.Failed(FailureCode.NETWORK, false)
                temp = null
                return EditHttpResult.Success(status, file = destination)
            }
            val buffer = ByteArrayOutputStream()
            stream.use { copyBounded(it, buffer, deadline, cancel, MAX_JSON) }
            if (cancel.isCancelled) return EditHttpResult.Cancelled
            return EditHttpResult.Success(status, buffer.toByteArray())
        } catch (_: SocketTimeoutException) {
            return if (cancel.isCancelled) EditHttpResult.Cancelled
                else EditHttpResult.Failed(FailureCode.TIMEOUT, true)
        } catch (_: SSLException) {
            return if (cancel.isCancelled) EditHttpResult.Cancelled
                else EditHttpResult.Failed(FailureCode.NETWORK, false)
        } catch (_: IOException) {
            return if (cancel.isCancelled) EditHttpResult.Cancelled
                else EditHttpResult.Failed(FailureCode.NETWORK, true)
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
            if (total > max) throw IOException("response too large")
            output.write(block, 0, count)
        }
    }

    companion object {
        private const val MAX_UPLOAD = 20_000_000
        private const val MAX_JSON = 1_000_000
        private const val MAX_DOWNLOAD = 32_000_000
    }
}
