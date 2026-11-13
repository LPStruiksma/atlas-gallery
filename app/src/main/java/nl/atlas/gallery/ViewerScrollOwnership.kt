package nl.atlas.gallery

/**
 * Pure main-thread scroll ownership transitions.
 *
 * Several Compose effects can scroll the same filmstrip concurrently: an
 * explicit thumbnail jump, pager reconciliation, and immersive-chrome return.
 * A Boolean flag lets one finishing effect clear another effect's ownership.
 * A positive depth retains ownership until every acquired scroll exits,
 * including cancellation in a caller's finally block.
 *
 * The Compose caller owns observable mutableIntStateOf depth. No global state,
 * references to images, jobs or saved user data are retained.
 */
internal fun acquireViewerScrollLease(depth: Int): Int {
    require(depth >= 0 && depth < Int.MAX_VALUE) { "Invalid programmatic scroll depth" }
    return depth + 1
}

internal fun releaseViewerScrollLease(depth: Int): Int {
    require(depth > 0) { "Unbalanced programmatic scroll release" }
    return depth - 1
}

internal fun isViewerScrollOwned(depth: Int): Boolean {
    require(depth >= 0) { "Invalid programmatic scroll depth" }
    return depth > 0
}
