package nl.atlas.gallery.featureaiedit.save

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/**
 * AIE-26 / #458: the portable "this was edited with AI" label embedded in a
 * saved AI edit. Pure byte transforms with no Android or Gradle dependency, so
 * they are JVM-testable; [AiEditSaver] applies them to the freshly encoded,
 * still-pending copy after the EXIF allowlist is written.
 *
 * The packet carries only the IPTC Digital Source Type and the creator tool.
 * No prompt, provider, person, place or source URI is embedded: those stay in
 * the private lineage store. This is not a C2PA manifest (see SAVE_POLICY.md).
 */
internal object AiEditXmp {
    /** IPTC NewsCodes: AI-generated content composited into an existing photo. */
    const val DIGITAL_SOURCE_TYPE =
        "http://cv.iptc.org/newscodes/digitalsourcetype/compositeWithTrainedAlgorithmicMedia"
    const val CREATOR_TOOL = "ATLAS AI Edit"

    val packet: String = buildString {
        append("<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>")
        append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">")
        append("<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">")
        append("<rdf:Description rdf:about=\"\"")
        append(" xmlns:Iptc4xmpExt=\"http://iptc.org/std/Iptc4xmpExt/2008-02-29/\"")
        append(" xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\"")
        append(" Iptc4xmpExt:DigitalSourceType=\"").append(DIGITAL_SOURCE_TYPE).append('"')
        append(" xmp:CreatorTool=\"").append(CREATOR_TOOL).append("\"/>")
        append("</rdf:RDF></x:xmpmeta><?xpacket end=\"r\"?>")
    }

