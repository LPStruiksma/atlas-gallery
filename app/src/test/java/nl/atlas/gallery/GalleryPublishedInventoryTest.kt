package nl.atlas.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure synthetic metadata and deliberately inaccessible list backing. */
class GalleryPublishedInventoryTest {
    private fun record(id: Long): MediaRecord = MediaRecord(
        id, "content://example/images/$id", "Invented $id",
        "DCIM/SYNTHETIC/", 123L, id * 1000L, false
    )

    @Test fun completionEpochAndRecordsAreOneImmutableValue() {
        val firstRecords = listOf(record(1))
        val secondRecords = listOf(record(2))
        val before = GalleryPublishedInventory(4L, firstRecords)
        val after = GalleryPublishedInventory(5L, secondRecords)
        assertFalse(before.satisfies(5L))
        assertTrue(after.satisfies(5L))
        assertEquals(1L, before.items.single().id)
        assertEquals(2L, after.items.single().id)
        assertEquals(4L, before.epoch)
        assertEquals(5L, after.epoch)
    }

    @Test fun repeatedIdenticalMetadataStillHasDistinctSuccessfulGeneration() {
        val identical = listOf(record(1), record(2))
        val old = GalleryPublishedInventory(99L, identical)
        val updated = GalleryPublishedInventory(100L, identical)
        assertFalse(old.satisfies(100L))
        assertTrue(updated.satisfies(100L))
        assertFalse(old == updated)
    }

    @Test fun newEpochComparisonDoesNotInspectLargeAlbum() {
        val inaccessible = object : AbstractList<MediaRecord>() {
            override val size: Int = 150_000
            override fun get(index: Int): MediaRecord =
                error("Epoch equality must not enumerate $index")
        }
        val old = GalleryPublishedInventory(199L, inaccessible)
        val latest = GalleryPublishedInventory(200L, inaccessible)
        assertFalse(old == latest)
        assertTrue(latest.satisfies(200L))
        assertFalse(old.satisfies(200L))
    }

    @Test fun staleSuccessCannotOpenGateAfterThirdReselection() {
        val inventory = listOf(record(1))
        val old = GalleryPublishedInventory(20L, inventory)
        val intermediate = GalleryPublishedInventory(21L, inventory)
        val latest = GalleryPublishedInventory(22L, listOf(record(3)))
        assertFalse(old.satisfies(22L))
        assertFalse(intermediate.satisfies(22L))
        assertTrue(latest.satisfies(22L))
    }

