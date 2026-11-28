package nl.atlas.gallery.featurepersistence

import nl.atlas.gallery.MediaRecord
import nl.atlas.gallery.PersonEntry
import nl.atlas.gallery.albumPaths
import nl.atlas.gallery.buildPeopleCovers
import nl.atlas.gallery.folderDeclutterDeck
import nl.atlas.gallery.searchCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HIDE-1 (#585). MainActivity feeds every ordinary surface from one catalogue
 * ([HiddenMedia.visible] applied at its source), so each surface is proven here
 * by running its own pure builder on the filtered list and checking the hidden
 * item leaves no trace (list, album, search hit, People cover or count, deck).
 */
class HiddenMediaTest {
    private fun record(id: Long, path: String, name: String = "$id.jpg", volume: String = "external_primary") =
        MediaRecord(id, "content://media/$volume/images/media/$id", name, path, 10L, id, false,
            volumeName = volume)

    private val a = record(1, "DCIM/Camera/")
    private val b = record(2, "DCIM/Camera/", "beach.jpg")
    private val secret = record(3, "DCIM/Secret/", "beach-secret.jpg")
    private val sdSameId = record(3, "DCIM/Camera/", "sd.jpg", volume = "1234-abcd")
    private val all = listOf(a, b, secret, sdSameId)
    private val hidden = setOf(HiddenMedia.keyOf(secret))

    @Test fun keyIsVolumeAndRowIdSoTheSameIdOnAnotherVolumeStaysVisible() {
        assertEquals("external_primary/3", HiddenMedia.keyOf(secret))
        assertEquals(listOf(a, b, sdSameId), HiddenMedia.visible(all, hidden))
    }

    @Test fun nothingHiddenOrNothingMatchedReturnsTheSameListInstance() {
        assertSame(all, HiddenMedia.visible(all, emptySet()))
        assertSame(all, HiddenMedia.visible(all, setOf("external_primary/999")))
    }

    @Test fun hiddenCollectionIsTheExactInverse() {
        assertEquals(listOf(secret), HiddenMedia.hiddenOnly(all, hidden))
        assertEquals(emptyList<MediaRecord>(), HiddenMedia.hiddenOnly(all, emptySet()))
    }

    @Test fun hideAndUnhideAreIdempotentAndPreserveOthers() {
        val keys = listOf(HiddenMedia.keyOf(a), HiddenMedia.keyOf(a), HiddenMedia.keyOf(b))
        val once = HiddenMedia.withHidden(hidden, keys)
        assertEquals(3, once.size)
        assertEquals(once, HiddenMedia.withHidden(once, keys))
        assertEquals(hidden, HiddenMedia.withoutHidden(once, keys))
        assertEquals(hidden, HiddenMedia.withoutHidden(hidden, listOf(HiddenMedia.keyOf(a))))
    }

    @Test fun timelinePicturesDropTheHiddenItemAndKeepOrder() {
        assertFalse(HiddenMedia.visible(all, hidden).any { it.id == 3L && it.volumeName == "external_primary" })
    }

    @Test fun albumsLoseTheHiddenOnlyFolder() {
        assertTrue("DCIM/Secret/" in albumPaths(all))
        assertFalse("DCIM/Secret/" in albumPaths(HiddenMedia.visible(all, hidden)))
    }

    @Test fun searchNeverReturnsAHiddenItem() {
        val shown = HiddenMedia.visible(all, hidden)
        assertEquals(listOf(b, secret), searchCatalog(all, emptyList(), "beach"))
        assertEquals(listOf(b), searchCatalog(shown, emptyList(), "beach"))
    }

    @Test fun peopleCoversAndCountsIgnoreHiddenPhotos() {
        val person = PersonEntry(7, "Sam", setOf(secret.absolutePath, b.absolutePath))
        val before = buildPeopleCovers(all, listOf(person))
        assertEquals(2, before.accessibleCounts[7L])
        val after = buildPeopleCovers(HiddenMedia.visible(all, hidden), listOf(person))
        assertEquals(1, after.accessibleCounts[7L])
        assertEquals(b, after.coversByPersonId[7L])
        val onlyHidden = PersonEntry(8, "Lee", setOf(secret.absolutePath))
        val gone = buildPeopleCovers(HiddenMedia.visible(all, hidden), listOf(onlyHidden))
        assertEquals(0, gone.accessibleCounts[8L])
        assertFalse(8L in gone.coversByPersonId)
    }

    @Test fun declutterDeckExcludesHiddenItems() {
        assertEquals(listOf(secret.uri),
            folderDeclutterDeck(all, "DCIM/Secret/").map { it.uri })
        assertTrue(folderDeclutterDeck(HiddenMedia.visible(all, hidden), "DCIM/Secret/").isEmpty())
    }
}
