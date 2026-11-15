package nl.atlas.gallery.featureaiedit.network

import com.sun.net.httpserver.HttpServer
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/** All requests terminate on loopback fake HTTP through the injected connection adapter.
 * The public transport URL and its validation remain HTTPS; no real network API is used. */
class EditHttpTransportTest {
    private fun withServer(block: (HttpServer) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        try { block(server) } finally { server.stop(0) }
    }

    private fun transport(server: HttpServer) = EditHttpTransport(
        connectionFactory = { requested: URL ->
            URL("http://127.0.0.1:${server.address.port}${requested.file}").openConnection() as HttpURLConnection
        },
        sleeper = { }
    )
    private fun config() = EditHttpConfig("https://editor.example/v1/", setOf("editor.example"),
        connectTimeoutMs = 500, readTimeoutMs = 100, overallTimeoutMs = 1_000)

    @Test fun httpsAndAllowedHostRequiredBeforeConnection() = withServer { server ->
        val calls = AtomicInteger()
        server.createContext("/") { exchange ->
            calls.incrementAndGet()
            exchange.sendResponseHeaders(200, 0)
            exchange.close()
        }
        val transport = transport(server)
        assertTrue(transport.execute(config().copy(baseUrl = "http://editor.example/v1/"),
            EditHttpRequest(EditHttpMethod.GET, "capabilities")) is EditHttpResult.Failed)
        assertTrue(transport.execute(config(), EditHttpRequest(EditHttpMethod.GET,
            "https://evil.example/v1/capabilities")) is EditHttpResult.Failed)
        assertTrue(transport.execute(config(), EditHttpRequest(EditHttpMethod.GET,
            "//evil.example/steal")) is EditHttpResult.Failed)
        assertEquals(0, calls.get())
    }

    @Test fun redirectsAreNotFollowedEvenToAllowedHost() = withServer { server ->
        val calls = AtomicInteger()
        server.createContext("/v1/") { exchange ->
            calls.incrementAndGet()
            exchange.responseHeaders.add("Location", "https://evil.example/credentials")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        assertTrue(transport(server).execute(config(), EditHttpRequest(EditHttpMethod.GET,
            "capabilities")) is EditHttpResult.Failed)
        assertEquals(1, calls.get())
    }

    @Test fun retryOnlyIdempotentMethodsAndNeverSwitchProvider() = withServer { server ->
        val attempts = AtomicInteger()
        server.createContext("/v1/") { exchange ->
            val n = attempts.incrementAndGet()
            if (n == 1) exchange.sendResponseHeaders(503, -1) else {
                val reply = "{}".toByteArray()
                exchange.sendResponseHeaders(200, reply.size.toLong())
                exchange.responseBody.write(reply)
            }
            exchange.close()
        }
        assertTrue(transport(server).execute(config(), EditHttpRequest(EditHttpMethod.GET,
            "capabilities")) is EditHttpResult.Success)
        assertEquals(2, attempts.get())
        attempts.set(0)
        val post = EditHttpRequest(EditHttpMethod.POST, "edits", EditHttpBody.Json("{}".toByteArray()))
        assertTrue(transport(server).execute(config(), post) is EditHttpResult.Failed)
        assertEquals(1, attempts.get())
    }

    @Test fun cancellationTimeoutAndRedactedFailures() = withServer { server ->
        server.createContext("/v1/") { exchange ->
            Thread.sleep(300)
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
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

    @Test fun multipartAndAuthAreSentOnlyToChosenHost() = withServer { server ->
        val count = AtomicInteger()
        server.createContext("/v1/") { exchange ->
            count.incrementAndGet()
            assertEquals("POST", exchange.requestMethod)
            assertEquals("Bearer test-only", exchange.requestHeaders.getFirst("Authorization"))
            assertTrue(exchange.requestHeaders.getFirst("Content-Type").startsWith("multipart/form-data; boundary="))
            assertTrue(exchange.requestBody.readBytes().toString(Charsets.UTF_8).contains("synthetic instruction"))
            exchange.sendResponseHeaders(202, 0)
            exchange.close()
        }
        val multipart = EditHttpBody.Multipart(listOf(
            EditMultipartPart.Text("instruction", "synthetic instruction"),
            EditMultipartPart.Binary("image", "synthetic.png", "image/png", byteArrayOf(1, 2, 3))
        ))
        assertTrue(transport(server).execute(config(),
            EditHttpRequest(EditHttpMethod.POST, "edits", multipart),
            EditHttpAuth.Bearer("test-only")) is EditHttpResult.Success)
        assertEquals(1, count.get())
    }
}
