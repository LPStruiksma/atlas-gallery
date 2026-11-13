package nl.atlas.gallery.featurealbums

import nl.atlas.gallery.MediaRecord
import org.junit.Assert.*
import org.junit.Test

class AlbumCoverChoiceTest {
    private fun media(
        id: Long, folder: String, taken: Long,
        volume: String = "external_primary", video: Boolean = false
    ) = MediaRecord(
        id = id, uri = "content://atlas/" + (if (video) "video/" else "photo/") + id,
        name = "Item $id", relativePath = folder, sizeBytes = 4096,
        takenAtMillis = taken, isVideo = video, volumeName = volume
    )

    @Test fun samePublicationEpochCannotReuseOldGrantPageOrChoice() {
        val source = (1L..5L).map { media(it, "DCIM/", it) }
        val route = AlbumRoute("DCIM/", AlbumMediaFilter.ALL, "external_primary")
        val original = AlbumCoverPermissionScope(401L)
        val regranted = AlbumCoverPermissionScope(402L)
        val page = buildAlbumCoverChoices(
            source, route, 88L, limit = 2, permissionScope = original)
        val cursor = page.nextOlderCursor!!
        assertEquals(original, cursor.permissionScope)
        assertEquals(page.candidates, currentAlbumCoverChoicesOrNull(
            page, source, 88L, route, permissionScope = original))
        assertNull(currentAlbumCoverChoicesOrNull(
            page, source, 88L, route, permissionScope = regranted))
        assertNull(chooseAlbumCoverOrNull(
            page, source, 88L, route, page.candidates.first().uri,
            permissionScope = regranted))
        assertNull(chooseAlbumCoverOrNull(
            page, source, 88L, route, page.candidates.first().uri))
        try {
            buildAlbumCoverChoices(source, route, 88L, limit = 2,
                olderThan = cursor, permissionScope = regranted)
            fail("Reused older-page cursor after grant transition")
        } catch (_: IllegalArgumentException) {}
        val fresh = buildAlbumCoverChoices(
            source, route, 88L, limit = 2, permissionScope = regranted)
        assertNotNull(chooseAlbumCoverOrNull(
            fresh, source, 88L, route, fresh.candidates.first().uri,
            permissionScope = regranted))
    }

    @Test fun onlyExactAuthorizedBucketAndTypeAreOffered() {
        val wanted = media(1, "DCIM/Camera/", 10)
        val wantedVideo = media(2, "DCIM/Camera/", 20, video = true)
        val otherPath = media(3, "Pictures/Camera/", 30)
        val otherVolume = media(4, "DCIM/Camera/", 40, volume = "ABCD-1234")
        val source = listOf(wanted, wantedVideo, otherPath, otherVolume)
        val photos = AlbumRoute("DCIM/Camera/", AlbumMediaFilter.PHOTOS, "external_primary")
        val result = buildAlbumCoverChoices(source, photos, 7L)
        assertEquals(listOf(wanted), result.candidates)
        assertSame(wanted, result.candidates.single())
        val choice = chooseAlbumCoverOrNull(result, source, 7L, photos, wanted.uri)!!
        assertSame(wanted, choice.media)
        assertEquals(AlbumBucketKey("external_primary", "DCIM/Camera/"), choice.bucket)
        assertEquals(7L, choice.accessEpoch)
        assertNull(chooseAlbumCoverOrNull(result, source, 7L, photos, wantedVideo.uri))
        val videoRoute = photos.copy(mediaFilter = AlbumMediaFilter.VIDEOS)
        assertEquals(listOf(wantedVideo),
            buildAlbumCoverChoices(source, videoRoute, 7L).candidates)
    }

