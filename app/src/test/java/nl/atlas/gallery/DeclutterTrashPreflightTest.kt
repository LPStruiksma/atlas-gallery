package nl.atlas.gallery

import org.junit.Assert.*
import org.junit.Test

/** All metadata and faces are fictional; tests do not touch MediaStore. */
class DeclutterTrashPreflightTest {
    private val reviewed = MediaRecord(
        id = 41L,
        uri = "content://media/external/images/media/41",
        name = "imaginary.jpg",
        relativePath = "DCIM/Fictional/",
        sizeBytes = 12_345L,
        takenAtMillis = 1000L,
        isVideo = false,
        modifiedAtSeconds = 1_800_000_000L
    )
    private val current = CurrentDeclutterMedia(
        id = 41L, name = "imaginary.jpg", relativePath = "DCIM/Fictional/",
        sizeBytes = 12_345L, modifiedAtSeconds = 1_800_000_000L,
        isTrashed = false
    )

    @Test fun matchingReviewedMetadataIsEligibleButNeverAuthorizesDeletionAlone() {
        assertTrue(sameReviewedMedia(reviewed, current))
        assertFalse(sameReviewedMedia(reviewed, current.copy(isTrashed = true)))
    }

    @Test fun unchangedFilenameDoesNotMaskAnInPlaceEditOrReplacement() {
        assertFalse(sameReviewedMedia(reviewed,
            current.copy(modifiedAtSeconds = current.modifiedAtSeconds + 1)))
        assertFalse(sameReviewedMedia(reviewed, current.copy(sizeBytes = 12_346L)))
        assertFalse(sameReviewedMedia(reviewed, current.copy(id = 42L)))
        assertFalse(sameReviewedMedia(reviewed,
            current.copy(name = "imaginary-edited.jpg")))
        assertFalse(sameReviewedMedia(reviewed,
            current.copy(relativePath = "Pictures/Elsewhere/")))
    }

    @Test fun unknownTimestampMustBlockTrashEvenWhenEverythingElseMatches() {
        assertFalse(sameReviewedMedia(reviewed.copy(modifiedAtSeconds = 0L), current))
        assertFalse(sameReviewedMedia(reviewed, current.copy(modifiedAtSeconds = 0L)))
        assertFalse(sameReviewedMedia(reviewed.copy(modifiedAtSeconds = -1L), current))
    }
}
