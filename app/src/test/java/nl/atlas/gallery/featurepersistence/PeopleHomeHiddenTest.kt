package nl.atlas.gallery.featurepersistence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** HIDE-1 (#585): People home counts and covers ignore hidden media (synthetic keys). */
class PeopleHomeHiddenTest {
    private fun face(uid: String, media: String, taken: Long, video: Boolean = false,
        confirmed: Boolean = true) =
        PeopleHomeHidden.PersonFace(uid, media, taken, video, confirmed)

    private val stored = StoredPersonSummary("Ada", faceCount = 5, coverFaceUid = "f1",
        mediaCount = 4, firstAppearanceMs = 100, mostRecentAppearanceMs = 400,
        photoCount = 3, videoCount = 1)
    private val faces = listOf(
        face("f1", "m1", 100), face("f2", "m2", 200), face("f3", "m3", 300),
        face("f4", "m4", 400, video = true), face("f5", "m4", 400, video = true, confirmed = false)
    )

    @Test fun nothingHiddenReturnsTheStoredSummaryUntouched() {
        assertSame(stored, PeopleHomeHidden.summarize(stored, faces, emptySet()))
    }

    @Test fun hidingACoverMediaMovesTheCoverAndDropsTheCounts() {
        val home = PeopleHomeHidden.summarize(stored, faces, setOf("m1"))!!
        assertEquals("f2", home.coverFaceUid)
        assertEquals(4, home.faceCount)
        assertEquals(3, home.mediaCount)
        assertEquals(2, home.photoCount)
        assertEquals(1, home.videoCount)
        assertEquals(200L, home.firstAppearanceMs)
        assertEquals(400L, home.mostRecentAppearanceMs)
    }

    @Test fun hidingANonCoverMediaKeepsTheCover() {
        val home = PeopleHomeHidden.summarize(stored, faces, setOf("m3", "m4"))!!
        assertEquals("f1", home.coverFaceUid)
        assertEquals(2, home.faceCount)
        assertEquals(2, home.photoCount)
        assertEquals(0, home.videoCount)
        assertEquals(200L, home.mostRecentAppearanceMs)
    }

    @Test fun unhideRestoresTheOriginalCountsAndCover() {
        val hiddenHome = PeopleHomeHidden.summarize(stored, faces, setOf("m1", "m2"))!!
        assertEquals("f3", hiddenHome.coverFaceUid)
        val unhidden = PeopleHomeHidden.summarize(stored, faces, emptySet())!!
        assertEquals(stored, unhidden)
    }

    @Test fun aPersonWithNoVisibleMediaOrNoVisibleConfirmedFaceLeavesHome() {
        assertNull(PeopleHomeHidden.summarize(stored, faces, setOf("m1", "m2", "m3", "m4")))
        // Only a matched (unconfirmed) face remains visible: no cover, so no row.
        val matchedOnly = listOf(face("f1", "m1", 100), face("f9", "m9", 900, confirmed = false))
        assertNull(PeopleHomeHidden.summarize(stored, matchedOnly, setOf("m1")))
    }

    @Test fun unnamedGroupsLoseHiddenFacesAndHiddenAnchors() {
        val groups = listOf(StoredUnnamedGroup("a", 5), StoredUnnamedGroup("b", 4),
            StoredUnnamedGroup("c", 3), StoredUnnamedGroup("d", 3))
        val result = PeopleHomeHidden.unnamed(groups,
            hiddenMembers = mapOf("a" to 1, "c" to 1), hiddenAnchors = setOf("d"))
        assertEquals(listOf(StoredUnnamedGroup("a", 4), StoredUnnamedGroup("b", 4)), result)
    }
}
