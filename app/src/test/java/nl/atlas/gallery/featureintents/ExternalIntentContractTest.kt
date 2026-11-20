package nl.atlas.gallery.featureintents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** BENCH-2 (#573): every rule of the external intent contract, synthetic URIs only. */
class ExternalIntentContractTest {
    private val photo = "content://media/external_primary/images/media/41"
    private val chatPhoto = "content://com.example.chat.files/shared/IMG_0001.jpg"

    private fun parse(
        action: String?, data: String? = null, type: String? = null,
        clip: List<String> = emptyList(), multiple: Boolean = false, mimes: List<String>? = null
    ) = ExternalIntentParser.parse(ExternalIntentFacts(action, data, type, clip, multiple, mimes))

    @Test fun actionNamesAreTheStandardPlatformConstants() {
        assertEquals("android.intent.action.VIEW", ExternalIntentParser.ACTION_VIEW)
        assertEquals("android.intent.action.GET_CONTENT", ExternalIntentParser.ACTION_GET_CONTENT)
        assertEquals("android.intent.action.PICK", ExternalIntentParser.ACTION_PICK)
        assertEquals("android.provider.action.REVIEW", ExternalIntentParser.ACTION_REVIEW)
        assertEquals("android.provider.action.REVIEW_SECURE", ExternalIntentParser.ACTION_REVIEW_SECURE)
    }

    @Test fun launcherAndUnknownActionsOpenTheLibrary() {
        assertEquals(ExternalEntry.Library, parse("android.intent.action.MAIN"))
        assertEquals(ExternalEntry.Library, parse(null))
        // The OEM alias is not a substitute for MediaStore.ACTION_REVIEW (#573 revision).
        assertEquals(ExternalEntry.Library, parse("com.android.camera.action.REVIEW", photo, "image/jpeg"))
    }

    @Test fun viewOpensExactlyTheDataUri() {
        assertEquals(ExternalEntry.Viewer(chatPhoto, ExternalViewKind.VIEW, "image/jpeg"),
            parse(ExternalIntentParser.ACTION_VIEW, chatPhoto, "image/jpeg"))
    }

    @Test fun reviewAndSecureReviewKeepTheirKinds() {
        val review = parse(ExternalIntentParser.ACTION_REVIEW, photo, "image/jpeg") as ExternalEntry.Viewer
        val secure = parse(ExternalIntentParser.ACTION_REVIEW_SECURE, photo, "video/mp4") as ExternalEntry.Viewer
        assertEquals(ExternalViewKind.REVIEW, review.kind)
        assertFalse(review.kind.secure)
        assertEquals(ExternalViewKind.REVIEW_SECURE, secure.kind)
        assertTrue(secure.kind.secure)
    }

    @Test fun dataWinsOverClipDataAndExtraClipItemsAreIgnored() {
        val other = "content://media/external_primary/images/media/42"
        val entry = parse(ExternalIntentParser.ACTION_REVIEW_SECURE, photo, "image/jpeg",
            clip = listOf(other, "content://media/external_primary/images/media/43"))
        assertEquals(photo, (entry as ExternalEntry.Viewer).uri)
    }

    @Test fun clipDataIsUsedOnlyWhenDataIsAbsentAndOnlyItsFirstItem() {
        val other = "content://media/external_primary/images/media/42"
        val entry = parse(ExternalIntentParser.ACTION_REVIEW, null, "image/jpeg", clip = listOf(photo, other))
        assertEquals(photo, (entry as ExternalEntry.Viewer).uri)
    }

    @Test fun missingUriIsRejectedAndKeepsTheSecureFlag() {
        assertEquals(ExternalEntry.Rejected(ExternalRejectReason.MISSING_URI, secure = false),
            parse(ExternalIntentParser.ACTION_VIEW))
        assertEquals(ExternalEntry.Rejected(ExternalRejectReason.MISSING_URI, secure = true),
            parse(ExternalIntentParser.ACTION_REVIEW_SECURE, "  "))
    }

    @Test fun fileAndOtherSchemesAreNeverFollowed() {
        for (uri in listOf("file:///sdcard/DCIM/a.jpg", "FILE:///storage/emulated/0/a.jpg",
                "https://example.com/a.jpg", "/sdcard/DCIM/a.jpg", "content:/no-authority/1",
                "content:///1", "contentx://media/1", "content://bad host/1")) {
            assertEquals(uri, ExternalEntry.Rejected(ExternalRejectReason.NOT_CONTENT_URI, secure = true),
                parse(ExternalIntentParser.ACTION_REVIEW_SECURE, uri, "image/jpeg"))
        }
        // A file:// clip item is not used to rescue a missing data URI either.
        assertEquals(ExternalEntry.Rejected(ExternalRejectReason.NOT_CONTENT_URI, secure = false),
            parse(ExternalIntentParser.ACTION_VIEW, null, "image/jpeg", clip = listOf("file:///a.jpg")))
    }

    @Test fun nonMediaDeclaredTypesAreRejected() {
        assertEquals(ExternalEntry.Rejected(ExternalRejectReason.UNSUPPORTED_TYPE, secure = false),
            parse(ExternalIntentParser.ACTION_VIEW, chatPhoto, "application/pdf"))
        assertEquals(ExternalEntry.Rejected(ExternalRejectReason.UNSUPPORTED_TYPE, secure = false),
            parse(ExternalIntentParser.ACTION_VIEW, chatPhoto, "imagejpeg"))
    }

    @Test fun absentOrWildcardTypeIsLeftForTheProviderCheck() {
        assertEquals(ExternalEntry.Viewer(chatPhoto, ExternalViewKind.VIEW, null),
            parse(ExternalIntentParser.ACTION_VIEW, chatPhoto, null))
        assertEquals(ExternalEntry.Viewer(chatPhoto, ExternalViewKind.VIEW, null),
            parse(ExternalIntentParser.ACTION_VIEW, chatPhoto, "*/*"))
    }

    @Test fun onlySecureEntriesMayShowWhenLocked() {
        assertTrue(ExternalIntentParser.showWhenLocked(
            parse(ExternalIntentParser.ACTION_REVIEW_SECURE, photo, "image/jpeg")))
        assertTrue(ExternalIntentParser.showWhenLocked(
            parse(ExternalIntentParser.ACTION_REVIEW_SECURE, "file:///a.jpg", "image/jpeg")))
        assertFalse(ExternalIntentParser.showWhenLocked(
            parse(ExternalIntentParser.ACTION_REVIEW, photo, "image/jpeg")))
        assertFalse(ExternalIntentParser.showWhenLocked(
            parse(ExternalIntentParser.ACTION_VIEW, photo, "image/jpeg")))
        assertFalse(ExternalIntentParser.showWhenLocked(parse(ExternalIntentParser.ACTION_GET_CONTENT, type = "image/*")))
        assertFalse(ExternalIntentParser.showWhenLocked(ExternalEntry.Library))
    }

    @Test fun mediaKindNeedsATopLevelImageOrVideoType() {
        assertEquals(ExternalMediaKind.IMAGE, ExternalIntentParser.mediaKindOf("image/heic"))
        assertEquals(ExternalMediaKind.VIDEO, ExternalIntentParser.mediaKindOf("VIDEO/MP4"))
        assertNull(ExternalIntentParser.mediaKindOf("image"))
        assertNull(ExternalIntentParser.mediaKindOf("audio/mpeg"))
        assertNull(ExternalIntentParser.mediaKindOf(null))
    }

    @Test fun getContentReadsTypeAndAllowMultiple() {
        assertEquals(ExternalEntry.Pick(PickMediaFilter.IMAGES, allowMultiple = true, legacyPick = false),
            parse(ExternalIntentParser.ACTION_GET_CONTENT, type = "image/*", multiple = true))
        assertEquals(ExternalEntry.Pick(PickMediaFilter.ANY_MEDIA, allowMultiple = false, legacyPick = false),
            parse(ExternalIntentParser.ACTION_GET_CONTENT, type = "*/*"))
        assertEquals(ExternalEntry.Pick(PickMediaFilter.ANY_MEDIA, allowMultiple = false, legacyPick = false),
            parse(ExternalIntentParser.ACTION_GET_CONTENT))
    }

    @Test fun extraMimeTypesNarrowAWildcardRequest() {
        val entry = parse(ExternalIntentParser.ACTION_GET_CONTENT, type = "*/*",
            mimes = listOf("image/png", "application/pdf")) as ExternalEntry.Pick
        // */* in the type itself keeps any media; the caller re-checks on return.
        assertEquals(PickMediaFilter.ANY_MEDIA, entry.filter)
        val narrow = parse(ExternalIntentParser.ACTION_GET_CONTENT, type = null,
            mimes = listOf("image/png", "application/pdf")) as ExternalEntry.Pick
        assertTrue(narrow.filter.allowsImages)
        assertFalse(narrow.filter.allowsVideos)
        assertTrue(narrow.filter.accepts("image/png"))
        assertFalse(narrow.filter.accepts("image/jpeg"))
    }

    @Test fun legacyPickReadsTheCollectionUri() {
        assertEquals(ExternalEntry.Pick(PickMediaFilter.IMAGES, allowMultiple = false, legacyPick = true),
            parse(ExternalIntentParser.ACTION_PICK, "content://media/external/images/media"))
        assertEquals(ExternalEntry.Pick(PickMediaFilter.VIDEOS, allowMultiple = true, legacyPick = true),
            parse(ExternalIntentParser.ACTION_PICK, "content://media/external/video/media", multiple = true))
    }

    @Test fun nonMediaPickIsRejected() {
        assertEquals(ExternalEntry.Rejected(ExternalRejectReason.UNSUPPORTED_TYPE, secure = false, pick = true),
            parse(ExternalIntentParser.ACTION_GET_CONTENT, type = "application/pdf"))
        assertEquals(ExternalEntry.Rejected(ExternalRejectReason.UNSUPPORTED_TYPE, secure = false, pick = true),
            parse(ExternalIntentParser.ACTION_GET_CONTENT, type = "audio/*"))
    }

    @Test fun pickFilterNormalisesAndCollapses() {
        assertEquals(PickMediaFilter.ANY_MEDIA, PickMediaFilter.of(listOf("image/*", "VIDEO/*")))
        assertEquals(PickMediaFilter.IMAGES, PickMediaFilter.of(listOf("image/*", "image/png")))
        assertEquals(PickMediaFilter.ANY_MEDIA, PickMediaFilter.of(emptyList()))
        assertNull(PickMediaFilter.of(listOf("text/plain")))
        val videos = PickMediaFilter.VIDEOS
        assertTrue(videos.offers(isVideo = true))
        assertFalse(videos.offers(isVideo = false))
        assertTrue(videos.accepts("video/mp4; codecs=avc1"))
        assertFalse(videos.accepts("image/jpeg"))
        assertFalse(videos.accepts(null))
        assertFalse(PickMediaFilter.ANY_MEDIA.accepts("application/octet-stream"))
    }

    @Test fun singlePickReturnsOneUriAndRejectsMore() {
        val single = ExternalEntry.Pick(PickMediaFilter.IMAGES, allowMultiple = false, legacyPick = false)
        val plan = PickResultPlanner.plan(single, listOf(photo))!!
        assertEquals(photo, plan.primaryUri)
        assertEquals(listOf(photo), plan.clipUris)
        assertNull(PickResultPlanner.plan(single, listOf(photo, chatPhoto)))
        assertNull(PickResultPlanner.plan(single, emptyList()))
        assertTrue(PickResultPlanner.doneEnabled(single, 1))
        assertFalse(PickResultPlanner.doneEnabled(single, 0))
        assertFalse(PickResultPlanner.doneEnabled(single, 2))
    }

    @Test fun multiplePickKeepsOrderAndRefusesBadEntries() {
        val multiple = ExternalEntry.Pick(PickMediaFilter.ANY_MEDIA, allowMultiple = true, legacyPick = false)
        val second = "content://media/external_primary/video/media/7"
        val plan = PickResultPlanner.plan(multiple, listOf(second, photo))!!
        assertEquals(second, plan.primaryUri)
        assertEquals(listOf(second, photo), plan.clipUris)
        assertNull(PickResultPlanner.plan(multiple, listOf(photo, photo)))
        assertNull(PickResultPlanner.plan(multiple, listOf(photo, "file:///a.jpg")))
        assertTrue(PickResultPlanner.doneEnabled(multiple, 3))
        assertFalse(PickResultPlanner.doneEnabled(multiple, 0))
    }
}
