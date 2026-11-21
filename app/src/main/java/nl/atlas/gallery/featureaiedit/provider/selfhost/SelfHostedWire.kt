package nl.atlas.gallery.featureaiedit.provider.selfhost

import nl.atlas.gallery.featureaiedit.provider.EditImage
import nl.atlas.gallery.featureaiedit.provider.ImageFormat
import nl.atlas.gallery.featureaiedit.provider.ResizePlan
import nl.atlas.gallery.featureaiedit.provider.hosted.EncodedImage
import nl.atlas.gallery.featureaiedit.provider.hosted.HostedJson
import nl.atlas.gallery.featureaiedit.provider.hosted.ImageProbe
import nl.atlas.gallery.featureaiedit.provider.hosted.SourceImageLoader
import java.net.URI
import java.util.Locale

/** Shared restrictive wire primitives, never user-facing exception text. */
internal object SelfHostedWire {
    fun json(value: Any?): String = when (value) {
        null -> "null"
        is String -> HostedJson.quote(value)
        is Boolean -> value.toString()
        is Long, is Int -> value.toString()
        is Double -> require(value.isFinite()).let { value.toString() }
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (k, v) ->
            require(k is String)
            HostedJson.quote(k) + ":" + json(v)
        }
        is List<*> -> value.joinToString(",", "[", "]") { json(it) }
        else -> error("Unsupported JSON type")
    }

    fun objectReply(bytes: ByteArray): Map<String, Any?>? =
        (HostedJson.parseOrNull(bytes) as? Map<*, *>)?.takeIf { it.keys.all { key -> key is String } }
            ?.let { @Suppress("UNCHECKED_CAST") (it as Map<String, Any?>) }

    fun map(value: Any?): Map<String, Any?>? =
        (value as? Map<*, *>)?.takeIf { it.keys.all { key -> key is String } }
            ?.let { @Suppress("UNCHECKED_CAST") (it as Map<String, Any?>) }

    fun list(value: Any?): List<Any?>? = value as? List<Any?>
    fun int(value: Any?, min: Int, max: Int): Int? {
        val n = (value as? Number)?.toLong() ?: return null
        if (n !in min.toLong()..max.toLong() ||
            (value as? Double)?.let { it != n.toDouble() } == true) return null
        return n.toInt()
    }
    fun long(value: Any?, min: Long, max: Long): Long? {
        val n = (value as? Number)?.toLong() ?: return null
        if (n !in min..max || (value as? Double)?.let { it != n.toDouble() } == true) return null
        return n
    }
    fun decimal(value: Any?): Double? =
        (value as? Number)?.toDouble()?.takeIf { it.isFinite() }

    fun code(value: Any?): String =
        (value as? String)?.takeIf { Regex("[A-Za-z][A-Za-z0-9_-]{0,63}").matches(it) }
            ?: "PROVIDER_REJECTED"

    fun safeId(value: Any?): String? =
        (value as? String)?.takeIf { Regex("[A-Za-z0-9_-]{1,80}").matches(it) }

    fun secureBase(url: String, hosts: Set<String>): Boolean = try {
        val uri = URI(url)
        uri.scheme == "https" && uri.host != null && uri.rawPath?.endsWith("/") == true &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.normalize() == uri && uri.host.lowercase(Locale.ROOT) in
                hosts.map { it.lowercase(Locale.ROOT) } && hosts.size == 1 &&
            uri.port in -1..65535
    } catch (_: Exception) { false }

    /** Images come exclusively from the explicit request and a metadata-stripping caller loader. */
    fun selected(loader: SourceImageLoader, image: EditImage, resize: ResizePlan): EncodedImage? {
        val encoded = loader.load(image, resize) ?: return null
        if (encoded.format !in setOf(ImageFormat.PNG, ImageFormat.JPEG) ||
            encoded.bytes.size !in 1..12_000_000) return null
        val probe = ImageProbe.probe(encoded.bytes) ?: return null
        val expectedWidth = if (resize is ResizePlan.Downscale) resize.width else image.width
        val expectedHeight = if (resize is ResizePlan.Downscale) resize.height else image.height
        if (probe.format != encoded.format || probe.width != expectedWidth ||
            probe.height != expectedHeight) return null
        return encoded
    }

    fun validResult(probe: ImageProbe.Probe?, format: ImageFormat,
                    width: Int, height: Int, maxPixels: Long): Boolean =
        probe != null && probe.format == format && probe.width == width &&
            probe.height == height && probe.width.toLong() * probe.height <= maxPixels
}
