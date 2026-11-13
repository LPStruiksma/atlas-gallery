package nl.atlas.gallery

/**
 * Android 14+ partial media grants may reject a scan after the cursor has
 * already supplied some rows. Preserve previously completed media types,
 * but never keep the denied scan's partially collected rows.
 *
 * SecurityException is expected for an unavailable media type; unrelated
 * exceptions still propagate so the enclosing inventory is not published.
 */
internal inline fun <T> appendMediaTypeOrRollbackOnDenial(
    target: MutableList<T>,
    scan: () -> Unit
) {
    val offset = target.size
    try {
        scan()
    } catch (_: SecurityException) {
        target.subList(offset, target.size).clear()
    }
}
