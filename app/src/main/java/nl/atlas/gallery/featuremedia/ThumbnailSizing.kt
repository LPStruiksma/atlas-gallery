package nl.atlas.gallery.featuremedia

/**
 * The sharp grid preview needs enough pixels for its settled cell, not for an
 * entire display. Use the actual measured cell width so every zoom level keeps
 * its existing at-rest detail. Clamp malformed dimensions before constructing
 * a Coil request; a cell cannot be wider than its display.
 *
 * Pure sizing logic so the zoom/cell contract can be regression-tested on JVM.
 */
internal fun thumbnailDecodePixels(cellPixels: Int, displayPixels: Int): Int {
    val display = displayPixels.coerceAtLeast(1)
    return cellPixels.coerceIn(1, display)
}
