package nl.atlas.gallery

/**
 * Tracks a single public-editor handoff without remembering private photos,
 * writing a file or invalidating the entire gallery cache.
 *
 * The pending URI is in memory only. ON_RESUME consumes it once, so ordinary
 * app resumes do not unnecessarily reload a 150k-image filmstrip.
 */
internal data class EditorReturnState(
    val pendingUri: String? = null,
    val refreshedUri: String? = null,
    val revision: Long = 0L
) {
    fun launched(uri: String, didLaunch: Boolean): EditorReturnState =
        if (didLaunch && uri.startsWith("content://media/")) copy(pendingUri = uri) else this

    fun resumed(): EditorReturnState =
        if (pendingUri == null) this else
            copy(pendingUri = null, refreshedUri = pendingUri, revision = revision + 1L)

    /** Unchanged image: use Coil's normal warm cache. Only returned image opts out. */
    fun mustReload(mediaUri: String): Boolean =
        revision > 0 && refreshedUri == mediaUri
}


/**
 * Coil 3 memory keys for a returned-from-editor photo. A new revision
 * prevents serving the pre-edit bitmap; an explicit pixel size prevents a
 * thumbnail from polluting the full-resolution cache.
 *
 * Keep memory caching ENABLED for the new key, but bypass the potentially
 * stale disk cache for this one MediaStore URI. All other imagery retains
 * Coil's normal keys and caching behavior.
 */
internal fun editedPhotoMemoryKey(
    uri: String, revision: Long, requestedPixels: Int
): String {
    require(uri.startsWith("content://media/"))
    require(revision > 0L && requestedPixels in 1..8192)
    return "${uri}#atlasEdited:${revision}:pixels:${requestedPixels}"
}