    @Test fun invalidTicketNeverAuthorizesInventory() {
        val clean = GalleryPublishedInventory(0L, emptyList())
        assertFalse(clean.satisfies(-1L))
        assertFalse(clean.satisfies(1L))
        assertTrue(clean.satisfies(0L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativePublishedEpochIsRejected() {
        GalleryPublishedInventory(-1L, listOf(record(42)))
    }
    @Test fun pendingReselectionSuppliesNoRecordsToBackgroundCatalog() {
        val old = GalleryPublishedInventory(18L, listOf(record(99)))
        val selected = GalleryMediaGrant(images = false, videos = false, selected = true)
        assertTrue(old.authorizedItems(selected, 19L).isEmpty())
        assertTrue(old.authorizedItems(selected, 20L).isEmpty())
        assertEquals(1, old.items.size) // historical snapshot remains immutable
    }

    @Test fun deniedAllGrantsSupplyNoRecordsEvenAfterSuccessfulScan() {
        val published = GalleryPublishedInventory(51L, listOf(record(17)))
        val denied = GalleryMediaGrant(false, false, false)
        assertTrue(published.authorizedItems(denied, 51L).isEmpty())
        assertTrue(published.authorizedItems(denied, 0L).isEmpty())
    }

    @Test fun validatedImagesOrVideosExposeExactlyPublishedListByReference() {
        val records = listOf(record(10), record(11))
        val imagesOnly = GalleryMediaGrant(true, false, false)
        val videosOnly = GalleryMediaGrant(false, true, false)
        val selectedOnly = GalleryMediaGrant(false, false, true)
        // Every scan owns exactly one grant scope, never three at once.
        val photos = GalleryPublishedInventory(67L, records, imagesOnly)
        val videos = GalleryPublishedInventory(68L, records, videosOnly)
        val selected = GalleryPublishedInventory(69L, records, selectedOnly)
        assertTrue(photos.authorizedItems(imagesOnly, 67L) === records)
        assertTrue(videos.authorizedItems(videosOnly, 68L) === records)
        assertTrue(selected.authorizedItems(selectedOnly, 69L) === records)
        assertTrue(photos.authorizedItems(imagesOnly, 70L).isEmpty())
        assertTrue(photos.authorizedItems(videosOnly, 67L).isEmpty())
    }

    @Test fun historicalLargeAlbumIsNeverEnumeratedWhenGrantIsBlocked() {
        val inaccessible = object : AbstractList<MediaRecord>() {
            override val size: Int = 150_000
            override fun get(index: Int): MediaRecord =
                error("Not allowed to traverse historical media row $index")
        }
        val old = GalleryPublishedInventory(99L, inaccessible)
        val selected = GalleryMediaGrant(false, false, true)
        val denied = GalleryMediaGrant(false, false, false)
        assertTrue(old.authorizedItems(selected, 100L).isEmpty())
        assertTrue(old.authorizedItems(denied, 0L).isEmpty())
        assertTrue(old.authorizedItems(selected, Long.MAX_VALUE).isEmpty())
    }

    @Test fun threeConsecutiveReselectionsRequireTheVeryLatestSuccessfulEpoch() {
        val selected = GalleryMediaGrant(false, false, true)
        val stale = GalleryPublishedInventory(38L, listOf(record(1)))
        val intermediate = GalleryPublishedInventory(39L, listOf(record(2)))
        val fresh = GalleryPublishedInventory(40L, listOf(record(3)), selected)
        assertTrue(stale.authorizedItems(selected, 40L).isEmpty())
        assertTrue(intermediate.authorizedItems(selected, 40L).isEmpty())
        assertEquals(3L, fresh.authorizedItems(selected, 40L).single().id)
    }

    @Test fun negativeOrUnresolvedTicketNeverFeedsDerivedCatalogWork() {
        val published = GalleryPublishedInventory(1L, listOf(record(1)))
        val full = GalleryMediaGrant(true, true, false)
        assertTrue(published.authorizedItems(full, -1L).isEmpty())
        assertTrue(published.authorizedItems(full, Long.MAX_VALUE).isEmpty())
    }

    @Test fun initialUnboundInventoryNeverAuthorizesFullGrantPhotos() {
        val full = GalleryMediaGrant(true, true, false)
        val old = GalleryPublishedInventory(11L, listOf(record(100)))
        assertFalse(old.matchesGrantedScope(full))
        assertTrue(old.authorizedItems(full, 0L).isEmpty())
        assertTrue(old.authorizedItems(full, 11L).isEmpty())
    }

    @Test fun retainedSelectedScopeCannotPassNewFullGrantEvenAtMatchingEpoch() {
        val selected = GalleryMediaGrant(false, false, true)
        val full = GalleryMediaGrant(true, true, false)
        val stale = GalleryPublishedInventory(77L, listOf(record(1)), selected)
        assertTrue(stale.matchesGrantedScope(selected))
        assertEquals(1L, stale.authorizedItems(selected, 77L).single().id)
        assertFalse(stale.matchesGrantedScope(full))
        assertTrue(stale.authorizedItems(full, 0L).isEmpty())
        assertTrue(stale.authorizedItems(full, 77L).isEmpty())
    }

    @Test fun completingFullGrantRequeryMakesExactNewRecordsAvailable() {
        val selected = GalleryMediaGrant(false, false, true)
        val full = GalleryMediaGrant(true, true, false)
        val old = GalleryPublishedInventory(7L, listOf(record(1)), selected)
        val fresh = GalleryPublishedInventory(8L, listOf(record(2)), full)
        assertTrue(old.authorizedItems(full, 8L).isEmpty())
        assertFalse(fresh.authorizedItems(full, 9L).isNotEmpty())
        assertEquals(2L, fresh.authorizedItems(full, 8L).single().id)
    }

    @Test fun missingVideoGrantRejectsSameImageGrantCatalog() {
        val full = GalleryMediaGrant(true, true, false)
        val imagesOnly = GalleryMediaGrant(true, false, false)
        val stale = GalleryPublishedInventory(13L, listOf(record(3)), full)
        assertTrue(stale.authorizedItems(imagesOnly, 13L).isEmpty())
        val newCatalog = GalleryPublishedInventory(14L, listOf(record(4)), imagesOnly)
        assertEquals(4L, newCatalog.authorizedItems(imagesOnly, 14L).single().id)
    }

    @Test fun protectedCatalogNeverEnumeratesPoisoned150kRowsWhenScopeChanged() {
        val poisoned = object : AbstractList<MediaRecord>() {
            override val size: Int = 150_000
            override fun get(index: Int): MediaRecord =
                error("Stale media row $index must not be touched")
        }
        val selected = GalleryMediaGrant(false, false, true)
        val full = GalleryMediaGrant(true, true, false)
        val old = GalleryPublishedInventory(500L, poisoned, selected)
        assertTrue(old.authorizedItems(full, 0L).isEmpty())
        assertTrue(old.authorizedItems(full, 500L).isEmpty())
        assertFalse(old.matchesGrantedScope(full))
    }

    @Test fun exactGrantAndNewestEpochPreserveOriginalListReference() {
        val grant = GalleryMediaGrant(true, true, true)
        val original = listOf(record(9), record(10))
        val inventory = GalleryPublishedInventory(300L, original, grant)
        assertTrue(inventory.authorizedItems(grant, 300L) === original)
        assertTrue(inventory.authorizedItems(grant, 301L).isEmpty())
        assertTrue(inventory.authorizedItems(GalleryMediaGrant(false, false, false), 300L).isEmpty())
    }

    @Test fun deniedSnapshotCannotAuthorizePhotosEvenWhenGrantFlagsMatch() {
        val noGrant = GalleryMediaGrant(false, false, false)
        val snapshot = GalleryPublishedInventory(22L, listOf(record(3)), noGrant)
        assertFalse(snapshot.matchesGrantedScope(noGrant))
        assertTrue(snapshot.authorizedItems(noGrant, 22L).isEmpty())
    }

    @Test fun newSuccessfulEpochNeverTraversesOldLargeCatalog() {
        val oldRows = object : AbstractList<MediaRecord>() {
            override val size: Int = 150_000
            override fun get(index: Int): MediaRecord =
                error("Epoch transition compared historical row $index")
        }
        val oldGrant = GalleryMediaGrant(false, false, true)
        val full = GalleryMediaGrant(true, true, false)
        val before = GalleryPublishedInventory(98L, oldRows, oldGrant)
        val after = GalleryPublishedInventory(99L, listOf(record(12)), full)
        assertFalse(before == after)
        assertEquals(12L, after.authorizedItems(full, 99L).single().id)
    }

}
