package nl.atlas.gallery.featurepeopleui

import java.util.Locale

/** Explicit, non-sensitive ordering preference; never changes reviewed identity membership. */
enum class ReviewedPeopleOrder { AS_PROVIDED, MOST_PHOTOS, NAME }

/** Keeps only confirmed profiles; no face score or speculative person classification. */
fun orderReviewedPeopleProfiles(
    profiles: List<PeopleProfileUi>,
    order: ReviewedPeopleOrder
): List<PeopleProfileUi> = when (order) {
    ReviewedPeopleOrder.AS_PROVIDED -> profiles
    ReviewedPeopleOrder.MOST_PHOTOS -> profiles.sortedWith(
        compareByDescending<PeopleProfileUi> { it.accessiblePhotos }
            .thenBy { it.name.lowercase(Locale.ROOT) }
            .thenBy { it.personId }
    )
    ReviewedPeopleOrder.NAME -> profiles.sortedWith(
        compareBy<PeopleProfileUi> { it.name.lowercase(Locale.ROOT) }
            .thenBy { it.personId }
    )
}
