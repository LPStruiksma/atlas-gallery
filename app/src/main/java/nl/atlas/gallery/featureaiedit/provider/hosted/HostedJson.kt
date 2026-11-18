package nl.atlas.gallery.featureaiedit.provider.hosted

/**
 * Minimal, bounded JSON reader and string encoder for hosted adapters.
 *
 * `org.json` is an Android stub in JVM unit tests and adding a Gradle dependency is gated, so
 * hosted adapters share this small parser. It accepts only RFC 8259 JSON, enforces depth and
 * size bounds, and never echoes input text in its errors (provider replies may quote prompts).
 */
object HostedJson {
    class ParseException internal constructor() : Exception("Malformed JSON")

    private const val MAX_DEPTH = 64

    /** Returns Map<String, Any?>, List<Any?>, String, Double/Long, Boolean or null. */
    fun parse(text: String): Any? {
        val reader = Reader(text)
        reader.skipWhitespace()
        val value = reader.readValue(0)
        reader.skipWhitespace()
        if (!reader.atEnd()) throw ParseException()
        return value
    }

    fun parseOrNull(bytes: ByteArray): Any? = try {
        parse(bytes.toString(Charsets.UTF_8))
    } catch (_: ParseException) {
        null
    } catch (_: StackOverflowError) {
        null
    }

    /** JSON string literal, including the surrounding quotes. */
    fun quote(value: String): String {
        val out = StringBuilder(value.length + 2)
        out.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000c' -> out.append("\\f")
                else -> if (ch < ' ' || ch == ' ' || ch == ' ') {
                    out.append("\\u").append(String.format("%04x", ch.code))
                } else {
                    out.append(ch)
                }
            }
        }
        out.append('"')
        return out.toString()
    }

    private class Reader(private val s: String) {
        private var i = 0

        fun atEnd() = i >= s.length

        fun skipWhitespace() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        fun readValue(depth: Int): Any? {
            if (depth > MAX_DEPTH || atEnd()) throw ParseException()
            return when (s[i]) {
                '{' -> readObject(depth + 1)
                '[' -> readArray(depth + 1)
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> readNumber()
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, i)) throw ParseException()
            i += word.length
            return value
        }

        private fun readObject(depth: Int): Map<String, Any?> {
            i++ // {
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (!atEnd() && s[i] == '}') { i++; return result }
            while (true) {
                skipWhitespace()
                if (atEnd() || s[i] != '"') throw ParseException()
                val key = readString()
                skipWhitespace()
                if (atEnd() || s[i] != ':') throw ParseException()
                i++
                skipWhitespace()
                result[key] = readValue(depth)
                skipWhitespace()
                if (atEnd()) throw ParseException()
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return result }
                    else -> throw ParseException()
                }
            }
        }

        private fun readArray(depth: Int): List<Any?> {
            i++ // [
            val result = ArrayList<Any?>()
            skipWhitespace()
            if (!atEnd() && s[i] == ']') { i++; return result }
            while (true) {
                skipWhitespace()
                result.add(readValue(depth))
                skipWhitespace()
                if (atEnd()) throw ParseException()
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return result }
                    else -> throw ParseException()
                }
            }
        }

        private fun readString(): String {
            i++ // opening quote
            val out = StringBuilder()
            while (true) {
                if (atEnd()) throw ParseException()
                val ch = s[i++]
                when {
                    ch == '"' -> return out.toString()
                    ch == '\\' -> {
                        if (atEnd()) throw ParseException()
                        when (s[i++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000c')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw ParseException()
                                val code = s.substring(i, i + 4).toIntOrNull(16) ?: throw ParseException()
                                out.append(code.toChar())
                                i += 4
                            }
                            else -> throw ParseException()
                        }
                    }
                    ch < ' ' -> throw ParseException()
                    else -> out.append(ch)
                }
            }
        }

        private fun readNumber(): Any {
            val start = i
            if (i < s.length && s[i] == '-') i++
            if (i >= s.length || !s[i].isDigit()) throw ParseException()
            if (s[i] == '0') i++ else while (i < s.length && s[i].isDigit()) i++
            var integral = true
            if (i < s.length && s[i] == '.') {
                integral = false
                i++
                if (i >= s.length || !s[i].isDigit()) throw ParseException()
                while (i < s.length && s[i].isDigit()) i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                integral = false
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                if (i >= s.length || !s[i].isDigit()) throw ParseException()
                while (i < s.length && s[i].isDigit()) i++
            }
            val text = s.substring(start, i)
            return if (integral) text.toLongOrNull() ?: text.toDouble() else text.toDouble()
        }
    }
}

/** Typed accessors that return null on any shape mismatch instead of throwing. */
@Suppress("UNCHECKED_CAST")
internal fun Any?.obj(): Map<String, Any?>? = this as? Map<String, Any?>
internal fun Any?.arr(): List<Any?>? = this as? List<Any?>
internal fun Any?.str(): String? = this as? String
internal fun Any?.bool(): Boolean? = this as? Boolean
internal fun Any?.num(): Number? = this as? Number
