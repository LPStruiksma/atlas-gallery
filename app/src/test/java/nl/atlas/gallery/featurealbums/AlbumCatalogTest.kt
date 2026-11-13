package nl.atlas.gallery.featurealbums

import nl.atlas.gallery.MediaRecord
import org.junit.Assert.*
import org.junit.Test

class AlbumCatalogTest {
    private fun media(
        id: Long, folder: String, taken: Long, video: Boolean = false
    ) = MediaRecord(
        id = id,
        uri = "content://atlas/" + (if (video) "video/" else "image/") + id,
        name = "item-" + id,
        relativePath = folder,
        sizeBytes = 1024,
        takenAtMillis = taken,
        isVideo = video
    )

    @Test fun exactRelativePathsNeverMergeOrMisroute() {
        val source = listOf(
            media(1, "DCIM/Camera/", 10),
            media(2, "Pictures/Camera/", 20),
            media(3, "DCIM/Camera/", 30)
        )
        val albums = buildAlbumSummaries(source)
        assertEquals(2, albums.size)
        assertEquals("DCIM/Camera/", albums.first { it.mediaCount == 2 }.relativePath)
        assertEquals("Pictures/Camera/", albums.first { it.mediaCount == 1 }.relativePath)
        assertEquals(listOf("Camera", "Camera"), albums.map { it.title })
        assertEquals(3, albums.sumOf { it.mediaCount })
    }

    @Test fun coverChooserAlwaysDisambiguatesExactParentAndStorageVolume() {
        val phone = AlbumRoute(
            "DCIM/Camera/", AlbumMediaFilter.ALL, "external_primary")
        val shared = AlbumRoute(
            "Pictures/Camera/", AlbumMediaFilter.PHOTOS, "external_primary")
        val sd = AlbumRoute(
            "DCIM/Camera/", AlbumMediaFilter.VIDEOS, "ABCD-1234")
        assertEquals("Camera · DCIM · Device storage", albumCoverRouteContext(phone))
        assertEquals("Camera · Pictures · Device storage",
            albumCoverRouteContext(shared))
        assertEquals("Camera · DCIM · Storage ABCD-1234",
            albumCoverRouteContext(sd))
        assertEquals("On device · Storage root · Device storage",
            albumCoverRouteContext(phone.copy(relativePath = "")))
        assertEquals("On device · Top-level folder · Device storage",
            albumCoverRouteContext(phone.copy(relativePath = "On device/")))
        assertEquals(phone.relativePath,
            phone.copy(mediaFilter = AlbumMediaFilter.VIDEOS).relativePath)
        assertNotEquals(albumCoverRouteContext(phone), albumCoverRouteContext(sd))
        // The display caption does NOT become the route or merge mount identities.
        assertEquals("DCIM/Camera/", sd.relativePath)
        assertEquals("ABCD-1234", sd.volumeName)
    }

    @Test fun albumGridPublicationKeyUsesO1ReferenceNotStructuralEquality() {
        val album = buildAlbumSummaries(listOf(media(1, "DCIM/Camera/", 9L))).single()
        val first = listOf(album)
        val nextPublication = listOf(album.copy())
        assertEquals(first, nextPublication)
        assertFalse(first === nextPublication)
        val a = AlbumPresentationListKey(first)
        val same = AlbumPresentationListKey(first)
        val b = AlbumPresentationListKey(nextPublication)
        assertEquals(a, same)
        assertEquals(a.hashCode(), same.hashCode())
        assertNotEquals(a, b)
        val large = (0 until 150_000).map { album.copy(
            relativePath = "Synthetic/Folder $it/",
            title = "Folder $it") }
        val noTraversal = object : AbstractList<AlbumSummary>() {
            override val size: Int = large.size
            override fun get(index: Int): AlbumSummary =
                error("Reference-key comparisons must not access any album rows")
        }
        assertEquals(
            AlbumPresentationListKey(noTraversal),
            AlbumPresentationListKey(noTraversal))
        assertNotEquals(
            AlbumPresentationListKey(noTraversal),
            AlbumPresentationListKey(large))
    }

