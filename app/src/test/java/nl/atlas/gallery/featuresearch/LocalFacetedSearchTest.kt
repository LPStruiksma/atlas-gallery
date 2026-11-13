package nl.atlas.gallery.featuresearch

import nl.atlas.gallery.MediaRecord
import nl.atlas.gallery.PersonEntry
import nl.atlas.gallery.featurepeopleui.IndexedFaceCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalFacetedSearchTest {
    /**
     * Integration-style fixture: Lane I passes M's provider-volume field for
     * every reviewed-People search. The PRODUCTION default is intentionally
     * null, and dedicated regressions below verify it fails closed.
     */
    private fun searchWithFacets(
        media: List<MediaRecord>,
        people: List<PersonEntry>,
        filters: LocalSearchFacets,
        verifiedFaceCountsByUri: Map<String, IndexedFaceCount> = emptyMap(),
        currentPermissionEpoch: String? = null,
        currentMediaRevisionsByUri: Map<String, String> = emptyMap(),
        mediaVolumeOf: (MediaRecord) -> String? = { it.volumeName },
        cancellationCheckpoint: () -> Unit = {}
    ): List<MediaRecord> = nl.atlas.gallery.featuresearch.searchWithFacets(
        media, people, filters, verifiedFaceCountsByUri,
        currentPermissionEpoch, currentMediaRevisionsByUri,
        mediaVolumeOf, cancellationCheckpoint
    )

    private fun eligibleForIndexedFacesFilter(
        media: List<MediaRecord>,
        people: List<PersonEntry>,
        selectedPersonIds: Set<Long>,
        verifiedFaceCountsByUri: Map<String, IndexedFaceCount>,
        currentPermissionEpoch: String? = null,
        currentMediaRevisionsByUri: Map<String, String> = emptyMap(),
        mediaVolumeOf: (MediaRecord) -> String? = { it.volumeName },
        cancellationCheckpoint: () -> Unit = {}
    ): Boolean = nl.atlas.gallery.featuresearch.eligibleForIndexedFacesFilter(
        media, people, selectedPersonIds, verifiedFaceCountsByUri,
        currentPermissionEpoch, currentMediaRevisionsByUri,
        mediaVolumeOf, cancellationCheckpoint
    )

    private fun image(id: Long, folder: String, name: String, video: Boolean = false) =
        MediaRecord(id, "content://media/external/${if (video) "video" else "images"}/media/$id",
            name, folder, 100, 42, video)

    private val shared = image(42, "Pictures/Beach/", "sunrise.jpg")
    private val separate = image(43, "Pictures/Beach/", "sunset.jpg")
    private val indoor = image(44, "Pictures/House/", "family.jpg")
    private val video = image(42, "DCIM/", "clip.mp4", video = true)
    private val catalog = listOf(shared, separate, indoor, video)
    private val a = PersonEntry(1, "Alex", setOf(shared.absolutePath, indoor.absolutePath))
    private val b = PersonEntry(2, "Blair", setOf(shared.absolutePath))
    private val homonym = PersonEntry(3, "Blair", setOf(separate.absolutePath))

    @Test fun reviewedPersonIsNeverAttributedToAVideoWithSamePath() {
        val videoOnly = image(202, "Pictures/Beach/", "sunrise.jpg", video = true)
        val results = searchWithFacets(listOf(videoOnly), listOf(a),
            LocalSearchFacets(confirmedPersonIds = setOf(a.id)))
        assertTrue(results.isEmpty())
        // An ordinary filename search still finds the authorized VIDEO,
        // without fabricating a reviewed V4 person identity.
        assertEquals(listOf(videoOnly), searchWithFacets(
            listOf(videoOnly), listOf(a), LocalSearchFacets(text = "sunrise")))
    }

    @Test fun removableVolumeCannotClaimConfirmedV4Person() {
        val removable = shared.copy(volumeName = "7F3B-A7F1")
        assertTrue(searchWithFacets(listOf(removable), listOf(a),
            LocalSearchFacets(confirmedPersonIds = setOf(a.id))).isEmpty())
        assertEquals(listOf(removable), searchWithFacets(
            listOf(removable), listOf(a), LocalSearchFacets(text = "sunrise")))
    }

    @Test fun missingVolumeProvenanceFailsClosedForPeopleButNotMetadata() {
        assertTrue(nl.atlas.gallery.featuresearch.searchWithFacets(
            catalog, listOf(a), LocalSearchFacets(confirmedPersonIds = setOf(a.id))
        ).isEmpty())
        assertTrue(nl.atlas.gallery.featuresearch.searchWithFacets(
            catalog, listOf(a), LocalSearchFacets(text = "alex")
        ).isEmpty())
        assertEquals(listOf(shared, separate), nl.atlas.gallery.featuresearch.searchWithFacets(
            catalog, listOf(a), LocalSearchFacets(text = "Beach")
        ))
        assertFalse(nl.atlas.gallery.featuresearch.eligibleForIndexedFacesFilter(
            catalog, listOf(a), setOf(a.id), emptyMap()
        ))
    }

    @Test fun duplicateReviewedPersonIdsNeverOverwriteIdentityOrLeakNames() {
        val conflicting = PersonEntry(a.id, "Fictional Conflicting Name",
            setOf(separate.absolutePath))
        val malformed = listOf(a, conflicting)
        assertTrue(searchWithFacets(catalog, malformed,
            LocalSearchFacets(confirmedPersonIds = setOf(a.id))).isEmpty())
        // Name-token matches must not borrow a last-write-wins duplicate ID.
        assertTrue(searchWithFacets(catalog, malformed,
            LocalSearchFacets(text = "Alex")).isEmpty())
        assertTrue(searchWithFacets(catalog, malformed,
            LocalSearchFacets(text = "Conflicting")).isEmpty())
        // Plain authorized filenames/albums have NO identity assertion.
        assertEquals(listOf(shared, separate), searchWithFacets(catalog, malformed,
            LocalSearchFacets(text = "Beach")))
    }

    @Test fun lexicalResultsMatchAccentInsensitiveSuggestionsWithoutGuessingIdentity() {
        val first = image(301, "Pictures/Réunion/", "café.jpg")
        val second = image(302, "Pictures/Family/", "portrait.jpg")
        val eloise = PersonEntry(901L, "Éloïse", setOf(first.absolutePath))
        val jose = PersonEntry(902L, "José", setOf(second.absolutePath))

        // Suggestions already fold diacritics. The result engine must use
        // identical lexical semantics on confirmed names and media metadata.
        assertEquals(listOf(first), searchWithFacets(listOf(first, second),
            listOf(eloise, jose), LocalSearchFacets(text = "eloise")))
        assertEquals(listOf(first), searchWithFacets(listOf(first, second),
            listOf(eloise, jose), LocalSearchFacets(text = "cafe")))
        assertEquals(listOf(first), searchWithFacets(listOf(first, second),
            listOf(eloise, jose), LocalSearchFacets(text = "reunion")))
        assertEquals(listOf(second), searchWithFacets(listOf(first, second),
            listOf(eloise, jose), LocalSearchFacets(text = "jose")))
        assertEquals(listOf(second), searchWithFacets(listOf(first, second),
            listOf(eloise, jose), LocalSearchFacets(text = "JOSE",
                confirmedPersonIds = setOf(jose.id))))
        // Accent folding affects only search text. Stable reviewed IDs,
        // permissions and the same-volume photo-admission remain mandatory.
        assertTrue(searchWithFacets(listOf(first, second), listOf(eloise, jose),
            LocalSearchFacets(text = "jose", confirmedPersonIds = setOf(eloise.id))
        ).isEmpty())
        assertTrue(searchWithFacets(listOf(first), listOf(eloise),
            LocalSearchFacets(text = "\u0301")).isEmpty())
        val ineligibleVideo = first.copy(isVideo = true,
            uri = "content://media/external/video/media/301")
        assertTrue(searchWithFacets(listOf(ineligibleVideo), listOf(eloise),
            LocalSearchFacets(text = "eloise")).isEmpty())
    }

    @Test fun selectedPeopleIntersectByStableIdsNotNames() {
        val filtered = searchWithFacets(catalog, listOf(a, b, homonym),
            LocalSearchFacets(confirmedPersonIds = setOf(1, 2)))
        assertEquals(listOf(shared), filtered)
        assertTrue(searchWithFacets(catalog, listOf(a, b, homonym),
            LocalSearchFacets(confirmedPersonIds = setOf(1, 3))).isEmpty())
    }

    @Test fun wordsCanMatchDifferentReviewedAndMetadataFields() {
        assertEquals(listOf(shared), searchWithFacets(
            catalog, listOf(a, b),
            LocalSearchFacets(text = "Alex Beach", kind = SearchMediaKind.PHOTOS)))
        assertTrue(searchWithFacets(catalog, listOf(a),
            LocalSearchFacets(text = "unreviewed stranger")).isEmpty())
    }

    @Test fun kindFacetKeepsPhotoAndVideoId42Distinct() {
        val photos = searchWithFacets(catalog, emptyList(),
            LocalSearchFacets(kind = SearchMediaKind.PHOTOS))
        val videos = searchWithFacets(catalog, emptyList(),
            LocalSearchFacets(kind = SearchMediaKind.VIDEOS))
        assertFalse(video in photos)
        assertEquals(listOf(video), videos)
    }

    @Test fun onlyIndexedFacesFailsClosedWithoutVerifiedTotals() {
        val f = LocalSearchFacets(confirmedPersonIds = setOf(1, 2),
            onlyTheseIndexedFaces = true)
        assertTrue(searchWithFacets(catalog, listOf(a, b), f).isEmpty())
        val unverified = mapOf(shared.uri to IndexedFaceCount(
            2, false, "hash", "grant1", "rev1", shared.uri))
        assertTrue(searchWithFacets(catalog, listOf(a, b), f, unverified).isEmpty())
        val matched = mapOf(shared.uri to IndexedFaceCount(
            2, true, "seal-epoch", "grant1", "rev1", shared.uri))
        assertTrue(searchWithFacets(catalog, listOf(a, b), f, matched).isEmpty())
        assertEquals(listOf(shared), searchWithFacets(
            catalog, listOf(a, b), f, matched, "grant1", mapOf(shared.uri to "rev1")))
        assertTrue(searchWithFacets(
            catalog, listOf(a, b), f, matched, "grant2", mapOf(shared.uri to "rev1")).isEmpty())
        assertTrue(searchWithFacets(
            catalog, listOf(a, b), f, matched, "grant1", mapOf(shared.uri to "rev2")).isEmpty())
        assertFalse(eligibleForIndexedFacesFilter(catalog, listOf(a, b), setOf(1, 2), unverified))
        assertFalse(eligibleForIndexedFacesFilter(catalog, listOf(a, b), setOf(1, 2), matched))
        assertTrue(eligibleForIndexedFacesFilter(
            catalog, listOf(a, b), setOf(1, 2), matched,
            "grant1", mapOf(shared.uri to "rev1")))
    }

    @Test fun twoValidPrimaryImagesAtOneReviewedPathRemainAmbiguous() {
        val competing = shared.copy(id = 99L,
            uri = "content://media/external/images/media/99")
        val source = catalog + competing
        assertTrue(searchWithFacets(source, listOf(a),
            LocalSearchFacets(confirmedPersonIds = setOf(1))).none {
                it.absolutePath == shared.absolutePath
            })
        // This person's second, unambiguous reviewed photo remains valid.
        assertEquals(listOf(indoor), searchWithFacets(source, listOf(a),
            LocalSearchFacets(text = "alex")))
        // Plain filename lexical search has no V4 identity claim.
        assertEquals(2, searchWithFacets(source, listOf(a),
            LocalSearchFacets(text = "sunrise")).size)
    }

    @Test fun invalidSamePathVideoCannotSuppressValidPrimaryPersonImage() {
        val samePathVideo = shared.copy(isVideo = true,
            uri = "content://media/external/video/media/42")
        val source = listOf(shared, samePathVideo)
        // Exactly one valid reviewed photo; the video is not a contender.
        assertEquals(listOf(shared), searchWithFacets(source, listOf(a),
            LocalSearchFacets(confirmedPersonIds = setOf(a.id))))
        assertEquals(listOf(shared), searchWithFacets(source, listOf(a),
            LocalSearchFacets(text = "Alex")))
        assertEquals(listOf(shared, samePathVideo),
            searchWithFacets(source, listOf(a), LocalSearchFacets(text = "sunrise")))
        assertEquals(listOf(samePathVideo),
            searchWithFacets(source, listOf(a),
                LocalSearchFacets(text = "sunrise", kind = SearchMediaKind.VIDEOS)))
    }

    @Test fun mismatchedUriVolumeCannotPoisonOneValidPrimaryPersonImage() {
        val badUri = shared.copy(id = 99L,
            uri = "content://media/removable/images/media/99")
        val source = listOf(shared, badUri)
        assertEquals(listOf(shared), searchWithFacets(source, listOf(a),
            LocalSearchFacets(confirmedPersonIds = setOf(a.id))))
        assertEquals(listOf(shared), searchWithFacets(source, listOf(a),
            LocalSearchFacets(text = "Alex")))
        assertEquals(listOf(shared, badUri), searchWithFacets(source, listOf(a),
            LocalSearchFacets(text = "sunrise")))
    }

    @Test fun removableAliasCannotPoisonAValidPrimaryPersonPhoto() {
        val removable = shared.copy(id = 99L, volumeName = "A1B2-34CD",
            uri = "content://media/A1B2-34CD/images/media/99")
        val source = listOf(shared, removable)
        assertEquals(listOf(shared), searchWithFacets(source, listOf(a),
            LocalSearchFacets(confirmedPersonIds = setOf(a.id))))
        assertEquals(listOf(shared, removable), searchWithFacets(source, listOf(a),
            LocalSearchFacets(text = "sunrise")))
    }

    @Test fun onlyTheseFacesChipIsDisabledIfNoSelectedPersonIntersection() {
        val count = mapOf(separate.uri to IndexedFaceCount(
            2, true, "seal", "grant1", "rev1", separate.uri))
        assertFalse(eligibleForIndexedFacesFilter(
            catalog, listOf(a, b), setOf(1, 2), count,
            "grant1", mapOf(separate.uri to "rev1")))
    }

    @Test fun unknownPersonNeverBroadensResults() {
        assertTrue(searchWithFacets(catalog, listOf(a),
            LocalSearchFacets(confirmedPersonIds = setOf(999))).isEmpty())
        assertTrue(searchWithFacets(catalog, listOf(a),
            LocalSearchFacets(onlyTheseIndexedFaces = true)).isEmpty())
    }

    @Test fun recentsRemainEphemeralBoundedAndClearable() {
        val history = EphemeralSearchHistory().remember(" alex ").remember("BLAIR")
            .remember("Alex")
        assertEquals(listOf("Alex", "BLAIR"), history.terms)
        assertEquals(emptyList<String>(), history.clear().terms)
    }

    @Test fun searchHistoryDoesNotLeakAcrossPhotoRegrantOrRevocation() {
        val old = EphemeralSearchHistory().rememberForGrant("fictional person", "grant-A")
        assertEquals(listOf("fictional person"), old.forGrant("grant-A").terms)
        assertTrue(old.forGrant("grant-B").terms.isEmpty())
        assertTrue(old.forGrant(null).terms.isEmpty())
        assertTrue(old.forGrant("").terms.isEmpty())
        // A missing grant is NOT permission to create unscoped recents.
        assertTrue(old.rememberForGrant("private name", null).terms.isEmpty())
        // First search after a regrant must not resurrect old query terms.
        val next = old.rememberForGrant("new private query", "grant-B")
        assertEquals(listOf("new private query"), next.terms)
        assertEquals("grant-B", next.permissionEpoch)
        assertTrue(next.forGrant("grant-A").terms.isEmpty())
        assertTrue(next.clear().terms.isEmpty())
        assertEquals("grant-B", next.clear().permissionEpoch)
        assertTrue(EphemeralSearchHistory().remember("legacy")
            .forGrant("grant-A").terms.isEmpty())
    }

    @Test fun workerResultNeverPublishesAcrossGrantOrPeopleGeneration() {
        val source = catalog
        val roster = listOf(a, b)
        val facets = LocalSearchFacets(text = "Beach")
        val snap = searchAuthorizedSnapshot(source, roster, facets, "grant-1")
        assertEquals(listOf(shared, separate), currentFacetedSearchOrNull(
            snap, source, roster, facets, "grant-1", null))
        assertEquals(null, currentFacetedSearchOrNull(
            snap, source.toList(), roster, facets, "grant-1", null))
        assertEquals(null, currentFacetedSearchOrNull(
            snap, source, roster.toList(), facets, "grant-1", null))
        assertEquals(null, currentFacetedSearchOrNull(
            snap, source, roster, facets, "grant-2", null))
        assertEquals(null, currentFacetedSearchOrNull(
            snap, source, roster, facets.copy(kind = SearchMediaKind.PHOTOS),
            "grant-1", null))
        assertEquals(null, currentFacetedSearchOrNull(
            snap, source, roster, facets, "grant-1", "face-index-new"))
    }

    @Test fun qualifiedAlbumFacetNeverCrossesAnIdenticallyNamedOtherVolume() {
        val removable = shared.copy(id = 99L,
            uri = "content://media/removable/images/media/99")
        val all = listOf(shared, removable, indoor)
        val scoped = LocalSearchFacets(
            albumLocations = setOf(SearchAlbumLocation("external_primary", "Pictures/Beach/"))
        )
        // A U-only build without Lane M's exact volume mapping refuses this filter.
        assertTrue(searchWithFacets(all, emptyList(), scoped).isEmpty())
        assertEquals(listOf(shared), searchWithFacets(
            all, emptyList(), scoped,
            mediaVolumeOf = {
                if (it.uri == removable.uri) "sdcard" else "external_primary"
            }
        ))
        assertEquals(listOf(shared, removable), searchWithFacets(
            all, emptyList(), LocalSearchFacets(albumPaths = setOf("Pictures/Beach/"))
        ))
    }

    @Test fun multipleTypedAlbumSelectionsUnionAndStillIntersectPersonFacet() {
        val extra = indoor.copy(id = 201L, name = "other.jpg",
            uri = "content://media/removable/images/media/201")
        val all = listOf(shared, indoor, extra)
        val facets = LocalSearchFacets(
            confirmedPersonIds = setOf(1L),
            albumLocations = setOf(
                SearchAlbumLocation("external", "Pictures/Beach/"),
                SearchAlbumLocation("external", "Pictures/House/"),
                SearchAlbumLocation("sdcard", "Pictures/House/")
            )
        )
        assertEquals(listOf(shared, indoor), searchWithFacets(
            all, listOf(a), facets,
            mediaVolumeOf = { if (it.uri == extra.uri) "sdcard" else "external" }
        ))
    }

    @Test fun pastedOverlongQueryMustNeverSilentlyDropRestrictiveSuffix() {
        // A legitimate search for an accessible fictional file is valid at
        // exactly 160 characters (including trailing whitespace).
        val validPrefix = "sunrise" +
            " ".repeat(MAX_SEARCH_QUERY_CHARS - "sunrise".length)
        assertEquals(MAX_SEARCH_QUERY_CHARS, validPrefix.length)
        assertTrue(searchWithFacets(
            catalog, emptyList(), LocalSearchFacets(text = validPrefix)
        ).isNotEmpty())

        // Old Compose code used take(160), turning a rejected request into
        // the above broad, valid query. Preserve the overflow sentinel.
        val pasted = validPrefix + "do_not_drop_this_restrictive_term"
        val bounded = boundSearchQueryInput(pasted)
        assertEquals(MAX_SEARCH_QUERY_CHARS + 1, bounded.length)
        assertEquals(validPrefix + "d", bounded)
        assertTrue(searchWithFacets(
            catalog, emptyList(), LocalSearchFacets(text = bounded)
        ).isEmpty())
        assertEquals("", boundSearchQueryInput(""))
        assertEquals("ordinary", boundSearchQueryInput("ordinary"))
        assertEquals(MAX_SEARCH_QUERY_CHARS + 1,
            boundSearchQueryInput("x".repeat(100_000)).length)
    }

    @Test fun recentSearchHistoryNeverTruncatesSubmittedRestrictions() {
        val old = EphemeralSearchHistory(permissionEpoch = "grant-A")
        val valid = "s".repeat(MAX_SEARCH_QUERY_CHARS)
        val accepted = old.remember(valid)
        assertEquals(listOf(valid), accepted.terms)
        // All accepted search text fits in a session-only 8-entry history.
        assertEquals(listOf("s".repeat(121)), old.remember("s".repeat(121)).terms)
        assertTrue(old.remember("s".repeat(MAX_SEARCH_QUERY_CHARS + 1)).terms.isEmpty())

        // A full-length Search query whose meaningful content is shorter
        // is still invalid and MUST NOT be resurrected as a broader recent.
        val oversizedWithSpaces = "sunrise" + " ".repeat(MAX_SEARCH_QUERY_CHARS - 6)
        assertTrue(old.remember(oversizedWithSpaces).terms.isEmpty())
        assertTrue(old.rememberForGrant(oversizedWithSpaces, "grant-A").terms.isEmpty())
        assertTrue(old.rememberForGrant(oversizedWithSpaces, "grant-B").terms.isEmpty())
        val thirteen = List(MAX_SEARCH_QUERY_TERMS + 1) { "sunrise" }.joinToString(" ")
        assertTrue(old.remember(thirteen).terms.isEmpty())
        assertTrue(old.remember("sunrise " + 0x0301.toChar()).terms.isEmpty())
        assertEquals(listOf("sunrise"), old.remember("sunrise").terms)
    }

    @Test fun tooLongOrTooManySearchTermsFailClosedRatherThanBroaden() {
        assertTrue(searchWithFacets(catalog, listOf(a),
            LocalSearchFacets(text = "a".repeat(MAX_SEARCH_QUERY_CHARS + 1))).isEmpty())
        val thirteen = (1..MAX_SEARCH_QUERY_TERMS + 1).joinToString(" ") { "beach" }
        assertTrue(searchWithFacets(catalog, listOf(a),
            LocalSearchFacets(text = thirteen)).isEmpty())
        assertEquals(listOf(shared, separate), searchWithFacets(
            catalog, listOf(a), LocalSearchFacets(text = "beach beach beach")))
    }

    @Test fun multipleReviewedNameWordsMayMatchDifferentConfirmedPeople() {
        assertEquals(listOf(shared), searchWithFacets(
            catalog, listOf(a, b), LocalSearchFacets(text = "alex blair")))
        assertTrue(searchWithFacets(catalog, listOf(a, b),
            LocalSearchFacets(text = "alex blair unconfirmed")).isEmpty())
    }

    @Test fun cancellationInterruptsHugeReviewedPersonPathExpansion() {
        val source = PersonEntry(100, "Fictional Ana",
            (1..8192).map { "/storage/emulated/0/DCIM/fake_$it.jpg" }.toSet())
        var checkpoints = 0
        try {
            searchWithFacets(emptyList(), listOf(source),
                LocalSearchFacets(text = "ana"),
                cancellationCheckpoint = {
                    checkpoints++
                    if (checkpoints >= 4) throw java.util.concurrent.CancellationException()
                })
            throw AssertionError("Expected cancellation of long reviewed path expansion")
        } catch (_: java.util.concurrent.CancellationException) {
            assertTrue(checkpoints >= 4)
        }
    }

    @Test fun sparseReviewedPeopleAvoid150kUnnecessaryVolumeLookups() {
        val many = List(150_000) { index ->
            image((index + 1).toLong(), "DCIM/Fictional/", "synthetic_$index.jpg")
        }
        val first = many[1234]
        val last = many.last()
        val p = PersonEntry(777L, "Fictional Reviewed",
            setOf(first.absolutePath, last.absolutePath))
        var providerVolumeReads = 0
        var checkpoints = 0
        val matches = searchWithFacets(
            many, listOf(p), LocalSearchFacets(confirmedPersonIds = setOf(p.id)),
            mediaVolumeOf = {
                providerVolumeReads++
                it.volumeName
            },
            cancellationCheckpoint = { checkpoints++ }
        )
        assertEquals(listOf(first, last), matches)
        // Two independently admitted paths, checked in the ambiguity
        // prepass and in final results. No provider call for the other
        // 149,998 photos even with a Person facet active.
        assertEquals(4, providerVolumeReads)
        assertTrue(checkpoints >= 580)
    }

    @Test fun cancellationIsCheckedOnLargeSyntheticCatalog() {
        var checks = 0
        val many = List(2048) { image(it.toLong(), "DCIM/", "fictional_$it.jpg") }
        assertEquals(2048, searchWithFacets(many, emptyList(),
            LocalSearchFacets()) { checks++ }.size)
        assertTrue(checks >= 5)
    }

    @Test fun dateAndTextAndMediaTypeAndReviewedPersonFacetsIntersect() {
        val inside = image(701, "Pictures/Beach/", "Fictional sunrise.jpg")
            .copy(takenAtMillis = 150L)
        val outside = image(702, "Pictures/Beach/", "Fictional sunset.jpg")
            .copy(takenAtMillis = 200L)
        val undated = image(703, "Pictures/Beach/", "Fictional unknown.jpg")
            .copy(takenAtMillis = 0L)
        val video = image(704, "Pictures/Beach/", "Fictional clip.mp4", video = true)
            .copy(takenAtMillis = 150L)
        val reviewed = PersonEntry(81, "Fictional Mira",
            setOf(inside.absolutePath, outside.absolutePath, undated.absolutePath))
        val media = listOf(inside, outside, undated, video)
        val window = SearchDateWindow(100L, 200L)
        assertEquals(listOf(inside), searchWithFacets(media, listOf(reviewed),
            LocalSearchFacets(
                text = "fictional",
                kind = SearchMediaKind.PHOTOS,
                confirmedPersonIds = setOf(reviewed.id),
                dateWindow = window
            )))
        assertEquals(listOf(video), searchWithFacets(media, emptyList(),
            LocalSearchFacets(
                text = "clip",
                kind = SearchMediaKind.VIDEOS,
                dateWindow = window
            )))
        assertEquals(listOf(inside, outside, undated, video),
            searchWithFacets(media, emptyList(), LocalSearchFacets()))
    }

    @Test fun dateFacetNeverMatchesUnknownTimestampEvenWithMatchingAlbum() {
        val undated = image(705, "Pictures/Trip/", "untimed.jpg")
            .copy(takenAtMillis = 0L)
        val dated = image(706, "Pictures/Trip/", "dated.jpg")
            .copy(takenAtMillis = 101L)
        assertEquals(listOf(dated), searchWithFacets(listOf(undated, dated),
            emptyList(), LocalSearchFacets(
                albumPaths = setOf("Pictures/Trip/"),
                dateWindow = SearchDateWindow(100L, 200L)
            )))
    }

    @Test fun clearFacetsKeepsSubmittedQueryButDropsEveryRestrictiveSelection() {
        val restricted = LocalSearchFacets(
            text = "sunrise",
            confirmedPersonIds = setOf(1L, 999L),
            kind = SearchMediaKind.VIDEOS,
            albumPaths = setOf("Pictures/Never/"),
            albumLocations = setOf(SearchAlbumLocation("external", "Pictures/Beach/")),
            onlyTheseIndexedFaces = true,
            dateWindow = SearchDateWindow(100L, 200L)
        )
        assertTrue(restricted.hasActiveFilterFacets())
        assertTrue(searchWithFacets(catalog, listOf(a), restricted).isEmpty())
        val withoutDate = restricted.clearDateFilter()
        assertEquals(null, withoutDate.dateWindow)
        assertEquals(restricted.text, withoutDate.text)
        assertEquals(restricted.confirmedPersonIds, withoutDate.confirmedPersonIds)
        assertTrue(withoutDate.hasActiveFilterFacets())

        val cleared = restricted.clearFiltersKeepingQuery()
        assertEquals(LocalSearchFacets(text = "sunrise"), cleared)
        assertFalse(cleared.hasActiveFilterFacets())
        assertEquals(listOf(shared), searchWithFacets(catalog, listOf(a), cleared))
        assertEquals(LocalSearchFacets(), cleared.copy(text = ""))
        assertEquals(setOf(1L, 999L), restricted.confirmedPersonIds)
        assertEquals(SearchMediaKind.VIDEOS, restricted.kind)
        assertEquals(SearchDateWindow(100L, 200L), restricted.dateWindow)
        // The 161st byte of an invalid pasted query is intentionally retained:
        // clearing filters must never convert it into a broad 160-char match.
        val prefix = "sunrise" + " ".repeat(MAX_SEARCH_QUERY_CHARS - "sunrise".length)
        val overflow = boundSearchQueryInput(prefix + "extra")
        val unsafe = LocalSearchFacets(text = overflow, kind = SearchMediaKind.VIDEOS)
        assertEquals(overflow, unsafe.clearFiltersKeepingQuery().text)
        assertTrue(searchWithFacets(catalog, emptyList(),
            unsafe.clearFiltersKeepingQuery()).isEmpty())
    }
}
