package nl.atlas.gallery.featuresearch

import nl.atlas.gallery.MediaRecord
import nl.atlas.gallery.featurealbums.AlbumBucketKey
import nl.atlas.gallery.featurealbums.AlbumMediaFilter
import nl.atlas.gallery.featurealbums.AlbumSummary
import nl.atlas.gallery.selectAuthorizedReviewedPersonAlbum

/** FIND-2 advanced tools offered by an overview header. */
enum class OverviewAdvancedMode { FACE, IMAGE }

/**
 * FIND-2 (#181): the People entries an overview list currently shows, after
 * its own name filter. Only reviewed entries count: unnamed face groups are
 * not a person and never widen the source.
 */
data class PeopleOverviewSelection(
    val importedPersonIds: Set<Long>,
    val nativePersonKeys: Set<String>
) {
    val isEmpty: Boolean get() = importedPersonIds.isEmpty() && nativePersonKeys.isEmpty()
}

/**
 * FIND-2 (#181) F: the media an Albums list represents. The result is drawn
 * only from [authorized] (the current grant's sorted publication) and keeps
 * its order, so Screen sort follows the gallery sort. An album is matched by
 * its exact volume and relative path, never by its title, and the list's
 * own photo/video filter applies to the members too.
 */
fun albumsOverviewUnion(
    authorized: List<MediaRecord>,
    displayed: List<AlbumSummary>,
    filter: AlbumMediaFilter,
    cancellationCheckpoint: () -> Unit = {}
): List<MediaRecord> {
    if (displayed.isEmpty() || authorized.isEmpty()) return emptyList()
    val keys = displayed.mapTo(HashSet()) { AlbumBucketKey(it.volumeName, it.relativePath) }
    return authorized.filterIndexed { index, record ->
        if ((index and 511) == 0) cancellationCheckpoint()
        filter.accepts(record) && AlbumBucketKey(record.volumeName, record.relativePath) in keys
    }.also { cancellationCheckpoint() }
}

/**
 * FIND-2 (#181) F: the media a People list represents. Imported reviewed
 * people contribute their confirmed paths through the same authorized
 * selection as their person album; native people contribute the URIs the
 * face host resolved under the live grant. Either way a member is kept only
 * when it is in [authorized], in that list's order. Anything else the
 * caller passes (stale URIs, another volume, a picked reference) is ignored.
 */
fun peopleOverviewUnion(
    authorized: List<MediaRecord>,
    importedConfirmedPaths: Set<String>,
    nativeAuthorizedUris: Set<String>,
    cancellationCheckpoint: () -> Unit = {}
): List<MediaRecord> {
    if (authorized.isEmpty()) return emptyList()
    if (importedConfirmedPaths.isEmpty() && nativeAuthorizedUris.isEmpty()) return emptyList()
    val imported = if (importedConfirmedPaths.isEmpty()) emptySet() else
        selectAuthorizedReviewedPersonAlbum(authorized, importedConfirmedPaths,
            cancellationCheckpoint = cancellationCheckpoint).mapTo(HashSet()) { it.uri }
    return authorized.filterIndexed { index, record ->
        if ((index and 511) == 0) cancellationCheckpoint()
        record.uri in imported || record.uri in nativeAuthorizedUris
    }.also { cancellationCheckpoint() }
}

/**
 * The publication a union was built from. A union stays usable only while
 * the grant, the gallery epoch, the overview route and these exact source
 * lists (reference identity, never a 150k-row equals) are unchanged.
 */
class OverviewUnionOrigin(
    val label: String,
    val authorized: List<MediaRecord>,
    val epoch: Long,
    val route: String,
    val grant: String,
    /** Imported reviewed People list (identity); null for an Albums union. */
    val peopleSource: Any? = null,
    /** Native People source generation; a new scan publication keeps it. */
    val nativeGeneration: String? = null
) {
    val tracksPeople: Boolean get() = peopleSource != null

    fun isCurrent(
        authorized: List<MediaRecord>, epoch: Long, route: String,
        grant: String?, peopleSource: Any? = null, nativeGeneration: String? = null
    ): Boolean = grant != null && grant == this.grant && epoch == this.epoch &&
        route == this.route && authorized === this.authorized &&
        (!tracksPeople || (peopleSource === this.peopleSource &&
            nativeGeneration == this.nativeGeneration))
}
