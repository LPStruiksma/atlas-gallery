package nl.atlas.gallery.featurealbums

import java.io.InputStream
import java.io.OutputStream

/**
 * Picker identity is the exact MediaStore volume and relative path, not the
 * visible album title. This model contains no media and holds no URI grants.
 */
data class AlbumCopyAlbum(val volumeName: String, val relativePath: String, val title: String)

sealed class AlbumCopyDestination {
    data class Existing(val volumeName: String, val relativePath: String) : AlbumCopyDestination()
    data class NewAlbum(
        val volumeName: String, val parentRelativePath: String, val name: String
    ) : AlbumCopyDestination()
}

data class AlbumCopyChoice(val label: String, val destination: AlbumCopyDestination)

sealed class AlbumCopyPickerOutcome {
    data class Selected(val destination: AlbumCopyDestination.Existing) : AlbumCopyPickerOutcome()
    object Cancelled : AlbumCopyPickerOutcome()
    object Invalid : AlbumCopyPickerOutcome()
    object Stale : AlbumCopyPickerOutcome()
}

/**
 * No filesystem access: validating a destination is not a claim that a
 * provider will accept its RELATIVE_PATH. Host must revalidate at operation time.
 */
class AlbumCopyPicker(albums: List<AlbumCopyAlbum>, private val accessEpoch: Long) {
    private val existing = albums.map {
        AlbumCopyDestination.Existing(it.volumeName, it.relativePath)
    }.toSet()
    val choices: List<AlbumCopyChoice> = albums.map {
        AlbumCopyChoice(
            "${it.title} · ${it.relativePath} · ${it.volumeName}",
            AlbumCopyDestination.Existing(it.volumeName, it.relativePath)
        )
    } + AlbumCopyChoice("New album", AlbumCopyDestination.NewAlbum("external_primary", "Pictures/", ""))

    fun cancel(): AlbumCopyPickerOutcome = AlbumCopyPickerOutcome.Cancelled

    fun choose(destination: AlbumCopyDestination, currentEpoch: Long): AlbumCopyPickerOutcome {
        if (currentEpoch != accessEpoch) return AlbumCopyPickerOutcome.Stale
        return when (destination) {
            is AlbumCopyDestination.Existing ->
                if (destination in existing && validPath(destination.relativePath))
                    AlbumCopyPickerOutcome.Selected(destination)
                else AlbumCopyPickerOutcome.Invalid
            is AlbumCopyDestination.NewAlbum -> {
                if (!validPath(destination.parentRelativePath) ||
                    !validName(destination.name) || destination.volumeName.isBlank())
                    AlbumCopyPickerOutcome.Invalid
                else AlbumCopyPickerOutcome.Selected(
                    AlbumCopyDestination.Existing(
                        destination.volumeName,
                        destination.parentRelativePath + destination.name.trim() + "/"
                    )
                )
            }
        }
    }
}

private fun validName(name: String): Boolean {
    val trimmed = name.trim()
    return trimmed.isNotEmpty() && trimmed != "." && trimmed != ".." &&
        trimmed.length <= 100 && trimmed.none {
            it == '/' || it == '\\' || it.code < 32 || it == 127.toChar()
        }
}

/** MediaStore relative paths contain normal directory components only. */
private fun validPath(path: String): Boolean =
    path.isNotBlank() && path.endsWith("/") &&
        !path.startsWith("/") && !path.contains("\\") &&
        path.split('/').dropLast(1).all { validName(it) }

data class AlbumCopySource(
    val uri: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val volumeName: String,
    val accessEpoch: Long
)

/**
 * Adapter for a provider-backed implementation. createPending must insert a
 * new row with IS_PENDING=1; publish must clear IS_PENDING only after stream
 * completion. deletePending must target only the URI returned by createPending.
 *
 * This interface does NOT promise Android provider compatibility: the host
 * must use a collection URI matching media MIME/volume, check runtime grants,
 * handle insert/openOutputStream returning null, and handle provider-specific
 * RELATIVE_PATH and duplicate-name behavior. No update/delete of source media
 * and no absolute filesystem paths are permitted.
 */
interface AlbumCopyStore {
    fun currentAccessEpoch(): Long
    fun destinationWritable(volumeName: String, relativePath: String): Boolean
    fun nameExists(volumeName: String, relativePath: String, displayName: String): Boolean
    fun createPending(
        volumeName: String, relativePath: String, displayName: String, mimeType: String
    ): String?
    fun openSource(uri: String): InputStream?
    fun openDestination(uri: String): OutputStream?
    fun publish(uri: String)
    fun deletePending(uri: String)
}

