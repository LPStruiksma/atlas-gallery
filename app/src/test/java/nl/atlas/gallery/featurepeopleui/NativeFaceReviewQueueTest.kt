package nl.atlas.gallery.featurepeopleui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeFaceReviewQueueTest {
    private fun item(i: Int, epoch: String = "grant1") = DetectedFaceReviewCandidate(
        key = DetectedFaceReviewKey(
            nativeFaceUid = i.toString(16).padStart(32, '0'),
            canonicalMediaKey = "fictional-media-$i",
            sourceRevision = "rev1", detectorVersion = "fictional-detector",
            permissionEpoch = epoch, ordinal = i % 32
        ),
        title = "Unknown detection", explanation = "No identity"
    )

    private fun page(items: List<DetectedFaceReviewCandidate> = listOf(item(1))) =
        NativeFaceReviewQueue("grant1", "snapshot1", items, 100, hasMore = true)

    private fun live(
        page: NativeFaceReviewQueue?,
        epoch: String = "grant1",
        generation: String = "snapshot1"
    ) = NativeReviewActionSnapshot(NativeReviewScope(epoch, generation), page)

    @Test fun onlyCurrentGrantAndSourceGenerationCanExposeReviewPage() {
        val p = page()
        assertEquals(p, currentNativeFaceReviewQueueOrNull(p, "grant1", "snapshot1"))
        assertNull(currentNativeFaceReviewQueueOrNull(p, "grant2", "snapshot1"))
        assertNull(currentNativeFaceReviewQueueOrNull(p, "grant1", "snapshot2"))
        assertNull(currentNativeFaceReviewQueueOrNull(p, null, "snapshot1"))
        assertNull(currentNativeFaceReviewQueueOrNull(p, "grant1", null))
        assertNull(currentNativeFaceReviewQueueOrNull(null, "grant1", "snapshot1"))
    }

    @Test fun reviewCommandUsesExactNativeUidNotOrdinalOrV4NumericId() {
        val p = page(listOf(item(1), item(33)))
        assertEquals(item(1).key.ordinal, item(33).key.ordinal)
        val first = currentNativeReviewCommandOrNull(
            p, item(1).key, DetectionReviewChoice.NOT_A_FACE, "grant1", "snapshot1")
        val second = currentNativeReviewCommandOrNull(
            p, item(33).key, DetectionReviewChoice.NOT_A_FACE, "grant1", "snapshot1")
        assertEquals("1".padStart(32, '0'), first?.nativeFaceUid)
        assertEquals("21".padStart(32, '0'), second?.nativeFaceUid)
        assertTrue(first?.nativeFaceUid != second?.nativeFaceUid)
    }

    @Test fun neverSendReviewFromOtherPageOrStaleGrant() {
        val p = page()
        assertNull(currentNativeReviewCommandOrNull(
            p, item(2).key, DetectionReviewChoice.LATER, "grant1", "snapshot1"))
        assertNull(currentNativeReviewCommandOrNull(
            p, item(1).key, DetectionReviewChoice.LATER, "grant1", "snapshot2"))
        assertNull(currentNativeReviewCommandOrNull(
            p, item(1).key, DetectionReviewChoice.LATER, "grant2", "snapshot1"))
        assertNull(currentNativeReviewCommandOrNull(
            p, item(1, "grant2").key, DetectionReviewChoice.LATER, "grant1", "snapshot1"))
    }

    @Test fun undoIsExplicitAndNeverBecomesIdentityAttachment() {
        val p = page()
        val command = currentNativeReviewCommandOrNull(
            p, item(1).key, DetectionReviewChoice.UNREVIEWED, "grant1", "snapshot1")
        assertEquals(DetectionReviewChoice.UNREVIEWED, command?.decision)
        assertEquals("grant1", command?.permissionEpoch)
    }

    @Test fun rejectsMoreThanFiftyAndDuplicateUids() {
        assertThrows(IllegalArgumentException::class.java) {
            page((1..51).map(::item))
        }
        assertThrows(IllegalArgumentException::class.java) {
            page(listOf(item(1), item(1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            page(listOf(item(1, "grant2")))
        }
    }

    @Test fun queueIsImmutableAfterAdmissionEvenIfCallerOwnedListChanges() {
        val caller = mutableListOf(item(1))
        val accepted = page(caller)
        caller.clear()
        caller.add(item(99))
        assertEquals(listOf(item(1)), accepted.candidates)
        assertNull(currentNativeReviewCommandOrNull(
            accepted, item(99).key, DetectionReviewChoice.LATER, "grant1", "snapshot1"))
        assertThrows(UnsupportedOperationException::class.java) {
            (accepted.candidates as MutableList).add(item(99))
        }
    }

    @Test fun rejectsNumericOrMalformedNativeUidsAndUnprovenSnapshots() {
        assertThrows(IllegalArgumentException::class.java) {
            item(1).key.copy(nativeFaceUid = "1") // historical V4 face_id
        }
        assertThrows(IllegalArgumentException::class.java) {
            item(1).key.copy(nativeFaceUid = "G".repeat(32))
        }
        assertThrows(IllegalArgumentException::class.java) {
            NativeFaceReviewQueue("", "snapshot1", listOf(item(1)), 1, false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NativeFaceReviewQueue("grant1", "", listOf(item(1)), 1, false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NativeFaceReviewQueue("grant1", "snapshot1", listOf(item(1)), 0, false)
        }
    }

    @Test fun liveGrantRevocationRejectsOldRenderedButton() {
        val p = page()
        assertNull(currentNativeReviewCommandWithLiveScopeOrNull(
            p, item(1).key, DetectionReviewChoice.LATER,
            "grant1", "snapshot1", null
        ))
        assertNull(currentNativeReviewCommandWithLiveScopeOrNull(
            p, item(1).key, DetectionReviewChoice.NOT_A_FACE,
            "grant1", "snapshot1", live(p, "grant2", "snapshot1")
        ))
        assertNull(currentNativeReviewCommandWithLiveScopeOrNull(
            p, item(1).key, DetectionReviewChoice.UNREVIEWED,
            "grant1", "snapshot1", live(p, "grant1", "snapshot2")
        ))
    }

    @Test fun liveCurrentScopeAllowsOnlyCurrentPageUid() {
        val p = page()
        val scope = live(p)
        val cmd = currentNativeReviewCommandWithLiveScopeOrNull(
            p, item(1).key, DetectionReviewChoice.LATER,
            "grant1", "snapshot1", scope
        )
        assertEquals("1".padStart(32, '0'), cmd?.nativeFaceUid)
        assertEquals(DetectionReviewChoice.LATER, cmd?.decision)
        assertNull(currentNativeReviewCommandWithLiveScopeOrNull(
            p, item(2).key, DetectionReviewChoice.LATER,
            "grant1", "snapshot1", scope
        ))
    }

    @Test fun olderRenderedGrantCannotUseNewerLivePage() {
        val newer = NativeFaceReviewQueue("grant2", "snapshot2",
            listOf(item(1, "grant2")), 1, false)
        assertNull(currentNativeReviewCommandWithLiveScopeOrNull(
            newer, item(1, "grant2").key, DetectionReviewChoice.LATER,
            "grant1", "snapshot1", live(newer, "grant2", "snapshot2")
        ))
    }

    @Test fun paginationChecksLiveScopeAndVisibleMoreFlag() {
        val p = page()
        assertTrue(nativeReviewNextPageAllowed(
            p, "grant1", "snapshot1", live(p)
        ))
        assertTrue(!nativeReviewNextPageAllowed(
            p, "grant1", "snapshot1", live(p, "grant2", "snapshot1")
        ))
        assertTrue(!nativeReviewNextPageAllowed(
            p, "grant1", "snapshot1", live(p, "grant1", "snapshot2")
        ))
        assertTrue(!nativeReviewNextPageAllowed(p, "grant1", "snapshot1", null))
        assertTrue(!nativeReviewNextPageAllowed(
            NativeFaceReviewQueue("grant1", "snapshot1",
                listOf(item(1)), 1, false),
            "grant1", "snapshot1", live(p)
        ))
    }

    @Test fun differentPageInSameGrantAndGenerationRejectsOldCallbacks() {
        val old = page(listOf(item(1)))
        val latest = page(listOf(item(2)))
        val liveState = live(latest)
        assertNull(currentNativeReviewCommandWithLiveScopeOrNull(
            old, item(1).key, DetectionReviewChoice.NOT_A_FACE,
            "grant1", "snapshot1", liveState
        ))
        assertTrue(!nativeReviewNextPageAllowed(
            old, "grant1", "snapshot1", liveState
        ))
        assertTrue(nativeReviewNextPageAllowed(
            latest, "grant1", "snapshot1", liveState
        ))
    }

    @Test fun sameUidOnDifferentPageDoesNotResurrectOldReviewButton() {
        val old = page(listOf(item(1)))
        val reissued = page(listOf(item(1), item(2)))
        assertNull(currentNativeReviewCommandWithLiveScopeOrNull(
            old, item(1).key, DetectionReviewChoice.LATER,
            "grant1", "snapshot1", live(reissued)
        ))
        assertEquals("1".padStart(32, '0'),
            currentNativeReviewCommandWithLiveScopeOrNull(
                reissued, item(1).key, DetectionReviewChoice.LATER,
                "grant1", "snapshot1", live(reissued)
            )?.nativeFaceUid)
    }

    @Test fun blankLiveScopeCannotBePresentedAsCommitted() {
        assertThrows(IllegalArgumentException::class.java) {
            NativeReviewScope("", "snapshot1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NativeReviewScope("grant1", "")
        }
    }

    @Test fun zeroIsKnownEmptyButUnknownIsNull() {
        val empty = NativeFaceReviewQueue("grant1", "snapshot1", emptyList(), 0, false)
        assertEquals(0, currentNativeFaceReviewQueueOrNull(empty, "grant1", "snapshot1")
            ?.totalVisibleObservations)
        assertNull(currentNativeFaceReviewQueueOrNull(null, "grant1", "snapshot1"))
    }
}
