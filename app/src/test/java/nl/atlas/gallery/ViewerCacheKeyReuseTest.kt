package nl.atlas.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PERF-3 (#124): the viewer's borrowed first frame. Synthetic records only. */
class ViewerCacheKeyReuseTest {
    private fun photo(
        version: String? = "v1", generation: Long = 7L, width: Int = 4000, height: Int = 3000
    ) = MediaRecord(42L, "content://media/external/images/media/42", "synthetic.jpg",
        "DCIM/Camera/", 1_000_000L, 1_700_000_000_000L, isVideo = false,
        modifiedAtSeconds = 1_700_000_000L, providerVersion = version,
        generationModified = generation, widthPixels = width, heightPixels = height)

    @Test fun gridTileKeyIsBorrowedExactly() {
        val media = photo()
        val epoch = 3L
        // What the grid cached: a 352 px tile (gridPreviewRequestPixels of a ~235 px cell).
        val gridKey = mediaPreviewKey(media, 352, epoch)
        val cached = setOf(gridKey)
        val key = viewerPlaceholderKey({ media.presentationCacheKeyOrNull(it, epoch) }, { it in cached })
        assertEquals(gridKey, key)
    }

    @Test fun everyGridRequestSizeIsACandidate() {
        val candidates = ViewerInstantPreview.CANDIDATE_PIXELS.toSet()
        for (cell in 1..2000) {
            val px = nl.atlas.gallery.featuregrid.gridPreviewRequestPixels(cell, cell)
            // A grid tile of exactly 800 px is the viewer's own key: a direct hit.
            assertTrue("grid size $px (cell $cell) is not borrowed",
                px in candidates || px == ViewerInstantPreview.VIEWER_PREVIEW_PIXELS)
        }
        assertTrue(144 in candidates) // filmstrip
        assertTrue(600 in candidates) // shared transition
        assertFalse(ViewerInstantPreview.VIEWER_PREVIEW_PIXELS in candidates)
    }

    @Test fun sharpestCachedCopyWins() {
        val media = photo()
        val cached = setOf(mediaPreviewKey(media, 144, 1L), mediaPreviewKey(media, 512, 1L),
            mediaPreviewKey(media, 288, 1L))
        val key = viewerPlaceholderKey({ media.presentationCacheKeyOrNull(it, 1L) }, { it in cached })
        assertEquals(mediaPreviewKey(media, 512, 1L), key)
        val order = ViewerInstantPreview.CANDIDATE_PIXELS
        assertTrue(order.toList() == order.sortedDescending())
    }

    @Test fun neverBorrowsAnotherRevisionEpochOrGeometry() {
        val media = photo()
        val others = setOf(
            mediaPreviewKey(photo(generation = 8L), 352, 1L), // re-saved file
            mediaPreviewKey(photo(version = "v2"), 352, 1L), // provider version changed
            mediaPreviewKey(photo(width = 3000, height = 4000), 352, 1L), // rotated geometry
            mediaPreviewKey(media, 352, 2L), // other publication epoch (permission change)
            media.presentationCacheKeyOrNull(352, 1L, editorRevision = 5L)!! // edited copy
        )
        assertNull(viewerPlaceholderKey({ media.presentationCacheKeyOrNull(it, 1L) }, { it in others }))
    }

    @Test fun unknownRevisionBorrowsNothingAndBuildsOneKeyAtMost() {
        val media = photo(version = null)
        var built = 0
        var looked = 0
        val key = viewerPlaceholderKey({ built++; media.presentationCacheKeyOrNull(it, 1L) },
            { looked++; true })
        assertNull(key)
        assertEquals(1, built)
        assertEquals(0, looked)
    }

    @Test fun searchStopsAtTheFirstHitAndIsBounded() {
        val media = photo()
        var built = 0
        viewerPlaceholderKey({ built++; media.presentationCacheKeyOrNull(it, 1L) }, { true })
        assertEquals(1, built)
        built = 0
        assertNull(viewerPlaceholderKey({ built++; media.presentationCacheKeyOrNull(it, 1L) }, { false }))
        assertEquals(ViewerInstantPreview.CANDIDATE_PIXELS.size, built)
        assertTrue(built <= 40)
    }

    @Test fun onlyTheOpeningPageSkipsTheLargeDecodeSettle() {
        assertEquals(0L, viewerLargeDecodeDelayMs(openingPage = true))
        assertEquals(110L, viewerLargeDecodeDelayMs(openingPage = false))
    }

    private fun mediaPreviewKey(media: MediaRecord, px: Int, epoch: Long): String =
        media.presentationCacheKeyOrNull(px, epoch)!!
}