    @Test fun duplicateFolderNamesShowSourceLocationWithoutChangingRoutes() {
        val first = media(1, "DCIM/Camera/", 10)
        val second = media(2, "Pictures/Camera/", 20)
        val albums = buildAlbumSummaries(listOf(first, second))
        assertEquals(listOf("Camera", "Camera"), albums.map { it.title })
        assertEquals("DCIM", albums.single { it.relativePath == "DCIM/Camera/" }.locationLabel)
        assertEquals("Pictures", albums.single {
            it.relativePath == "Pictures/Camera/"
        }.locationLabel)
        assertEquals(first, albums.single { it.relativePath == "DCIM/Camera/" }.coverMedia)
        assertEquals(second, albums.single {
            it.relativePath == "Pictures/Camera/"
        }.coverMedia)
        assertEquals(setOf("DCIM/Camera/", "Pictures/Camera/"),
            albums.map { it.relativePath }.toSet())
        assertNull(buildAlbumSummaries(listOf(first)).single().locationLabel)
    }

    @Test fun duplicateMountedPathsExposeDistinctStorageCaptions() {
        val primary = media(11, "DCIM/Camera/", 1)
        val removable = media(12, "DCIM/Camera/", 2)
        val volumeOf: (MediaRecord) -> String = {
            if (it.id == 11L) "external_primary" else "ABCD-1234"
        }
        val albums = buildAlbumSummaries(
            listOf(primary, removable), volumeOf = volumeOf)
        val phone = albums.single { it.volumeName == "external_primary" }
        val sd = albums.single { it.volumeName == "ABCD-1234" }
        assertEquals("DCIM · Device storage", phone.locationLabel)
        assertEquals("DCIM · Storage ABCD-1234", sd.locationLabel)
        assertEquals("DCIM/Camera/", phone.relativePath)
        assertEquals("DCIM/Camera/", sd.relativePath)
    }

    @Test fun caseVariantsAndRootCollisionEscalateToDistinctLabels() {
        val siblings = buildAlbumSummaries(listOf(
            media(1, "Pictures/Camera/", 10),
            media(2, "Pictures/camera/", 20)
        ))
        assertEquals("Pictures/Camera/ · Shared storage",
            siblings.single { it.title == "Camera" }.locationLabel)
        assertEquals("Pictures/camera/ · Shared storage",
            siblings.single { it.title == "camera" }.locationLabel)
        val root = buildAlbumSummaries(listOf(
            media(3, "", 30),
            media(4, "On device/", 40)
        ))
        assertEquals("Storage root", root.single { it.relativePath == "" }.locationLabel)
        assertEquals("Top-level folder",
            root.single { it.relativePath == "On device/" }.locationLabel)
        // MediaStore normally canonicalizes trailing slashes, but exact
        // folders must remain distinguishable if a provider does not.
        val trailing = buildAlbumSummaries(listOf(
            media(5, "Pictures/Camera/", 50),
            media(6, "Pictures/Camera", 60)
        ))
        assertEquals(2, trailing.size)
        assertEquals(2, trailing.mapNotNull { it.locationLabel }.toSet().size)
        assertEquals(setOf("Pictures/Camera/", "Pictures/Camera"),
            trailing.map { it.relativePath }.toSet())
    }

    @Test fun photoCoverWinsOverVideoAndManualOverrideMustRemainAccessible() {
        val photo = media(1, "DCIM/Trip/", 10)
        val newerVideo = media(2, "DCIM/Trip/", 40, video = true)
        val alternatePhoto = media(3, "DCIM/Trip/", 20)
        val othersPhoto = media(4, "Pictures/Trip/", 50)
        val all = listOf(newerVideo, alternatePhoto, othersPhoto, photo)

        val automatic = buildAlbumSummaries(all).first { it.relativePath == "DCIM/Trip/" }
        assertEquals(alternatePhoto.uri, automatic.coverUri)
        assertFalse(automatic.coverIsVideo)
        assertEquals(2, automatic.photoCount)
        assertEquals(1, automatic.videoCount)
        assertEquals(40L, automatic.latestAtMillis)

        val manual = buildAlbumSummaries(
            all,
            preferredCoverByPath = mapOf("DCIM/Trip/" to photo.uri)
        ).first { it.relativePath == "DCIM/Trip/" }
        assertEquals(photo.uri, manual.coverUri)

        val alien = buildAlbumSummaries(
            all,
            preferredCoverByPath = mapOf("DCIM/Trip/" to othersPhoto.uri)
        ).first { it.relativePath == "DCIM/Trip/" }
        assertEquals(alternatePhoto.uri, alien.coverUri)

        // After the chosen original is no longer accessible, no stale cover.
        val revoked = buildAlbumSummaries(
            listOf(newerVideo, photo),
            preferredCoverByPath = mapOf("DCIM/Trip/" to alternatePhoto.uri)
        ).single()
        assertEquals(photo.uri, revoked.coverUri)
    }

