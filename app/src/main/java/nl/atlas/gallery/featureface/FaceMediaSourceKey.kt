package nl.atlas.gallery.featureface

import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Pure, versioned bridge from Lane M's currently visible MediaRecord fields to
 * P/X's durable face index. No bitmap bytes, names, file paths, embeddings or
 * identities are hashed. I supplies the actual live MediaStore record and
 * revalidates grants/revisions; THIS HASH IS NOT MEDIA PERMISSION.
 *
 * Permission/catalogue epochs are intentionally NOT a component of the
 * sourceRevision: unchanged photos stay processed after a renewed grant.
 * Photos with partial provider metadata use explicit unknown revision markers,
 * while valid legacy revisions remain byte-identical. A missing canonical
 * volume/ID cannot produce an authorized identity.
 */
object FaceMediaSourceKey {
    private const val KEY_VERSION = "atlas-face-source-key-v1"
    private const val REV_VERSION = "atlas-face-revision-v1"
    private const val VIDEO_REV_VERSION = "atlas-face-revision-video-v1"
    private const val UNKNOWN = "unknown"
    private const val WEAK_REV_VERSION = "atlas-face-revision-weak-v1"
    private val VOLUME_NAME = Regex("[A-Za-z0-9_-]{1,120}")
    private const val HEX = "0123456789abcdef"

    fun fromVisiblePhoto(
        uri: String,
        volumeName: String,
        isVideo: Boolean,
        id: Long,
        providerVersion: String?,
        generationModified: Long,
        sizeBytes: Long,
        modifiedAtSeconds: Long,
        editorRevision: Long,
        widthPixels: Int,
        heightPixels: Int,
        orientationDegrees: Int
    ): FaceMediaKey {
        require(!isVideo) { "Use fromVisibleVideo for a video row" }
        return photoKey(uri, volumeName, id, providerVersion, generationModified,
            sizeBytes, modifiedAtSeconds, editorRevision, widthPixels, heightPixels,
            orientationDegrees)
    }

    /**
     * A photo can be decoded when optional MediaStore metadata is absent.
     * Unknown stays distinct from measured zero and from every later valid
     * value. Existing complete photos keep their exact v1 revision digest.
     */
    private fun photoKey(
        uri: String,
        volumeName: String,
        id: Long,
        providerVersion: String?,
        generationModified: Long,
        sizeBytes: Long,
        modifiedAtSeconds: Long,
        editorRevision: Long,
        widthPixels: Int,
        heightPixels: Int,
        orientationDegrees: Int
    ): FaceMediaKey {
        val canonical = canonicalKey(MediaKind.IMAGE, uri, volumeName, id)
        val version = providerVersion?.takeIf {
            it.isNotBlank() && it.length <= 256 && '\u0000' !in it
        } ?: UNKNOWN
        val generation = generationModified.takeIf { it > 0 }?.toString() ?: UNKNOWN
        val size = sizeBytes.takeIf { it > 0 }?.toString() ?: UNKNOWN
        val modified = modifiedAtSeconds.takeIf { it >= 0 }?.toString() ?: UNKNOWN
        val editor = editorRevision.takeIf { it >= 0 }?.toString() ?: UNKNOWN
        val width = widthPixels.takeIf { it > 0 }?.toString() ?: UNKNOWN
        val height = heightPixels.takeIf { it > 0 }?.toString() ?: UNKNOWN
        val rotation = rotationStamp(orientationDegrees)
        val fullyValid = version != UNKNOWN && generation != UNKNOWN && size != UNKNOWN &&
            modified != UNKNOWN && editor != UNKNOWN && widthPixels >= 0 &&
            heightPixels >= 0 && (orientationDegrees == Int.MIN_VALUE ||
                orientationDegrees % 90 == 0)
        val revision = digest(if (fullyValid) REV_VERSION else WEAK_REV_VERSION,
            version, generation, size, modified,
            if (fullyValid) widthPixels.toString() else width,
            if (fullyValid) heightPixels.toString() else height,
            rotation, editor)
        return FaceMediaKey("atlas-face-media-v1:$canonical", "atlas-face-rev-v1:$revision")
    }

    private fun rotationStamp(orientationDegrees: Int): String =
        if (orientationDegrees == Int.MIN_VALUE || orientationDegrees % 90 != 0) UNKNOWN
        else (((orientationDegrees % 360) + 360) % 360).toString()

    private fun canonicalKey(kind: MediaKind, uri: String, volumeName: String, id: Long): String {
        require(id > 0 && volumeName.matches(VOLUME_NAME) &&
            volumeName != "external" && volumeName != "unknown") {
            "Only mounted specific MediaStore volume and positive ID are durable identities"
        }
        require(uri.length in 1..512 && '\u0000' !in uri)
        val parsed = try { URI(uri) } catch (_: Exception) { null }
        require(parsed != null && parsed.scheme == "content" && parsed.host == "media" &&
            parsed.rawQuery == null && parsed.rawFragment == null &&
            parsed.rawUserInfo == null &&
            parsed.rawPath == "/$volumeName/${kind.segment}/media/$id") {
            "Only exact specific-volume MediaStore ${kind.label} URIs are valid"
        }
        return digest(KEY_VERSION, uri, volumeName, kind.label, id.toString())
    }

    /** Named, non-identifying diagnostics; never retain URI or media ID. */
    fun missingPhotoFields(
        providerVersion: String?,
        generationModified: Long,
        sizeBytes: Long,
        modifiedAtSeconds: Long,
        editorRevision: Long,
        widthPixels: Int,
        heightPixels: Int,
        orientationDegrees: Int
    ): Set<String> = buildSet {
        if (providerVersion.isNullOrBlank() || providerVersion.length > 256 ||
            '\u0000' in providerVersion) add("providerVersion")
        if (generationModified <= 0) add("generationModified")
        if (sizeBytes <= 0) add("sizeBytes")
        if (modifiedAtSeconds < 0) add("modifiedAtSeconds")
        if (editorRevision < 0) add("editorRevision")
        if (widthPixels <= 0) add("widthPixels")
        if (heightPixels <= 0) add("heightPixels")
        if (orientationDegrees == Int.MIN_VALUE || orientationDegrees % 90 != 0)
            add("orientationDegrees")
    }

