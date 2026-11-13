package nl.atlas.gallery

import org.junit.Assert.*
import org.junit.Test

class ArchiveLogicTest {
    private val records = (0 until 1000).map { i ->
        MediaRecord(i.toLong(), "content://atlas/$i", "image_$i.jpg",
            if (i % 2 == 0) "DCIM/Black and White/" else "DCIM/Other/",
            i.toLong() * 10, i.toLong(), false)
    }

    @Test fun albumIsExactAndDoesNotLeakMovedPhotos() {
        assertEquals(500, selectAlbum(records, "DCIM/Black and White/").size)
        assertTrue(selectAlbum(records, "DCIM/Not this folder/").isEmpty())
    }
    @Test fun blackAndWhitePersonAlbumPreservesExactMembershipAndOrder() {
        val blackWhite = records[0]
        val color = records[1]
        val secondBlackWhite = records[2]
        val confirmed = setOf(blackWhite.absolutePath, color.absolutePath, secondBlackWhite.absolutePath)
        assertEquals(listOf(blackWhite, color, secondBlackWhite),
            selectPersonPhotos(records, confirmed))
        assertEquals(listOf(blackWhite, secondBlackWhite),
            selectPersonPhotos(records, confirmed, blackAndWhiteFolderOnly = true))
        // Two people can independently link to the same image without copying it.
        assertEquals(listOf(blackWhite),
            selectPersonPhotos(records, setOf(blackWhite.absolutePath), true))
    }

    @Test fun blackAndWhiteFolderMatchRejectsLookalikePathsAndVideos() {
        assertTrue(isInBlackAndWhiteFolder("DCIM/Black and White/"))
        assertTrue(isInBlackAndWhiteFolder("DCIM/Black and White/scans/"))
        assertFalse(isInBlackAndWhiteFolder("DCIM/Black and White-ish/"))
        assertFalse(isInBlackAndWhiteFolder("Pictures/Black and White/"))
        assertFalse(isInBlackAndWhiteFolder("DCIM/Other/"))
        val photo = records[0]
        val video = photo.copy(id = 999999L, name = "test.mp4", isVideo = true)
        assertEquals(listOf(photo), selectPersonPhotos(listOf(video, photo),
            setOf(video.absolutePath, photo.absolutePath), true))
    }

    @Test fun reviewedV4PhotoPathsDoNotAttributeCollidingVideos() {
        val photo = records[0]
        // MediaStore image/video IDs are independent. A malformed video row
        // may share a pathname without being the originally reviewed image.
        val video = photo.copy(
            uri = "content://media/external_primary/video/media/42",
            isVideo = true, id = 42L)
        val confirmed = setOf(photo.absolutePath)
        assertEquals(photo.absolutePath, video.absolutePath)
        assertEquals(listOf(photo), selectPersonPhotos(listOf(video, photo), confirmed))
        assertEquals(emptyList<MediaRecord>(),
            selectPersonPhotos(listOf(video), confirmed))
        assertEquals(listOf(photo),
            filterMedia(listOf(video, photo), "reviewed-person", confirmed))
        // Ordinary text search still includes videos by explicit name.
        assertEquals(listOf(video, photo),
            filterMedia(listOf(video, photo), photo.name))
        val reviewed = listOf(PersonEntry(7L, "Reviewed Person", confirmed))
        assertEquals(listOf(photo),
            searchCatalog(listOf(video, photo), reviewed, "Reviewed Person"))
        val covers = buildPeopleCovers(listOf(photo, video), reviewed)
        assertEquals(1, covers.accessibleCounts[7L])
        assertEquals(photo, covers.coversByPersonId[7L])
        assertEquals(photo, covers.coversByPath[photo.absolutePath])
        assertTrue(buildPeopleCovers(listOf(video), reviewed).coversByPath.isEmpty())
    }

    @Test fun emptyPeoplePathTextSearchPreservesResultsAndCancellation() {
        val source = records.take(513)
        assertEquals(listOf(records[7]),
            filterMedia(source, "image_7.jpg", emptySet()))
        var checkpoints = 0
        try {
            filterMedia(source, "never-matches", emptySet()) {
                if (++checkpoints == 2) error("cancel")
            }
            fail("Large metadata scan did not check cancellation")
        } catch (expected: IllegalStateException) {
            assertEquals("cancel", expected.message)
        }
        assertEquals(2, checkpoints)
    }

