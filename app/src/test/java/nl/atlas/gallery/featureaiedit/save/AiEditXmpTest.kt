package nl.atlas.gallery.featureaiedit.save

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/**
 * #458: JVM proof of the XMP byte transforms on hand-built container
 * skeletons (unit tests compile against android.jar, so no desktop codecs).
 * Real encode → label → decode → readback runs in AiEditSaverAndroidTest.
 */
class AiEditXmpTest {
    private val jfif = byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10) +
        "JFIF".toByteArray() + byteArrayOf(0, 1, 1, 0, 0, 1, 0, 1, 0, 0)
    /** SOI, JFIF APP0, then a stand-in for the coded image and EOI. */
    private val jpegBody = byteArrayOf(0xFF.toByte(), 0xDB.toByte(), 0x00, 0x04, 7, 9,
        0xFF.toByte(), 0xD9.toByte())

    private fun encoded(type: String): ByteArray = when (type) {
        "jpg" -> byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + jfif + jpegBody
        "png" -> {
            fun chunk(t: String, data: ByteArray): ByteArray {
                val out = ByteArrayOutputStream()
                val crc = CRC32().apply { update(t.toByteArray()); update(data) }.value
                out.write(be32(data.size.toLong())); out.write(t.toByteArray()); out.write(data)
                out.write(be32(crc)); return out.toByteArray()
            }
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
                chunk("IHDR", be32(12) + be32(8) + byteArrayOf(8, 2, 0, 0, 0)) +
                chunk("IDAT", byteArrayOf(1, 2, 3, 4)) + chunk("IEND", ByteArray(0))
        }
        else -> error(type)
    }

    private fun be32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(),
        (v shr 8).toByte(), v.toByte())

    /** Everything after the inserted XMP is the untouched original tail. */
    private fun keepsTail(source: ByteArray, labelled: ByteArray, tailFrom: Int) {
        val tail = source.copyOfRange(tailFrom, source.size)
        assertArrayEquals(tail, labelled.copyOfRange(labelled.size - tail.size, labelled.size))
        assertArrayEquals(source.copyOfRange(0, tailFrom), labelled.copyOfRange(0, tailFrom))
    }

    @Test fun packetCarriesOnlyDigitalSourceTypeAndCreatorTool() {
        val xmp = AiEditXmp.packet
        assertTrue(xmp.contains("Iptc4xmpExt:DigitalSourceType=\"" +
            "http://cv.iptc.org/newscodes/digitalsourcetype/compositeWithTrainedAlgorithmicMedia\""))
        assertTrue(xmp.contains("xmp:CreatorTool=\"ATLAS AI Edit\""))
        for (forbidden in listOf("prompt", "content://", "GPS", "c2pa", "Person"))
            assertFalse(forbidden, xmp.contains(forbidden, ignoreCase = true))
    }

    @Test fun jpegGetsStandardApp1XmpAfterJfifAndStillDecodes() {
        val source = encoded("jpg")
        assertNull(AiEditXmp.read(source, AiEditImageFormat.JPEG))
        val labelled = AiEditXmp.embed(source, AiEditImageFormat.JPEG)
        assertEquals(AiEditXmp.packet, AiEditXmp.read(labelled, AiEditImageFormat.JPEG))
        // JFIF APP0 (FFE0) stays first; XMP APP1 follows it.
        assertEquals(0xE0, labelled[3].toInt() and 0xFF)
        val app1 = labelled.indices.first {
            it > 2 && labelled[it] == 0xFF.toByte() && labelled[it + 1] == 0xE1.toByte()
        }
        assertEquals("http://ns.adobe.com/xap/1.0/",
            String(labelled, app1 + 4, 28, Charsets.US_ASCII))
        keepsTail(source, labelled, 2 + jfif.size)
    }

    @Test fun pngGetsUncompressedItxtWithValidCrcRightAfterIhdr() {
        val source = encoded("png")
        val labelled = AiEditXmp.embed(source, AiEditImageFormat.PNG)
        assertEquals(AiEditXmp.packet, AiEditXmp.read(labelled, AiEditImageFormat.PNG))
        val ihdrEnd = 8 + 8 + 13 + 4
        assertEquals("iTXt", String(labelled, ihdrEnd + 4, 4, Charsets.US_ASCII))
        val length = ByteArrayInputStream(labelled, ihdrEnd, 4).readBytes()
            .fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }.toInt()
        val crc = CRC32().apply { update(labelled, ihdrEnd + 4, 4 + length) }.value
        val stored = labelled.copyOfRange(ihdrEnd + 8 + length, ihdrEnd + 12 + length)
            .fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
        assertEquals(crc, stored)
        keepsTail(source, labelled, ihdrEnd)
    }

    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(),
        (v shr 16).toByte(), (v shr 24).toByte())

    /** A structurally valid simple lossless WebP header (payload not decoded). */
    private fun simpleLossless(width: Int, height: Int, alpha: Boolean): ByteArray {
        val bits = (width - 1) or ((height - 1) shl 14) or ((if (alpha) 1 else 0) shl 28)
        val payload = byteArrayOf(0x2F) + le32(bits) + byteArrayOf(1, 2, 3) // odd: 8 bytes
        val chunk = "VP8L".toByteArray() + le32(payload.size) + payload
        return "RIFF".toByteArray() + le32(4 + chunk.size) + "WEBP".toByteArray() + chunk
    }

    @Test fun simpleLosslessWebpIsPromotedToVp8xWithXmpFlagAndTrailingChunk() {
        val source = simpleLossless(1080, 2340, alpha = true)
        val labelled = AiEditXmp.embed(source, AiEditImageFormat.WEBP)
        assertEquals(AiEditXmp.packet, AiEditXmp.read(labelled, AiEditImageFormat.WEBP))
        assertEquals("VP8X", String(labelled, 12, 4, Charsets.US_ASCII))
        val flags = labelled[20].toInt()
        assertEquals("XMP flag", 0x04, flags and 0x04)
        assertEquals("alpha flag", 0x10, flags and 0x10)
        fun u24(at: Int) = (labelled[at].toInt() and 0xFF) or
            ((labelled[at + 1].toInt() and 0xFF) shl 8) or ((labelled[at + 2].toInt() and 0xFF) shl 16)
        assertEquals(1079, u24(24))
        assertEquals(2339, u24(27))
        val riff = (labelled[4].toInt() and 0xFF) or ((labelled[5].toInt() and 0xFF) shl 8) or
            ((labelled[6].toInt() and 0xFF) shl 16) or ((labelled[7].toInt() and 0xFF) shl 24)
        assertEquals(labelled.size - 8, riff)
        assertEquals(0, labelled.size % 2)
        // Original image chunk is preserved byte for byte after VP8X.
        val vp8l = source.copyOfRange(12, source.size)
        assertArrayEquals(vp8l, labelled.copyOfRange(30, 30 + vp8l.size))
    }

    @Test fun existingVp8xOnlyGainsTheXmpFlag() {
        val promoted = AiEditXmp.embed(simpleLossless(4, 4, alpha = false),
            AiEditImageFormat.WEBP)
        // Strip the XMP chunk and flag to simulate an EXIF-only extended file.
        val xmpAt = (12 until promoted.size).first {
            String(promoted, it, 4, Charsets.US_ASCII) == "XMP "
        }
        val extended = promoted.copyOfRange(0, xmpAt).also {
            it[20] = (it[20].toInt() and 0x04.inv()).toByte()
            val riff = le32(it.size - 8); for (i in 0..3) it[4 + i] = riff[i]
        }
        assertNull(AiEditXmp.read(extended, AiEditImageFormat.WEBP))
        val labelled = AiEditXmp.embed(extended, AiEditImageFormat.WEBP)
        assertEquals(0x04, labelled[20].toInt() and 0x04)
        assertEquals(0, labelled[20].toInt() and 0x10)
        assertEquals(1, (12 until labelled.size - 4).count {
            String(labelled, it, 4, Charsets.US_ASCII) == "VP8X"
        })
        assertEquals(AiEditXmp.packet, AiEditXmp.read(labelled, AiEditImageFormat.WEBP))
    }

    @Test fun refusesToLabelTwiceOrAWrongFormat() {
        val once = AiEditXmp.embed(encoded("jpg"), AiEditImageFormat.JPEG)
        assertThrows(IllegalStateException::class.java) {
            AiEditXmp.embed(once, AiEditImageFormat.JPEG)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AiEditXmp.embed(encoded("png"), AiEditImageFormat.JPEG)
        }
    }
}
