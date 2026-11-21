package nl.atlas.gallery.featureintents

import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalPickModelTest {
    private val a = "content://media/external/images/media/1"
    private val b = "content://media/external/images/media/2"
    private val c = "content://media/external/video/media/3"

    private fun pick(filter: PickMediaFilter, multiple: Boolean) =
        ExternalEntry.Pick(filter, allowMultiple = multiple, legacyPick = false)

    @Test fun singlePickTapReplacesOrClears() {
        assertEquals(listOf(a), PickSelection.toggle(emptyList(), a, allowMultiple = false))
        assertEquals(listOf(b), PickSelection.toggle(listOf(a), b, allowMultiple = false))
        assertEquals(emptyList<String>(), PickSelection.toggle(listOf(a), a, allowMultiple = false))
    }

    @Test fun multiplePickTapKeepsPickOrder() {
        var chosen = emptyList<String>()
        for (uri in listOf(b, a, c)) chosen = PickSelection.toggle(chosen, uri, allowMultiple = true)
        assertEquals(listOf(b, a, c), chosen)
        assertEquals(listOf(b, c), PickSelection.toggle(chosen, a, allowMultiple = true))
    }

    @Test fun dragKeepsChosenOrderAndAppendsInGridOrder() {
        val offered = listOf(a, b, c)
        assertEquals(listOf(c, a, b),
            PickSelection.replaced(listOf(c), setOf(a, b, c), offered, allowMultiple = true))
        assertEquals(listOf(b), PickSelection.replaced(listOf(c, b), setOf(b), offered, allowMultiple = true))
    }

    @Test fun singlePickIgnoresDrags() {
        assertEquals(listOf(a), PickSelection.replaced(listOf(a), setOf(a, b), listOf(a, b), allowMultiple = false))
    }

    @Test fun aChoiceTheCatalogueNoLongerOffersIsDropped() {
        assertEquals(listOf(c), PickSelection.stillOffered(listOf(a, c), setOf(b, c)))
    }

    @Test fun copyFollowsTheRequestedTypeAndMode() {
        assertEquals("Choose a photo", PickCopy.title(pick(PickMediaFilter.IMAGES, false)))
        assertEquals("Choose photos", PickCopy.title(pick(PickMediaFilter.IMAGES, true)))
        assertEquals("Choose a video", PickCopy.title(pick(PickMediaFilter.VIDEOS, false)))
        assertEquals("Choose items", PickCopy.title(pick(PickMediaFilter.ANY_MEDIA, true)))
        assertEquals("Choose an item", PickCopy.title(pick(PickMediaFilter.ANY_MEDIA, false)))
        assertEquals("No matching photos", PickCopy.empty(PickMediaFilter.of(listOf("image/png"))!!))
        assertEquals("No matching videos", PickCopy.empty(PickMediaFilter.VIDEOS))
        assertEquals("No matching items", PickCopy.empty(PickMediaFilter.ANY_MEDIA))
    }

    @Test fun doneCountsOnlyInAMultiplePick() {
        assertEquals("Done", PickCopy.done(pick(PickMediaFilter.ANY_MEDIA, true), 0))
        assertEquals("Done (2)", PickCopy.done(pick(PickMediaFilter.ANY_MEDIA, true), 2))
        assertEquals("Done", PickCopy.done(pick(PickMediaFilter.ANY_MEDIA, false), 1))
    }

    @Test fun filterOffersOnlyTheRequestedKindInTheGrid() {
        assertEquals(true, PickMediaFilter.IMAGES.offers(isVideo = false))
        assertEquals(false, PickMediaFilter.IMAGES.offers(isVideo = true))
        // image/png offers photos in the grid; the final provider check narrows to PNG.
        val png = PickMediaFilter.of(listOf("image/png"))!!
        assertEquals(true, png.offers(isVideo = false))
        assertEquals(false, png.accepts("image/jpeg"))
        assertEquals(true, png.accepts("image/png"))
    }
}
