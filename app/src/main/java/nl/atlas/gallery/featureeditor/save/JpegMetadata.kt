package nl.atlas.gallery.featureeditor.save

import java.io.ByteArrayOutputStream

/**
 * EDIT-3 (#633): byte-level JPEG metadata steps of the editor save path. Pure JVM, no Android
 * types, so every rule here is unit-tested (`JpegMetadataTest`).
 *
 * - XMP: the source packet is carried over after removing the parts that describe the *old*
 *   file layout (Ultra HDR `hdrgm:`, the GContainer directory and motion-photo offsets). Those
 *   are rewritten for the new file, or dropped with it.
 * - Motion photos: the Google Motion Photo format (`GCamera:MotionPhoto` + a GContainer
 *   directory whose last item is the MP4) and its legacy `GCamera:MicroVideoOffset` form are
 *   recognised. A Samsung-only trailer (`MotionPhoto_Data`) is recognised but not rewritten.
 */
internal object JpegMetadata {
    private val xmpNamespace = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII)
    private val mpfNamespace = "MPF\u0000".toByteArray(Charsets.US_ASCII)
    private const val MAX_SEGMENT_PAYLOAD = 0xFFFF - 2

    /** One marker segment before the scan data: [payloadStart, end) excludes marker and length. */
    data class Segment(val marker: Int, val headerAt: Int, val payloadStart: Int, val end: Int)

    fun isJpeg(image: ByteArray): Boolean =
        image.size > 4 && u8(image, 0) == 0xFF && u8(image, 1) == 0xD8

    /** Marker segments from SOI up to (not including) the first SOS. */
    fun segments(image: ByteArray): List<Segment> {
        require(isJpeg(image)) { "Not a JPEG" }
        val out = ArrayList<Segment>()
        var at = 2
        while (at + 4 <= image.size && u8(image, at) == 0xFF) {
            val marker = u8(image, at + 1)
            if (marker == 0xDA || marker == 0xD9) break
            if (marker == 0xFF) { at++; continue } // fill byte
            val length = u16be(image, at + 2)
            require(length >= 2 && at + 2 + length <= image.size) { "Malformed JPEG segment" }
            out += Segment(marker, at, at + 4, at + 2 + length)
            at += 2 + length
        }
        return out
    }

    private fun xmpSegment(image: ByteArray): Segment? = segments(image).firstOrNull {
        it.marker == 0xE1 && it.end - it.payloadStart >= xmpNamespace.size &&
            startsWith(image, it.payloadStart, xmpNamespace)
    }

    /** The standard XMP packet of a JPEG, or null. */
    fun readXmp(image: ByteArray): String? = xmpSegment(image)?.let {
        String(image, it.payloadStart + xmpNamespace.size,
            it.end - it.payloadStart - xmpNamespace.size, Charsets.UTF_8)
    }

    /**
     * Replaces the existing XMP segment with [packet]. Returns null (caller keeps the original
     * bytes) when there is none, when it would not fit one segment, or when it sits after an MPF
     * segment: growing it there would shift the offsets MPF uses to find an Ultra HDR gainmap.
     */
    fun replaceXmp(image: ByteArray, packet: String): ByteArray? {
        val all = segments(image)
        val index = all.indexOfFirst {
            it.marker == 0xE1 && it.end - it.payloadStart >= xmpNamespace.size &&
                startsWith(image, it.payloadStart, xmpNamespace)
        }
        if (index < 0) return null
        val mpfBefore = all.subList(0, index).any {
            it.marker == 0xE2 && startsWith(image, it.payloadStart, mpfNamespace)
        }
        if (mpfBefore) return null
        val payload = xmpNamespace + packet.toByteArray(Charsets.UTF_8)
        if (payload.size > MAX_SEGMENT_PAYLOAD) return null
        val segment = all[index]
        val out = ByteArrayOutputStream(image.size + payload.size)
        out.write(image, 0, segment.headerAt)
        out.write(0xFF); out.write(0xE1)
        out.write((payload.size + 2) shr 8); out.write((payload.size + 2) and 0xFF)
        out.write(payload)
        out.write(image, segment.end, image.size - segment.end)
        return out.toByteArray()
    }

    /** Inserts [packet] as a new XMP segment after the leading APP0/APP1 block. */
    fun insertXmp(image: ByteArray, packet: String): ByteArray? {
        check(readXmp(image) == null) { "JPEG already carries XMP" }
        val payload = xmpNamespace + packet.toByteArray(Charsets.UTF_8)
        if (payload.size > MAX_SEGMENT_PAYLOAD) return null
        val all = segments(image)
        var insertAt = 2
        for (s in all) { if (s.marker == 0xE0 || s.marker == 0xE1) insertAt = s.end else break }
        val out = ByteArrayOutputStream(image.size + payload.size + 4)
        out.write(image, 0, insertAt)
        out.write(0xFF); out.write(0xE1)
        out.write((payload.size + 2) shr 8); out.write((payload.size + 2) and 0xFF)
        out.write(payload)
        out.write(image, insertAt, image.size - insertAt)
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- XMP carry-over

    private val layoutAttribute = Regex(
        "\\s+(?:hdrgm:\\w+|GCamera:(?:MotionPhoto\\w*|MicroVideo\\w*)|xmpNote:HasExtendedXMP)" +
            "\\s*=\\s*(\"[^\"]*\"|'[^']*')")
    private val layoutElement = Regex(
        "<(hdrgm|Container):(\\w+)\\b[^>]*?/>|<(hdrgm|Container):(\\w+)\\b[^>]*>.*?</\\3:\\4>|" +
            "<GCamera:(MotionPhoto\\w*|MicroVideo\\w*)\\b[^>]*>.*?</GCamera:\\5>",
        RegexOption.DOT_MATCHES_ALL)

    /**
     * The source packet without anything that describes the old file layout: Ultra HDR gainmap
     * metadata, the GContainer directory, motion-photo flags and offsets, and the extended-XMP
     * pointer. Titles, ratings, keywords, people/region tags and other descriptive fields stay.
     */
    fun sanitizeForCarry(packet: String): String =
        layoutElement.replace(layoutAttribute.replace(packet, ""), "")

    private val rdfBody = Regex("<rdf:RDF\\b[^>]*>(.*)</rdf:RDF>", RegexOption.DOT_MATCHES_ALL)

    /** The `rdf:Description` content of a packet, for merging into another packet. */
    fun descriptions(packet: String): String? =
        rdfBody.find(packet)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /** A minimal packet holding [body] (already-formed `rdf:Description` elements). */
    fun packet(body: String): String = buildString {
        append("<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>")
        append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">")
        append("<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">")
        append(body)
        append("</rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>")
    }

    /** [target] with [body] appended inside its `rdf:RDF`, or null when it has none. */
    fun appendDescriptions(target: String, body: String): String? {
        val close = target.lastIndexOf("</rdf:RDF>")
        if (close < 0) return null
        return target.substring(0, close) + body + target.substring(close)
    }

    // ---------------------------------------------------------------- Motion photos

    sealed class Motion {
        object None : Motion()
        /** The clip is the last [length] bytes of the file. */
        data class Clip(val length: Int, val presentationTimestampUs: Long) : Motion()
        data class Unsupported(val reason: String) : Motion()
    }

    private val motionFlag = Regex(
        "GCamera:MotionPhoto\\s*=\\s*[\"']1[\"']|<GCamera:MotionPhoto>\\s*1\\s*<")
    private val microVideoFlag = Regex(
        "GCamera:MicroVideo\\s*=\\s*[\"']1[\"']|<GCamera:MicroVideo>\\s*1\\s*<")
    private val microVideoOffset = Regex(
        "GCamera:MicroVideoOffset\\s*=\\s*[\"'](\\d+)[\"']|<GCamera:MicroVideoOffset>\\s*(\\d+)\\s*<")
    private val timestamp = Regex(
        "GCamera:MotionPhotoPresentationTimestampUs\\s*=\\s*[\"'](-?\\d+)[\"']|" +
            "<GCamera:MotionPhotoPresentationTimestampUs>\\s*(-?\\d+)\\s*<")
    private val containerItem = Regex("<Container:Item\\b[^>]*>", RegexOption.DOT_MATCHES_ALL)
    private fun attr(tag: String, name: String): String? =
        Regex("$name\\s*=\\s*[\"']([^\"']*)[\"']").find(tag)?.groupValues?.get(1)

    /** What kind of motion photo [image] is, judged from its XMP and verified against its bytes. */
    fun motion(image: ByteArray): Motion {
        val xmp = readXmp(image)
        if (xmp != null && motionFlag.containsMatchIn(xmp)) {
            val items = containerItem.findAll(xmp).map { it.value }.toList()
            val video = items.lastOrNull { attr(it, "Item:Semantic") == "MotionPhoto" }
                ?: return Motion.Unsupported("motion flag without a container item")
            if (items.last() != video) return Motion.Unsupported("motion clip is not the last item")
            val padding = attr(video, "Item:Padding")?.toIntOrNull() ?: 0
            if (padding != 0) return Motion.Unsupported("padded motion clip")
            val length = attr(video, "Item:Length")?.toIntOrNull()
                ?: return Motion.Unsupported("motion clip without a length")
            return clipAt(image, length, ts(xmp))
        }
        if (xmp != null && microVideoFlag.containsMatchIn(xmp)) {
            val m = microVideoOffset.find(xmp) ?: return Motion.Unsupported("micro video without offset")
            val length = (m.groupValues[1].ifEmpty { m.groupValues[2] }).toIntOrNull()
                ?: return Motion.Unsupported("bad micro video offset")
            return clipAt(image, length, ts(xmp))
        }
        if (indexOf(image, "MotionPhoto_Data".toByteArray(Charsets.US_ASCII)) >= 0)
            return Motion.Unsupported("Samsung motion trailer")
        return Motion.None
    }

    private fun ts(xmp: String): Long = timestamp.find(xmp)?.let {
        (it.groupValues[1].ifEmpty { it.groupValues[2] }).toLongOrNull()
    } ?: -1L

    private fun clipAt(image: ByteArray, length: Int, timestampUs: Long): Motion {
        if (length <= 8 || length >= image.size) return Motion.Unsupported("clip length out of range")
        val start = image.size - length
        if (!startsWith(image, start + 4, "ftyp".toByteArray(Charsets.US_ASCII)))
            return Motion.Unsupported("clip does not start with an MP4 ftyp box")
        return Motion.Clip(length, timestampUs)
    }

    /** Descriptor for an appended clip of [length] bytes, as a stand-alone `rdf:Description`. */
    fun motionDescription(length: Int, timestampUs: Long, withDirectory: Boolean): String = buildString {
        append("<rdf:Description rdf:about=\"\"")
        append(" xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\"")
        if (withDirectory) {
            append(" xmlns:Container=\"http://ns.google.com/photos/1.0/container/\"")
            append(" xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\"")
        }
        append(" GCamera:MotionPhoto=\"1\" GCamera:MotionPhotoVersion=\"1\"")
        append(" GCamera:MotionPhotoPresentationTimestampUs=\"").append(timestampUs).append('"')
        if (!withDirectory) { append("/>"); return@buildString }
        append("><Container:Directory><rdf:Seq>")
        append("<rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Mime=\"image/jpeg\"")
        append(" Item:Semantic=\"Primary\" Item:Length=\"0\" Item:Padding=\"0\"/></rdf:li>")
        append(motionItem(length))
        append("</rdf:Seq></Container:Directory></rdf:Description>")
    }

    private fun motionItem(length: Int) =
        "<rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Mime=\"video/mp4\"" +
            " Item:Semantic=\"MotionPhoto\" Item:Length=\"$length\" Item:Padding=\"0\"/></rdf:li>"

    /**
     * Adds a motion clip of [length] bytes to an encoder-written packet (an Ultra HDR file
     * already lists Primary and GainMap in its own directory). Returns null when the packet has
     * a directory this code does not recognise, so the caller drops the clip instead of writing
     * two directories.
     */
    fun addMotionToPacket(target: String, length: Int, timestampUs: Long): String? {
        val dir = target.indexOf("<Container:Directory")
        if (dir < 0) {
            if (target.contains(":Directory")) return null
            return appendDescriptions(target, motionDescription(length, timestampUs, withDirectory = true))
        }
        val seqClose = target.indexOf("</rdf:Seq>", dir)
        if (seqClose < 0) return null
        val withItem = target.substring(0, seqClose) + motionItem(length) + target.substring(seqClose)
        val needsItemNs = !withItem.contains("xmlns:Item=")
        val description = motionDescription(length, timestampUs, withDirectory = false).let {
            if (needsItemNs) it.replace(" xmlns:GCamera=",
                " xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\" xmlns:GCamera=")
            else it
        }
        return appendDescriptions(withItem, description)
    }

    // ---------------------------------------------------------------- bytes

    private fun u8(b: ByteArray, at: Int) = b[at].toInt() and 0xFF
    private fun u16be(b: ByteArray, at: Int) = (u8(b, at) shl 8) or u8(b, at + 1)
    private fun startsWith(b: ByteArray, at: Int, prefix: ByteArray): Boolean =
        at >= 0 && at + prefix.size <= b.size && prefix.indices.all { b[at + it] == prefix[it] }
    private fun indexOf(b: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..b.size - needle.size) {
            for (j in needle.indices) if (b[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
