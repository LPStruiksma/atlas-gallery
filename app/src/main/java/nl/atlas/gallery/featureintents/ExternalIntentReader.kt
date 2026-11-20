package nl.atlas.gallery.featureintents

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns

/** Copies an Android [Intent] into [ExternalIntentFacts]; all decisions stay in the parser. */
internal object ExternalIntentReader {
    fun facts(intent: Intent?): ExternalIntentFacts {
        if (intent == null) return ExternalIntentFacts(null, null, null)
        // A hostile or buggy caller can send a Parcelable that fails to unparcel:
        // read each field on its own and treat a failure as absent.
        val clip = runCatching {
            val data = intent.clipData ?: return@runCatching emptyList<String>()
            (0 until data.itemCount).mapNotNull { data.getItemAt(it)?.uri?.toString() }
        }.getOrDefault(emptyList())
        return ExternalIntentFacts(
            action = intent.action,
            dataUri = runCatching { intent.data?.toString() }.getOrNull(),
            type = runCatching { intent.type }.getOrNull(),
            clipUris = clip,
            allowMultiple = runCatching {
                intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
            }.getOrDefault(false),
            extraMimeTypes = runCatching {
                intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList()
            }.getOrNull()
        )
    }

    fun entry(intent: Intent?): ExternalEntry = ExternalIntentParser.parse(facts(intent))
}

/** What [ExternalItemProbe] learned about a caller's URI, read on IO. */
internal sealed interface ExternalItemProbeResult {
    data class Readable(
        val uri: String,
        val kind: ExternalMediaKind,
        val displayName: String?,
        val sizeBytes: Long
    ) : ExternalItemProbeResult

    /** No grant, revoked grant, missing provider, or not a photo/video. Never a crash. */
    data object Unreadable : ExternalItemProbeResult
}

/**
 * Checks, before anything is drawn, that ATLAS can really open the caller's item
 * and that the provider says it is a photo or video. Only the item itself is
 * touched: no MediaStore query, no library scan, no permission request.
 */
internal object ExternalItemProbe {
    fun probe(resolver: ContentResolver, entry: ExternalEntry.Viewer): ExternalItemProbeResult {
        val uri = runCatching { Uri.parse(entry.uri) }.getOrNull()
            ?: return ExternalItemProbeResult.Unreadable
        return try {
            // The provider's own type wins; the caller's declared type is only a fallback.
            val resolved = runCatching { resolver.getType(uri) }.getOrNull()
            val kind = ExternalIntentParser.mediaKindOf(resolved)
                ?: ExternalIntentParser.mediaKindOf(entry.declaredType)?.takeIf { resolved == null }
                ?: return ExternalItemProbeResult.Unreadable
            // Opening proves the grant is live (a revoked grant throws SecurityException).
            resolver.openAssetFileDescriptor(uri, "r")?.use { } ?: return ExternalItemProbeResult.Unreadable
            var name: String? = null
            var size = 0L
            runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameColumn >= 0 && !cursor.isNull(nameColumn)) name = cursor.getString(nameColumn)
                        if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn)
                    }
                }
            }
            ExternalItemProbeResult.Readable(entry.uri, kind, name?.takeIf { it.isNotBlank() }, size)
        } catch (stopped: kotlinx.coroutines.CancellationException) {
            throw stopped
        } catch (_: Exception) {
            ExternalItemProbeResult.Unreadable
        }
    }
}
