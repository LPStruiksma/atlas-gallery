package nl.atlas.gallery

/**
 * One initial-page lookup for one immutable, ordered viewer catalogue/entry.
 *
 * GalleryViewer remembers this snapshot by [CatalogSourceKey] plus entry URI,
 * so pointer motion, filmstrip settling, overlay loads and chrome recomposition
 * never repeat an O(n) URI scan over a 150k-item album on the UI thread.
 *
 * The gallery owns the catalogue's actual grant/route lifetime and rekeys the
 * enclosing viewer on a new permission epoch. This class retains no cache
 * across galleries; no URI is persisted, logged, or exported.
 */
internal class ViewerInitialPageSnapshot(
    items: List<MediaRecord>,
    entryUri: String
) {
    val initialPage: Int = selectionIndex(items, entryUri).coerceAtLeast(0)
}
