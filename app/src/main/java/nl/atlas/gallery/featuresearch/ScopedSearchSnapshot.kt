package nl.atlas.gallery.featuresearch

import nl.atlas.gallery.MediaRecord
import nl.atlas.gallery.PersonEntry

/**
 * Worker result bound to the exact source list, grant and scope. No stale
 * prefix, different people publication or old permission is admissible.
 */
class ScopedMediaSearchSnapshot(
    val screenSource: List<MediaRecord>,
    val librarySource: List<MediaRecord>,
    val peopleSource: List<PersonEntry>,
    val permissionEpoch: String,
    val query: String,
    val scope: AtlasSearchScope,
    val results: List<MediaRecord>
)

fun currentScopedMediaSearchOrNull(
    snapshot: ScopedMediaSearchSnapshot?,
    screenSource: List<MediaRecord>,
    librarySource: List<MediaRecord>,
    peopleSource: List<PersonEntry>,
    permissionEpoch: String?,
    query: String,
    scope: AtlasSearchScope
): List<MediaRecord>? = snapshot?.takeIf {
    !permissionEpoch.isNullOrBlank() &&
        it.screenSource === screenSource &&
        it.librarySource === librarySource &&
        it.peopleSource === peopleSource &&
        it.permissionEpoch == permissionEpoch &&
        it.query == query && it.scope == scope
}?.results