sealed class AlbumCopyResult {
    data class Copied(val uri: String) : AlbumCopyResult()
    object Cancelled : AlbumCopyResult()
    object InvalidInput : AlbumCopyResult()
    object StaleAccess : AlbumCopyResult()
    object DestinationUnavailable : AlbumCopyResult()
    object DuplicateName : AlbumCopyResult()
    object CopyFailed : AlbumCopyResult()
    data class CleanupFailed(val pendingUri: String) : AlbumCopyResult()
    /** Publish may commit before throwing. Caller must reconcile this URI without deleting. */
    data class PublicationUncertain(val uri: String) : AlbumCopyResult()
}

/**
 * Synchronous domain contract; caller schedules off the UI thread. The caller
 * must supply a source from the current authorized catalogue (URI membership
 * cannot be established by this pure contract). Snapshot epoch checks prevent
 * stale publication, but a revoke/regrant within the same numeric epoch is only
 * safe when the host advances its epoch on EVERY access publication.
 */
fun copyToAlbum(
    source: AlbumCopySource,
    destination: AlbumCopyDestination.Existing?,
    store: AlbumCopyStore
): AlbumCopyResult {
    if (destination == null) return AlbumCopyResult.Cancelled
    if (source.accessEpoch != store.currentAccessEpoch()) return AlbumCopyResult.StaleAccess
    if (!source.uri.startsWith("content://") || source.displayName.isBlank() ||
        source.displayName == "." || source.displayName == ".." ||
        source.displayName.contains('/') || source.displayName.contains('\\') ||
        source.mimeType.isBlank() || source.sizeBytes < 0L ||
        source.volumeName.isBlank() || destination.volumeName.isBlank() ||
        !validPath(destination.relativePath)) return AlbumCopyResult.InvalidInput
    try {
        if (!store.destinationWritable(destination.volumeName, destination.relativePath))
            return AlbumCopyResult.DestinationUnavailable
        if (store.nameExists(destination.volumeName, destination.relativePath, source.displayName))
            return AlbumCopyResult.DuplicateName
    } catch (_: Exception) {
        return AlbumCopyResult.DestinationUnavailable
    }
    if (source.accessEpoch != store.currentAccessEpoch()) return AlbumCopyResult.StaleAccess
    var pendingUri: String? = null
    var outcome: AlbumCopyResult = AlbumCopyResult.CopyFailed
    try {
        val row = store.createPending(
            destination.volumeName, destination.relativePath, source.displayName, source.mimeType
        ) ?: return AlbumCopyResult.CopyFailed
        pendingUri = row
        if (source.accessEpoch != store.currentAccessEpoch()) {
            outcome = AlbumCopyResult.StaleAccess
        } else {
            var total = 0L
            val input = store.openSource(source.uri)
            if (input != null) {
                input.use { sourceStream ->
                    val output = store.openDestination(row)
                    if (output != null) {
                        output.use { destinationStream ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                if (source.accessEpoch != store.currentAccessEpoch()) {
                                    outcome = AlbumCopyResult.StaleAccess
                                    break
                                }
                                val n = sourceStream.read(buffer)
                                if (n < 0) break
                                if (n == 0) continue
                                destinationStream.write(buffer, 0, n)
                                total += n
                            }
                            if (outcome != AlbumCopyResult.StaleAccess) {
                                destinationStream.flush()
                                if (total > 0L &&
                                    (source.sizeBytes == 0L || total == source.sizeBytes))
                                    outcome = AlbumCopyResult.Copied(row)
                            }
                        }
                    }
                }
            }
            if (source.accessEpoch != store.currentAccessEpoch())
                outcome = AlbumCopyResult.StaleAccess
            if (outcome is AlbumCopyResult.Copied) {
                // A provider may commit publication and then throw: do not call deletePending.
                return try {
                    store.publish(row)
                    outcome
                } catch (_: Exception) {
                    AlbumCopyResult.PublicationUncertain(row)
                }
            }
        }
    } catch (_: Exception) {
        outcome = if (source.accessEpoch != store.currentAccessEpoch())
            AlbumCopyResult.StaleAccess else AlbumCopyResult.CopyFailed
    }
    return pendingUri?.let { finishFailedCopy(it, store, outcome) } ?: outcome
}

private fun finishFailedCopy(
    row: String, store: AlbumCopyStore, failure: AlbumCopyResult
): AlbumCopyResult = try {
    store.deletePending(row)
    failure
} catch (_: Exception) {
    AlbumCopyResult.CleanupFailed(row)
}
