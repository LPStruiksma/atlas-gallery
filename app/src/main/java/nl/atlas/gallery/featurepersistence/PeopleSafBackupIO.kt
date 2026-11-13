package nl.atlas.gallery.featurepersistence

import android.content.ContentResolver
import android.net.Uri
import nl.atlas.gallery.PeopleBackupPreview
import nl.atlas.gallery.PeopleRepository
import nl.atlas.gallery.PeopleRestoreComparison
import nl.atlas.gallery.PeopleDiscoveryHost

/**
 * This boundary ONLY handles user-chosen SAF documents. It never chooses a
 * provider, opens media files, schedules a backup or takes SAF permissions.
 * Lane I must wire ACTION_CREATE_DOCUMENT / ACTION_OPEN_DOCUMENT and explicit
 * consent, off the UI thread and inside the existing People sync mutex.
 */
class PeopleSafBackupIO(private val resolver: ContentResolver) {
    companion object {
        const val MIME_TYPE = "application/octet-stream"
        const val SUGGESTED_NAME = "atlas-people.atlaspeople"
        const val NAMES_SUGGESTED_NAME = "atlas-people-names.atlasnames"
        const val NAMES_EXPORT_WARNING =
            "This file contains private People names and opaque review keys. " +
            "It contains no photos, face vectors, file names or media paths. " +
            "Your chosen document provider may sync the unencrypted file to a cloud. " +
            "Choose local storage for an offline copy."
        const val NAMES_IMPORT_WARNING =
            "Import only applies names, hidden flags and review decisions for " +
            "existing local People keys. Unknown keys and conflicting decisions " +
            "are skipped; original photos are never modified."
        const val EXPORT_WARNING =
            "The selected document provider may upload this unencrypted backup to a cloud. " +
            "It contains confirmed People names and absolute image paths (no photo bytes). " +
            "Choose an on-device destination if you want local storage. Keep a separate " +
            "protected copy; this export does not include app settings or SAF grants."
        const val IMPORT_WARNING =
            "Import adds only new confirmed assignments. Conflicting face IDs or person " +
            "names cancel the entire import; existing assignments are never erased. " +
            "Only continue after reviewing the source, row count and checksum. " +
            "This does not restore settings, SAF access or original photographs."
    }

    // Separate from the path-bearing V4 backup; both retain independent formats.
    /** Runs only after a fresh, affirmative destination warning and SAF choice. */
    internal fun exportNamesToSelectedDocument(
        uri: Uri, host: PeopleDiscoveryHost, galleryGrant: String?,
        destinationRiskAcknowledged: Boolean
    ): NativePeopleNamesPreview {
        require(destinationRiskAcknowledged) { "Accept the document-provider warning first" }
        validateDocumentUri(uri)
        val bytes = host.exportNamesBackup(galleryGrant)
        PeopleDocumentTransport.writeAndReadBack(
            bytes, openOutput = { resolver.openOutputStream(uri, "w") },
            openInput = { resolver.openInputStream(uri) }
        )
        return NativePeopleNamesBackupCodec.preview(bytes)
    }

    internal fun previewNamesImport(uri: Uri): NativePeopleNamesPreview {
        validateDocumentUri(uri)
        return NativePeopleNamesBackupCodec.preview(
            PeopleDocumentTransport.readBounded(
                resolver.openInputStream(uri) ?: error("Selected names backup is unreadable"),
                NativePeopleNamesBackupCodec.MAX_BYTES
            )
        )
    }

    /** Reopen document at approval. An edited or swapped provider file is refused. */
    internal fun importNamesFromSelectedDocument(
        uri: Uri, host: PeopleDiscoveryHost, galleryGrant: String?,
        approvedChecksum: String, explicitlyApproved: Boolean
    ): NativePeopleNamesImportResult {
        require(explicitlyApproved) { "Names import needs explicit approval" }
        validateDocumentUri(uri)
        val bytes = PeopleDocumentTransport.readBounded(
            resolver.openInputStream(uri) ?: error("Selected names backup is unreadable"),
            NativePeopleNamesBackupCodec.MAX_BYTES
        )
        require(NativePeopleNamesBackupCodec.checksum(bytes) == approvedChecksum) {
            "Names backup changed after preview"
        }
        return host.importNamesBackup(galleryGrant, bytes, approvedChecksum)
    }

    data class ExportReceipt(val rowCount: Int, val byteCount: Int, val sha256: String)

    fun exportToSelectedDocument(
        uri: Uri,
        repository: PeopleRepository,
        destinationRiskAcknowledged: Boolean
    ): ExportReceipt {
        require(destinationRiskAcknowledged) { "Review the SAF destination privacy warning first" }
        validateDocumentUri(uri)
        val prepared = repository.preparePeopleBackupExport()
        val data = prepared.bytes
        val preview = prepared.preview
        // Provider-controlled (not guaranteed filesystem-atomic): fail closed if
        // an independently reopened document does not match every saved byte.
        PeopleDocumentTransport.writeAndReadBack(
            data,
            openOutput = { resolver.openOutputStream(uri, "w") },
            openInput = { resolver.openInputStream(uri) }
        )
        return ExportReceipt(preview.rowCount, data.size, preview.sha256)
    }

    /** Pure read/verify. The UI displays this before enabling the import action. */
    fun previewSelectedImport(uri: Uri, repository: PeopleRepository): PeopleBackupPreview {
        validateDocumentUri(uri)
        return repository.previewPeopleBackup(PeopleDocumentTransport.readBounded(
            resolver.openInputStream(uri) ?: error("Selected backup cannot be read")
        ))
    }

    /**
     * Optional, more useful additive restore preview for U's explicit consent UI.
     * It does not mutate confirmed labels, connected V4 state, document provider
     * data or SAF grants. Still reopen the chosen URI upon final approval.
     */
    fun compareSelectedImport(uri: Uri, repository: PeopleRepository): PeopleRestoreComparison {
        validateDocumentUri(uri)
        val bytes = PeopleDocumentTransport.readBounded(
            resolver.openInputStream(uri) ?: error("Selected backup cannot be read")
        )
        return repository.compareNativeBackupWithCurrentPeople(bytes)
    }

    /** Re-read on approval so a changed provider document cannot bypass preview. */
    fun importSelectedDocument(
        uri: Uri,
        repository: PeopleRepository,
        approvedPreviewSha256: String,
        explicitlyApproved: Boolean
    ): Int {
        require(explicitlyApproved) { "People restore requires separate affirmative approval" }
        validateDocumentUri(uri)
        // The repository checks approval SHA and decodes the original bytes
        // BEFORE it starts its SQLite write transaction. Re-decoding here
        // first would allocate a second potentially huge People row graph.
        val bytes = PeopleDocumentTransport.readBounded(
            resolver.openInputStream(uri) ?: error("Selected backup is no longer readable")
        )
        return repository.mergeNativeBackup(bytes, approvedPreviewSha256)
    }

    private fun validateDocumentUri(uri: Uri) {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT && !uri.authority.isNullOrBlank()) {
            "Choose a document with Android's Storage Access Framework"
        }
    }

}
