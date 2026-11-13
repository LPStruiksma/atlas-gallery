package nl.atlas.gallery.featuregrid

/**
 * A pointer focus must refer to the exact item observed in the current immutable
 * catalogue. A stale index never authorizes an O(150k) URI search on the UI
 * thread: reorder/republication should cancel this gesture and start fresh.
 * Calls [mediaKeyAtIndex] at most once on a valid candidate, never on invalid
 * indexes, and does not materialize or scan a catalogue.
 */
internal fun currentFocalIndexOrNull(
    capturedIndex: Int,
    capturedKey: String,
    currentSize: Int,
    mediaKeyAtIndex: (Int) -> String?
): Int? {
    if (currentSize <= 0 || capturedIndex !in 0 until currentSize ||
        capturedKey.isBlank()) return null
    return capturedIndex.takeIf { mediaKeyAtIndex(it) == capturedKey }
}
