package nl.atlas.gallery.featureaiedit.save

import android.net.Uri
import java.util.Locale

/** Local-only AI edit output formats. JPEG is the default; PNG/WebP preserve lossless pixels. */
enum class AiEditImageFormat(val extension: String, val mimeType: String) {
    JPEG("jpg", "image/jpeg"),
    PNG("png", "image/png"),
    WEBP("webp", "image/webp")
}

/** No Android dependencies; makes deterministic, folder-scoped unique names. */
internal object AiEditNaming {
    /**
     * The pre-insert journal records this unguessable, temporary MediaStore name
     * instead of the final collision-prone _AI_n name. The exact token is
     * persisted before insert, so death before recordInserted is recoverable.
     */
    fun pendingName(token: String, format: AiEditImageFormat): String =
        "atlas-ai-pending-$token.${format.extension}"

    fun isTokenNamedPendingRow(token: String, displayName: String): Boolean =
        AiEditImageFormat.values().any { pendingName(token, it) == displayName }

    fun next(originalName: String, format: AiEditImageFormat, existing: Set<String>): String {
        require(originalName.isNotBlank()) { "An original display name is required" }
        val file = originalName.substringAfterLast('/')
        val rawStem = file.substringBeforeLast('.', file)
            .replace(Regex("[^\\p{L}\\p{N}._ -]"), "_")
            .trim(' ', '.', '_')
            .take(96)
        val stem = rawStem.ifBlank { "Photo" }
        val occupied = existing.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
        for (number in 1..100_000) {
            val name = stem + "_AI_" + number + "." + format.extension
            if (name.lowercase(Locale.ROOT) !in occupied) return name
        }
        error("No unused AI edit name in source folder")
    }
}

/**
 * A candidate is a working copy, never the original. The source must be a
 * MediaStore image; candidate may be another content URI supplied by the editor.
 * Source revisions are explicit to avoid confusing identical URI / changed bytes.
 */
data class AiEditCandidate(
    val originalUri: Uri,
    val candidateUri: Uri,
    val originalRevision: String,
    val provider: String,
    val model: String,
    val modelVersion: String,
    val prompt: String,
    val settingsJson: String,
    val format: AiEditImageFormat = AiEditImageFormat.JPEG,
    val keepGps: Boolean = false,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val candidateParentUri: Uri? = null,
    /** Optional app-private identity metadata. It is never written to EXIF. */
    val identity: IdentityEditLineage? = null
) {
    init {
        require(originalUri.scheme == "content" && originalUri.authority == "media")
        // The editor's working files are app-private files; AiEditSaver checks the path is inside
        // the app's own cache or files folder before reading one (#431).
        require(candidateUri.scheme == "content" || candidateUri.scheme == "file")
        require(originalUri != candidateUri) { "Do not re-encode the original in-place" }
        require(originalRevision.isNotBlank() && originalRevision.length <= 256)
        require(provider.isNotBlank() && provider.length <= 128)
        require(model.isNotBlank() && model.length <= 128)
        require(modelVersion.isNotBlank() && modelVersion.length <= 128)
        require(prompt.length <= 16384 && settingsJson.length <= 8192)
        require(createdAtMillis >= 0L)
    }
}

/** App-private persisted record. No tokens, credentials, or remote endpoints. */
data class AiEditLineage(
    val editedUri: String,
    val originalUri: String,
    val originalRevision: String,
    val provider: String,
    val model: String,
    val modelVersion: String,
    val prompt: String,
    val settingsJson: String,
    val createdAtMillis: Long,
    val candidateParentUri: String?,
    /** Null for records written before Identity Studio support. */
    val identity: IdentityEditLineage? = null
)

/** Versioned, private provenance for an accepted Identity Studio candidate. */
data class IdentityEditLineage(
    val schemaVersion: Int = SCHEMA_VERSION,
    val kind: String,
    val engineId: String,
    val engineVersion: String,
    val route: String,
    val profileId: String,
    val variantId: String?,
    val candidateIndex: Int,
    val seed: Long,
    val refinementParent: String?
) {
    init {
        require(schemaVersion == SCHEMA_VERSION)
        require(kind == "face" || kind == "head")
        require(engineId.isNotBlank() && engineId.length <= 128)
        require(engineVersion.isNotBlank() && engineVersion.length <= 128)
        require(route == "local" || route == "self_hosted" || route == "paid")
        require(profileId.isNotBlank() && profileId.length <= 128)
        require(variantId == null || variantId.isNotBlank() && variantId.length <= 128)
        require(candidateIndex >= 0)
        require(refinementParent == null || refinementParent.isNotBlank() && refinementParent.length <= 128)
    }

    companion object { const val SCHEMA_VERSION = 1 }
}

data class AiEditSaveResult(
    val uri: Uri,
    val originalUri: Uri,
    val displayName: String,
    val lineage: AiEditLineage
)
