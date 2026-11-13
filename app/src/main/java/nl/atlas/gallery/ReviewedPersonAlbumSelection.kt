package nl.atlas.gallery

import nl.atlas.gallery.featuresearch.mayAttributeReviewedV4Person

/**
 * V4 reviewed CSV paths originate from primary emulated storage only.
 * A removable volume with a similar display path, or a non-MediaStore URI,
 * must not inherit someone else's confirmed People identity.
 */
internal fun isAuthorizedReviewedPrimaryImage(record: MediaRecord): Boolean =
    mayAttributeReviewedV4Person(
        mediaUri = record.uri,
        isVideo = record.isVideo,
        actualProviderVolume = record.volumeName,
        absolutePath = record.absolutePath
    )

/**
 * Exact, source-order-preserving and cancellation-aware People-album selector.
 *
 * A path claimed by two DIFFERENT MediaStore URIs is ambiguous, not two photos
 * of the same person. Neither is admitted, even if both have identical names.
 * Produces no writes, index files, cache entries, or unreviewed identities.
 */
internal fun selectAuthorizedReviewedPersonAlbum(
    sorted: List<MediaRecord>,
    confirmedPaths: Set<String>,
    blackAndWhiteFolderOnly: Boolean = false,
    cancellationCheckpoint: () -> Unit = {}
): List<MediaRecord> {
    if (confirmedPaths.isEmpty()) return emptyList()
    val uniqueByPath = HashMap<String, String>()
    val ambiguousPaths = HashSet<String>()
    sorted.forEachIndexed { index, record ->
        if ((index and 511) == 0) cancellationCheckpoint()
        if (!isAuthorizedReviewedPrimaryImage(record)) return@forEachIndexed
        val path = record.absolutePath
        if (path !in confirmedPaths || path in ambiguousPaths) return@forEachIndexed
        val prior = uniqueByPath.putIfAbsent(path, record.uri)
        if (prior != null && prior != record.uri) {
            uniqueByPath.remove(path)
            ambiguousPaths.add(path)
        }
    }
    cancellationCheckpoint()
    return sorted.filterIndexed { index, record ->
        if ((index and 511) == 0) cancellationCheckpoint()
        isAuthorizedReviewedPrimaryImage(record) &&
            uniqueByPath[record.absolutePath] == record.uri &&
            (!blackAndWhiteFolderOnly ||
                isInBlackAndWhiteFolder(record.relativePath))
    }.also { cancellationCheckpoint() }
}