    private val jpegNamespace = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII)
    private const val PNG_KEYWORD = "XML:com.adobe.xmp"
    private val pngSignature = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private const val VP8X_XMP_FLAG = 0x04
    private const val VP8X_ALPHA_FLAG = 0x10

    /** Returns [image] with the packet in the format's standard XMP location. */
    fun embed(image: ByteArray, format: AiEditImageFormat, xmp: String = packet): ByteArray {
        check(read(image, format) == null) { "Saved AI edit already carries XMP" }
        val bytes = xmp.toByteArray(Charsets.UTF_8)
        return when (format) {
            AiEditImageFormat.JPEG -> jpeg(image, bytes)
            AiEditImageFormat.PNG -> png(image, bytes)
            AiEditImageFormat.WEBP -> webp(image, bytes)
        }
    }

    /** The embedded XMP packet, or null; reads only the standard location. */
    fun read(image: ByteArray, format: AiEditImageFormat): String? = when (format) {
        AiEditImageFormat.JPEG -> jpegSegments(image).firstOrNull { (start, end) ->
            end - start >= jpegNamespace.size && startsWith(image, start, jpegNamespace)
        }?.let { (start, end) ->
            String(image, start + jpegNamespace.size, end - start - jpegNamespace.size,
                Charsets.UTF_8)
        }
        AiEditImageFormat.PNG -> pngChunks(image).firstOrNull { it.type == "iTXt" &&
            startsWith(image, it.start, (PNG_KEYWORD + "\u0000").toByteArray(Charsets.US_ASCII))
        }?.let { chunk ->
            // keyword\0 compression(0) method(0) language\0 translated\0 text
            var at = chunk.start + PNG_KEYWORD.length + 1
            check(image[at] == 0.toByte()) { "Compressed iTXt XMP is not written by ATLAS" }
            at += 2
            repeat(2) { while (image[at] != 0.toByte()) at++; at++ }
            String(image, at, chunk.end - at, Charsets.UTF_8)
        }
        AiEditImageFormat.WEBP -> webpChunks(image).firstOrNull { it.type == "XMP " }
            ?.let { String(image, it.start, it.end - it.start, Charsets.UTF_8) }
    }

    // --- JPEG: APP1 "http://ns.adobe.com/xap/1.0/" after SOI, JFIF and Exif ---

    /** Payload ranges (after the length field) of the leading APPn segments. */
    private fun jpegSegments(image: ByteArray): List<Pair<Int, Int>> {
        require(image.size > 4 && u8(image, 0) == 0xFF && u8(image, 1) == 0xD8) { "Not a JPEG" }
        val out = ArrayList<Pair<Int, Int>>()
        var at = 2
        while (at + 4 <= image.size && u8(image, at) == 0xFF &&
            u8(image, at + 1) in 0xE0..0xEF) {
            val length = u16be(image, at + 2)
            require(length >= 2 && at + 2 + length <= image.size) { "Malformed JPEG segment" }
            out += (at + 4) to (at + 2 + length)
            at += 2 + length
        }
        return out
    }

    private fun jpeg(image: ByteArray, xmp: ByteArray): ByteArray {
        val payload = jpegNamespace + xmp
        require(payload.size + 2 <= 0xFFFF) { "XMP packet too large for one APP1 segment" }
        // Insert after the leading APP0/APP1 block: JFIF must stay first, and
        // Exif conventionally precedes XMP.
        val insertAt = jpegSegments(image).lastOrNull()?.second ?: 2
        val out = ByteArrayOutputStream(image.size + payload.size + 4)
        out.write(image, 0, insertAt)
        out.write(0xFF); out.write(0xE1)
        out.write((payload.size + 2) shr 8); out.write((payload.size + 2) and 0xFF)
        out.write(payload)
        out.write(image, insertAt, image.size - insertAt)
        return out.toByteArray()
    }

    // --- PNG: iTXt "XML:com.adobe.xmp", uncompressed, right after IHDR ---

    private class Chunk(val type: String, val headerAt: Int, val start: Int, val end: Int)

    private fun pngChunks(image: ByteArray): List<Chunk> {
        require(startsWith(image, 0, pngSignature)) { "Not a PNG" }
        val out = ArrayList<Chunk>()
        var at = pngSignature.size
        while (at + 12 <= image.size) {
            val length = u32be(image, at)
            require(length in 0..(image.size - at - 12)) { "Malformed PNG chunk" }
            val type = String(image, at + 4, 4, Charsets.US_ASCII)
            out += Chunk(type, at, at + 8, at + 8 + length.toInt())
            at += 12 + length.toInt()
            if (type == "IEND") break
        }
        return out
    }

    private fun png(image: ByteArray, xmp: ByteArray): ByteArray {
        val ihdr = pngChunks(image).firstOrNull()?.takeIf { it.type == "IHDR" }
            ?: error("PNG does not start with IHDR")
        val data = ByteArrayOutputStream().apply {
            write(PNG_KEYWORD.toByteArray(Charsets.US_ASCII)); write(0)
            write(0); write(0) // not compressed, method 0
            write(0) // empty language tag
            write(0) // empty translated keyword
            write(xmp)
        }.toByteArray()
        val type = "iTXt".toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply { update(type); update(data) }.value
        val insertAt = ihdr.end + 4
        val out = ByteArrayOutputStream(image.size + data.size + 12)
        out.write(image, 0, insertAt)
        writeU32be(out, data.size.toLong()); out.write(type); out.write(data)
        writeU32be(out, crc)
        out.write(image, insertAt, image.size - insertAt)
        return out.toByteArray()
    }

    // --- WebP: extended format (VP8X) with the XMP flag and a trailing "XMP " chunk ---

    private fun webpChunks(image: ByteArray): List<Chunk> {
        require(image.size >= 20 && String(image, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(image, 8, 4, Charsets.US_ASCII) == "WEBP") { "Not a WebP" }
        val out = ArrayList<Chunk>()
        var at = 12
        while (at + 8 <= image.size) {
            val size = u32le(image, at + 4)
            require(size in 0..(image.size - at - 8)) { "Malformed WebP chunk" }
            out += Chunk(String(image, at, 4, Charsets.US_ASCII), at, at + 8, at + 8 + size.toInt())
            at += 8 + size.toInt() + (size.toInt() and 1)
        }
        return out
    }

    private fun webp(image: ByteArray, xmp: ByteArray): ByteArray {
        val chunks = webpChunks(image)
        val out = ByteArrayOutputStream(image.size + xmp.size + 32)
        out.write(image, 0, 12)
        val vp8x = chunks.firstOrNull()?.takeIf { it.type == "VP8X" }
        if (vp8x != null) {
            require(vp8x.end - vp8x.start == 10) { "Malformed VP8X chunk" }
            out.write(image, 12, image.size - 12)
        } else {
            // Simple lossless file (what Bitmap.compress WEBP_LOSSLESS writes
            // when no EXIF was added): promote to the extended layout.
            val vp8l = chunks.singleOrNull()?.takeIf { it.type == "VP8L" }
                ?: error("Only simple lossless or extended WebP can carry XMP here")
            require(vp8l.end - vp8l.start >= 5 && u8(image, vp8l.start) == 0x2F) {
                "Malformed VP8L header"
            }
            val bits = u32le(image, vp8l.start + 1)
            val width = (bits and 0x3FFF) + 1
            val height = ((bits shr 14) and 0x3FFF) + 1
            val alpha = (bits shr 28) and 1L
            out.write("VP8X".toByteArray(Charsets.US_ASCII)); writeU32le(out, 10)
            out.write(if (alpha == 1L) VP8X_ALPHA_FLAG else 0)
            out.write(0); out.write(0); out.write(0)
            writeU24le(out, width - 1); writeU24le(out, height - 1)
            out.write(image, 12, image.size - 12)
        }
        val body = out.toByteArray()
        // The VP8X chunk now sits at offset 12; its flags byte is at 20.
        body[20] = (body[20].toInt() or VP8X_XMP_FLAG).toByte()
        val result = ByteArrayOutputStream(body.size + xmp.size + 9)
        result.write(body)
        // The previous last chunk may lack its pad byte only if the file was
        // malformed; webpChunks already rejected that.
        result.write("XMP ".toByteArray(Charsets.US_ASCII))
        writeU32le(result, xmp.size.toLong()); result.write(xmp)
        if (xmp.size and 1 == 1) result.write(0)
        val final = result.toByteArray()
        val riff = (final.size - 8).toLong()
        for (i in 0 until 4) final[4 + i] = ((riff shr (8 * i)) and 0xFF).toByte()
        return final
    }

    // --- byte helpers ---

    private fun u8(b: ByteArray, at: Int) = b[at].toInt() and 0xFF
    private fun u16be(b: ByteArray, at: Int) = (u8(b, at) shl 8) or u8(b, at + 1)
    private fun u32be(b: ByteArray, at: Int): Long =
        (u8(b, at).toLong() shl 24) or (u8(b, at + 1).toLong() shl 16) or
            (u8(b, at + 2).toLong() shl 8) or u8(b, at + 3).toLong()
    private fun u32le(b: ByteArray, at: Int): Long =
        u8(b, at).toLong() or (u8(b, at + 1).toLong() shl 8) or
            (u8(b, at + 2).toLong() shl 16) or (u8(b, at + 3).toLong() shl 24)
    private fun startsWith(b: ByteArray, at: Int, prefix: ByteArray): Boolean =
        at + prefix.size <= b.size && prefix.indices.all { b[at + it] == prefix[it] }
    private fun writeU32be(out: ByteArrayOutputStream, v: Long) {
        for (shift in intArrayOf(24, 16, 8, 0)) out.write(((v shr shift) and 0xFF).toInt())
    }
    private fun writeU32le(out: ByteArrayOutputStream, v: Long) {
        for (shift in intArrayOf(0, 8, 16, 24)) out.write(((v shr shift) and 0xFF).toInt())
    }
    private fun writeU24le(out: ByteArrayOutputStream, v: Long) {
        for (shift in intArrayOf(0, 8, 16)) out.write(((v shr shift) and 0xFF).toInt())
    }
}
