package nl.atlas.gallery.featuregrid

/**
 * Lazy grid/list item keys may enter Android's saved-state registry.
 * Never pass a MediaStore URI, filename, folder, face ID or full media key as
 * a lazy-item key. Use a non-sensitive position scoped by the *parent* Compose
 * key(publication, layout), so reused positions cannot resurrect old-grant
 * remembered item composition state.
 *
 * Position keys are stable only for one immutable authorized layout; callers
 * MUST rekey the parent on catalogue/grant and layout changes.
 */
internal fun gridLazySaveablePositionKey(index: Int): Int {
    require(index >= 0)
    return index
}