    /**
     * VS-V1b (#468): a video's durable identity. Its canonical key hashes the
     * kind "video" and its exact specific-volume video URI, so a photo and a
     * video with the same _ID never collide. Its revision adds the duration,
     * so an in-place trim or re-encode re-scans it like an edited photo.
     * A zero duration (unknown to the provider) is a valid identity; the
     * scanner turns it into a typed skip, never into a catalogue abort.
     */
    fun fromVisibleVideo(
        uri: String,
        volumeName: String,
        id: Long,
        providerVersion: String?,
        generationModified: Long,
        sizeBytes: Long,
        modifiedAtSeconds: Long,
        editorRevision: Long,
        widthPixels: Int,
        heightPixels: Int,
        orientationDegrees: Int,
        durationMs: Long
    ): FaceMediaKey {
        require(durationMs >= 0) { "Negative video duration" }
        return key(MediaKind.VIDEO, uri, volumeName, id, providerVersion, generationModified,
            sizeBytes, modifiedAtSeconds, editorRevision, widthPixels, heightPixels,
            orientationDegrees, durationMs)
    }

    private enum class MediaKind(val segment: String, val label: String) {
        IMAGE("images", "image"), VIDEO("video", "video")
    }

    private fun key(
        kind: MediaKind,
        uri: String,
        volumeName: String,
        id: Long,
        providerVersion: String?,
        generationModified: Long,
        sizeBytes: Long,
        modifiedAtSeconds: Long,
        editorRevision: Long,
        widthPixels: Int,
        heightPixels: Int,
        orientationDegrees: Int,
        durationMs: Long?
    ): FaceMediaKey {
        require(id > 0 && generationModified > 0 && sizeBytes > 0 &&
            modifiedAtSeconds >= 0 && editorRevision >= 0 &&
            widthPixels >= 0 && heightPixels >= 0 &&
            (orientationDegrees == Int.MIN_VALUE || orientationDegrees % 90 == 0)) {
            "Incomplete source revision; abort index inventory"
        }
        require(volumeName.matches(VOLUME_NAME) &&
            volumeName != "external" && volumeName != "unknown") {
            "Only mounted specific MediaStore volumes are durable face identities"
        }
        val version = providerVersion?.takeIf {
            it.isNotBlank() && it.length <= 256 && '\u0000' !in it
        } ?: throw IllegalArgumentException("Missing source provider version")
        require(uri.length in 1..512 && '\u0000' !in uri)
        val parsed = try { URI(uri) } catch (_: Exception) { null }
        require(parsed != null && parsed.scheme == "content" && parsed.host == "media" &&
            parsed.rawQuery == null && parsed.rawFragment == null &&
            parsed.rawUserInfo == null &&
            parsed.rawPath == "/$volumeName/${kind.segment}/media/$id") {
            "Only exact specific-volume MediaStore ${kind.label} URIs are valid"
        }
        // Lane M publishes Int.MIN_VALUE when orientation is absent/invalid.
        // This is neither a measured 0-degree rotation nor a fatal catalogue
        // error: ImageDecoder determines the oriented frame at decode time.
        // Preserve UNKNOWN as a DISTINCT source revision, so a provider later
        // reporting a real quarter-turn invalidates old face-box geometry.
        val rotationStamp = if (orientationDegrees == Int.MIN_VALUE) "unknown"
            else (((orientationDegrees % 360) + 360) % 360).toString()
        val canonical = digest(KEY_VERSION, uri, volumeName, kind.label, id.toString())
        // Photo revisions stay byte-identical to v1, so no photo is re-scanned.
        val sourceRevision = if (durationMs == null) digest(
            REV_VERSION, version, generationModified.toString(),
            sizeBytes.toString(), modifiedAtSeconds.toString(),
            widthPixels.toString(), heightPixels.toString(),
            rotationStamp,
            editorRevision.toString()
        ) else digest(
            VIDEO_REV_VERSION, version, generationModified.toString(),
            sizeBytes.toString(), modifiedAtSeconds.toString(),
            widthPixels.toString(), heightPixels.toString(),
            rotationStamp,
            editorRevision.toString(), durationMs.toString()
        )
        return FaceMediaKey("atlas-face-media-v1:$canonical",
            "atlas-face-rev-v1:$sourceRevision")
    }

    /** Length-prefixed SHA-256 over nonsecret metadata; no ambiguous separators,
     * compact enough for X's SQLite constraints, no accidental raw URI logging.
     */
    private fun digest(vararg fields: String): String {
        val sha = MessageDigest.getInstance("SHA-256")
        for (field in fields) {
            val bytes = field.toByteArray(StandardCharsets.UTF_8)
            // No transient ByteBuffer per field: 150k photos × multiple
            // digest components would otherwise allocate millions of objects.
            val length = bytes.size
            sha.update((length ushr 24).toByte())
            sha.update((length ushr 16).toByte())
            sha.update((length ushr 8).toByte())
            sha.update(length.toByte())
            sha.update(bytes)
        }
        val output = CharArray(64)
        for ((i, value) in sha.digest().withIndex()) {
            val unsigned = value.toInt() and 255
            output[2 * i] = HEX[unsigned ushr 4]
            output[2 * i + 1] = HEX[unsigned and 15]
        }
        return String(output)
    }
}