    @Test fun manualAndAutomaticCoversAgreeWithPickerOnConflictingSourceRevisions() {
        val old = media(31, "Pictures/Family/", 100).copy(
            generationModified = 7L, modifiedAtSeconds = 90L,
            widthPixels = 800, heightPixels = 1200
        )
        val current = old.copy(
            generationModified = 9L, modifiedAtSeconds = 110L,
            widthPixels = 1200, heightPixels = 900
        )
        val reverse = listOf(current, old)
        val forward = listOf(old, current)
        val bucket = AlbumBucketKey("external_primary", "Pictures/Family/")
        val chosen = mapOf(bucket to old.uri)
        for (source in listOf(forward, reverse)) {
            val album = buildAlbumSummaries(
                source, preferredCoverByBucket = chosen,
                volumeOf = { "external_primary" }
            ).single()
            assertSame(current, album.coverMedia)
            assertEquals(2, album.mediaCount)
            val automatic = buildAlbumSummaries(source).single()
            assertSame(current, automatic.coverMedia)
            val route = AlbumRoute(
                "Pictures/Family/", AlbumMediaFilter.ALL, "external_primary")
            // Same bucket/volume resolution as the two summary builders above.
            val options = buildAlbumCoverChoices(source, route, 22L,
                volumeOf = { "external_primary" })
            assertSame(current, options.candidates.single())
        }
        val revoked = buildAlbumSummaries(
            listOf(old), preferredCoverByBucket = chosen,
            volumeOf = { "external_primary" }
        ).single()
        assertSame(old, revoked.coverMedia)
    }

    @Test fun naturalNumbersAccentsCaseAndSortsAreDeterministic() {
        val source = listOf(
            media(1, "Pictures/Été 10/", 1),
            media(2, "Pictures/ete 2/", 3),
            media(3, "Pictures/ete 01/", 2),
            media(4, "DCIM/Other/", 5),
            media(5, "DCIM/Other/", 6)
        )
        assertTrue(naturalAlbumCompare("Été 2", "ete 10") < 0)
        assertTrue(naturalAlbumCompare("IMG 9", "IMG 10") < 0)
        assertTrue(naturalAlbumCompare("Zero 3", "Zero 03") < 0)
        assertEquals(0, naturalAlbumCompare("ÉTÉ", "ete"))
        val named = buildAlbumSummaries(source, AlbumSort.NAME)
        assertEquals(listOf("ete 01", "ete 2", "Été 10", "Other"),
            named.map { it.title })
        val counted = buildAlbumSummaries(source, AlbumSort.COUNT)
        assertEquals("DCIM/Other/", counted.first().relativePath)
        assertEquals(buildAlbumSummaries(source.reversed(), AlbumSort.COUNT), counted)
    }

    @Test fun customOrderRespectsExactVolumesAndPins() {
        val media = listOf(
            media(1, "DCIM/Camera/", 1),
            media(2, "Pictures/A/", 2),
            media(3, "Pictures/B/", 3),
            media(4, "Pictures/A/", 4)
        )
        val keyB = AlbumBucketKey("external", "Pictures/B/")
        val keyA = AlbumBucketKey("external", "Pictures/A/")
        val ordered = buildAlbumSummaries(
            media, AlbumSort.CUSTOM, customOrder = listOf(keyB, keyA)
        )
        assertEquals(listOf("Camera", "B", "A"), ordered.map { it.title })
        assertEquals(listOf("B", "A"), ordered.drop(1).map { it.title })
        assertEquals(listOf("Camera", "A", "B"),
            buildAlbumSummaries(media, AlbumSort.NAME).map { it.title })
    }