    @Test fun revocationAndSourceEpochAndRouteChangesInvalidateChoices() {
        val original = media(1, "DCIM/", 10)
        val source = listOf(original)
        val route = AlbumRoute("DCIM/", AlbumMediaFilter.ALL, "external_primary")
        val result = buildAlbumCoverChoices(source, route, 10L)
        assertEquals(listOf(original),
            currentAlbumCoverChoicesOrNull(result, source, 10L, route))
        assertNull(currentAlbumCoverChoicesOrNull(result, source, 11L, route))
        assertNull(currentAlbumCoverChoicesOrNull(result, source.toList(), 10L, route))
        assertNull(currentAlbumCoverChoicesOrNull(result, source, 10L,
            route.copy(volumeName = "ABCD-1234")))
        assertNull(currentAlbumCoverChoicesOrNull(result, source, -1L, route))
        assertNull(chooseAlbumCoverOrNull(result, source, 11L, route, original.uri))
        assertNull(chooseAlbumCoverOrNull(result, emptyList(), 10L, route, original.uri))
        assertNull(chooseAlbumCoverOrNull(result, source, 10L, route, ""))
        assertNull(chooseAlbumCoverOrNull(null, source, 10L, route, original.uri))
    }

    @Test fun newestFirstAndDeterministicUriTieBreakWithBoundedCandidates() {
        val source = (1L..200L).map { media(it, "Photos/", it % 11) }
        val route = AlbumRoute("Photos/", AlbumMediaFilter.ALL, "external_primary")
        val first = buildAlbumCoverChoices(source, route, 8L, limit = 20)
        val other = buildAlbumCoverChoices(source.reversed(), route, 8L, limit = 20)
        assertEquals(20, first.candidates.size)
        assertEquals(first.candidates.map { it.uri }, other.candidates.map { it.uri })
        assertEquals((1 until first.candidates.size).all { i ->
            val a = first.candidates[i - 1]
            val b = first.candidates[i]
            a.takenAtMillis >= b.takenAtMillis
        }, true)
        assertEquals(20, first.candidates.map { it.uri }.toSet().size)
        assertNull(chooseAlbumCoverOrNull(
            first, source, 8L, route, media(999, "Photos/", 999L).uri))
    }

    @Test fun duplicateUrisDoNotCreateDuplicateTiles() {
        val first = media(1, "Photos/", 1)
        val duplicate = first.copy(takenAtMillis = 999L)
        val source = listOf(first, duplicate, media(2, "Photos/", 100))
        val route = AlbumRoute("Photos/", AlbumMediaFilter.ALL, "external_primary")
        val p = buildAlbumCoverChoices(source, route, 8L)
        val reversed = buildAlbumCoverChoices(source.reversed(), route, 8L)
        assertEquals(2, p.candidates.size)
        assertEquals(2, p.candidates.map { it.uri }.toSet().size)
        assertEquals(999L, p.candidates.first().takenAtMillis)
        assertEquals(p.candidates, reversed.candidates)
    }

    @Test fun identicalUriAndTimestampConflictingSourceRevisionsChooseDeterministically() {
        val base = media(17, "Photos/", 10)
        val modified = base.copy(
            generationModified = 7L, modifiedAtSeconds = 100L, sizeBytes = 8192L,
            widthPixels = 1080, heightPixels = 1440
        )
        val newest = base.copy(
            generationModified = 8L, modifiedAtSeconds = 90L, sizeBytes = 4096L,
            widthPixels = 900, heightPixels = 1200
        )
        val sameRevisionOtherMetadata = modified.copy(
            providerVersion = "v2", orientationDegrees = 90,
            name = "Edited synthetic photo"
        )
        val route = AlbumRoute("Photos/", AlbumMediaFilter.ALL, "external_primary")
        val records = listOf(base, modified, newest, sameRevisionOtherMetadata)
        val reversedRecords = records.reversed()
        val forward = buildAlbumCoverChoices(records, route, 77L)
        val reverse = buildAlbumCoverChoices(reversedRecords, route, 77L)
        assertEquals(1, forward.candidates.size)
        assertEquals(forward.candidates, reverse.candidates)
        assertSame(newest, forward.candidates.single())
        assertSame(newest, chooseAlbumCoverOrNull(
            forward, records, 77L, route, base.uri
        )!!.media)
        assertSame(newest, chooseAlbumCoverOrNull(
            reverse, reversedRecords, 77L, route, base.uri
        )!!.media)

        // When source generations tie, use the later modification stamp;
        // when that ties too, include the remaining source metadata.
        val sameGeneration = listOf(modified, modified.copy(
            modifiedAtSeconds = 101L
        ))
        val picked = buildAlbumCoverChoices(sameGeneration, route, 77L)
        assertEquals(101L, picked.candidates.single().modifiedAtSeconds)
        assertEquals(picked.candidates,
            buildAlbumCoverChoices(sameGeneration.reversed(), route, 77L).candidates)
        val sameVersion = listOf(modified, sameRevisionOtherMetadata)
        val v2 = buildAlbumCoverChoices(sameVersion, route, 77L)
        assertEquals("v2", v2.candidates.single().providerVersion)
        assertEquals(v2.candidates,
            buildAlbumCoverChoices(sameVersion.reversed(), route, 77L).candidates)
    }

