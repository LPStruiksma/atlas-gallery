package nl.atlas.gallery

/**
 * A URI-based return intent captured by the fullscreen viewer before its
 * destination grid is remounted. No index is persisted across publications.
 */
internal class PendingViewerReturn(
    val source: List<MediaRecord>, val uri: String,
    /** 0 Photos, 1 Search, 2 a human-reviewed Person album. */
    val route: Int,
    /** Monotone publication epoch: source-list identity alone cannot prove access. */
    val accessEpoch: Long
)

/** Source *reference* identity prevents O(150k) List.equals on Back. */
internal fun viewerReturnMatchesCurrentRoute(
    pending: PendingViewerReturn, current: List<MediaRecord>,
    route: Int, grantAuthorized: Boolean, currentAccessEpoch: Long
): Boolean = grantAuthorized && currentAccessEpoch >= 0L &&
    pending.accessEpoch == currentAccessEpoch &&
    pending.source === current && pending.route == route

/**
 * A result applies only after its exact current grant/route has enough mounted
 * entries and the target media URI remains at that position.
 */
internal fun viewerReturnCanApply(
    pending: PendingViewerReturn, current: List<MediaRecord>,
    route: Int, grantAuthorized: Boolean,
    mediaIndex: Int, mountedItemCount: Int,
    currentAccessEpoch: Long
): Boolean = viewerReturnMatchesCurrentRoute(
    pending, current, route, grantAuthorized, currentAccessEpoch) &&
    mediaIndex >= 0 && mediaIndex < current.size &&
    mountedItemCount > mediaIndex && current[mediaIndex].uri == pending.uri