    @Test fun emptyCatalogueAndVideoOnlyAlbumAreHonest() {
        assertTrue(buildAlbumSummaries(emptyList()).isEmpty())
        assertEquals("On device", albumDisplayName(""))
        val onlyVideo = buildAlbumSummaries(
            listOf(media(18, "Movies/", 1234L, video = true))
        ).single()
        assertEquals("Movies", onlyVideo.title)
        assertEquals(1, onlyVideo.mediaCount)
        assertEquals(0, onlyVideo.photoCount)
        assertEquals(1, onlyVideo.videoCount)
        assertTrue(onlyVideo.coverIsVideo)
        assertEquals("content://atlas/video/18", onlyVideo.coverUri)
    }

    @Test fun mediaTypeFilterCarriesThroughExactRoute() {
        val mixed = listOf(
            media(1, "DCIM/Camera/", 10),
            media(2, "DCIM/Camera/", 40, video = true),
            media(3, "DCIM/Camera/", 20),
            media(4, "Pictures/Videos/", 60, video = true)
        )
        val photos = buildAlbumSummaries(mixed, mediaFilter = AlbumMediaFilter.PHOTOS)
        assertEquals(1, photos.size)
        assertEquals(2, photos.single().mediaCount)
        assertEquals(0, photos.single().videoCount)
        assertEquals("content://atlas/image/3", photos.single().coverUri)
        val videos = buildAlbumSummaries(mixed, mediaFilter = AlbumMediaFilter.VIDEOS)
        assertEquals(2, videos.size)
        assertEquals(2, videos.sumOf { it.mediaCount })
        assertTrue(videos.all { it.photoCount == 0 && it.coverIsVideo })
        val route = AlbumRoute("DCIM/Camera/", AlbumMediaFilter.VIDEOS)
        assertEquals(AlbumMediaFilter.VIDEOS, route.mediaFilter)
        assertEquals(listOf(mixed[1]), mixed.filter {
            it.relativePath == route.relativePath && route.mediaFilter.accepts(it)
        })
    }

    @Test fun emptyRootFolderRemainsNavigable() {
        val root = media(8, "", 12)
        val group = buildAlbumSummaries(listOf(root)).single()
        assertEquals("On device", group.title)
        assertEquals("", group.relativePath)
        assertEquals("", AlbumRoute(group.relativePath, AlbumMediaFilter.ALL).relativePath)
    }

    @Test fun albumSnapshotRejectsOldMediaPermissionsFiltersAndCovers() {
        val original = listOf(media(1, "DCIM/Camera/", 1))
        val result = buildAlbumSummaries(original)
        val snapshot = AlbumProjectionSnapshot(
            original, AlbumSort.RECENT, AlbumMediaFilter.ALL, 9L, result
        )
        assertTrue(
            currentAlbumProjectionOrNull(
                snapshot, original, AlbumSort.RECENT, AlbumMediaFilter.ALL, 9L
            ) === result
        )
        val refreshed = original.toList()
        assertEquals(original, refreshed)
        assertFalse(original === refreshed)
        assertNull(currentAlbumProjectionOrNull(
            snapshot, refreshed, AlbumSort.RECENT, AlbumMediaFilter.ALL, 9L
        ))
        assertNull(currentAlbumProjectionOrNull(
            snapshot, original, AlbumSort.COUNT, AlbumMediaFilter.ALL, 9L
        ))
        assertNull(currentAlbumProjectionOrNull(
            snapshot, original, AlbumSort.RECENT, AlbumMediaFilter.VIDEOS, 9L
        ))
        assertNull(currentAlbumProjectionOrNull(
            snapshot, original, AlbumSort.RECENT, AlbumMediaFilter.ALL, 10L
        ))
        assertNull(currentAlbumProjectionOrNull(
            null, original, AlbumSort.RECENT, AlbumMediaFilter.ALL, 9L
        ))
    }

    @Test fun customOrderRevisionInvalidatesSameSourceProjectionInConstantTime() {
        val source = listOf(media(1, "DCIM/Camera/", 10))
        val cards = buildAlbumSummaries(source)
        val snapshot = AlbumProjectionSnapshot(
            source, AlbumSort.CUSTOM, AlbumMediaFilter.ALL, 0L, cards,
            accessEpoch = 7L, customOrderRevision = 3L
        )
        assertSame(cards, currentAlbumProjectionOrNull(
            snapshot, source, AlbumSort.CUSTOM, AlbumMediaFilter.ALL, 0L,
            accessEpoch = 7L, customOrderRevision = 3L
        ))
        assertNull(currentAlbumProjectionOrNull(
            snapshot, source, AlbumSort.CUSTOM, AlbumMediaFilter.ALL, 0L,
            accessEpoch = 7L, customOrderRevision = 4L
        ))
    }

