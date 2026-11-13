package nl.atlas.gallery.featuresearch

/**
 * V4's five-column confirmed CSV contains IMAGE paths on primary emulated
 * storage. These are *not* a filename-based person recognizer. Accept them
 * only on the currently authorized provider's actual primary photo domain.
 *
 * The caller must pass the independently read MediaStore per-row VOLUME_NAME
 * through Lane M/I, not infer the volume from an untrusted path.
 */
fun mayAttributeReviewedV4Person(
    mediaUri: String,
    isVideo: Boolean,
    actualProviderVolume: String?,
    absolutePath: String
): Boolean {
    if (isVideo || !mediaUri.startsWith("content://media/")) return false
    // "external" remains the legacy single-primary-volume alias used by
    // earlier MediaStore publications; new M publications use external_primary.
    if (actualProviderVolume != "external_primary" &&
        actualProviderVolume != "external") return false
    // M constructs item URI from the per-row mounted volume, not the
    // union collection. A URI for a different volume or media TYPE must not
    // impersonate the imported image even if a caller mislabels isVideo.
    val imagePrefix = "content://media/$actualProviderVolume/images/media/"
    if (!mediaUri.startsWith(imagePrefix)) return false
    val mediaId = mediaUri.substring(imagePrefix.length)
    if (mediaId.isEmpty() || !mediaId.all { it in '0'..'9' }) return false
    if (!absolutePath.startsWith("/storage/emulated/0/")) return false
    val relative = absolutePath.removePrefix("/storage/emulated/0/")
    return relative.isNotEmpty() && !relative.startsWith("/") &&
        !relative.endsWith("/") &&
        !relative.contains("//") && !relative.contains("/../") &&
        !relative.contains("/./") && !relative.startsWith("../") &&
        !relative.startsWith("./") && !relative.endsWith("/..") &&
        !relative.endsWith("/.")
}
