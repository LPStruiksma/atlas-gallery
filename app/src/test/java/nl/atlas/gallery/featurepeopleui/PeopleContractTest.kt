package nl.atlas.gallery.featurepeopleui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeopleContractTest {
    private val key = ReviewedFaceKey("/storage/emulated/0/DCIM/test.jpg", 7, 2)
    private val hint = V4FaceSidecarHint(
        schema = "atlas.v4.face_metadata.v2",
        key = key,
        reviewState = "confirmed",
        normalizedBox = NormalizedFaceBox(0.0, 0.1, 0.2, 0.3),
        indexedWidth = 1000, indexedHeight = 800,
        indexedSizeBytes = 1234, indexedMtimeNanos = 888,
        manualCover = true
    )
    private val media = CurrentMediaPortraitEvidence(
        mediaUri = "content://media/external/images/media/42",
        authorizedAbsolutePath = key.imagePath,
        width = 1000, height = 800, sizeBytes = 1234, nativeMtimeNanos = 888,
        permissionEpoch = "grant3", verifiedPermissionEpoch = "grant3",
        sourceOrientationMatched = true, authorizedNow = true
    )

    @Test fun unavailableDiscoveryNeverOffersFalseIndexingClaim() {
        val state = FaceDiscoveryUiState()
        assertEquals(FaceDiscoveryPhase.UNAVAILABLE, state.phase)
        assertTrue(discoveryHeadline(state).contains("isn't in this build"))
        assertTrue(discoveryExplanation(state).contains("does not scan faces"))
        assertFalse(state.isBusy)
    }

    @Test fun staleScannerStatusCannotEscapePermissionReselection() {
        val old = FaceDiscoveryUiState(
            phase = FaceDiscoveryPhase.SCANNING, processed = 4, eligible = 8,
            unknownObservations = 3, observedPermissionEpoch = "grant-7"
        )
        assertEquals(FaceDiscoveryPhase.UNAVAILABLE, old.restrictedToGrant("grant-8").phase)
        assertEquals(0, old.restrictedToGrant(null).unknownObservations)
        assertTrue(old.restrictedToGrant("grant-7") === old)
        val malformed = FaceDiscoveryUiState(
            FaceDiscoveryPhase.UNAVAILABLE, unknownObservations = 88,
            observedPermissionEpoch = "old")
        assertEquals(0, malformed.restrictedToGrant("new").unknownObservations)
    }

    @Test fun detectorReviewCannotApproveIdentityOrMergePeople() {
        assertEquals(
            setOf(DetectionReviewChoice.UNREVIEWED, DetectionReviewChoice.LATER,
                DetectionReviewChoice.NOT_A_FACE),
            DetectionReviewChoice.entries.toSet()
        )
        val key = DetectedFaceReviewKey(
            nativeFaceUid = "a".repeat(32),
            canonicalMediaKey = "content://media/fictional/images/42",
            sourceRevision = "revision-1", detectorVersion = "detector-1",
            permissionEpoch = "grant-7", ordinal = 0
        )
        val obs = DetectedFaceReviewCandidate(
            key = key, title = "Unknown face", explanation = "Detection only"
        )
        assertEquals(DetectionReviewChoice.UNREVIEWED, obs.decision)
        assertTrue(key.validFor("grant-7"))
        assertFalse(key.validFor("grant-8"))
        assertFalse(key.validFor(null))
        assertEquals(
            NativeFaceReviewCommand("grant-7", "a".repeat(32), DetectionReviewChoice.LATER),
            nativeReviewCommandOrNull(key, DetectionReviewChoice.LATER, "grant-7")
        )
        assertNull(nativeReviewCommandOrNull(key, DetectionReviewChoice.NOT_A_FACE, "grant-8"))
        assertNull(nativeReviewCommandOrNull(key, DetectionReviewChoice.UNREVIEWED, null))
        try {
            key.copy(nativeFaceUid = "7") // V4 numeric face IDs MUST NOT be usable.
            throw AssertionError("numeric V4 face ID accepted as native observation")
        } catch (_: IllegalArgumentException) { }
        try {
            key.copy(nativeFaceUid = "A".repeat(32))
            throw AssertionError("uppercase or noncanonical observation UID accepted")
        } catch (_: IllegalArgumentException) { }
        try {
            key.copy(ordinal = 32)
            throw AssertionError("out-of-range detector ordinal was accepted")
        } catch (_: IllegalArgumentException) { }
        assertTrue(discoveryExplanation(FaceDiscoveryUiState(
            phase = FaceDiscoveryPhase.REVIEW_AVAILABLE)).contains("not identified"))
    }

    @Test fun holdStatesAreExplicitAndDoNotSuggestScanning() {
        val permissions = FaceDiscoveryUiState(
            FaceDiscoveryPhase.PERMISSION_REQUIRED, observedPermissionEpoch = "grant-7")
        assertTrue(discoveryHeadline(permissions).contains("access"))
        assertTrue(discoveryExplanation(FaceDiscoveryUiState(
            FaceDiscoveryPhase.STORAGE_LOW)).contains("not make room"))
        assertTrue(discoveryExplanation(FaceDiscoveryUiState(
            FaceDiscoveryPhase.BATTERY_LOW)).contains("battery"))
    }

    @Test fun portraitRequiresExactCsvTripleAndCurrentMediaEvidence() {
        assertNull(bindVerifiedPortrait(hint, emptySet(), media))
        assertNull(bindVerifiedPortrait(hint,
            setOf(key.copy(faceId = 8)), media))
        assertNull(bindVerifiedPortrait(hint,
            setOf(key), media.copy(verifiedPermissionEpoch = "grant2")))
        assertNull(bindVerifiedPortrait(hint, setOf(key),
            media.copy(nativeMtimeNanos = null)))
        assertNull(bindVerifiedPortrait(hint, setOf(key),
            media.copy(sourceOrientationMatched = false)))
        assertNull(bindVerifiedPortrait(hint, setOf(key),
            media.copy(authorizedAbsolutePath = "/storage/emulated/0/DCIM/different.jpg")))
        assertNull(bindVerifiedPortrait(hint.copy(reviewState = "auto"), setOf(key), media))
        assertNull(bindVerifiedPortrait(hint.copy(schema = "atlas.v4.face_metadata.v1"), setOf(key), media))
        assertNull(bindVerifiedPortrait(hint.copy(normalizedBox = null), setOf(key), media))
        val bound = bindVerifiedPortrait(hint, setOf(key), media)
        assertEquals(media.mediaUri, bound?.mediaUri)
        assertTrue(bound?.chosenManually == true)
        assertEquals("grant3", bound?.verifiedPermissionEpoch)
    }

    @Test fun cropSlidesInsideBorderWithoutSquashingPortrait() {
        val expanded = expandedPortraitRect(NormalizedFaceBox(0.0, 0.1, 0.2, 0.3), 0.5)
        assertEquals(0.0, expanded.left, 0.00001)
        assertEquals(0.4, expanded.width, 0.00001)
        assertEquals(0.6, expanded.height, 0.00001)
        assertTrue(expanded.valid())
        assertFalse(NormalizedFaceBox(Double.NaN, 0.1, 0.2, 0.2).valid())
        assertFalse(NormalizedFaceBox(0.9, 0.1, 0.2, 0.2).valid())
    }

    @Test fun imageCountSidecarRequiresCsvAndExactCurrentMediaProof() {
        val source = V4ImageCountHint(
            schema = "atlas.v4.face_metadata.v2",
            imagePath = key.imagePath, countStatus = "verified",
            totalIndexedFaces = 2, eligibleAssignedFaceRows = 1,
            indexedWidth = 1000, indexedHeight = 800,
            indexedSizeBytes = 1234, indexedMtimeNanos = 888,
            verifiedSidecarSeal = "a".repeat(64)
        )
        val sourcePaths = setOf(key.imagePath)
        assertNull(bindVerifiedImageFaceCount(source, emptySet(), media, "rev1"))
        assertNull(bindVerifiedImageFaceCount(source, sourcePaths,
            media.copy(verifiedPermissionEpoch = "grant4"), "rev1"))
        assertNull(bindVerifiedImageFaceCount(source, sourcePaths,
            media.copy(nativeMtimeNanos = null), "rev1"))
        assertNull(bindVerifiedImageFaceCount(source, sourcePaths, media, null))
        assertNull(bindVerifiedImageFaceCount(
            source.copy(totalIndexedFaces = 0), sourcePaths, media, "rev1"))
        assertNull(bindVerifiedImageFaceCount(
            source.copy(countStatus = "unknown", totalIndexedFaces = null),
            sourcePaths, media, "rev1"))
        assertNull(bindVerifiedImageFaceCount(
            source.copy(schema = "atlas.v4.face_metadata.v1"), sourcePaths, media, "rev1"))
        assertNull(bindVerifiedImageFaceCount(
            source.copy(verifiedSidecarSeal = ""), sourcePaths, media, "rev1"))
        assertNull(bindVerifiedImageFaceCount(
            source.copy(verifiedSidecarSeal = "bad"), sourcePaths, media, "rev1"))
        val count = bindVerifiedImageFaceCount(source, sourcePaths, media, "rev1")
        assertEquals(2, count?.knownTotalFor(media.mediaUri, "grant3", "rev1"))
        assertNull(count?.knownTotalFor(media.mediaUri, "grant4", "rev1"))
        assertNull(count?.knownTotalFor(media.mediaUri, "grant3", "rev2"))
    }

    @Test fun zeroFaceCountNeedsVerifiedCurrentGrantAndRevision() {
        assertNull(IndexedFaceCount(0, false, "epoch", "grant", "rev", media.mediaUri)
            .knownTotalFor(media.mediaUri, "grant", "rev"))
        assertNull(IndexedFaceCount(0, true, "", "grant", "rev", media.mediaUri)
            .knownTotalFor(media.mediaUri, "grant", "rev"))
        val count = IndexedFaceCount(0, true, "sealed", "grant1", "media-rev1", media.mediaUri)
        assertNull(count.knownTotalFor(media.mediaUri, null, "media-rev1"))
        assertNull(count.knownTotalFor(media.mediaUri, "grant2", "media-rev1"))
        assertNull(count.knownTotalFor(media.mediaUri, "grant1", "media-rev2"))
        assertEquals(0, count.knownTotalFor(media.mediaUri, "grant1", "media-rev1"))
        assertNull(count.knownTotalFor("content://media/another/item", "grant1", "media-rev1"))
    }
}