    @Test fun differentMountedVolumesNeverShareAlbumIdentity() {
        // Two independent mounted volumes with exactly the same folder.
        val primary = media(7, "DCIM/Camera/", 100)
        val removable = media(8, "DCIM/Camera/", 200)
        val source = listOf(primary, removable)
        val volumeOf: (MediaRecord) -> String = {
            if (it.id == 7L) "external_primary" else "ABCD-1234"
        }
        val albums = buildAlbumSummaries(
            source, volumeOf = volumeOf,
            preferredCoverByBucket = mapOf(
                AlbumBucketKey("external_primary", "DCIM/Camera/") to primary.uri
            )
        )
        assertEquals(2, albums.size)
        assertTrue(albums.all { it.mediaCount == 1 })
        assertEquals(setOf("external_primary", "ABCD-1234"), albums.map {
            it.volumeName
        }.toSet())
        assertEquals(setOf(primary.uri, removable.uri), albums.map {
            it.coverUri
        }.toSet())
        val removableAlbum = albums.single { it.volumeName == "ABCD-1234" }
        val route = AlbumRoute(
            removableAlbum.relativePath, AlbumMediaFilter.ALL, removableAlbum.volumeName)
        assertEquals(listOf(removable), source.filter {
            volumeOf(it) == route.volumeName &&
                it.relativePath == route.relativePath &&
                route.mediaFilter.accepts(it)
        })
    }

    @Test fun coverRecordIsExactAuthorizedSourceAndNeverFromRevokedGeneration() {
        val first = media(101, "DCIM/Camera/", 10L)
        val second = media(102, "DCIM/Camera/", 20L)
        val initial = listOf(first, second)
        val album = buildAlbumSummaries(initial).single()
        assertSame(second, album.coverMedia)
        assertEquals(second.uri, album.coverUri)
        val snapshot = AlbumProjectionSnapshot(
            initial, AlbumSort.RECENT, AlbumMediaFilter.ALL, 0L, listOf(album),
            accessEpoch = 8L
        )
        assertEquals(listOf(album), currentAlbumProjectionOrNull(
            snapshot, initial, AlbumSort.RECENT, AlbumMediaFilter.ALL, 0L,
            accessEpoch = 8L
        ))
        assertNull(currentAlbumProjectionOrNull(
            snapshot, initial, AlbumSort.RECENT, AlbumMediaFilter.ALL, 0L,
            accessEpoch = 9L
        ))
        val limited = listOf(first)
        val permittedAgain = buildAlbumSummaries(limited).single()
        assertSame(first, permittedAgain.coverMedia)
        assertEquals(first.uri, permittedAgain.coverUri)
        assertFalse(permittedAgain.coverUri == second.uri)
        assertNull(currentAlbumProjectionOrNull(
            snapshot, limited, AlbumSort.RECENT, AlbumMediaFilter.ALL, 0L,
            accessEpoch = 8L
        ))
    }

    @Test fun manyAlbumsKeepStableNaturalOrderingAndUniqueVolumeKeys() {
        // Thousands of independent albums exercise the precomputed-key sorter.
        // This is functional coverage, NOT a performance claim about Android.
        val fake = (0 until 12_000).map { i ->
            media(i.toLong(), "Synthetic/Album " + i + "/", i.toLong())
        }
        val chronological = buildAlbumSummaries(fake, AlbumSort.RECENT)
        assertEquals(12_000, chronological.size)
        assertEquals(11_999L, chronological.first().latestAtMillis)
        val alphabetical = buildAlbumSummaries(fake.reversed(), AlbumSort.NAME)
        assertEquals(12_000, alphabetical.size)
        assertEquals("Album 0", alphabetical[0].title)
        assertEquals("Album 2", alphabetical[2].title)
        assertEquals("Album 10", alphabetical[10].title)
        assertEquals("Album 11999", alphabetical.last().title)
        assertEquals(alphabetical, buildAlbumSummaries(fake, AlbumSort.NAME))
    }

