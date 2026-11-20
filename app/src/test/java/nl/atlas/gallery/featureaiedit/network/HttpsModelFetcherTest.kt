package nl.atlas.gallery.featureaiedit.network

import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale
import kotlin.random.Random
import nl.atlas.gallery.featureidentity.install.InstallFailure
import nl.atlas.gallery.featureidentity.install.InstallState
import nl.atlas.gallery.featureidentity.install.ModelSetInstall
import nl.atlas.gallery.featureidentity.local.IdentityModelStore
import nl.atlas.gallery.featureidentity.local.LicenceTier
import nl.atlas.gallery.featureidentity.local.ModelEntry
import nl.atlas.gallery.featureidentity.local.ModelFetchException
import nl.atlas.gallery.featureidentity.local.ModelFetchFailure
import nl.atlas.gallery.featureidentity.local.ModelRole
import nl.atlas.gallery.featureidentity.local.ModelState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * IDS-19 (#619): the model fetcher + store + install runner against a loopback fake server.
 * The connection factory maps `https://<host>/<path>` to `http://127.0.0.1:<port>/<host>/<path>`,
 * so the server sees which host every request was meant for while the fetcher's own checks run
 * on the real HTTPS URLs. No real network, no real weights.
 */
class HttpsModelFetcherTest {
    private class Req(val path: String, val range: String?)
    private class Resp(
        val status: Int,
        val body: ByteArray = ByteArray(0),
        val headers: Map<String, String> = emptyMap(),
        /** Announce the full length but close after this many body bytes (a dropped connection). */
        val cutAfter: Int? = null
    )

    private class FakeServer(private val responder: (Req) -> Resp) : AutoCloseable {
        private val socket = ServerSocket(0, 8, java.net.InetAddress.getLoopbackAddress())
        val port = socket.localPort
        val requests: MutableList<Req> = Collections.synchronizedList(mutableListOf())
        private val thread = Thread({ serve() }, "ids19-fake-http").apply { isDaemon = true; start() }

        private fun serve() {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: java.io.IOException) { return }
                client.use {
                    try {
                        val input = BufferedInputStream(it.getInputStream())
                        val line = readLine(input) ?: return@use
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val h = readLine(input) ?: return@use
                            if (h.isEmpty()) break
                            val i = h.indexOf(':')
                            if (i > 0) headers[h.substring(0, i).lowercase(Locale.US)] = h.substring(i + 1).trim()
                        }
                        val req = Req(line.split(' ')[1], headers["range"])
                        requests += req
                        val r = responder(req)
                        val out = it.getOutputStream()
                        out.write("HTTP/1.1 ${r.status} X\r\n".toByteArray())
                        r.headers.forEach { (k, v) -> out.write("$k: $v\r\n".toByteArray()) }
                        out.write("Content-Length: ${r.body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        out.write(r.body, 0, r.cutAfter ?: r.body.size)
                        out.flush()
                    } catch (_: java.io.IOException) {
                    }
                }
            }
        }

        private fun readLine(input: BufferedInputStream): String? {
            val bytes = java.io.ByteArrayOutputStream()
            while (true) {
                val c = input.read()
                if (c < 0) return if (bytes.size() == 0) null else bytes.toString("US-ASCII")
                if (c == '\n'.code) return bytes.toString("US-ASCII").trimEnd('\r')
                bytes.write(c)
            }
        }

        override fun close() { socket.close(); thread.join(1_000) }
    }

    private val root: File = Files.createTempDirectory("ids19").toFile()
    @After fun cleanUp() { root.deleteRecursively() }

    private val payload = Random(619).nextBytes(300_000)
    private val origin = "https://github.com/facefusion/facefusion-assets/releases/download/test-1/model.onnx"
    private val originPath = "/github.com/facefusion/facefusion-assets/releases/download/test-1/model.onnx"

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun entry(bytes: ByteArray = payload, url: String = origin) = ModelEntry(
        id = "test-model", role = ModelRole.SWAP_NETWORK, fileName = "model.onnx", url = url,
        sha256 = sha(bytes), sizeBytes = bytes.size.toLong(), registerRow = "test", tier = LicenceTier.A
    )

    private fun fetcher(server: FakeServer) = HttpsModelFetcher(
        connectionFactory = { u -> URL("http://127.0.0.1:${server.port}/${u.host}${u.file}").openConnection() as HttpURLConnection },
        connectTimeoutMs = 1_000, readTimeoutMs = 2_000
    )

    private fun install(server: FakeServer, e: ModelEntry = entry(), free: Long = Long.MAX_VALUE) =
        ModelSetInstall(IdentityModelStore(root, fetcher(server)), listOf(e), root) { free }

    /** Serves [bytes] honouring `Range: bytes=N-` with a 206. */
    private fun ranged(bytes: ByteArray, req: Req, cutAfter: Int? = null): Resp {
        val from = req.range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: return Resp(200, bytes, cutAfter = cutAfter)
        val rest = bytes.copyOfRange(from, bytes.size)
        return Resp(206, rest, mapOf("Content-Range" to "bytes $from-${bytes.size - 1}/${bytes.size}"), cutAfter)
    }

    @Test fun resumesFromThePartialFileAfterTheProcessDies() {
        var first = true
        FakeServer { req -> if (first) { first = false; ranged(payload, req, cutAfter = 120_000) } else ranged(payload, req) }.use { server ->
            val outcome = install(server).run({ false }) { }
            assertEquals(ModelSetInstall.Outcome.Failed(InstallFailure.NO_NETWORK, retryable = true), outcome)
            val partial = IdentityModelStore(root) { _, _ -> error("state only") }.state(entry())
            assertTrue("partial file kept for the next run", partial is ModelState.Partial && partial.bytes == 120_000L)

            // A new store and runner, as after the app process was killed and WorkManager re-ran the worker.
            val states = mutableListOf<InstallState>()
            assertEquals(ModelSetInstall.Outcome.Installed, install(server).run({ false }) { states += it })
            assertEquals("bytes=120000-", server.requests.last().range)
            assertEquals(InstallState.Downloading(120_000, payload.size.toLong()), states.first())
            assertEquals(InstallState.Verifying, states.last())
            assertTrue(File(File(root, IdentityModelStore.DIR), "model.onnx").readBytes().contentEquals(payload))
        }
    }

    @Test fun restartsCleanlyWhenTheHostIgnoresTheRange() {
        FakeServer { Resp(200, payload) }.use { server ->
            val dir = File(root, IdentityModelStore.DIR).apply { mkdirs() }
            File(dir, "model.onnx.part").writeBytes(ByteArray(50_000) { 7 }) // stale bytes that must not survive
            assertEquals(ModelSetInstall.Outcome.Installed, install(server).run({ false }) { })
            assertEquals("bytes=50000-", server.requests.single().range)
            assertTrue(File(dir, "model.onnx").readBytes().contentEquals(payload))
        }
    }

    @Test fun followsARedirectOnlyToGitHubReleaseAssetStorage() {
        FakeServer { req ->
            if (req.path == originPath) Resp(302, headers = mapOf("Location" to "https://release-assets.githubusercontent.com/github-production-release-asset/1/abc?sig=token"))
            else ranged(payload, req)
        }.use { server ->
            assertEquals(ModelSetInstall.Outcome.Installed, install(server).run({ false }) { })
            assertEquals(
                listOf(originPath, "/release-assets.githubusercontent.com/github-production-release-asset/1/abc?sig=token"),
                server.requests.map { it.path }
            )
        }
    }

    @Test fun refusesARedirectOffTheAllowlistWithoutFollowingIt() {
        FakeServer { req ->
            if (req.path == originPath) Resp(302, headers = mapOf("Location" to "https://evil.example/model.onnx?sig=token"))
            else ranged(payload, req)
        }.use { server ->
            val outcome = install(server).run({ false }) { }
            assertEquals(ModelSetInstall.Outcome.Failed(InstallFailure.HOST_REFUSED, retryable = false), outcome)
            assertEquals(listOf(originPath), server.requests.map { it.path })
        }
    }

    @Test fun redirectHostListIsExact() {
        assertTrue(HttpsModelFetcher.redirectAllowed("https://release-assets.githubusercontent.com/a?b=c"))
        assertTrue(HttpsModelFetcher.redirectAllowed("https://objects.githubusercontent.com/a"))
        for (bad in listOf(
            "http://release-assets.githubusercontent.com/a",
            "https://release-assets.githubusercontent.com.evil.example/a",
            "https://evil-release-assets.githubusercontent.com/a",
            "https://x.release-assets.githubusercontent.com/a",
            "https://user@release-assets.githubusercontent.com/a",
            "https://release-assets.githubusercontent.com:8443/a",
            "https://github.com/facefusion/facefusion-assets/releases/download/x",
            "//release-assets.githubusercontent.com/a",
            "not a url"
        )) assertFalse(bad, HttpsModelFetcher.redirectAllowed(bad))
    }

    @Test fun refusesANonOriginalHostBeforeConnecting() {
        FakeServer { Resp(200, payload) }.use { server ->
            try {
                fetcher(server).open("https://huggingface.co/x/model.onnx", 0)
                fail("mirror must be refused")
            } catch (e: ModelFetchException) {
                assertEquals(ModelFetchFailure.HOST_REFUSED, e.failure)
                assertEquals("Model fetch failed: HOST_REFUSED", e.message) // no URL in the message
            }
            assertTrue(server.requests.isEmpty())
        }
    }

    @Test fun hashMismatchFailsClearlyDeletesThePartialAndIsNotRetried() {
        val wrong = payload.copyOf().also { it[1234] = (it[1234] + 1).toByte() }
        FakeServer { req -> ranged(wrong, req) }.use { server ->
            val outcome = install(server).run({ false }) { }
            assertEquals(ModelSetInstall.Outcome.Failed(InstallFailure.FILE_CHECK_FAILED, retryable = false), outcome)
            assertFalse(File(File(root, IdentityModelStore.DIR), "model.onnx.part").exists())
        }
    }

    @Test fun sizeMismatchFailsClearlyDeletesThePartialAndIsNotRetried() {
        FakeServer { Resp(200, payload + ByteArray(10)) }.use { server ->
            val outcome = install(server).run({ false }) { }
            assertEquals(ModelSetInstall.Outcome.Failed(InstallFailure.FILE_CHECK_FAILED, retryable = false), outcome)
            assertFalse(File(File(root, IdentityModelStore.DIR), "model.onnx.part").exists())
        }
    }

    @Test fun cancelStopsTheDownloadAndUserCancelDiscardsThePartial() {
        FakeServer { req -> ranged(payload, req) }.use { server ->
            val set = install(server)
            var seen = 0
            val outcome = set.run({ seen > 0 }) { if (it is InstallState.Downloading) seen++ }
            assertEquals(ModelSetInstall.Outcome.Cancelled, outcome)
            val dir = File(root, IdentityModelStore.DIR)
            assertFalse(File(dir, "model.onnx").exists())
            set.discardUnfinished()
            assertFalse(File(dir, "model.onnx.part").exists())
        }
    }

    @Test fun notEnoughSpaceFailsWithoutFetching() {
        FakeServer { Resp(200, payload) }.use { server ->
            val outcome = install(server, free = 100_000).run({ false }) { }
            assertEquals(ModelSetInstall.Outcome.Failed(InstallFailure.NOT_ENOUGH_SPACE, retryable = false), outcome)
            assertTrue(server.requests.isEmpty())
        }
    }

    @Test fun temporaryHostErrorsRetryAndRefusalsDoNot() {
        FakeServer { Resp(503) }.use { server ->
            assertEquals(ModelSetInstall.Outcome.Failed(InstallFailure.NO_NETWORK, retryable = true), install(server).run({ false }) { })
        }
        FakeServer { Resp(404) }.use { server ->
            assertEquals(ModelSetInstall.Outcome.Failed(InstallFailure.HOST_REFUSED, retryable = false), install(server).run({ false }) { })
        }
    }

    @Test fun aPartialReplyForOtherBytesIsNeverSpliced() {
        FakeServer { req ->
            val from = req.range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
            Resp(206, payload.copyOfRange(0, payload.size - from), mapOf("Content-Range" to "bytes 0-${payload.size - from - 1}/${payload.size}"))
        }.use { server ->
            val dir = File(root, IdentityModelStore.DIR).apply { mkdirs() }
            File(dir, "model.onnx.part").writeBytes(payload.copyOfRange(0, 1_000))
            assertEquals(ModelSetInstall.Outcome.Failed(InstallFailure.NO_NETWORK, retryable = true), install(server).run({ false }) { })
            assertEquals(1_000L, File(dir, "model.onnx.part").length())
        }
    }

    @Test fun unreachableHostIsANetworkFailure() {
        val dead = HttpsModelFetcher(connectionFactory = { u ->
            URL("http://127.0.0.1:1/${u.host}${u.file}").openConnection() as HttpURLConnection
        }, connectTimeoutMs = 500, readTimeoutMs = 500)
        val outcome = ModelSetInstall(IdentityModelStore(root, dead), listOf(entry()), root) { Long.MAX_VALUE }.run({ false }) { }
        assertEquals(ModelSetInstall.Outcome.Failed(InstallFailure.NO_NETWORK, retryable = true), outcome)
    }
}
