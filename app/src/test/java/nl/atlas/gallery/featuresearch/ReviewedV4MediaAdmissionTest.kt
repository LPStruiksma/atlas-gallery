package nl.atlas.gallery.featuresearch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewedV4MediaAdmissionTest {
    private val path = "/storage/emulated/0/Pictures/Fictional/person.jpg"
    private val uri = "content://media/external_primary/images/media/420"

    @Test fun currentPrimaryPhotoMayCarryConfirmedV4Membership() {
        assertTrue(mayAttributeReviewedV4Person(uri, false, "external_primary", path))
        assertTrue(mayAttributeReviewedV4Person(
            "content://media/external/images/media/420", false, "external", path))
    }

    @Test fun identicalImagePathVideoCannotImpersonateV4ReviewedPerson() {
        assertFalse(mayAttributeReviewedV4Person(
            "content://media/external_primary/video/media/420", true,
            "external_primary", path))
        assertFalse(mayAttributeReviewedV4Person(uri, true, "external_primary", path))
        assertFalse(mayAttributeReviewedV4Person(
            "content://media/external_primary/video/media/420", false,
            "external_primary", path))
    }

    @Test fun removableVolumeAndUnknownVolumeCannotImpersonatePrimary() {
        assertFalse(mayAttributeReviewedV4Person(uri, false, "7F3A-19CC", path))
        assertFalse(mayAttributeReviewedV4Person(uri, false, null, path))
        assertFalse(mayAttributeReviewedV4Person(uri, false, "", path))
        assertFalse(mayAttributeReviewedV4Person(uri, false, "external-other", path))
    }

    @Test fun inconsistentUriAndProviderVolumeCannotClaimPerson() {
        assertFalse(mayAttributeReviewedV4Person(
            "content://media/external/images/media/420", false, "external_primary", path))
        assertFalse(mayAttributeReviewedV4Person(
            "content://media/external_primary/images/media/420", false, "external", path))
        assertFalse(mayAttributeReviewedV4Person(
            "content://media/7F3A-19CC/images/media/420", false, "external_primary", path))
    }

    @Test fun noExternalOrFilesystemUriIsTrusted() {
        assertFalse(mayAttributeReviewedV4Person(
            "file:///storage/emulated/0/Pictures/Fictional/person.jpg", false,
            "external_primary", path))
        assertFalse(mayAttributeReviewedV4Person(
            "content://com.example.private/images/420", false, "external_primary", path))
    }

    @Test fun canonicalImageMediaUriMustEndInNumericRowId() {
        for (uriValue in listOf(
            "content://media/external_primary/images/media/",
            "content://media/external_primary/images/media/not-an-id",
            "content://media/external_primary/images/media/42/video",
            "content://media/external_primary/images/media/42?secret=1",
            "content://media/external_primary/images/media/42#fragment"
        )) {
            assertFalse(mayAttributeReviewedV4Person(
                uriValue, false, "external_primary", path))
        }
        assertTrue(mayAttributeReviewedV4Person(uri, false, "external_primary", path))
    }

    @Test fun noRelativeOrOtherVolumePathsBecomeConfirmed() {
        assertFalse(mayAttributeReviewedV4Person(
            uri, false, "external_primary", "Pictures/Fictional/person.jpg"))
        assertFalse(mayAttributeReviewedV4Person(
            uri, false, "external_primary", "/storage/7F3A-19CC/Pictures/Fictional/person.jpg"))
        assertFalse(mayAttributeReviewedV4Person(
            uri, false, "external_primary", "/storage/emulated/0/"))
        assertFalse(mayAttributeReviewedV4Person(
            uri, false, "external_primary", "/storage/emulated/0/Pictures/"))
    }

    @Test fun pathTraversalOrAmbiguousSegmentsCannotBecomeReviewed() {
        for (untrusted in listOf(
            "/storage/emulated/0/../sdcard/Person.jpg",
            "/storage/emulated/0/Pictures/../Person.jpg",
            "/storage/emulated/0/Pictures/./Person.jpg",
            "/storage/emulated/0/Pictures//Person.jpg",
            "/storage/emulated/0/Pictures/..",
            "/storage/emulated/0/Pictures/."
        )) {
            assertFalse(mayAttributeReviewedV4Person(
                uri, false, "external_primary", untrusted))
        }
    }
}
