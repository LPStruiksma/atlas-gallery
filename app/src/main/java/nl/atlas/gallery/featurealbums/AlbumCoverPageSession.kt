package nl.atlas.gallery.featurealbums

import nl.atlas.gallery.MediaRecord

/**
 * Immutable, session-only page navigation. Each step keeps ONE cursor and a
 * link to its predecessor: advancing/backtracking is O(1), with no growing
 * List.copyOf or 150k-item source equality comparison.
 *
 * A session is never an access capability. I must supply the currently
 * authorized source and grant epoch again before rendering/applying a choice.
 *
 * Every admission below takes an optional live permission scope; null means
 * the conservative epoch-derived default and an invalid (negative) epoch fails
 * closed instead of throwing from a default-argument constructor.
 */
class AlbumCoverPageSession private constructor(
    val source: List<MediaRecord>,
    val accessEpoch: Long,
    val route: AlbumRoute,
    val cursor: AlbumCoverPageCursor?,
    val pageNumber: Int,
    val permissionScope: AlbumCoverPermissionScope,
    private val previousPage: AlbumCoverPageSession?
) {
    companion object {
        /** Opening a cover chooser creates a new page-1 session. */
        fun start(
            authorizedSource: List<MediaRecord>,
            accessEpoch: Long,
            route: AlbumRoute,
            permissionScope: AlbumCoverPermissionScope? = null
        ): AlbumCoverPageSession {
            require(accessEpoch >= 0L) { "Album access epoch must be nonnegative" }
            return AlbumCoverPageSession(
                authorizedSource, accessEpoch, route, null, 1,
                permissionScope ?: AlbumCoverPermissionScope(accessEpoch), null
            )
        }
    }

    /** Compare source by reference, never by structural List.equals. */
    fun isCurrent(
        authorizedSource: List<MediaRecord>,
        accessEpoch: Long,
        route: AlbumRoute,
        currentPermissionScope: AlbumCoverPermissionScope? = null
    ): Boolean {
        val scope = currentPermissionScope
            ?: defaultAlbumCoverPermissionScopeOrNull(accessEpoch) ?: return false
        return source === authorizedSource &&
            this.accessEpoch == accessEpoch && this.route == route &&
            permissionScope == scope &&
            accessEpoch >= 0L
    }

    /** An existing session is invalid after a new permission/source publication. */
    fun currentOrNull(
        authorizedSource: List<MediaRecord>,
        accessEpoch: Long,
        route: AlbumRoute,
        currentPermissionScope: AlbumCoverPermissionScope? = null
    ): AlbumCoverPageSession? =
        takeIf { isCurrent(authorizedSource, accessEpoch, route, currentPermissionScope) }

    /** The currently published page must match the exact pending cursor. */
    fun displayedCandidatesOrNull(
        projection: AlbumCoverChoiceProjection?,
        authorizedSource: List<MediaRecord>,
        accessEpoch: Long,
        route: AlbumRoute,
        currentPermissionScope: AlbumCoverPermissionScope? = null
    ): List<MediaRecord>? {
        val scope = currentPermissionScope
            ?: defaultAlbumCoverPermissionScopeOrNull(accessEpoch) ?: return null
        return if (!isCurrent(authorizedSource, accessEpoch, route, scope)) null
        else currentAlbumCoverChoicesOrNull(
            projection, authorizedSource, accessEpoch, route, cursor,
            permissionScope = scope
        )
    }

    /**
     * Advance only from a fully published, exact-current page.
     * Repeated or out-of-order callbacks cannot skip a page or revive an
     * old grant. A missing overflow sentinel means no Older button.
     */
    fun olderOrNull(
        projection: AlbumCoverChoiceProjection?,
        authorizedSource: List<MediaRecord>,
        accessEpoch: Long,
        route: AlbumRoute,
        currentPermissionScope: AlbumCoverPermissionScope? = null
    ): AlbumCoverPageSession? {
        val scope = currentPermissionScope
            ?: defaultAlbumCoverPermissionScopeOrNull(accessEpoch) ?: return null
        if (displayedCandidatesOrNull(
                projection, authorizedSource, accessEpoch, route, scope
            ) == null || pageNumber == Int.MAX_VALUE) return null
        val next = projection?.nextOlderCursor ?: return null
        if (next.accessEpoch != accessEpoch || next.route != route ||
            next.permissionScope != scope) return null
        if (cursor != null && !isStrictlyOlder(next, cursor)) return null
        return AlbumCoverPageSession(
            source, accessEpoch, route, next, pageNumber + 1, permissionScope, this
        )
    }

    /** Backtracking is O(1) and rejects stale permission/source state. */
    fun newerOrNull(
        authorizedSource: List<MediaRecord>,
        accessEpoch: Long,
        route: AlbumRoute,
        currentPermissionScope: AlbumCoverPermissionScope? = null
    ): AlbumCoverPageSession? =
        if (isCurrent(authorizedSource, accessEpoch, route, currentPermissionScope))
            previousPage else null
}

private fun isStrictlyOlder(
    candidate: AlbumCoverPageCursor,
    present: AlbumCoverPageCursor
): Boolean = candidate.takenAtMillis < present.takenAtMillis ||
    (candidate.takenAtMillis == present.takenAtMillis &&
        candidate.uri < present.uri)
