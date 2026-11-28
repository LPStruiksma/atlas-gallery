package nl.atlas.gallery.featureeditor.save

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

/** EDIT-3 (#633): JPEG XMP carry-over and motion-photo rules on synthetic bytes only. */
class JpegMetadataTest {
    /** Segment skeleton of a JPEG (SOI, JFIF, DQT, SOS, entropy bytes, EOI); real decoding is emulator-tested. */
    private fun jpeg(): ByteArray = ByteArrayOutputStream().apply {
        write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        val jfif = "JFIF\u0000".toByteArray() + byteArrayOf(1, 1, 0, 0, 1, 0, 1, 0, 0)
        write(0xFF); write(0xE0); write(0); write(jfif.size + 2); write(jfif)
        val dqt = ByteArray(65) { if (it == 0) 0 else 1 }
        write(0xFF); write(0xDB); write(0); write(dqt.size + 2); write(dqt)
        val sos = byteArrayOf(1, 1, 0, 0, 63, 0)
        write(0xFF); write(0xDA); write(0); write(sos.size + 2); write(sos)
        write(ByteArray(40) { (it * 13 + 1).toByte() })
        write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
    }.toByteArray()

    private fun clip(size: Int = 64): ByteArray = ByteArray(size) { (it * 7).toByte() }.also {
        it[0] = 0; it[1] = 0; it[2] = 0; it[3] = 0x18
        "ftypmp42".toByteArray().copyInto(it, 4)
    }

    private val descriptive = "<rdf:Description rdf:about=\"\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\"" +
        " xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\" xmp:Rating=\"4\">" +
        "<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">Synthetic title</rdf:li></rdf:Alt></dc:title>" +
        "</rdf:Description>"

    private fun motionPhoto(clip: ByteArray, extra: String = descriptive): ByteArray {
        val body = extra + JpegMetadata.motionDescription(clip.size, 123456L, withDirectory = true)
        return JpegMetadata.insertXmp(jpeg(), JpegMetadata.packet(body))!! + clip
    }

    @Test fun insertsReadsAndReplacesXmpWithoutTouchingScanData() {
        val plain = jpeg()
        assertNull(JpegMetadata.readXmp(plain))
        val packet = JpegMetadata.packet(descriptive)
        val with = JpegMetadata.insertXmp(plain, packet)!!
        assertEquals(packet, JpegMetadata.readXmp(with))
        val replaced = JpegMetadata.replaceXmp(with, JpegMetadata.packet(""))!!
        assertEquals(JpegMetadata.packet(""), JpegMetadata.readXmp(replaced))
        // Scan data after the header is byte-identical.
        val tail = plain.size - JpegMetadata.segments(plain).last().end
        assertArrayEquals(plain.copyOfRange(plain.size - tail, plain.size),
            replaced.copyOfRange(replaced.size - tail, replaced.size))
    }

    @Test fun refusesToGrowXmpThatSitsAfterAnMpfSegment() {
        val plain = jpeg()
        // Hand-build SOI, APP2 "MPF\0..." then APP1 XMP, then the rest of a real JPEG.
        val mpfPayload = "MPF\u0000II*\u0000".toByteArray(Charsets.ISO_8859_1)
        val withXmp = JpegMetadata.insertXmp(plain, JpegMetadata.packet(""))!!
        val out = ByteArrayOutputStream()
        out.write(withXmp, 0, 2)
        out.write(0xFF); out.write(0xE2); out.write(0); out.write(mpfPayload.size + 2); out.write(mpfPayload)
        out.write(withXmp, 2, withXmp.size - 2)
        val bytes = out.toByteArray()
        assertNotNull(JpegMetadata.readXmp(bytes))
        assertNull("offsets after MPF must not move", JpegMetadata.replaceXmp(bytes, JpegMetadata.packet(descriptive)))
    }

