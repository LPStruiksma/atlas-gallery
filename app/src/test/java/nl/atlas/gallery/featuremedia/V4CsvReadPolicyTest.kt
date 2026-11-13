package nl.atlas.gallery

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class V4CsvReadPolicyTest {
    @Test fun readableUnicodeAndExactByteBoundaryPreserveWholeDocument() {
        val csv = "person_id,person_name,image_path,face_id,confidence\r\n" +
            "1,Éloïse,/storage/emulated/0/DCIM/img.jpg,11,0.99\r\n"
        val bytes = csv.toByteArray(Charsets.UTF_8)
        assertEquals(csv, readBoundedV4Csv(ByteArrayInputStream(bytes), bytes.size))
        assertEquals(csv, readBoundedV4Csv(ByteArrayInputStream(bytes)))
        assertEquals("", readBoundedV4Csv(ByteArrayInputStream(byteArrayOf())))
    }

    @Test fun oneByteBeyondQuotaRejectsAndClosesProvider() {
        var wasClosed = false
        val stream = object : ByteArrayInputStream("0123456789A".toByteArray()) {
            override fun close() { wasClosed = true; super.close() }
        }
        try {
            readBoundedV4Csv(stream, 10)
            fail("Overlong CSV must not enter People transaction")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("size limit"))
        }
        assertTrue(wasClosed)
        assertEquals("0123456789", readBoundedV4Csv(
            ByteArrayInputStream("0123456789".toByteArray()), 10))
    }

    @Test fun malformedUtf8RejectsRatherThanChangingReviewedIdentity() {
        try {
            readBoundedV4Csv(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28)))
            fail("Invalid UTF-8 must never become replacement characters")
        } catch (expected: java.nio.charset.MalformedInputException) {
            assertTrue(expected.inputLength > 0)
        }
    }

    @Test fun cancelledLargeDocumentClosesProviderWithoutProducingPartialCsv() {
        val contents = ByteArray(800_000) { 'x'.code.toByte() }
        var wasClosed = false
        val stream = object : ByteArrayInputStream(contents) {
            override fun close() { wasClosed = true; super.close() }
        }
        val stop = IllegalStateException("newer V4 choice superseded import")
        var checks = 0
        try {
            readBoundedV4Csv(stream) {
                if (++checks == 4) throw stop
            }
            fail("Cancelled read must not succeed")
        } catch (caught: IllegalStateException) {
            assertSame(stop, caught)
        }
        assertEquals(4, checks)
        assertTrue(wasClosed)
    }

    @Test fun providerWithRepeatedZeroByteReadsCannotSpinIndefinitely() {
        var wasClosed = false
        val stalled = object : InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int = 0
            override fun close() { wasClosed = true }
        }
        try {
            readBoundedV4Csv(stalled)
            fail("Stalled provider was accepted")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("progress") ||
                expected.message!!.contains("zero", ignoreCase = true))
        }
        assertTrue(wasClosed)
    }

    @Test fun oneHundredFiftyThousandFictionalAssignmentsRemainComplete() {
        val csv = buildString(16_000_000) {
            append("person_id,person_name,image_path,face_id,confidence\r\n")
            for (row in 0 until 150_000) {
                append(row).append(",Éloïse,/storage/emulated/0/DCIM/FICTION/img_")
                    .append(row).append(".jpg,").append(row).append(",0.99\r\n")
            }
        }
        val bytes = csv.toByteArray(Charsets.UTF_8)
        assertTrue(bytes.size < MAX_V4_CSV_BYTES)
        val restored = readBoundedV4Csv(ByteArrayInputStream(bytes))
        assertEquals(csv.length, restored.length)
        assertEquals(csv, restored)
    }

    @Test fun limitMustBeWithinSafeRange() {
        for (limit in listOf(0, -1, MAX_V4_CSV_BYTES + 1)) {
            try {
                readBoundedV4Csv(ByteArrayInputStream(byteArrayOf(1)), limit)
                fail("Unsafe size limit accepted")
            } catch (expected: IllegalArgumentException) { }
        }
    }
}
