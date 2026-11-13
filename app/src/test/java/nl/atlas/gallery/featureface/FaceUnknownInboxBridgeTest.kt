package nl.atlas.gallery.featureface

import nl.atlas.gallery.featurepersistence.IndexedFaceBox
import nl.atlas.gallery.featurepersistence.IndexedFaceObservation
import nl.atlas.gallery.featurepersistence.IndexedFacePageCursor
import nl.atlas.gallery.featurepersistence.IndexedMedia
import nl.atlas.gallery.featurepersistence.IndexedPendingFaceReview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Invented opaque keys, UIDs and MediaStore ids only; no photos or real faces. */
class FaceUnknownInboxBridgeTest {
    private val epoch = "epoch-A"
    private val box = IndexedFaceBox(0.1f, 0.2f, 0.5f, 0.8f)

    private fun row(key: String, ordinal: Int, uid: String, epochTag: String = epoch) =
        IndexedPendingFaceReview(
            cursor = IndexedFacePageCursor(epochTag, key, ordinal),
            media = IndexedMedia(key, "rev-$key"),
            observation = IndexedFaceObservation(uid, box),
            detectorVersion = "detector-1"
        )

    private fun uid(n: Int) = n.toString(16).padStart(32, '0')
    private fun uriFor(media: FaceMediaKey) =
        "content://media/external_primary/images/media/" + (media.canonicalMediaKey.hashCode().toLong() and 0xffffL).plus(1)

    private fun bridge(
        rows: List<IndexedPendingFaceReview>,
        scope: () -> Boolean = { true },
        resolve: (FaceMediaKey) -> String = ::uriFor
    ) = FaceUnknownInboxBridge({ _, _, _ -> rows }, resolve, { scope() })

    @Test fun fullPageCarriesAuthorizedUrisAndNextCursor() {
        val rows = listOf(row("k1", 0, uid(1)), row("k1", 1, uid(2)), row("k2", 0, uid(3)))
        val page = bridge(rows).page(epoch, limit = 3)
        assertEquals(3, page.items.size)
        assertEquals(rows.last().cursor, page.nextCursor)
        assertEquals(listOf(uid(1), uid(2), uid(3)), page.items.map { it.nativeFaceUid })
        assertTrue(page.items.all { it.sourceUri.startsWith("content://media/external_primary/images/media/") })
        assertEquals(UnknownFaceBox(0.1f, 0.2f, 0.5f, 0.8f), page.items.first().box)
        assertEquals(FaceMediaKey("k2", "rev-k2"), page.items.last().media)
    }

    @Test fun shortPageHasNoNextCursorAndIsImmutable() {
        val page = bridge(listOf(row("k1", 0, uid(1)))).page(epoch, limit = 32)
        assertNull(page.nextCursor)
        try {
            @Suppress("UNCHECKED_CAST")
            (page.items as MutableList<AuthorizedUnknownFace>).clear()
            fail("validated page must not be mutable")
        } catch (_: UnsupportedOperationException) { }
    }

    @Test fun revokedScopeAbortsBeforeLoading() {
        var loaded = false
        val bridge = FaceUnknownInboxBridge({ _, _, _ -> loaded = true; emptyList() }, ::uriFor, { false })
        expect<SecurityException> { bridge.page(epoch) }
        assertTrue(!loaded)
    }

    @Test fun rejectsForeignEpochCursorsDuplicatesAndDisorder() {
        expect<IllegalArgumentException> {
            bridge(emptyList()).page(epoch, IndexedFacePageCursor("epoch-B", "k1", 0))
        }
        expect<IllegalArgumentException> { bridge(listOf(row("k1", 0, uid(1), "epoch-B"))).page(epoch) }
        expect<IllegalArgumentException> { bridge(listOf(row("k1", 0, uid(1)), row("k2", 0, uid(1)))).page(epoch) }
        expect<IllegalArgumentException> { bridge(listOf(row("k2", 0, uid(1)), row("k1", 0, uid(2)))).page(epoch) }
        expect<IllegalArgumentException> {
            bridge(listOf(row("k1", FaceDiscoveryContract.MAX_FACES_PER_IMAGE, uid(1)))).page(epoch)
        }
    }

    @Test fun refusesNonMediaStoreOrGenericVolumeUris() {
        val rows = listOf(row("k1", 0, uid(1)))
        for (bad in listOf(
            "file:///storage/emulated/0/DCIM/a.jpg",
            "content://media/external/images/media/5",
            "content://evil.provider/external_primary/images/media/5",
            "content://media/external/video/media/5",
            "content://media/external_primary/audio/media/5",
            "content://media/external_primary/video/media/5/thumbnail",
            "content://media/external_primary/images/media/5?x=1",
            "content://media/external_primary/images/media/0"
        )) {
            expect<SecurityException> { bridge(rows, resolve = { bad }).page(epoch) }
        }
    }

    /** VS-V1c (#468): a face found in a video keeps the review page working. */
    @Test fun acceptsASpecificVolumeVideoUri() {
        val video = "content://media/external_primary/video/media/5"
        val page = bridge(listOf(row("k1", 0, uid(1))), resolve = { video }).page(epoch)
        assertEquals(listOf(video), page.items.map { it.sourceUri })
    }

    @Test fun resolverFailureAbortsWholePage() {
        val rows = listOf(row("k1", 0, uid(1)), row("k2", 0, uid(2)))
        expect<SecurityException> {
            bridge(rows, resolve = { if (it.canonicalMediaKey == "k2") throw SecurityException("stale") else uriFor(it) })
                .page(epoch)
        }
    }

    private inline fun <reified T : Throwable> expect(block: () -> Unit) {
        try { block() } catch (e: Throwable) {
            if (e is T) return
            throw AssertionError("expected ${T::class.java.simpleName}, got $e", e)
        }
        fail("expected ${T::class.java.simpleName}")
    }
}
