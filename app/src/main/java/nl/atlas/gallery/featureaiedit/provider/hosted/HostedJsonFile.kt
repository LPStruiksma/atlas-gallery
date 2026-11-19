package nl.atlas.gallery.featureaiedit.provider.hosted

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.Base64

/**
 * A JSON string too long to hold in memory, left in the reply file. [offset] and [length] are
 * byte positions of its raw content (between the quotes) in that file. Only escape-free strings
 * qualify, which covers base64 image data.
 */
class LargeJsonString(val file: File, val offset: Long, val length: Long) {
    override fun toString(): String = "LargeJsonString($length bytes)"

    /** Decodes this base64 string straight into [target] with a fixed-size buffer. */
    fun decodeBase64To(target: File, maxDecodedBytes: Long): Boolean = try {
        FileInputStream(file).use { raw ->
            var skipped = 0L
            while (skipped < offset) {
                val n = raw.skip(offset - skipped)
                if (n <= 0) return false
                skipped += n
            }
            val region = BoundedStream(BufferedInputStream(raw, 65_536), length)
            Base64.getDecoder().wrap(region).use { decoded ->
                FileOutputStream(target).use { out ->
                    val buffer = ByteArray(65_536)
                    var total = 0L
                    while (true) {
                        val n = decoded.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > maxDecodedBytes) return false
                        out.write(buffer, 0, n)
                    }
                    out.fd.sync()
                    total > 0
                }
            }
        }
    } catch (_: Exception) {
        false
    }

    private class BoundedStream(input: InputStream, private var remaining: Long) : FilterInputStream(input) {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = super.read()
            if (b >= 0) remaining--
            return b
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = super.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n > 0) remaining -= n
            return n
        }
    }
}

/**
 * Streaming counterpart of [HostedJson] for replies spooled to a file: the same tree shapes,
 * except that strings longer than [inlineLimit] bytes become [LargeJsonString]. Heap use stays
 * at the small values plus a read buffer, whatever the reply size.
 */
object HostedJsonFile {
    private const val MAX_DEPTH = 64

    fun parseOrNull(file: File, inlineLimit: Int = 65_536): Any? = try {
        FileInputStream(file).use { stream ->
            val reader = Reader(file, BufferedInputStream(stream, 65_536), inlineLimit)
            reader.skipWhitespace()
            val value = reader.readValue(0)
            reader.skipWhitespace()
            if (reader.peek() != -1) null else value
        }
    } catch (_: HostedJson.ParseException) {
        null
    } catch (_: java.io.IOException) {
        null
    } catch (_: StackOverflowError) {
        null
    }

    private class Reader(private val file: File, private val input: BufferedInputStream, private val inlineLimit: Int) {
        private var position = 0L
        private var peeked = -2

        fun peek(): Int {
            if (peeked == -2) peeked = input.read()
            return peeked
        }

        fun next(): Int {
            val c = peek()
            peeked = -2
            if (c >= 0) position++
            return c
        }

        private fun fail(): Nothing = throw HostedJson.ParseException()

        private fun expect(c: Char) { if (next() != c.code) fail() }

        fun skipWhitespace() {
            while (true) {
                val c = peek()
                if (c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code) next() else return
            }
        }

        fun readValue(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail()
            return when (peek()) {
                '{'.code -> readObject(depth + 1)
                '['.code -> readArray(depth + 1)
                '"'.code -> readString(allowLarge = true)
                't'.code -> literal("true", true)
                'f'.code -> literal("false", false)
                'n'.code -> literal("null", null)
                -1 -> fail()
                else -> readNumber()
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            for (ch in word) if (next() != ch.code) fail()
            return value
        }

        private fun readObject(depth: Int): Map<String, Any?> {
            expect('{')
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}'.code) { next(); return result }
            while (true) {
                skipWhitespace()
                if (peek() != '"'.code) fail()
                val key = readString(allowLarge = false) as String
                skipWhitespace()
                expect(':')
                skipWhitespace()
                result[key] = readValue(depth)
                skipWhitespace()
                when (next()) {
                    ','.code -> Unit
                    '}'.code -> return result
                    else -> fail()
                }
            }
        }

        private fun readArray(depth: Int): List<Any?> {
            expect('[')
            val result = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']'.code) { next(); return result }
            while (true) {
                skipWhitespace()
                result.add(readValue(depth))
                skipWhitespace()
                when (next()) {
                    ','.code -> Unit
                    ']'.code -> return result
                    else -> fail()
                }
            }
        }

        private fun readString(allowLarge: Boolean): Any {
            expect('"')
            val start = position
            val raw = ByteArrayOutputStream()
            var escaped = false
            var large = false
            var count = 0L
            while (true) {
                val c = next()
                if (c < 0 || c < 0x20) fail()
                if (c == '"'.code) break
                count++
                if (c == '\\'.code) {
                    if (large) fail() // escapes inside a large string are not supported
                    escaped = true
                    val e = next()
                    when (e) {
                        '"'.code, '\\'.code, '/'.code -> raw.write(e)
                        'b'.code -> raw.write(8)
                        'f'.code -> raw.write(12)
                        'n'.code -> raw.write(10)
                        'r'.code -> raw.write(13)
                        't'.code -> raw.write(9)
                        'u'.code -> {
                            var code = 0
                            repeat(4) {
                                val h = Character.digit(next(), 16)
                                if (h < 0) fail()
                                code = code * 16 + h
                            }
                            raw.write(code.toChar().toString().toByteArray(Charsets.UTF_8))
                        }
                        else -> fail()
                    }
                    continue
                }
                if (!large) {
                    raw.write(c)
                    if (raw.size() > inlineLimit) {
                        if (!allowLarge || escaped) fail()
                        large = true
                        raw.reset()
                    }
                }
            }
            return if (large) LargeJsonString(file, start, count) else raw.toString(Charsets.UTF_8.name())
        }

        private fun readNumber(): Any {
            val text = StringBuilder()
            while (true) {
                val c = peek()
                if (c >= 0 && (c.toChar().isDigit() || c.toChar() in "+-.eE")) {
                    text.append(c.toChar()); next()
                    if (text.length > 64) fail()
                } else break
            }
            // Reuse the strict number grammar of the in-memory parser.
            return HostedJson.parse(text.toString()) as? Number ?: fail()
        }
    }
}
