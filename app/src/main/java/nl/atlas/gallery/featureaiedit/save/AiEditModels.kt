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
    val candidateParentUri: Uri? = null
) {
    init {
        require(originalUri.scheme == "content" && originalUri.authority == "media")
        require(candidateUri.scheme == "content")
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
    val candidateParentUri: String?
)

data class AiEditSaveResult(
    val uri: Uri,
    val originalUri: Uri,
    val displayName: String,
    val lineage: AiEditLineage
)
