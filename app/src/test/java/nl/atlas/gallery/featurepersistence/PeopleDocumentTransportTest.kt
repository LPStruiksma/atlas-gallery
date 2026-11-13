package nl.atlas.gallery.featurepersistence

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/** Hostile SAF-provider fixtures, no Android, media data or network involved. */
class PeopleDocumentTransportTest {
    private val row = PeopleBackupRow(
        1, 11, "Fictional", "/storage/emulated/0/DCIM/fictional.jpg"
    )
    private fun doc(): ByteArray = PeopleBackupCodec.encode(listOf(row))

    @Test fun chunkedReadPreservesFragmentsAndExactMaxBytes() {
        val expected = ByteArray(4 * 1024 * 1024 + 19) { i ->
            (i * 17 + 91).toByte()
        }
        val source = object : ByteArrayInputStream(expected) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                super.read(buffer, offset, minOf(length, 137))
        }
        assertArrayEquals(expected, PeopleDocumentTransport.readBounded(
            source, maxBytes = expected.size))
        expectFailure {
            PeopleDocumentTransport.readBounded(
                ByteArrayInputStream(expected), maxBytes = expected.size - 1)
        }
    }

    @Test fun chunkedReadHandlesManySubBufferBoundariesAndZeroProgress() {
        val expected = ByteArray(5 * 32 * 1024 + 1) { i -> i.toByte() }
        val source = object : ByteArrayInputStream(expected) {
            var readCount = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (++readCount % 9 == 0) return 0
                return super.read(buffer, offset, minOf(length, 503))
            }
        }
        assertArrayEquals(expected, PeopleDocumentTransport.readBounded(source))
        assertArrayEquals(byteArrayOf(),
            PeopleDocumentTransport.readBounded(ByteArrayInputStream(byteArrayOf())))
    }

    @Test fun writeAndReadBackOnlyAcceptsCompleteProviderPersistence() {
        val original = doc()
        var persisted = byteArrayOf()
        PeopleDocumentTransport.writeAndReadBack(
            original,
            openOutput = {
                object : ByteArrayOutputStream() {
                    override fun close() {
                        persisted = toByteArray()
                        super.close()
                    }
                }
            },
            openInput = { ByteArrayInputStream(persisted) }
        )
        assertArrayEquals(original, persisted)
    }

    @Test fun providerThatSilentlyTruncatesCannotProduceSuccessReceipt() {
        val original = doc()
        val stored = ByteArrayOutputStream()
        expectFailure {
            PeopleDocumentTransport.writeAndReadBack(
                original,
                openOutput = {
                    object : OutputStream() {
                        override fun write(value: Int) {
                            if (stored.size() < original.size / 2) stored.write(value)
                        }
                    }
                },
                openInput = { ByteArrayInputStream(stored.toByteArray()) }
            )
        }
        assertTrue(stored.size() < original.size)
    }

    @Test fun streamingReadbackHandlesLargeFictionalDocumentInSmallChunks() {
        val expected = ByteArray(4 * 1024 * 1024 + 37) { index ->
            (index * 31 + 7).toByte()
        }
        var stored = byteArrayOf()
        PeopleDocumentTransport.writeAndReadBack(
            expected,
            openOutput = {
                object : ByteArrayOutputStream() {
                    override fun close() {
                        stored = toByteArray()
                        super.close()
                    }
                }
            },
            openInput = {
                object : ByteArrayInputStream(stored) {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                        super.read(buffer, offset, minOf(length, 137))
                }
            }
        )
        assertArrayEquals(expected, stored)
    }

    @Test fun streamingReadbackRefusesCorruptOrOverlongProviderFiles() {
        val expected = doc()
        val different = expected.clone().also { bytes ->
            bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 1).toByte()
        }
        for (actual in listOf(
            different, expected.copyOf(expected.size - 1),
            expected + byteArrayOf(0x55)
        )) {
            expectFailure {
                PeopleDocumentTransport.writeAndReadBack(
                    expected,
                    openOutput = { ByteArrayOutputStream() },
                    openInput = { ByteArrayInputStream(actual) }
                )
            }
        }
    }

    @Test fun streamingReadbackRejectsPermanentZeroProgress() {
        expectFailure {
            PeopleDocumentTransport.writeAndReadBack(
                doc(),
                openOutput = { ByteArrayOutputStream() },
                openInput = {
                    object : java.io.InputStream() {
                        override fun read(): Int = 0
                        override fun read(bytes: ByteArray, offset: Int, length: Int): Int = 0
                    }
                }
            )
        }
    }

    @Test fun changedSourceBetweenPreviewAndUserApprovalIsRefused() {
        val original = doc()
        val expected = PeopleBackupCodec.digestHex(original)
        assertArrayEquals(
            original,
            PeopleDocumentTransport.approvedBytes(expected) { ByteArrayInputStream(original) }
        )
        val changed = PeopleBackupCodec.encode(listOf(row.copy(personName = "Other Fictional")))
        expectFailure {
            PeopleDocumentTransport.approvedBytes(expected) { ByteArrayInputStream(changed) }
        }
        expectFailure { PeopleDocumentTransport.approvedBytes(expected) { null } }
    }

    @Test fun boundedReadRejectsOversizedAndUnreadableDocument() {
        val payload = byteArrayOf(1, 2, 3, 4, 5, 6)
        assertArrayEquals(payload,
            PeopleDocumentTransport.readBounded(ByteArrayInputStream(payload), maxBytes = 6))
        expectFailure {
            PeopleDocumentTransport.readBounded(ByteArrayInputStream(payload), maxBytes = 5)
        }
        expectFailure {
            PeopleDocumentTransport.readBounded(object : java.io.InputStream() {
                override fun read(): Int = 0
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int = 0
            })
        }
        expectFailure {
            PeopleDocumentTransport.writeAndReadBack(doc(),
                openOutput = { null }, openInput = { ByteArrayInputStream(doc()) })
        }
    }

    private fun expectFailure(block: () -> Unit) {
        try {
            block()
            fail("Unsafe document operation was allowed")
        } catch (_: IllegalArgumentException) {
        } catch (_: IllegalStateException) {
        }
    }
}
