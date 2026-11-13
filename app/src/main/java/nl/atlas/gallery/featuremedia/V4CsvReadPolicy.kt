package nl.atlas.gallery

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction

/** A finite upper bound for linked/manual V4 CSV from an arbitrary SAF provider. */
internal const val MAX_V4_CSV_BYTES = 64 * 1024 * 1024

/**
 * Decode one complete UTF-8 V4 export without accepting unbounded provider
 * output, silently replaced invalid UTF-8, or a stopped/cancelled import.
 * The source is always closed, including on quota/cancellation failure.
 * A bounded reusable char buffer avoids making an extra copy of all input bytes.
 *
 * This is a read limit, not a schema/identity/confirmation validator.
 * The existing V4 parser and sealed-live-sync footer still run afterwards.
 */
internal fun readBoundedV4Csv(
    input: InputStream,
    maxBytes: Int = MAX_V4_CSV_BYTES,
    cancellationCheckpoint: () -> Unit = {}
): String {
    require(maxBytes in 1..MAX_V4_CSV_BYTES) { "Invalid V4 document size limit" }
    var received = 0
    var emptyReads = 0
    val bounded = object : FilterInputStream(input) {
        override fun read(): Int {
            val result = super.read()
            if (result >= 0) {
                require(received < maxBytes) { "V4 document exceeds the safe size limit" }
                received++
            }
            return result
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            // Allow one extra byte to distinguish exact-limit EOF from an
            // overlong export. This never buffers unbounded source bytes.
            val count = super.read(buffer, offset,
                minOf(length, maxBytes - received + 1))
            if (count == 0) {
                if (++emptyReads > 16)
                    throw IOException("V4 document provider made no read progress")
            } else if (count > 0) {
                emptyReads = 0
                require(received <= maxBytes - count) {
                    "V4 document exceeds the safe size limit"
                }
                received += count
            }
            return count
        }
    }
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return InputStreamReader(bounded, decoder).use { reader ->
        val buffer = CharArray(8192)
        val result = StringBuilder()
        while (true) {
            cancellationCheckpoint()
            val chars = reader.read(buffer)
            if (chars < 0) break
            if (chars > 0) result.append(buffer, 0, chars)
        }
        cancellationCheckpoint()
        result.toString()
    }
}
