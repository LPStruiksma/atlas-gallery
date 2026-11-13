package nl.atlas.gallery.featurepersistence

import java.io.InputStream
import java.io.OutputStream

/**
 * Provider-neutral SAF byte transport. Any provider may be local or cloud
 * backed; it may keep a partial file after a failed write. Never call a
 * write complete until an independently reopened read matches every byte.
 *
 * Pure JVM implementation for hostile-provider fixture tests. Android picker
 * consent and document URI ownership remain in PeopleSafBackupIO/Lane I.
 */
object PeopleDocumentTransport {
    fun readBounded(input: InputStream, maxBytes: Int = PeopleBackupCodec.MAX_BYTES): ByteArray {
        require(maxBytes in 1..PeopleBackupCodec.MAX_BYTES)
        return input.use { source ->
            // Hold only the bytes actually received. A growing
            // ByteArrayOutputStream may round its capacity up to 2x and then
            // copy the entire buffer again at toByteArray(), which is
            // wasteful for a large SAF document on a bounded Android heap.
            val chunks = ArrayList<ByteArray>()
            val buffer = ByteArray(32 * 1024)
            var occupied = 0
            var received = 0
            var zeroProgressReads = 0
            while (true) {
                val size = source.read(buffer, occupied, buffer.size - occupied)
                if (size < 0) break
                if (size == 0) {
                    require(++zeroProgressReads <= 16) {
                        "Document provider made no read progress"
                    }
                    continue
                }
                zeroProgressReads = 0
                require(size <= buffer.size - occupied && received <= maxBytes - size) {
                    "Selected People backup exceeds the safe size limit"
                }
                received += size
                occupied += size
                if (occupied == buffer.size) {
                    chunks.add(buffer.copyOf())
                    occupied = 0
                }
            }
            if (occupied > 0) chunks.add(buffer.copyOf(occupied))
            val document = ByteArray(received)
            var offset = 0
            for (chunk in chunks) {
                chunk.copyInto(document, offset)
                offset += chunk.size
            }
            check(offset == received)
            document
        }
    }

    fun writeAndReadBack(
        bytes: ByteArray,
        openOutput: () -> OutputStream?,
        openInput: () -> InputStream?
    ) {
        require(bytes.size <= PeopleBackupCodec.MAX_BYTES) { "Backup exceeds size limit" }
        val output = openOutput() ?: error("Provider refused a writable stream")
        output.use {
            it.write(bytes)
            it.flush()
        }
        val input = openInput() ?: error("Provider refused to verify the saved document")
        // Compare one bounded chunk at a time. The export already holds the
        // document byte array; reading it back into a second 128 MiB buffer
        // would risk avoidable peak Android memory exhaustion.
        verifyExactReadback(bytes, input)
    }

    private fun verifyExactReadback(expected: ByteArray, input: InputStream) {
        input.use { source ->
            val buffer = ByteArray(32 * 1024)
            var offset = 0
            var zeroProgressReads = 0
            while (true) {
                val read = source.read(buffer)
                if (read < 0) {
                    require(offset == expected.size) {
                        "Backup verification failed; document was truncated"
                    }
                    return
                }
                if (read == 0) {
                    require(++zeroProgressReads <= 16) {
                        "Backup verification failed; document provider stalled"
                    }
                    continue
                }
                zeroProgressReads = 0
                require(read <= expected.size - offset) {
                    "Backup verification failed; provider appended extra bytes"
                }
                for (index in 0 until read) {
                    require(buffer[index] == expected[offset + index]) {
                        "Backup verification failed; provider changed document bytes"
                    }
                }
                offset += read
            }
        }
    }

    /**
     * Reopen at the moment of approval, not just preview. These exact bytes
     * must subsequently be passed unchanged to mergeNativeBackup.
     */
    fun approvedBytes(
        previewSha256: String,
        openInput: () -> InputStream?
    ): ByteArray {
        val bytes = readBounded(openInput() ?: error("Document is no longer readable"))
        require(previewSha256.matches(Regex("[0-9a-f]{64}")) &&
            PeopleBackupCodec.digestHex(bytes) == previewSha256) {
            "People backup changed after approval preview"
        }
        PeopleBackupCodec.decode(bytes)
        return bytes
    }
}
