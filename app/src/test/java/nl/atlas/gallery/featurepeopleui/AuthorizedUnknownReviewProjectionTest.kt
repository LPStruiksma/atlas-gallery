package nl.atlas.gallery.featurepeopleui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorizedUnknownReviewProjectionTest {
    private val scope = NativeReviewScope("grant-one", "source-index-one")
    private fun row(i: Int = 1, model: String = "detector-v1") =
        AuthorizedUnknownReviewEvidence(
            nativeFaceUid = i.toString(16).padStart(32, '0'),
            canonicalMediaKey = "atlas-face-media-v1:" + "a".repeat(64),
            sourceRevision = "atlas-face-rev-v1:" + "b".repeat(64),
            detectorVersion = model, ordinal = i - 1
        )
    private fun project(
        rows: List<AuthorizedUnknownReviewEvidence> = listOf(row()),
        grant: String = scope.permissionEpoch,
        source: String = scope.sourceGeneration,
        pending: Int? = rows.size,
        more: Boolean = false,
        live: NativeReviewScope? = scope
    ) = authorizedUnknownReviewQueueOrNull(
        grant, source, rows, pending, more, live
    )

    @Test fun currentPAndXProofMapsExactlyWithoutFakeIdentityOrUri() {
        val p = requireNotNull(project(
            rows = listOf(row(1, "stored-old-model"), row(2, "stored-new-model")),
            pending = 30, more = true
        ))
        assertEquals(30, p.totalVisibleObservations)
        assertTrue(p.hasMore)
        assertEquals(listOf("stored-old-model", "stored-new-model"),
            p.candidates.map { it.key.detectorVersion })
        assertEquals(listOf(0, 1), p.candidates.map { it.key.ordinal })
        assertEquals(listOf(row(1).nativeFaceUid, row(2).nativeFaceUid),
            p.candidates.map { it.key.nativeFaceUid })
        assertTrue(p.candidates.all { it.title == "Unknown face" })
        assertTrue(p.candidates.all { it.key.permissionEpoch == "grant-one" })
        assertEquals("grant-one", currentNativeReviewCommandOrNull(
            p, p.candidates.first().key, DetectionReviewChoice.NOT_A_FACE,
            scope.permissionEpoch, scope.sourceGeneration
        )?.permissionEpoch)
    }

    @Test fun staleGrantSourceReplacementAndMissingLiveSnapshotFailClosed() {
        assertNull(project(grant = "grant-old"))
        assertNull(project(source = "source-index-old"))
        assertNull(project(live = null))
        assertNull(project(live = NativeReviewScope("grant-two", "source-index-one")))
        assertNull(project(live = NativeReviewScope("grant-one", "source-index-two")))
        assertNull(project(pending = null))
        assertNull(project(pending = -1))
        assertNull(project(pending = 0))
        assertNull(project(grant = ""))
        assertNull(project(source = ""))
    }

    @Test fun entirePageRejectedForMalformedModelUidOrSourceRevision() {
        assertNull(project(rows = listOf(row().copy(nativeFaceUid = "1"))))
        assertNull(project(rows = listOf(row().copy(nativeFaceUid = "G".repeat(32)))))
        assertNull(project(rows = listOf(row().copy(canonicalMediaKey = ""))))
        assertNull(project(rows = listOf(row().copy(canonicalMediaKey = "abc\u0000secret"))))
        assertNull(project(rows = listOf(row().copy(sourceRevision = " "))))
        assertNull(project(rows = listOf(row().copy(detectorVersion = ""))))
        assertNull(project(rows = listOf(row().copy(detectorVersion = "x".repeat(257)))))
        assertNull(project(rows = listOf(row().copy(detectorVersion = "fake\u0000model"))))
        assertNull(project(rows = listOf(row().copy(ordinal = 32))))
        assertNull(project(rows = listOf(row().copy(ordinal = -1))))
    }

    @Test fun neverAllowPathOrUriOrUnversionedHashesIntoPrivateReviewKey() {
        val valid = requireNotNull(project())
        assertEquals("atlas-face-media-v1:" + "a".repeat(64),
            valid.candidates.single().key.canonicalMediaKey)
        assertEquals("atlas-face-rev-v1:" + "b".repeat(64),
            valid.candidates.single().key.sourceRevision)

        val forbiddenKeys = listOf(
            "/storage/emulated/0/DCIM/private.jpg",
            "file:///storage/emulated/0/DCIM/private.jpg",
            "content://media/external_primary/images/media/42",
            "atlas-face-media-v1:" + "A".repeat(64),
            "atlas-face-media-v1:" + "a".repeat(63),
            "atlas-face-media-v1:" + "a".repeat(65),
            "atlas-face-media-v2:" + "a".repeat(64),
            "a".repeat(64),
            "atlas-face-media-v1:" + "a".repeat(63) + "/"
        )
        for (bad in forbiddenKeys) {
            assertNull("private/unproven media ID admitted: $bad",
                project(rows = listOf(row().copy(canonicalMediaKey = bad))))
        }
        val forbiddenRevisions = listOf(
            "/storage/emulated/0/DCIM/private.jpg",
            "content://media/external_primary/images/media/42",
            "atlas-face-rev-v1:" + "F".repeat(64),
            "atlas-face-rev-v1:" + "b".repeat(63),
            "atlas-face-rev-v2:" + "b".repeat(64),
            "b".repeat(64),
            "atlas-face-rev-v1:" + "b".repeat(65)
        )
        for (bad in forbiddenRevisions) {
            assertNull("unproven source revision admitted: $bad",
                project(rows = listOf(row().copy(sourceRevision = bad))))
        }
    }

    @Test fun oneUnprovenRowDropsEntireOtherwiseValidPage() {
        val first = row().copy(canonicalMediaKey =
            "atlas-face-media-v1:" + "1".repeat(64))
        val second = row(2).copy(
            canonicalMediaKey = "file:///private/photo.jpg",
            sourceRevision = "atlas-face-rev-v1:" + "2".repeat(64)
        )
        assertNull(project(rows = listOf(first, second), pending = 2))
        assertEquals(1, requireNotNull(project(
            rows = listOf(first), pending = 1)).candidates.size)
    }

    @Test fun duplicatedFaceUidAndUnboundedOrEmptyNextPageRefusePublication() {
        assertNull(project(rows = listOf(row(), row())))
        assertNull(project(rows = listOf(row(), row().copy(
            nativeFaceUid = "e".repeat(32))))) // same image slot, different UID
        assertNull(project(rows = (1..51).map { row(it % 32 + 1) }, pending = 51))
        assertNull(project(rows = emptyList(), pending = 5, more = true))
        val empty = requireNotNull(project(rows = emptyList(), pending = 0))
        assertTrue(empty.candidates.isEmpty())
        assertFalse(empty.hasMore)
    }

    @Test fun callerCannotMutatePublishedPageOrFakeReviewIdentity() {
        val supplied = mutableListOf(row())
        val accepted = requireNotNull(project(supplied))
        supplied.clear()
        assertEquals(1, accepted.candidates.size)
        assertThrows(UnsupportedOperationException::class.java) {
            (accepted.candidates as MutableList<DetectedFaceReviewCandidate>).clear()
        }
        assertNull(currentNativeReviewCommandOrNull(
            accepted,
            DetectedFaceReviewKey(row(2).nativeFaceUid, row(2).canonicalMediaKey,
                row(2).sourceRevision, "detector-v1", scope.permissionEpoch, 1),
            DetectionReviewChoice.LATER,
            scope.permissionEpoch, scope.sourceGeneration
        ))
    }

    @Test fun syntheticFiftyFacePageMaintainsUidAndOrdinalBound() {
        // MAX 32 per image, but each distinct media item can have 1 detection.
        val rows = (1..50).map {
            row(1).copy(
                nativeFaceUid = it.toString(16).padStart(32, '0'),
                canonicalMediaKey = "atlas-face-media-v1:" +
                    it.toString(16).padStart(64, '0')
            )
        }
        val current = requireNotNull(project(rows, pending = 50))
        assertEquals(50, current.candidates.size)
        assertEquals(50, current.candidates.map { it.key.nativeFaceUid }.toSet().size)
        assertTrue(current.candidates.all { it.key.ordinal == 0 })
        assertNull(project(rows + row(1).copy(nativeFaceUid = "f".repeat(32)),
            pending = 51))
    }
}
