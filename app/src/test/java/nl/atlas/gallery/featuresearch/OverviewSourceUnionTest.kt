package nl.atlas.gallery.featuresearch

import nl.atlas.gallery.MediaRecord
import nl.atlas.gallery.featurealbums.AlbumMediaFilter
import nl.atlas.gallery.featurealbums.AlbumSummary
import nl.atlas.gallery.featuresearch.face.screenScopeLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** FIND-2 F (#181): synthetic-only proof for Albums/People overview sources. */
class OverviewSourceUnionTest {
    private fun media(
        i: Int, path: String = "Pictures/Fictional/", video: Boolean = false,
        volume: String = "external"
    ) = MediaRecord(i.toLong(), "content://media/external/images/media/$i",
        "synthetic_$i.jpg", path, 100, 1_000L * i, video, volumeName = volume)

    private fun album(path: String, title: String = path, volume: String = "external") =
        AlbumSummary(path, title, 1, 1, 0, null, false, 0L, volumeName = volume)

    // Display order of the authorized gallery publication (the screen sort).
    private val summer = listOf(media(5, "DCIM/Summer/"), media(1, "DCIM/Summer/", video = true))
    private val work = listOf(media(9, "Pictures/Work/"))
    private val other = listOf(media(3, "Pictures/Other/"))
    private val sdSummer = listOf(media(7, "DCIM/Summer/", volume = "1234-abcd"))
    private val authorized = listOf(summer[0], other[0], work[0], sdSummer[0], summer[1])

    @Test fun albumsUnionIsOnlyTheShownAlbumsInGalleryOrder() {
        val union = albumsOverviewUnion(authorized,
            listOf(album("Pictures/Work/"), album("DCIM/Summer/")), AlbumMediaFilter.ALL)
        assertEquals(listOf(summer[0], work[0], summer[1]), union)
    }

    @Test fun albumsUnionMatchesExactVolumeAndPathNeverTitle() {
        // A same-named folder on removable storage is another album.
        val sd = albumsOverviewUnion(authorized,
            listOf(album("DCIM/Summer/", volume = "1234-abcd")), AlbumMediaFilter.ALL)
        assertEquals(sdSummer, sd)
        // A title that looks like another album's path selects nothing.
        assertTrue(albumsOverviewUnion(authorized,
            listOf(album("DCIM/Nowhere/", title = "Summer")), AlbumMediaFilter.ALL).isEmpty())
    }

    @Test fun albumsUnionAppliesTheListsPhotoVideoFilter() {
        val shown = listOf(album("DCIM/Summer/"))
        assertEquals(listOf(summer[0]),
            albumsOverviewUnion(authorized, shown, AlbumMediaFilter.PHOTOS))
        assertEquals(listOf(summer[1]),
            albumsOverviewUnion(authorized, shown, AlbumMediaFilter.VIDEOS))
    }

    @Test fun emptyListOrRevokedSourceGivesAnEmptyUnion() {
        assertTrue(albumsOverviewUnion(authorized, emptyList(), AlbumMediaFilter.ALL).isEmpty())
        assertTrue(albumsOverviewUnion(emptyList(),
            listOf(album("DCIM/Summer/")), AlbumMediaFilter.ALL).isEmpty())
        assertTrue(peopleOverviewUnion(emptyList(),
            setOf(summer[0].absolutePath), setOf(work[0].uri)).isEmpty())
        assertTrue(peopleOverviewUnion(authorized, emptySet(), emptySet()).isEmpty())
    }

    @Test fun peopleUnionKeepsOnlyAuthorizedMembersInGalleryOrder() {
        val union = peopleOverviewUnion(authorized,
            importedConfirmedPaths = setOf(work[0].absolutePath,
                "/storage/emulated/0/Pictures/Gone/synthetic_404.jpg"),
            nativeAuthorizedUris = setOf(summer[0].uri,
                "content://media/external/images/media/999"))
        assertEquals(listOf(summer[0], work[0]), union)
    }

    @Test fun importedPeopleNeverAttributeAVideoOrAnotherVolume() {
        // Reviewed V4 paths name primary-volume images only.
        val union = peopleOverviewUnion(authorized,
            importedConfirmedPaths = setOf(summer[1].absolutePath, sdSummer[0].absolutePath),
            nativeAuthorizedUris = emptySet())
        assertTrue(union.isEmpty())
    }

    @Test fun nativeUrisFromAnotherGrantDoNotWidenTheSource() {
        val narrowed = authorized.take(2)
        assertEquals(listOf(summer[0]), peopleOverviewUnion(narrowed, emptySet(),
            setOf(summer[0].uri, work[0].uri)))
    }

    @Test fun unionOriginExpiresOnGrantEpochRouteOrSourceChange() {
        val origin = OverviewUnionOrigin("Albums", authorized, 7L, "1:a", "7")
        assertTrue(origin.isCurrent(authorized, 7L, "1:a", "7"))
        assertFalse(origin.isCurrent(authorized, 7L, "1:a", null))
        assertFalse(origin.isCurrent(authorized, 7L, "1:a", "8"))
        assertFalse(origin.isCurrent(authorized, 8L, "1:a", "7"))
        assertFalse(origin.isCurrent(authorized, 7L, "2:a", "7"))
        // Equal rows from a new publication are a new source (identity, not equals).
        assertFalse(origin.isCurrent(authorized.toList(), 7L, "1:a", "7"))
        // An Albums union ignores People changes.
        assertTrue(origin.isCurrent(authorized, 7L, "1:a", "7", listOf<Any>(), "g2"))
    }

    @Test fun peopleUnionOriginAlsoTracksPeopleSources() {
        val people = listOf<Any>()
        val origin = OverviewUnionOrigin("People", authorized, 7L, "2:a", "7",
            peopleSource = people, nativeGeneration = "g1")
        assertTrue(origin.isCurrent(authorized, 7L, "2:a", "7", people, "g1"))
        assertFalse(origin.isCurrent(authorized, 7L, "2:a", "7", listOf<Any>(), "g1"))
        assertFalse(origin.isCurrent(authorized, 7L, "2:a", "7", people, "g2"))
        assertFalse(origin.isCurrent(authorized, 7L, "2:a", "7", people, null))
    }

    /**
     * A toy deterministic ranker standing in for the face/visual engine: the
     * global stream restricted to S must equal the engine run on S alone, for
     * both best-match order and membership, with hit tiers untouched.
     */
    @Test fun scopedHitsEqualTheEnginesRestrictedQueryForAUnion() {
        data class Hit(val uri: String, val score: Int, val tier: String)
        val library = List(64) { media(it, if (it % 3 == 0) "DCIM/Summer/" else "Pictures/Work/") }
        fun engine(catalog: List<MediaRecord>) = catalog
            .map { Hit(it.uri, (it.id * 37 % 101).toInt(), if (it.id % 2 == 0L) "same" else "probable") }
            .sortedWith(compareByDescending<Hit> { it.score }.thenBy { it.uri })
        val s = albumsOverviewUnion(library, listOf(album("DCIM/Summer/")), AlbumMediaFilter.ALL)
        val global = engine(library)
        val restricted = engine(s)
        assertEquals(restricted, scopeRankedHits(global, s.map { it.uri }, { it.uri }))
        val screen = scopeRankedHits(global, s.map { it.uri }, { it.uri }, ScopedRankOrder.SCREEN_SORT)
        assertEquals(s.map { it.uri }, screen.map { it.uri })
        assertEquals(restricted.toSet(), screen.toSet())
    }

    @Test fun scopeLineNamesTheOriginList() {
        assertEquals("All", screenScopeLine(false, "Albums", "All"))
        assertEquals("Matches in this screen", screenScopeLine(true, null, "All"))
        assertEquals("Matches in these albums", screenScopeLine(true, "Albums", "All"))
        assertEquals("Matches in these people", screenScopeLine(true, "People", "All"))
    }
}
