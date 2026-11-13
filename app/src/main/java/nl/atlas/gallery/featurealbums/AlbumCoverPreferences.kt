package nl.atlas.gallery.featurealbums

import nl.atlas.gallery.MediaRecord

/**
 * A coordinator-owned permission-generation token. It must change on every
 * revoke, regrant or scope change, but may remain stable when the catalogue
 * is refreshed under the very same continuously verified grant.
 *
 * Never derive this token from a URI, filename, person or gallery epoch alone.
 */
data class AlbumCoverPermissionScope(val generation: Long) {
    init { require(generation >= 0L) { "Invalid permission-scope generation" } }
}

/**
 * Conservative default scope for callers that have no independent grant token.
 * Returns null (fail closed) for an invalid negative epoch, so *OrNull
 * admissions never throw from a default-argument constructor.
 */
internal fun defaultAlbumCoverPermissionScopeOrNull(accessEpoch: Long): AlbumCoverPermissionScope? =
    if (accessEpoch >= 0L) AlbumCoverPermissionScope(accessEpoch) else null

/**
 * Nonpersistent album-cover choices for one authorized inventory publication.
 * No filesystem/MediaStore operation; no raw URI is ever an access grant.
 *
 * The caller must obtain the CURRENT source/epoch on every event. A stale
 * Compose callback cannot publish a preference from an older grant.
 */
class AlbumCoverPreferences private constructor(
    val source: List<MediaRecord>,
    val accessEpoch: Long,
    val permissionScope: AlbumCoverPermissionScope,
    val revision: Long,
    val preferredCoverByBucket: Map<AlbumBucketKey, String>
) {
    companion object {
        const val MAX_SESSION_COVERS = 4096

        fun start(
            source: List<MediaRecord>,
            accessEpoch: Long,
            permissionScope: AlbumCoverPermissionScope =
                AlbumCoverPermissionScope(accessEpoch)
        ): AlbumCoverPreferences {
            require(accessEpoch >= 0L) { "An authorized nonnegative epoch is required" }
            return AlbumCoverPreferences(
                source, accessEpoch, permissionScope, 0L, emptyMap()
            )
        }
    }

    /** O(1); source identity deliberately uses === rather than List.equals. */
    fun currentOrNull(
        source: List<MediaRecord>,
        accessEpoch: Long,
        /** null = conservative default derived from [accessEpoch]; never throws. */
        currentPermissionScope: AlbumCoverPermissionScope? = null
    ): AlbumCoverPreferences? {
        val scope = currentPermissionScope
            ?: defaultAlbumCoverPermissionScopeOrNull(accessEpoch) ?: return null
        return takeIf {
            it.source === source && it.accessEpoch == accessEpoch &&
                it.permissionScope == scope && accessEpoch >= 0L
        }
    }

    /**
     * Admit only a typed candidate from the exact current projected page.
     * Never trust a free-standing URI, old permission epoch or stale row copy.
     * The mutation result is a new immutable session; originals are untouched.
     */
    fun chooseOrNull(
        projection: AlbumCoverChoiceProjection?,
        choice: AlbumCoverChoice,
        source: List<MediaRecord>,
        accessEpoch: Long,
        route: AlbumRoute,
        /** null = conservative default derived from [accessEpoch]; never throws. */
        currentPermissionScope: AlbumCoverPermissionScope? = null
    ): AlbumCoverPreferences? {
        val scope = currentPermissionScope
            ?: defaultAlbumCoverPermissionScopeOrNull(accessEpoch) ?: return null
        if (currentOrNull(source, accessEpoch, scope) == null ||
            revision == Long.MAX_VALUE) return null
        val accepted = chooseAlbumCoverOrNull(
            projection, source, accessEpoch, route, choice.coverUri,
            projection?.olderThan,
            permissionScope = scope
        ) ?: return null
        if (accepted.media !== choice.media ||
            accepted.bucket != choice.bucket ||
            accepted.accessEpoch != choice.accessEpoch ||
            accepted.permissionScope != choice.permissionScope ||
            choice.permissionScope != scope) return null
        if (preferredCoverByBucket[choice.bucket] == choice.coverUri) return this
        if (choice.bucket !in preferredCoverByBucket &&
            preferredCoverByBucket.size >= MAX_SESSION_COVERS) return null
        return AlbumCoverPreferences(
            source, accessEpoch, permissionScope, revision + 1L,
            preferredCoverByBucket + (choice.bucket to choice.coverUri)
        )
    }

    /**
     * Clear one preference without touching the album or its photos.
     * The current catalogue's default accessible photo becomes the cover.
     */
    fun clearOrNull(
        bucket: AlbumBucketKey,
        source: List<MediaRecord>,
        accessEpoch: Long,
        /** null = conservative default derived from [accessEpoch]; never throws. */
        currentPermissionScope: AlbumCoverPermissionScope? = null
    ): AlbumCoverPreferences? {
        if (currentOrNull(source, accessEpoch, currentPermissionScope) == null ||
            revision == Long.MAX_VALUE) return null
        if (bucket !in preferredCoverByBucket) return this
        return AlbumCoverPreferences(
            source, accessEpoch, permissionScope, revision + 1L,
            preferredCoverByBucket - bucket
        )
    }

    /**
     * Rebase only across a continually verified permission generation.
     * Default newPermissionScope conservatively follows publication epoch
     * and therefore purges choices on every new publication; I may provide
     * a stable independently minted grant scope to preserve preferences.
     *
     * Exact volume/path/URI membership is still rechecked in newSource.
     * The source itself is never copied, hashed or compared by value.
     */
    fun rebase(
        newSource: List<MediaRecord>,
        newEpoch: Long,
        checkActive: (() -> Unit)? = null,
        volumeOf: (MediaRecord) -> String = { it.volumeName },
        newPermissionScope: AlbumCoverPermissionScope =
            AlbumCoverPermissionScope(newEpoch)
    ): AlbumCoverPreferences {
        require(newEpoch >= 0L) { "An authorized nonnegative epoch is required" }
        // An obsolete/out-of-order publication may not inherit choices
        // from a newer gallery, even if a caller accidentally reuses a scope.
        if (newEpoch < accessEpoch) {
            return start(newSource, newEpoch, newPermissionScope)
        }
        // Conservative default: a new publication epoch is treated as a new
        // permission scope until I supplies an independently verified stable
        // grant generation. A changed explicit scope always clears choices.
        if (newPermissionScope != permissionScope) {
            return start(newSource, newEpoch, newPermissionScope)
        }
        if (newSource === source && newEpoch == accessEpoch) return this
        // Most users have no manually selected covers. Do not scan 150k items
        // after every ordinary catalogue refresh in that common case.
        if (preferredCoverByBucket.isEmpty()) {
            checkActive?.invoke()
            return AlbumCoverPreferences(
                newSource, newEpoch, newPermissionScope, revision, emptyMap()
            )
        }
        val retained = HashMap<AlbumBucketKey, String>()
        newSource.forEachIndexed { index, media ->
            if ((index and 511) == 0) checkActive?.invoke()
            val key = AlbumBucketKey(volumeOf(media), media.relativePath)
            val requested = preferredCoverByBucket[key]
            if (requested != null && requested == media.uri) retained[key] = requested
        }
        checkActive?.invoke()
        return AlbumCoverPreferences(
            newSource, newEpoch, newPermissionScope, revision,
            retained.toMap()
        )
    }
}
