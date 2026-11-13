package nl.atlas.gallery.featurepeopleui

import nl.atlas.gallery.MediaRecord
import nl.atlas.gallery.PersonEntry

/**
 * Already-permitted input only. The caller must explicitly establish that the
 * row is from the primary emulated-storage identity domain used by V4 exports.
 * Do NOT pass a default true lambda: other volumes share display/path strings.
 */
class ReviewedPeopleProjection(
    val source: List<MediaRecord>,
    val people: List<PersonEntry>,
    val permissionEpoch: String,
    val profiles: List<PeopleProfileUi>,
    val ambiguousPathCount: Int
) {
    init { require(permissionEpoch.isNotBlank()) }
}

fun currentReviewedPeopleProjectionOrNull(
    projection: ReviewedPeopleProjection?,
    media: List<MediaRecord>,
    people: List<PersonEntry>,
    permissionEpoch: String?
): List<PeopleProfileUi>? = projection?.takeIf {
    !permissionEpoch.isNullOrBlank() &&
        it.permissionEpoch == permissionEpoch &&
        it.source === media && it.people === people
}?.profiles

/**
 * One inventory pass; restrict to the exact reviewed V4 paths and current
 * primary-storage MediaStore images. Conflicting URI matches are dropped to
 * Unknown rather than picking an arbitrary cover or inflating photo counts.
 *
 * Run off the Compose thread and pass a coroutine cancellation checkpoint.
 * Original paths are never written/logged or uploaded by this pure function.
 */
fun buildReviewedPeopleProjection(
    media: List<MediaRecord>,
    people: List<PersonEntry>,
    permissionEpoch: String,
    isAuthorizedPrimaryImage: (MediaRecord) -> Boolean,
    cancellationCheckpoint: () -> Unit = {}
): ReviewedPeopleProjection {
    require(permissionEpoch.isNotBlank())
    val wanted = HashSet<String>()
    val distinctPeopleIds = HashSet<Long>()
    people.forEachIndexed { index, person ->
        if ((index and 31) == 0) cancellationCheckpoint()
        // A corrupt V4 import must not publish two different named cards
        // under the same stable ID. Search applies the same no-winner rule.
        if (!distinctPeopleIds.add(person.id)) return ReviewedPeopleProjection(
            source = media, people = people, permissionEpoch = permissionEpoch,
            profiles = emptyList(), ambiguousPathCount = 0
        )
        wanted.addAll(person.imagePaths)
    }

    val unique = HashMap<String, MediaRecord>()
    val ambiguous = HashSet<String>()
    media.forEachIndexed { index, record ->
        if ((index and 511) == 0) cancellationCheckpoint()
        if (!isAuthorizedPrimaryImage(record) || record.isVideo ||
            !record.uri.startsWith("content://media/")) return@forEachIndexed
        val path = record.absolutePath
        if (path !in wanted || path in ambiguous) return@forEachIndexed
        val previous = unique.putIfAbsent(path, record)
        if (previous != null && previous.uri != record.uri) {
            unique.remove(path)
            ambiguous.add(path)
        }
    }
    cancellationCheckpoint()

    val profiles = people.mapIndexed { index, person ->
        if ((index and 31) == 0) cancellationCheckpoint()
        val matched = person.imagePaths.filter { it in unique }
        PeopleProfileUi(
            personId = person.id,
            name = person.name,
            confirmedPhotoReferences = person.count,
            accessiblePhotos = matched.size,
            coverUri = matched.firstNotNullOfOrNull { unique[it]?.uri }
        )
    }
    cancellationCheckpoint()
    return ReviewedPeopleProjection(
        source = media, people = people, permissionEpoch = permissionEpoch,
        profiles = profiles, ambiguousPathCount = ambiguous.size
    )
}
