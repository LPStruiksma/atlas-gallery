package nl.atlas.gallery.featureaiedit.network

import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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


    @Test fun workerStopDisconnectsARequestBlockedOnTheServerReply() {
        val requestArrived = CountDownLatch(1)
        withServer({ requestArrived.countDown(); FakeResponse(200, delayMs = 4_000) }) { server ->
            val slow = EditHttpConfig("https://editor.example/v1/", setOf("editor.example"),
                connectTimeoutMs = 500, readTimeoutMs = 8_000, overallTimeoutMs = 10_000)
            var result: EditHttpResult? = null
            var elapsedMs = 0L
            runBlocking {
                val worker = launch(Dispatchers.IO) {
                    withContext(Dispatchers.IO) {
                        val cancellation = EditRequestCancellation()
                        val watcher = WorkerStopWatcher(this) { cancellation.cancel() }
                        try {
                            val start = System.nanoTime()
                            result = transport(server).execute(slow,
                                EditHttpRequest(EditHttpMethod.GET, "edits/abc"), cancellation = cancellation)
                            elapsedMs = (System.nanoTime() - start) / 1_000_000L
                        } finally { watcher.release() }
                    }
                }
                assertTrue(requestArrived.await(5, TimeUnit.SECONDS))
                worker.cancel()
                worker.join()
            }
            // The read is blocked for 4 s on the server; the stop must disconnect it well before that.
            assertEquals(EditHttpResult.Cancelled, result)
            assertTrue("stop took ${elapsedMs} ms", elapsedMs < 2_000)
        }
    }

    @Test fun authenticationAndInvalidRequestsMapToFixedCodes() = withServer({ request ->
            val status = when (request.path) {
                "/v1/auth" -> 401
                "/v1/forbidden" -> 403
                "/v1/quota" -> 429
                "/v1/bad" -> 400
                "/v1/invalid" -> 422
                else -> 500
            }
            FakeResponse(status)
        }) { server ->
        fun failure(path: String) = transport(server).execute(config(),
            EditHttpRequest(EditHttpMethod.GET, path)) as EditHttpResult.Failed
        assertEquals(FailureCode.AUTH, failure("auth").code)
        assertEquals(FailureCode.AUTH, failure("forbidden").code)
        assertEquals(FailureCode.QUOTA, failure("quota").code)
        assertEquals(FailureCode.INVALID_REQUEST, failure("bad").code)
        assertEquals(FailureCode.INVALID_REQUEST, failure("invalid").code)
        assertFalse(failure("invalid").retryable)
    }

    @Test fun paymentRequiredIsBillingNonretryableAndDoesNotReplayPostsOrLeakBody() {
        val calls = AtomicInteger()
        val privateReply = "private-billing-account-and-key"
        withServer({
            calls.incrementAndGet()
            FakeResponse(402, body = privateReply.toByteArray())
        }) { server ->
            val http = transport(server)
            val get = http.execute(config(), EditHttpRequest(EditHttpMethod.GET, "balance")) as EditHttpResult.Failed
            assertEquals(FailureCode.BILLING, get.code)
            assertEquals(402, get.status)
            assertFalse(get.retryable)
            assertEquals("no GET retry on depleted credits", 1, calls.get())
            assertTrue("diagnostic input remains bounded", get.errorBody.size <= EditHttpTransport.MAX_ERROR_REPLY)
            assertFalse(get.toString().contains(privateReply))
            assertTrue(get.toString().contains("[REDACTED]"))

            val post = http.execute(config(),
                EditHttpRequest(EditHttpMethod.POST, "edits", EditHttpBody.Json("{}".toByteArray()))) as EditHttpResult.Failed
            assertEquals(FailureCode.BILLING, post.code)
            assertFalse(post.retryable)
            assertEquals("no paid POST replay", 2, calls.get())
            assertFalse(post.toString().contains(privateReply))
            assertFalse(post.toString().contains("editor.example"))
        }
    }

    @Test fun errorRepliesAreCappedAndNeverRenderedByToString() {
        val secret = "provider-private-error-text"
        val cases = listOf(
            (secret.repeat(10).toByteArray()) to true,
            (ByteArray(EditHttpTransport.MAX_ERROR_REPLY) { 'a'.code.toByte() }) to true,
            (ByteArray(EditHttpTransport.MAX_ERROR_REPLY + 1) { 'b'.code.toByte() }) to false
        )
        for ((body, retained) in cases) withServer({ FakeResponse(422, body = body) }) { server ->
            val result = transport(server).execute(config(),
                EditHttpRequest(EditHttpMethod.GET, "invalid")) as EditHttpResult.Failed
            assertEquals(retained, result.errorBody.contentEquals(body))
            assertTrue(result.errorBody.size <= EditHttpTransport.MAX_ERROR_REPLY)
            assertFalse(result.toString().contains(secret))
            assertTrue(result.toString().contains("[REDACTED]"))
        }
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


    @Test fun oversizedJsonIsRejectedWithoutRetry() {
        val attempts = AtomicInteger()
        withServer({
            attempts.incrementAndGet()
            FakeResponse(200, body = ByteArray(1_000_001) { 'x'.code.toByte() })
        }) { server ->
            val result = transport(server).execute(config(), EditHttpRequest(EditHttpMethod.GET, "capabilities"))
            assertEquals(EditHttpResult.Failed(FailureCode.RESPONSE_TOO_LARGE, false), result)
            assertEquals(1, attempts.get())
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

    // #441: hosted services (large JSON replies, provider auth scheme, PUT).

    private fun bigJson(size: Int): ByteArray {
        val body = ByteArray(size) { 'A'.code.toByte() }
        "{\"d\":\"".toByteArray().copyInto(body)
        "\"}".toByteArray().copyInto(body, size - 2)
        return body
    }

    @Test fun largeReplyNeedsAnExplicitCapAndSpoolsToFile() {
        val size = 30_000_000
        val attempts = AtomicInteger()
        withServer({
            attempts.incrementAndGet()
            FakeResponse(200, body = bigJson(size))
        }) { server ->
            val transport = transport(server)
            val dir = java.nio.file.Files.createTempDirectory("aie-441").toFile()
            try {
                val roomy = config().copy(readTimeoutMs = 5_000, overallTimeoutMs = 20_000)
                val defaultCap = transport.execute(roomy, EditHttpRequest(EditHttpMethod.GET, "status"))
                assertTrue("default 1 MB cap still applies", defaultCap is EditHttpResult.Failed)

                val target = java.io.File(dir, "reply.json")
                val spooled = transport.execute(roomy, EditHttpRequest(EditHttpMethod.POST, "edit",
                    EditHttpBody.Json("{}".toByteArray())), downloadTo = target,
                    maxReplyBytes = EditHttpTransport.MAX_REPLY_CEILING)
                assertEquals(java.io.File(dir, "reply.json"), (spooled as EditHttpResult.Success).file)
                assertEquals(size.toLong(), target.length())
                assertEquals(dir.listFiles()!!.toList(), listOf(target))
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    @Test fun inMemoryCapIsBoundedAndRaisedCapWorksInMemory() = withServer({
        FakeResponse(200, body = bigJson(3_000_000))
    }) { server ->
        val transport = transport(server)
        val roomy = config().copy(readTimeoutMs = 5_000, overallTimeoutMs = 20_000)
        val raised = transport.execute(roomy, EditHttpRequest(EditHttpMethod.GET, "status"), maxReplyBytes = 4_000_000)
        assertEquals(3_000_000, (raised as EditHttpResult.Success).bytes.size)
        for (bad in listOf(0, -1, EditHttpTransport.MAX_IN_MEMORY_REPLY + 1, EditHttpTransport.MAX_REPLY_CEILING + 1)) {
            assertEquals(EditHttpResult.Failed(FailureCode.INVALID_REQUEST, false),
                transport.execute(roomy, EditHttpRequest(EditHttpMethod.GET, "status"), maxReplyBytes = bad))
        }
    }

    @Test fun replyAboveRequestedCapFailsWithoutRetry() {
        val attempts = AtomicInteger()
        withServer({
            attempts.incrementAndGet()
            FakeResponse(200, body = bigJson(2_000_000))
        }) { server ->
            val result = transport(server).execute(config().copy(readTimeoutMs = 5_000, overallTimeoutMs = 20_000),
                EditHttpRequest(EditHttpMethod.GET, "status"), maxReplyBytes = 1_500_000)
            assertEquals(EditHttpResult.Failed(FailureCode.RESPONSE_TOO_LARGE, false), result)
            assertEquals("GET over the cap is not retried", 1, attempts.get())
        }
    }

    @Test fun streamedReplyWithoutLengthStopsAtCap() {
        // Chunked reply: no Content-Length to reject early, so the byte counter must stop it.
        val socket = ServerSocket(0, 4, java.net.InetAddress.getLoopbackAddress())
        val served = AtomicInteger()
        val thread = Thread {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: java.io.IOException) { break }
                served.incrementAndGet()
                client.use {
                    try {
                        val input = BufferedInputStream(it.getInputStream())
                        var last = 0
                        while (true) { // read headers until blank line
                            val b = input.read(); if (b < 0) break
                            if (b == '\n'.code && last == '\n'.code) break
                            if (b != '\r'.code) last = b
                        }
                        val out = it.getOutputStream()
                        out.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n".toByteArray())
                        val chunk = ByteArray(65_536) { 'B'.code.toByte() }
                        repeat(40) {
                            out.write("10000\r\n".toByteArray()); out.write(chunk); out.write("\r\n".toByteArray())
                        }
                        out.write("0\r\n\r\n".toByteArray())
                        out.flush()
                    } catch (_: java.io.IOException) { }
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            val transport = EditHttpTransport(connectionFactory = { requested: URL ->
                URL("http://127.0.0.1:${socket.localPort}${requested.file}").openConnection() as HttpURLConnection
            }, sleeper = { })
            val result = transport.execute(config().copy(readTimeoutMs = 5_000, overallTimeoutMs = 20_000),
                EditHttpRequest(EditHttpMethod.GET, "status"), maxReplyBytes = 1_000_000)
            assertEquals(EditHttpResult.Failed(FailureCode.RESPONSE_TOO_LARGE, false), result)
            assertEquals(1, served.get())
        } finally {
            socket.close(); thread.join(1_000)
        }
    }

    @Test fun schemeAuthSendsExactHeaderAndStaysRedacted() {
        val secret = listOf("fal", "synthetic", "key", "42").joinToString("-") // guard: allow-synthetic-secret
        var captured: FakeRequest? = null
        withServer({
            captured = it
            FakeResponse(200, body = "{}".toByteArray())
        }) { server ->
            val auth = EditHttpAuth.Scheme("Key", secret)
            val result = transport(server).execute(config(), EditHttpRequest(EditHttpMethod.GET, "status"), auth)
            assertTrue(result is EditHttpResult.Success)
            assertEquals("Key $secret", captured!!.headers["authorization"])
            assertFalse(captured!!.path.contains(secret))
            assertFalse(auth.toString().contains(secret))
            assertEquals("Scheme([REDACTED])", auth.toString())
            assertFalse(result.toString().contains(secret))
        }
    }

    /** #464: a provider's documented non-secret headers are sent as given, beside the auth. */
    @Test fun extraHeadersAreSentBesideAuth() {
        var captured: FakeRequest? = null
        withServer({ captured = it; FakeResponse(200, body = "{}".toByteArray()) }) { server ->
            val result = transport(server).execute(config(),
                EditHttpRequest(EditHttpMethod.POST, "submit", EditHttpBody.Json("{}".toByteArray()),
                    mapOf("X-Fal-Object-Lifecycle-Preference" to """{"expiration_duration_seconds":3600}""")),
                EditHttpAuth.Scheme("Key", "synthetic"))
            assertTrue(result is EditHttpResult.Success)
            assertEquals("""{"expiration_duration_seconds":3600}""",
                captured!!.headers["x-fal-object-lifecycle-preference"])
            assertEquals("Key synthetic", captured!!.headers["authorization"])
            assertEquals("no-store", captured!!.headers["cache-control"])
        }
    }

    @Test fun unsafeExtraHeadersAreRefusedBeforeConnecting() {
        val calls = AtomicInteger()
        withServer({ calls.incrementAndGet(); FakeResponse(200) }) { server ->
            val transport = transport(server)
            for (headers in listOf(
                mapOf("Authorization" to "Bearer x"), mapOf("authorization" to "x"), mapOf("Cookie" to "a=b"),
                mapOf("Host" to "evil.example"), mapOf("Content-Type" to "text/plain"), mapOf("Accept" to "*/*"),
                mapOf("Cache-Control" to "max-age=60"), mapOf("x-goog-api-key" to "k"), mapOf("X-Api" to "a\r\nX-Evil: 1"),
                mapOf("X-Api" to "a\u0000b"), mapOf("X-Api" to ""), mapOf("X-Api" to "a".repeat(1025)),
                mapOf("X Api" to "a"), mapOf("" to "a"), mapOf("X-Custom" to "a"),
                (1..5).associate { "X-H$it" to "v" }
            )) {
                val auth = if (headers.keys.singleOrNull() == "X-Custom") EditHttpAuth.CustomHeader("X-Custom", "s")
                    else EditHttpAuth.None
                assertEquals(headers.keys.toString(), EditHttpResult.Failed(FailureCode.INVALID_REQUEST, false),
                    transport.execute(config(), EditHttpRequest(EditHttpMethod.GET, "status", headers = headers), auth))
            }
            assertEquals(0, calls.get())
        }
    }

    @Test fun invalidSchemeOrSecretIsRefusedBeforeConnecting() {
        val calls = AtomicInteger()
        withServer({ calls.incrementAndGet(); FakeResponse(200) }) { server ->
            val transport = transport(server)
            for (auth in listOf(
                EditHttpAuth.Scheme("", "s"), EditHttpAuth.Scheme("Key Key", "s"), EditHttpAuth.Scheme("K3y", "s"),
                EditHttpAuth.Scheme("Key\r\nX-Evil", "s"), EditHttpAuth.Scheme("Basic", "s"),
                EditHttpAuth.Scheme("A".repeat(17), "s"), EditHttpAuth.Scheme("Key", ""),
                EditHttpAuth.Scheme("Key", "a b"), EditHttpAuth.Scheme("Key", "a\nb")
            )) {
                assertEquals(auth.toString(), EditHttpResult.Failed(FailureCode.INVALID_REQUEST, false),
                    transport.execute(config(), EditHttpRequest(EditHttpMethod.GET, "status"), auth))
            }
            assertEquals(0, calls.get())
        }
    }

    @Test fun putIsSentWithOrWithoutBodyAndNotReplayedUnlessIdempotent() {
        val seen = mutableListOf<FakeRequest>()
        withServer({
            synchronized(seen) { seen.add(it) }
            FakeResponse(503)
        }) { server ->
            val transport = transport(server)
            val bare = transport.execute(config(), EditHttpRequest(EditHttpMethod.PUT, "requests/r1/cancel"))
            assertEquals(EditHttpResult.Failed(FailureCode.SERVER, true), bare)
            assertEquals(1, seen.size)
            assertEquals("PUT", seen[0].method)
            assertEquals("/v1/requests/r1/cancel", seen[0].path)

            transport.execute(config(), EditHttpRequest(EditHttpMethod.PUT, "items/1",
                EditHttpBody.Json("{\"a\":1}".toByteArray())))
            assertEquals(2, seen.size)
            assertEquals("{\"a\":1}", seen[1].body.toString(Charsets.UTF_8))

            transport.execute(config(), EditHttpRequest(EditHttpMethod.PUT, "requests/r1/cancel"), idempotent = true)
            assertEquals("idempotent PUT may retry", 2 + config().maxAttempts, seen.size)
        }
    }

    @Test fun bodyRulesStayStrictForOtherMethods() = withServer({ FakeResponse(200) }) { server ->
        val transport = transport(server)
        val json = EditHttpBody.Json("{}".toByteArray())
        val invalid = EditHttpResult.Failed(FailureCode.INVALID_REQUEST, false)
        assertEquals(invalid, transport.execute(config(), EditHttpRequest(EditHttpMethod.POST, "x")))
        assertEquals(invalid, transport.execute(config(), EditHttpRequest(EditHttpMethod.GET, "x", json)))
        assertEquals(invalid, transport.execute(config(), EditHttpRequest(EditHttpMethod.DELETE, "x", json)))
        val dir = java.nio.file.Files.createTempDirectory("aie-441b").toFile()
        try {
            assertEquals(invalid, transport.execute(config(), EditHttpRequest(EditHttpMethod.DELETE, "x"),
                downloadTo = java.io.File(dir, "f")))
        } finally { dir.deleteRecursively() }
    }
}