    @Test fun importedPeoplePathsOnlyLinkAccessibleExactPhotographs() {
        val original = "/storage/emulated/0/DCIM/Black and White/photo-7.jpg"
        val moved = "/storage/emulated/0/DCIM/old/photo-9.jpg"
        val accessible = setOf(original, "/storage/emulated/0/DCIM/new/photo-9.jpg")
        assertEquals(1, countAccessiblePersonPhotos(setOf(original, moved), accessible))
        assertEquals(1, countAccessiblePersonPhotos(setOf(original), accessible))
        assertEquals(0, countAccessiblePersonPhotos(setOf(moved), accessible))
        assertEquals(0, countAccessiblePersonPhotos(emptySet(), accessible))
    }

    @Test fun checksumSealedNativePeopleCsvAcceptsOnlyCompleteExport() {
        val original = "person_id,person_name,image_path,face_id,confidence\r\n" +
            "1,Existing Person,/storage/emulated/0/DCIM/example.jpg,17,0.99\r\n"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(original.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val complete = original + "#ATLAS_SYNC_END,1,$digest\r\n"
        assertEquals(original, validatedLiveV4Csv(complete))
        for (incomplete in listOf(original,
                original + "#ATLAS_SYNC_END,2,$digest\r\n",
                original + "#ATLAS_SYNC_END,1,${"0".repeat(64)}\r\n",
                complete.dropLast(20))) {
            try {
                validatedLiveV4Csv(incomplete)
                fail("Accepted incomplete or corrupted V4 export")
            } catch (_: IllegalArgumentException) {
                // Existing, fully imported People records must be retained.
            }
        }
    }

    @Test fun asynchronousSortedSnapshotNeverExposesStaleSourceOrSeed() {
        val ordered = sortMedia(records, SortOrder.RANDOM, 8675309L)
        val snapshot = CatalogSortSnapshot(records, SortOrder.RANDOM, 8675309L, ordered)
        assertSame(ordered,
            currentSortedCatalogOrNull(snapshot, records, SortOrder.RANDOM, 8675309L))
        assertNull(currentSortedCatalogOrNull(null, records, SortOrder.RANDOM, 8675309L))
        assertNull(currentSortedCatalogOrNull(snapshot, records, SortOrder.NEWEST, 8675309L))
        assertNull(currentSortedCatalogOrNull(snapshot, records, SortOrder.RANDOM, 8675310L))
        // MediaStore refreshes can yield equal metadata with different access
        // generations. Never reuse the old list merely because equals() matches.
        val anotherGeneration = records.toList()
        assertEquals(records, anotherGeneration)
        assertFalse(records === anotherGeneration)
        assertEquals(CatalogSourceKey(records), CatalogSourceKey(records))
        assertNotEquals(CatalogSourceKey(records), CatalogSourceKey(anotherGeneration))
        assertNull(currentSortedCatalogOrNull(
            snapshot, anotherGeneration, SortOrder.RANDOM, 8675309L))
        val newOrder = sortMedia(anotherGeneration, SortOrder.RANDOM, 8675310L)
        val replacement = CatalogSortSnapshot(
            anotherGeneration, SortOrder.RANDOM, 8675310L, newOrder)
        assertEquals(newOrder, currentSortedCatalogOrNull(
            replacement, anotherGeneration, SortOrder.RANDOM, 8675310L))
    }

    @Test fun cancellableSortPreservesEveryLegacyOrdering() {
        val source = records.reversed()
        for (order in SortOrder.entries) {
            var checkpoints = 0
            val baseline = sortMedia(source, order, 99173L)
            val cancellable = sortMedia(source, order, 99173L) { checkpoints++ }
            assertEquals("Cancellation must not alter sort order: $order", baseline, cancellable)
            assertTrue("Start/end checkpoints required: $order", checkpoints >= 2)
            assertEquals(source.toSet(), cancellable.toSet())
        }
    }

    @Test fun largeSortStopsDuringComputationNotOnlyAfterIt() {
        // Reverse input exposes comparator work even for nearly ordered modes;
        // RANDOM also checks decoration and post-sort projection.
        val source = (0 until 25_000).map { i ->
            records[0].copy(id = i.toLong(), uri = "content://fiction/$i",
                takenAtMillis = i.toLong(), sizeBytes = i.toLong())
        }.reversed()
        for (order in SortOrder.entries) {
            var checkpoints = 0
            val stop = IllegalStateException("superseded sort")
            try {
                sortMedia(source, order, 7L) {
                    if (++checkpoints == 4) throw stop
                }
                fail("Unresponsive sort completed after cancellation: $order")
            } catch (expected: IllegalStateException) {
                assertSame(stop, expected)
            }
            assertEquals("Must observe in-sort cancellation: $order", 4, checkpoints)
        }
    }

    @Test fun cancelledSortChecksEvenEmptyInput() {
        val stop = IllegalStateException("scope changed")
        try {
            sortMedia(emptyList(), SortOrder.NEWEST, 1L) { throw stop }
            fail("Empty sort failed to check current permission source")
        } catch (expected: IllegalStateException) {
            assertSame(stop, expected)
        }
    }

    @Test fun shuffleStableUntilSeedChanges() {
        assertEquals(sortMedia(records, SortOrder.RANDOM, 123), sortMedia(records, SortOrder.RANDOM, 123))
        assertNotEquals(sortMedia(records, SortOrder.RANDOM, 123), sortMedia(records, SortOrder.RANDOM, 124))
    }
    @Test fun multiVolumeSameIdOrderingNeverDependsOnCursorRowOrder() {
        val primary = MediaRecord(42, "content://media/external_primary/images/media/42",
            "same.jpg", "DCIM/", 100, 123, false, volumeName = "external_primary")
        val secondary = primary.copy(
            uri = "content://media/1234-ABCD/images/media/42", volumeName = "1234-ABCD")
        val another = primary.copy(
            uri = "content://media/5678-EFGH/images/media/42", volumeName = "5678-EFGH")
        val forward = listOf(primary, secondary, another)
        for (order in SortOrder.entries) {
            val stable = sortMedia(forward, order, 12345)
            assertEquals("Same timestamp/id must not inherit cursor row order for $order",
                stable, sortMedia(forward.reversed(), order, 12345))
            assertEquals(3, stable.map { it.uri }.toSet().size)
        }
        // Legacy random key for primary storage never shifts on upgrade.
        assertEquals((primary.id shl 1), primary.randomShuffleDiscriminator())
        assertEquals(primary.randomShuffleDiscriminator(),
            primary.copy(volumeName = "external").randomShuffleDiscriminator())
        assertNotEquals(primary.randomShuffleDiscriminator(),
            secondary.randomShuffleDiscriminator())
        assertNotEquals(secondary.randomShuffleDiscriminator(),
            another.randomShuffleDiscriminator())
    }

    @Test fun sortingBySizeIsExact() {
        assertEquals(999L, sortMedia(records, SortOrder.LARGEST, 0).first().id)
    }
    @Test fun smallestIsTheExactReverseOfLargestForDistinctSizes() {
        // #514: Smallest sits next to Largest in the sort menu.
        val distinct = records.distinctBy { it.sizeBytes }
        val smallest = sortMedia(distinct, SortOrder.SMALLEST, 0)
        assertEquals(sortMedia(distinct, SortOrder.LARGEST, 0).reversed(), smallest)
        assertTrue(smallest.zipWithNext().all { (a, b) -> a.sizeBytes <= b.sizeBytes })
        assertEquals(SortOrder.LARGEST.ordinal + 1, SortOrder.SMALLEST.ordinal)
    }
    @Test fun coarseScrubCoversEndpointsAndClamps() {
        assertEquals(0, seekByFraction(0f, 67000))
        assertEquals(66999, seekByFraction(1f, 67000))
        assertEquals(0, seekByFraction(-10f, 67000))
        assertEquals(66999, seekByFraction(5f, 67000))
        assertEquals(0, clampedScrubTarget(-3, 200))
        assertEquals(199, clampedScrubTarget(300, 200))
    }
    @Test fun previewPrefetchWindowIsSmallAndBounded() {
        assertEquals(listOf(11, 9, 12, 8), nearbyPreviewIndices(10, 64))
        assertEquals(listOf(1, 2), nearbyPreviewIndices(0, 64))
        assertEquals(listOf(62, 61), nearbyPreviewIndices(63, 64))
        assertTrue(nearbyPreviewIndices(0, 0).isEmpty())
        assertTrue(nearbyPreviewIndices(-1, 64).isEmpty())
        assertTrue(nearbyPreviewIndices(64, 64).isEmpty())
        assertTrue(nearbyPreviewIndices(0, 64, 0).isEmpty())
        assertEquals(8, nearbyPreviewIndices(30, 67_000, 999).size)
    }

    @Test fun directionalPrefetchPrioritizesTravelWithoutIncreasingDecodeBudget() {
        assertEquals(listOf(11, 9, 12, 8),
            nearbyPreviewIndicesForMotion(10, 9, 64))
        assertEquals(listOf(9, 11, 8, 12),
            nearbyPreviewIndicesForMotion(10, 11, 64))
        assertEquals(nearbyPreviewIndices(10, 64),
            nearbyPreviewIndicesForMotion(10, 10, 64))
        assertEquals(nearbyPreviewIndices(10, 64),
            nearbyPreviewIndicesForMotion(10, -1, 64))
        assertEquals(listOf(1, 2),
            nearbyPreviewIndicesForMotion(0, 10, 64))
        assertEquals(listOf(62, 61),
            nearbyPreviewIndicesForMotion(63, 10, 64))
        assertTrue(nearbyPreviewIndicesForMotion(4, 3, 0).isEmpty())
        assertTrue(nearbyPreviewIndicesForMotion(-1, 3, 64).isEmpty())
        assertTrue(nearbyPreviewIndicesForMotion(64, 3, 64).isEmpty())
        assertTrue(nearbyPreviewIndicesForMotion(8, 7, 64, 0).isEmpty())
        // A 150k-item gallery must perform bounded neighbor work,
        // never scan all items or request more preview bitmaps.
        val n = 150_000
        for (index in listOf(0, 1, 42, n / 2, n - 2, n - 1)) {
            val selected = nearbyPreviewIndicesForMotion(index, n - 1, n, radius = 999)
            assertTrue("Prefetch budget exceeded at $index", selected.size <= 8)
            assertEquals(selected.size, selected.toSet().size)
            assertTrue(selected.all { it in 0 until n && it != index })
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun noScrubIntoEmptyCollection() { seekByFraction(0.5f, 0) }
    @Test fun backgroundSearchKeepsExactNamedPersonPhotosAndLegacyTextMatches() {
        val alicePath = records[7].absolutePath
        val secondAlice = records[11].absolutePath
        val sameFilenameWrongFolder = records[7].copy(
            id = 99_999L, uri = "content://atlas/99999", relativePath = "DCIM/Elsewhere/")
        val source = records + sameFilenameWrongFolder
        val people = listOf(
            PersonEntry(41L, "Alex Example", setOf(alicePath)),
            PersonEntry(77L, "Alex Example", setOf(secondAlice))
        )
        val found = searchCatalog(source, people, "alex")
        assertEquals(listOf(records[7], records[11]), found)
        assertFalse(found.contains(sameFilenameWrongFolder))
        assertEquals(filterMedia(source, "IMAGE_9"),
            searchCatalog(source, people, "IMAGE_9"))
        assertSame(source, searchCatalog(source, people, "   "))
        assertEquals(emptyList<MediaRecord>(), searchCatalog(source, people, "No Such Photo"))
        val snapshot = CatalogSearchSnapshot(source, people, "alex", found)
        assertSame(found, currentSearchCatalogOrNull(snapshot, source, people, "alex"))
        assertNull(currentSearchCatalogOrNull(snapshot, source, people, "al"))
        assertNull(currentSearchCatalogOrNull(snapshot, source.toList(), people, "alex"))
        assertNull(currentSearchCatalogOrNull(snapshot, source, people.toList(), "alex"))
    }

    @Test fun syntheticLargeSearchBudgetChecksCancellationAndIdentity() {
        val huge = (0 until 150_000).map { index ->
            MediaRecord(index.toLong(), "content://atlas.synth/$index",
                "Camera_photo_$index.jpeg", "DCIM/Example/",
                sizeBytes = 1, takenAtMillis = index.toLong(), isVideo = false)
        }
        val found = searchCatalog(huge, emptyList(), "camera_photo_14999")
        assertEquals(listOf(huge[14_999], huge[149_990], huge[149_991],
            huge[149_992], huge[149_993], huge[149_994], huge[149_995],
            huge[149_996], huge[149_997], huge[149_998], huge[149_999]), found)
        var checkpointCount = 0
        try {
            searchCatalog(huge, emptyList(), "never_matches") {
                if (++checkpointCount >= 3) error("Canceled obsolete search")
            }
            fail("Superseded query should cooperatively abort")
        } catch (ex: IllegalStateException) {
            assertEquals("Canceled obsolete search", ex.message)
        }
        assertEquals(3, checkpointCount)
    }

    @Test fun exactPeopleSearchAndCsv() {
        val selectedPath = records[7].absolutePath
        assertEquals(listOf(records[7]), filterMedia(records, "Suus", setOf(selectedPath)))
        val input = "a,b\n\"hello, again\",\"a\"\"b\""
        assertEquals("a\"b", parseCsv(input)[1][1])
    }
}