    @Test fun emptyRootIsAValidBucketAndUnknownUrisNeverBecomeSelections() {
        val root = media(8, "", 1)
        val camera = media(9, "DCIM/Camera/", 2)
        val route = AlbumRoute("", AlbumMediaFilter.ALL, "external_primary")
        val source = listOf(root, camera)
        assertEquals(listOf(root), buildAlbumCoverChoices(source, route, 1L).candidates)
        val pick = buildAlbumCoverChoices(source, route, 1L)
        assertNull(chooseAlbumCoverOrNull(pick, source, 1L, route, camera.uri))
    }

    @Test fun scanCancellationAndLimitsFailClosed() {
        val route = AlbumRoute("DCIM/", AlbumMediaFilter.ALL, "external_primary")
        val source = (1L..2000L).map { media(it, "DCIM/", it) }
        var checkpoints = 0
        try {
            buildAlbumCoverChoices(source, route, 0L, checkActive = {
                checkpoints++
                if (checkpoints == 3) error("cancel")
            })
            fail("Should have cancelled")
        } catch (expected: IllegalStateException) {
            assertEquals("cancel", expected.message)
        }
        assertEquals(3, checkpoints)
        for (invalid in listOf(0, -1, 65, 1000)) {
            try {
                buildAlbumCoverChoices(source, route, 0L, limit = invalid)
                fail("Accepted invalid limit")
            } catch (_: IllegalArgumentException) { }
        }
        try {
            buildAlbumCoverChoices(source, route, -1L)
            fail("Accepted invalid access epoch")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun cursorVisitsAllAlbumItemsWithoutAllocatingWholeAlbumPages() {
        val source = (1L..257L).map { media(it, "DCIM/", it / 6L) }
        val route = AlbumRoute("DCIM/", AlbumMediaFilter.ALL, "external_primary")
        val found = mutableListOf<String>()
        var cursor: AlbumCoverPageCursor? = null
        var pages = 0
        do {
            val page = buildAlbumCoverChoices(
                source, route, 17L, limit = 20, olderThan = cursor
            )
            assertEquals(
                page.candidates,
                currentAlbumCoverChoicesOrNull(page, source, 17L, route, cursor)
            )
            assertTrue(page.candidates.size in 1..20)
            assertTrue(page.candidates.all { candidate ->
                found.add(candidate.uri)
                true
            })
            if (cursor != null) {
                assertNull(currentAlbumCoverChoicesOrNull(
                    page, source, 17L, route, olderThan = null))
                assertNull(chooseAlbumCoverOrNull(
                    page, source, 17L, route, page.candidates.first().uri))
                assertNotNull(chooseAlbumCoverOrNull(
                    page, source, 17L, route, page.candidates.first().uri, cursor))
            }
            pages++
            cursor = page.nextOlderCursor
            assertEquals(page.hasOlder, cursor != null)
            assertTrue("Cursor did not advance", pages < 30)
        } while (cursor != null)
        assertEquals(13, pages)
        assertEquals(source.map { it.uri }.toSet(), found.toSet())
        assertEquals(source.size, found.size)
    }

    @Test fun pageBoundariesAreStableAcrossEqualDatesAndSourceOrder() {
        val source = (1L..103L).map { media(it, "", 7L) }
        val route = AlbumRoute("", AlbumMediaFilter.ALL, "external_primary")
        fun pages(input: List<MediaRecord>): List<List<String>> {
            val result = mutableListOf<List<String>>()
            var cursor: AlbumCoverPageCursor? = null
            do {
                val page = buildAlbumCoverChoices(
                    input, route, 9L, limit = 12, olderThan = cursor
                )
                result.add(page.candidates.map { it.uri })
                cursor = page.nextOlderCursor
            } while (cursor != null)
            return result
        }
        val first = pages(source)
        assertEquals(first, pages(source.reversed()))
        assertEquals(9, first.size)
        assertEquals(103, first.flatten().toSet().size)
        assertEquals(103, first.flatten().size)
    }

    @Test fun randomizedCursorPagesCoverEveryItemOnceWithDateTies() {
        val random = java.util.Random(20261024L)
        val route = AlbumRoute("DCIM/", AlbumMediaFilter.ALL, "external_primary")
        repeat(150) { trial ->
            val count = 30 + random.nextInt(500)
            val records = (1L..count.toLong()).map {
                media(it, "DCIM/", random.nextInt(13).toLong())
            }.shuffled(java.util.Random(trial.toLong()))
            val limit = 1 + random.nextInt(50)
            val seen = mutableListOf<String>()
            var cursor: AlbumCoverPageCursor? = null
            var pages = 0
            do {
                val result = buildAlbumCoverChoices(
                    records, route, trial.toLong(), limit = limit, olderThan = cursor
                )
                assertTrue(result.candidates.size <= limit)
                seen.addAll(result.candidates.map { it.uri })
                cursor = result.nextOlderCursor
                pages++
                assertTrue("Cursor did not advance", pages <= count)
            } while (cursor != null)
            assertEquals("Missing album items at trial $trial", count, seen.size)
            assertEquals(records.map { it.uri }.toSet(), seen.toSet())
        }
    }

    @Test fun cursorRejectsBlankKeysAndNewPermissionEpoch() {
        val source = listOf(media(1, "DCIM/", 1), media(2, "DCIM/", 2))
        val route = AlbumRoute("DCIM/", AlbumMediaFilter.ALL, "external_primary")
        try {
            buildAlbumCoverChoices(
                source, route, 5L, olderThan = AlbumCoverPageCursor(3L, "")
            )
            fail("Accepted blank cursor URI")
        } catch (_: IllegalArgumentException) {}
        val first = buildAlbumCoverChoices(source, route, 5L, limit = 1)
        val older = first.nextOlderCursor!!
        assertEquals(5L, older.accessEpoch)
        assertEquals(route, older.route)
        val second = buildAlbumCoverChoices(
            source, route, 5L, limit = 1, olderThan = older
        )
        assertEquals(1, second.candidates.size)
        assertNull(second.nextOlderCursor)
        assertNull(currentAlbumCoverChoicesOrNull(
            second, source, 6L, route, olderThan = older))
        assertNull(chooseAlbumCoverOrNull(
            second, source.toList(), 5L, route,
            second.candidates.single().uri, older))
        assertNotNull(chooseAlbumCoverOrNull(
            second, source, 5L, route,
            second.candidates.single().uri, older))
        for (wrong in listOf(
            older.copy(accessEpoch = 6L),
            older.copy(route = route.copy(volumeName = "ABCD-1234")),
            older.copy(route = route.copy(mediaFilter = AlbumMediaFilter.VIDEOS)),
            older.copy(accessEpoch = null),
            older.copy(route = null)
        )) {
            try {
                buildAlbumCoverChoices(source, route, 5L, olderThan = wrong)
                fail("Admitted a cursor from a different permission or album scope")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun synthetic150kCatalogueRetainsOnlyTinyCandidateBudget() {
        val target = "DCIM/Camera/"
        val source = (0 until 150_000).map { i ->
            media(i.toLong(), if (i % 3 == 0) target else "Other/",
                i.toLong())
        }
        val route = AlbumRoute(target, AlbumMediaFilter.ALL, "external_primary")
        val p = buildAlbumCoverChoices(source, route, 99L, limit = 36)
        assertEquals(36, p.candidates.size)
        assertEquals(149_997L, p.candidates.first().id)
        assertEquals(36, p.candidates.map { it.uri }.toSet().size)
        assertTrue(p.candidates.all { it.relativePath == target })
        val pick = chooseAlbumCoverOrNull(p, source, 99L, route, p.candidates[5].uri)!!
        assertSame(p.candidates[5], pick.media)
        assertNull(chooseAlbumCoverOrNull(p, source, 100L, route, pick.coverUri))
    }
}