    @Test fun sanitizeKeepsDescriptiveFieldsAndDropsOldLayout() {
        val source = JpegMetadata.packet(
            "<rdf:Description rdf:about=\"\" xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\"" +
                " xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\" hdrgm:Version=\"1.0\"" +
                " GCamera:MotionPhoto=\"1\" GCamera:MotionPhotoVersion=\"1\" GCamera:MicroVideoOffset=\"99\"" +
                " GCamera:SpecialTypeID=\"keep-me\"><Container:Directory><rdf:Seq><rdf:li>" +
                "<Container:Item Item:Semantic=\"GainMap\" Item:Length=\"10\"/></rdf:li></rdf:Seq>" +
                "</Container:Directory><hdrgm:GainMapMax>2</hdrgm:GainMapMax></rdf:Description>" + descriptive)
        val clean = JpegMetadata.sanitizeForCarry(source)
        for (gone in listOf("hdrgm:Version", "MotionPhoto=", "MicroVideoOffset", "Container:Directory",
            "Item:Length", "GainMapMax")) assertFalse(gone, clean.contains(gone))
        for (kept in listOf("Synthetic title", "xmp:Rating=\"4\"", "GCamera:SpecialTypeID=\"keep-me\""))
            assertTrue(kept, clean.contains(kept))
        assertTrue(JpegMetadata.descriptions(clean)!!.contains("Synthetic title"))
    }

    @Test fun recognisesGoogleMotionPhotoAndVerifiesTheClipOffset() {
        val c = clip(80)
        val motion = JpegMetadata.motion(motionPhoto(c))
        assertEquals(JpegMetadata.Motion.Clip(80, 123456L), motion)
        assertEquals(JpegMetadata.Motion.None, JpegMetadata.motion(jpeg()))
        // A wrong length does not land on an ftyp box: unsupported, never a guessed clip.
        val wrong = motionPhoto(c).let { it.copyOf(it.size - 1) }
        assertTrue(JpegMetadata.motion(wrong) is JpegMetadata.Motion.Unsupported)
    }

    @Test fun recognisesLegacyMicroVideoAndSamsungTrailer() {
        val c = clip(48)
        val legacy = JpegMetadata.insertXmp(jpeg(), JpegMetadata.packet(
            "<rdf:Description rdf:about=\"\" xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\"" +
                " GCamera:MicroVideo=\"1\" GCamera:MicroVideoVersion=\"1\" GCamera:MicroVideoOffset=\"48\"/>"))!! + c
        assertEquals(JpegMetadata.Motion.Clip(48, -1L), JpegMetadata.motion(legacy))
        val samsung = jpeg() + "MotionPhoto_Data".toByteArray() + c
        assertTrue(JpegMetadata.motion(samsung) is JpegMetadata.Motion.Unsupported)
    }

    @Test fun motionDescriptorRoundTripsOnARebuiltFile() {
        val c = clip(72)
        val packet = JpegMetadata.addMotionToPacket(JpegMetadata.packet(descriptive), c.size, 555L)!!
        val rebuilt = JpegMetadata.insertXmp(jpeg(), packet)!! + c
        assertEquals(JpegMetadata.Motion.Clip(72, 555L), JpegMetadata.motion(rebuilt))
        assertTrue(JpegMetadata.readXmp(rebuilt)!!.contains("Synthetic title"))
    }

    @Test fun motionJoinsAnEncoderDirectoryInsteadOfAddingASecondOne() {
        val encoder = JpegMetadata.packet(
            "<rdf:Description rdf:about=\"\" xmlns:Container=\"http://ns.google.com/photos/1.0/container/\"" +
                " xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\"" +
                " xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\" hdrgm:Version=\"1.0\">" +
                "<Container:Directory><rdf:Seq>" +
                "<rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Semantic=\"Primary\" Item:Mime=\"image/jpeg\"/></rdf:li>" +
                "<rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Semantic=\"GainMap\" Item:Mime=\"image/jpeg\" Item:Length=\"900\"/></rdf:li>" +
                "</rdf:Seq></Container:Directory></rdf:Description>")
        val merged = JpegMetadata.addMotionToPacket(encoder, 64, -1L)!!
        assertEquals(1, Regex("<Container:Directory").findAll(merged).count())
        assertTrue(merged.indexOf("Item:Semantic=\"GainMap\"") < merged.indexOf("Item:Semantic=\"MotionPhoto\""))
        assertTrue(merged.contains("GCamera:MotionPhoto=\"1\""))
        assertTrue(merged.contains("hdrgm:Version=\"1.0\""))
        // An unknown directory prefix: refuse rather than write two directories.
        assertNull(JpegMetadata.addMotionToPacket(encoder.replace("Container:Directory", "C2:Directory"), 64, -1L))
    }
}