    @Test fun cancellationStopsAlbumNameNormalization() {
        val fake = (0 until 4096).map { i ->
            media(i.toLong(), "Synthetic/Folder " + i + "/", i.toLong())
        }
        var checkpoints = 0
        try {
            buildAlbumSummaries(fake, AlbumSort.NAME, checkActive = {
                checkpoints++
                // 8 catalogue scan checkpoints + 1 end-scan; check #11
                // therefore proves cancellation inside the name-key map.
                if (checkpoints == 11) throw IllegalStateException("normalization cancelled")
            })
            fail("Album name precomputation did not observe cancellation")
        } catch (expected: IllegalStateException) {
            assertEquals("normalization cancelled", expected.message)
        }
        assertEquals(11, checkpoints)
    }

    @Test fun cancellationStopsSortAfterEntireAlbumSnapshotWasBuilt() {
        val fake = (0 until 4096).map { i ->
            media(i.toLong(), "Synthetic/Folder " + i + "/", i.toLong())
        }
        var checkpoints = 0
        try {
            buildAlbumSummaries(fake, AlbumSort.NAME, checkActive = {
                checkpoints++
                // 8 scan + end scan + 8 normalize + end normalize = 18.
                // This exception can only occur once comparison begins.
                if (checkpoints == 19) throw IllegalStateException("sort cancelled")
            })
            fail("Album comparator did not observe cancellation")
        } catch (expected: IllegalStateException) {
            assertEquals("sort cancelled", expected.message)
        }
        assertEquals(19, checkpoints)
    }

    @Test fun distinctRecentDatesStillCancelComparatorWork() {
        // All 4,096 dates are distinct: the natural-name tie-break cannot
        // run. Checking cancellation ONLY in byName used to miss this.
        val fake = (0 until 4096).map { i ->
            media(i.toLong(), "Synthetic/Recent " + i + "/", i.toLong())
        }
        var checkpoints = 0
        try {
            buildAlbumSummaries(fake, AlbumSort.RECENT, checkActive = {
                checkpoints++
                // 8 scan + end scan + 8 key normalization + end = 18.
                if (checkpoints == 19)
                    throw IllegalStateException("recent sort cancelled")
            })
            fail("The outer RECENT comparator missed cancellation")
        } catch (expected: IllegalStateException) {
            assertEquals("recent sort cancelled", expected.message)
        }
        assertEquals(19, checkpoints)
    }

    @Test fun countSortAlsoChecksWhileComparing() {
        // 1,024 independent albums with varying photo counts require a
        // nontrivial COUNT sort without allocating a real image thumbnail.
        val fake = (0 until 1024).flatMap { i ->
            (0 until 1 + i % 8).map { offset ->
                media((i * 8 + offset).toLong(),
                    "Synthetic/Count " + i + "/", i.toLong())
            }
        }
        // N metadata scan checks + one final, then exactly two
        // normalization checks and one end-of-normalization.
        val afterNormalization = (fake.size - 1) / 512 + 1 + 1 +
            (1024 - 1) / 512 + 1 + 1
        var checkpoints = 0
        try {
            buildAlbumSummaries(fake, AlbumSort.COUNT, checkActive = {
                checkpoints++
                if (checkpoints == afterNormalization + 1)
                    throw IllegalStateException("count sort cancelled")
            })
            fail("The outer COUNT comparator missed cancellation")
        } catch (expected: IllegalStateException) {
            assertEquals("count sort cancelled", expected.message)
        }
        assertEquals(afterNormalization + 1, checkpoints)
    }

    @Test fun cancellationStopsLargeCatalogAndAllRowsAreCounted() {
        val fake = (1..150_000).map { i ->
            media(i.toLong(), "Synthetic/Album " + (i % 150) + "/", i.toLong())
        }
        var checkpoints = 0
        val albums = buildAlbumSummaries(fake, checkActive = { checkpoints++ })
        assertEquals(150, albums.size)
        assertEquals(150_000, albums.sumOf { it.mediaCount })
        assertEquals(1000, albums.first().mediaCount)
        assertTrue(checkpoints >= 290)
        try {
            buildAlbumSummaries(fake, checkActive = { throw IllegalStateException("cancel") })
            fail("Cancellation ignored")
        } catch (expected: IllegalStateException) {
            assertEquals("cancel", expected.message)
        }
    }
}
