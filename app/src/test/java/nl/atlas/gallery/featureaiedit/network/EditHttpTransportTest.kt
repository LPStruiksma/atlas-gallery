package nl.atlas.gallery.featureaiedit.network

import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/** All requests terminate on loopback fake HTTP through the injected connection adapter.
 * The public transport URL and its validation remain HTTPS; no real network API is used. */
class EditHttpTransportTest {
    private data class FakeRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: ByteArray
    )

    private data class FakeResponse(
        val status: Int,
        val headers: Map<String, String> = emptyMap(),
        val body: ByteArray = ByteArray(0),
        val delayMs: Long = 0
    )

    /** Minimal loopback HTTP/1.1 server which uses only the JVM test classpath. */
    private class FakeHttpServer(private val responder: (FakeRequest) -> FakeResponse) : AutoCloseable {
        private val socket = ServerSocket(0, 8, java.net.InetAddress.getLoopbackAddress())
        val port: Int = socket.localPort
        private val thread = Thread({ serve() }, "aie-5-fake-http").apply {
            isDaemon = true
            start()
        }

        private fun serve() {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: java.io.IOException) { return }
                client.use {
                    try {
                        val input = BufferedInputStream(it.getInputStream())
                        val requestLine = readLine(input) ?: return@use
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val line = readLine(input) ?: return@use
                            if (line.isEmpty()) break
                            val separator = line.indexOf(':')
                            if (separator > 0) headers[line.substring(0, separator).lowercase(Locale.US)] =
                                line.substring(separator + 1).trim()
                        }
                        val length = headers["content-length"]?.toIntOrNull() ?: 0
                        val body = ByteArray(length)
                        var offset = 0
                        while (offset < length) {
                            val count = input.read(body, offset, length - offset)
                            if (count < 0) break
                            offset += count
                        }
                        val parts = requestLine.split(' ')
                        val response = responder(FakeRequest(parts[0], parts[1], headers, body))
                        if (response.delayMs > 0) Thread.sleep(response.delayMs)
                        val output = it.getOutputStream()
                        val reason = when (response.status) {
                            200 -> "OK"; 202 -> "Accepted"; 302 -> "Found"; 401 -> "Unauthorized"
                            422 -> "Unprocessable Entity"; 429 -> "Too Many Requests"
                            500 -> "Internal Server Error"; 503 -> "Service Unavailable"
                            else -> "Test Response"
                        }
                        output.write("HTTP/1.1 ${response.status} $reason\r\n".toByteArray())
                        response.headers.forEach { (name, value) ->
                            output.write("$name: $value\r\n".toByteArray())
                        }
                        output.write("Content-Length: ${response.body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        output.write(response.body)
                        output.flush()
                    } catch (_: java.io.IOException) {
                        // Expected when the transport enforces a read timeout or cancellation.
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
            }
        }

        private fun readLine(input: BufferedInputStream): String? {
            val bytes = ArrayList<Byte>()
            while (true) {
                val next = input.read()
                if (next < 0) return if (bytes.isEmpty()) null else bytes.toByteArray().toString(Charsets.US_ASCII)
                if (next == '\n'.code) return bytes.dropLastWhile { it == '\r'.code.toByte() }
                    .toByteArray().toString(Charsets.US_ASCII)
                bytes.add(next.toByte())
            }
        }

        override fun close() {
            socket.close()
            thread.join(1_000)
        }
    }

    private fun withServer(
        responder: (FakeRequest) -> FakeResponse,
        block: (FakeHttpServer) -> Unit
    ) {
        FakeHttpServer(responder).use(block)
    }

    private fun transport(server: FakeHttpServer) = EditHttpTransport(
        connectionFactory = { requested: URL ->
            URL("http://127.0.0.1:${server.port}${requested.file}").openConnection() as HttpURLConnection
        },
        sleeper = { }
    )
    private fun config() = EditHttpConfig("https://editor.example/v1/", setOf("editor.example"),
        connectTimeoutMs = 500, readTimeoutMs = 100, overallTimeoutMs = 1_000)

    @Test fun httpsAndAllowedHostRequiredBeforeConnection() {
        val calls = AtomicInteger()
        withServer({
            calls.incrementAndGet()
            FakeResponse(200)
        }) { server ->
            val transport = transport(server)
            assertTrue(transport.execute(config().copy(baseUrl = "http://editor.example/v1/"),
                EditHttpRequest(EditHttpMethod.GET, "capabilities")) is EditHttpResult.Failed)
            assertTrue(transport.execute(config(), EditHttpRequest(EditHttpMethod.GET,
                "https://evil.example/v1/capabilities")) is EditHttpResult.Failed)
            assertTrue(transport.execute(config(), EditHttpRequest(EditHttpMethod.GET,
                "//evil.example/steal")) is EditHttpResult.Failed)
            assertTrue(transport.execute(config(), EditHttpRequest(EditHttpMethod.GET,
                "../admin")) is EditHttpResult.Failed)
            assertEquals(0, calls.get())
        }
    }

    @Test fun redirectsAreNotFollowedEvenToAllowedHost() {
        val calls = AtomicInteger()
        withServer({
            calls.incrementAndGet()
            FakeResponse(302, mapOf("Location" to "https://evil.example/credentials"))
        }) { server ->
            assertTrue(transport(server).execute(config(), EditHttpRequest(EditHttpMethod.GET,
                "capabilities")) is EditHttpResult.Failed)
            assertEquals(1, calls.get())
        }
    }

    @Test fun retryOnlyIdempotentMethodsAndNeverSwitchProvider() {
        val attempts = AtomicInteger()
        withServer({
            val n = attempts.incrementAndGet()
            if (n == 1) FakeResponse(503) else FakeResponse(200, body = "{}".toByteArray())
        }) { server ->
            assertTrue(transport(server).execute(config(), EditHttpRequest(EditHttpMethod.GET,
                "capabilities")) is EditHttpResult.Success)
            assertEquals(2, attempts.get())
            attempts.set(0)
            val post = EditHttpRequest(EditHttpMethod.POST, "edits", EditHttpBody.Json("{}".toByteArray()))
            assertTrue(transport(server).execute(config(), post) is EditHttpResult.Failed)
            assertEquals(1, attempts.get())
        }
    }

    @Test fun cancellationTimeoutAndRedactedFailures() = withServer({ FakeResponse(200, delayMs = 300) }) { server ->
            val stopped = EditRequestCancellation().apply { cancel() }
            assertEquals(EditHttpResult.Cancelled,
                transport(server).execute(config(), EditHttpRequest(EditHttpMethod.GET, "edits/abc"),
                    cancellation = stopped))
            val result = transport(server).execute(config(),
                EditHttpRequest(EditHttpMethod.GET, "edits/abc"),
                auth = EditHttpAuth.Bearer("MY_TEST_SECRET"))
            assertTrue(result is EditHttpResult.Failed)
            assertEquals(FailureCode.TIMEOUT, (result as EditHttpResult.Failed).code)
            assertFalse(result.toString().contains("MY_TEST_SECRET"))
            assertFalse(result.toString().contains("editor.example"))
    }


    @Test fun authenticationAndInvalidRequestsMapToFixedCodes() = withServer({ request ->
            val status = when (request.path) {
                "/v1/auth" -> 401
                "/v1/quota" -> 429
                "/v1/invalid" -> 422
                else -> 500
            }
            FakeResponse(status)
        }) { server ->
        fun failure(path: String) = transport(server).execute(config(),
            EditHttpRequest(EditHttpMethod.GET, path)) as EditHttpResult.Failed
        assertEquals(FailureCode.AUTH, failure("auth").code)
        assertEquals(FailureCode.QUOTA, failure("quota").code)
        assertEquals(FailureCode.INVALID_REQUEST, failure("invalid").code)
        assertFalse(failure("invalid").retryable)
    }

    @Test fun binaryDownloadWritesOnlyCompletedOutputFile() = withServer({
            FakeResponse(200, body = byteArrayOf(1, 2, 3, 4, 5))
        }) { server ->
        val target = java.io.File.createTempFile("aie-5-", ".bin")
        target.delete()
        try {
            val result = transport(server).execute(config(),
                EditHttpRequest(EditHttpMethod.GET, "edits/abc/result"), downloadTo = target)
            assertTrue(result is EditHttpResult.Success)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), target.readBytes())
        } finally {
            target.delete()
        }
    }

    @Test fun multipartAndAuthAreSentOnlyToChosenHost() {
        val count = AtomicInteger()
        var captured: FakeRequest? = null
        withServer({ request ->
            count.incrementAndGet()
            captured = request
            FakeResponse(202)
        }) { server ->
            val multipart = EditHttpBody.Multipart(listOf(
                EditMultipartPart.Text("instruction", "synthetic instruction"),
                EditMultipartPart.Binary("image", "synthetic.png", "image/png", byteArrayOf(1, 2, 3))
            ))
            assertTrue(transport(server).execute(config(),
                EditHttpRequest(EditHttpMethod.POST, "edits", multipart),
                EditHttpAuth.Bearer("test-only")) is EditHttpResult.Success)
            assertEquals(1, count.get())
            assertEquals("POST", captured?.method)
            assertEquals("Bearer test-only", captured?.headers?.get("authorization"))
            assertTrue(captured?.headers?.get("content-type")?.startsWith("multipart/form-data; boundary=") == true)
            assertTrue(captured?.body?.toString(Charsets.UTF_8)?.contains("synthetic instruction") == true)
        }
    }
}
