package nl.atlas.gallery.featureaiedit.settings

import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.util.Collections
import java.util.Locale
import nl.atlas.gallery.featureaiedit.network.EditHttpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AIE-19 (#430): every documented key-check result, from a loopback fake server reached
 * through the transport's injected connector. The checked URL stays HTTPS and host-pinned;
 * no test reaches a real service, and every key here is synthetic.
 */
class ProviderKeyCheckerTest {
    private data class Seen(val method: String, val path: String, val headers: Map<String, String>)

    private class FakeServer(private val status: Int, private val body: String = "{}") : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        val port = socket.localPort
        val seen: MutableList<Seen> = Collections.synchronizedList(mutableListOf())
        private val thread = Thread({
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: java.io.IOException) { break }
                client.use {
                    try {
                        val input = BufferedInputStream(it.getInputStream())
                        val request = readLine(input) ?: return@use
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val line = readLine(input) ?: return@use
                            if (line.isEmpty()) break
                            val colon = line.indexOf(':')
                            if (colon > 0) headers[line.substring(0, colon).lowercase(Locale.US)] =
                                line.substring(colon + 1).trim()
                        }
                        val parts = request.split(' ')
                        seen.add(Seen(parts[0], parts[1], headers))
                        val bytes = body.toByteArray()
                        val out = it.getOutputStream()
                        out.write(("HTTP/1.1 $status Test\r\nContent-Type: application/json\r\n" +
                            (if (status == 302) "Location: https://elsewhere.example/\r\n" else "") +
                            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                        out.write(bytes)
                        out.flush()
                    } catch (_: java.io.IOException) { }
                }
            }
        }, "aie-19-fake-http").apply { isDaemon = true; start() }

        private fun readLine(input: BufferedInputStream): String? {
            val bytes = ArrayList<Byte>()
            while (true) {
                val next = input.read()
                if (next < 0) return if (bytes.isEmpty()) null else String(bytes.toByteArray())
                if (next == '\n'.code) return String(bytes.toByteArray()).trimEnd('\r')
                bytes.add(next.toByte())
            }
        }

        override fun close() { socket.close(); thread.join(1_000) }
    }

    private fun checker(port: Int) = ProviderKeyChecker(EditHttpTransport(
        connectionFactory = { requested: URL ->
            URL("http://127.0.0.1:$port${requested.file}").openConnection() as HttpURLConnection
        },
        sleeper = { }
    ))

    private val gemini = ProviderRegistry.GEMINI_PRESET
    private val geminiProfile = ProviderProfile("gemini-1", gemini.title, gemini.baseUrl!!,
        gemini.authType, gemini.headerName, ProviderKind.GEMINI)
    private val custom = ProviderProfile("custom-1", "Home server", "https://editor.example/v1/",
        ProviderAuthType.BEARER)
    private val key = "synthetic-key-430"

    private fun gemini(status: Int, body: String = "{}"): Pair<KeyCheckOutcome, List<Seen>> =
        FakeServer(status, body).use { server ->
            checker(server.port).check(geminiProfile, key, gemini.keyCheck) to server.seen.toList()
        }

    private fun customServer(status: Int): KeyCheckOutcome = FakeServer(status).use { server ->
        checker(server.port).check(custom, key, ProviderRegistry.CUSTOM_PRESET.keyCheck)
    }

    @Test fun geminiKeyWorksOnModelsListWithItsOwnHeaderAndNoUpload() {
        val (outcome, seen) = gemini(200, """{"models":[]}""")
        assertEquals(KeyCheckOutcome.WORKS, outcome)
        assertEquals(1, seen.size)
        assertEquals("GET", seen[0].method)
        assertEquals("/v1beta/models", seen[0].path)
        assertEquals(key, seen[0].headers["x-goog-api-key"])
        assertNull(seen[0].headers["authorization"])
        assertNull(seen[0].headers["content-length"])
    }

    @Test fun geminiWrongKeyIsNotAccepted() {
        // Body observed 2026-11-18 for a made-up key (key-free request, no spend).
        val invalid = """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.",
            "status":"INVALID_ARGUMENT","details":[{"reason":"API_KEY_INVALID"}]}}"""
        assertEquals(KeyCheckOutcome.REJECTED, gemini(400, invalid).first)
        assertEquals(KeyCheckOutcome.REJECTED, gemini(403).first)
        assertEquals(KeyCheckOutcome.REJECTED, gemini(401).first)
    }

    @Test fun busyOrBrokenServiceKeepsTheKeyAsNotCheckedNeverOutOfCredit() {
        assertEquals(KeyCheckOutcome.NOT_RESPONDING, gemini(429).first)
        assertEquals(KeyCheckOutcome.NOT_RESPONDING, gemini(500).first)
        assertEquals(KeyCheckOutcome.NOT_RESPONDING, gemini(503).first)
        assertEquals(KeyStatus.UNCHECKED, KeyCheckOutcome.NOT_RESPONDING.toStatus())
        assertEquals(KeyStatus.UNCHECKED, KeyCheckOutcome.NO_CONNECTION.toStatus())
    }

    @Test fun redirectIsNeverFollowed() {
        val (outcome, seen) = gemini(302)
        assertEquals(KeyCheckOutcome.NOT_RESPONDING, outcome)
        assertEquals(1, seen.size)
    }

    @Test fun noServerMeansNoConnection() {
        val closed = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        assertEquals(KeyCheckOutcome.NO_CONNECTION,
            checker(closed).check(geminiProfile, key, gemini.keyCheck))
    }

    @Test fun customServerChecksAuthenticatedCapabilities() {
        FakeServer(200).use { server ->
            assertEquals(KeyCheckOutcome.WORKS,
                checker(server.port).check(custom, key, ProviderRegistry.CUSTOM_PRESET.keyCheck))
            assertEquals("/v1/capabilities", server.seen.single().path)
            assertEquals("Bearer $key", server.seen.single().headers["authorization"])
        }
        assertEquals(KeyCheckOutcome.REJECTED, customServer(401))
        assertEquals(KeyCheckOutcome.REJECTED, customServer(403))
        // A wrong path on someone's own server is not proof of a wrong key.
        assertEquals(KeyCheckOutcome.NOT_RESPONDING, customServer(404))
    }

    @Test fun kindWithoutDocumentedCheckSendsNothing() {
        FakeServer(200).use { server ->
            val fal = ProviderRegistry.FAL_PRESET
            val profile = ProviderProfile("fal-1", fal.title, fal.baseUrl!!, fal.authType,
                fal.headerName, ProviderKind.FAL)
            assertEquals(KeyCheckOutcome.NO_CHECK, checker(server.port).check(profile, key, fal.keyCheck))
            assertTrue(server.seen.isEmpty())
        }
        assertEquals(KeyStatus.NO_CHECK, KeyCheckOutcome.NO_CHECK.toStatus())
    }

    @Test fun missingKeyIsRejectedWithoutARequest() {
        FakeServer(200).use { server ->
            assertEquals(KeyCheckOutcome.REJECTED,
                checker(server.port).check(geminiProfile, null, gemini.keyCheck))
            assertTrue(server.seen.isEmpty())
        }
    }

    @Test fun pastedKeysAreTrimmedAndBrokenOnesRefusedLocally() {
        assertEquals("abc123", ProviderKeyChecker.normalizeKey("  abc123\n"))
        assertNull(ProviderKeyChecker.normalizeKey(""))
        assertNull(ProviderKeyChecker.normalizeKey("   "))
        assertNull(ProviderKeyChecker.normalizeKey("abc 123"))
        assertNull(ProviderKeyChecker.normalizeKey("abc\r\nX-Evil: 1"))
        assertNull(ProviderKeyChecker.normalizeKey("a".repeat(8193)))
        assertFalse(ProviderKeyChecker.normalizeKey("a".repeat(8192)).isNullOrEmpty())
    }
}
