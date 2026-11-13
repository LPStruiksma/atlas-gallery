package nl.atlas.gallery

/**
 * Cheap, bounded cancellation checkpoints for a very large MediaStore cursor.
 *
 * The calling IO coroutine supplies Job.ensureActive() as [checkActive].
 * Inspect 1 in every 128 rows (starting at row zero), independently of
 * image metadata, to bound expensive abandoned scans without per-row work.
 * A scan with no rows is checked before the query by the repository.
 */
internal inline fun checkGalleryScanCancellation(
    rowIndex: Int,
    checkActive: () -> Unit
) {
    require(rowIndex >= 0) { "Negative MediaStore scan row index" }
    if ((rowIndex and 127) == 0) checkActive()
}
