package nl.atlas.gallery.featureaiedit.provider.hosted

import nl.atlas.gallery.featureaiedit.network.EditHttpTransport
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.util.Collections
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.Deflater

/** A request as the fake provider saw it; [host] is the HTTPS host the adapter addressed. */
class FakeRequest(
    val method: String,
    val path: String,
    val host: String,
    val headers: Map<String, String>,
    val body: ByteArray
) {
    val text: String get() = body.toString(Charsets.UTF_8)
}

class FakeResponse(
    val status: Int,
    val body: ByteArray = ByteArray(0),
    val contentType: String = "application/json",
    val delayMs: Long = 0,
    /** Close the socket without any reply: the transport sees an I/O failure. */
    val dropConnection: Boolean = false
) {
    companion object {
        fun json(text: String, status: Int = 200, delayMs: Long = 0) =
            FakeResponse(status, text.toByteArray(Charsets.UTF_8), delayMs = delayMs)
    }
}

/**
 * Loopback HTTP/1.1 fake built only from the JDK. The adapter keeps its real HTTPS URLs and
 * host allowlist; [transport] reroutes each verified URL to this socket and records the host.
 */
class FakeProviderServer(private val responder: (FakeRequest) -> FakeResponse) : AutoCloseable {
    private val socket = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
    val port: Int = socket.localPort
    val requests: MutableList<FakeRequest> = Collections.synchronizedList(mutableListOf())
    @Volatile private var pendingHost: String = ""
    private val thread = Thread({ serve() }, "atlas-fake-provider").apply {
        isDaemon = true
        start()
    }

    fun transport(): EditHttpTransport = EditHttpTransport(
        connectionFactory = { requested: URL ->
            pendingHost = requested.host
            URL("http://127.0.0.1:$port${requested.file}").openConnection() as HttpURLConnection
        },
        sleeper = { }
    )

    private fun serve() {
        while (!socket.isClosed) {
            val client = try { socket.accept() } catch (_: java.io.IOException) { return }
            val host = pendingHost
            Thread({
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
                        val request = FakeRequest(parts[0], parts[1], host, headers, body)
                        requests.add(request)
                        val response = responder(request)
                        if (response.delayMs > 0) Thread.sleep(response.delayMs)
                        if (response.dropConnection) return@use
                        val output = it.getOutputStream()
                        output.write("HTTP/1.1 ${response.status} Test\r\n".toByteArray())
                        output.write("Content-Type: ${response.contentType}\r\n".toByteArray())
                        output.write("Content-Length: ${response.body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        output.write(response.body)
                        output.flush()
                    } catch (_: java.io.IOException) {
                        // Expected when the adapter cancels or times out.
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
            }, "atlas-fake-provider-conn").apply { isDaemon = true }.start()
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            if (next < 0) return if (bytes.size() == 0) null else bytes.toString(Charsets.US_ASCII.name())
            if (next == '\n'.code) return bytes.toString(Charsets.US_ASCII.name()).trimEnd('\r')
            bytes.write(next)
        }
    }

    override fun close() {
        socket.close()
        thread.join(1_000)
    }
}

/** Tiny deterministic PNG encoder for synthetic fixtures (no real photos anywhere). */
object SyntheticPng {
    fun encode(width: Int, height: Int, seed: Int = 7): ByteArray {
        val raw = ByteArrayOutputStream()
        for (y in 0 until height) {
            raw.write(0)
            for (x in 0 until width) {
                raw.write((x * 31 + seed) and 255)
                raw.write((y * 17 + seed) and 255)
                raw.write((x + y + seed) and 255)
            }
        }
        val deflater = Deflater()
        deflater.setInput(raw.toByteArray())
        deflater.finish()
        val compressed = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (!deflater.finished()) compressed.write(buffer, 0, deflater.deflate(buffer))
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
        val ihdr = ByteArrayOutputStream()
        DataOutputStream(ihdr).apply {
            writeInt(width); writeInt(height)
            writeByte(8); writeByte(2); writeByte(0); writeByte(0); writeByte(0)
        }
        chunk(out, "IHDR", ihdr.toByteArray())
        chunk(out, "IDAT", compressed.toByteArray())
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val stream = DataOutputStream(out)
        stream.writeInt(data.size)
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        stream.write(typeBytes)
        stream.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        stream.writeInt(crc.value.toInt())
    }
}

/** Returns synthetic bytes for any handle; records which handles were read. */
class SyntheticImageLoader(private val bytes: ByteArray = SyntheticPng.encode(8, 8)) : SourceImageLoader {
    val loaded: MutableList<String> = Collections.synchronizedList(mutableListOf())
    override fun load(
        image: nl.atlas.gallery.featureaiedit.provider.EditImage,
        plan: nl.atlas.gallery.featureaiedit.provider.ResizePlan
    ): EncodedImage {
        loaded.add(image.handle)
        return EncodedImage(bytes, nl.atlas.gallery.featureaiedit.provider.ImageFormat.PNG)
    }
}
