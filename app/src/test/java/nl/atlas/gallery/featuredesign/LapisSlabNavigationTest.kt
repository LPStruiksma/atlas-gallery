package nl.atlas.gallery.featuredesign

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** BRAND-L4 (#657): slab places, People's home and the held-sphere month. */
class LapisSlabNavigationTest {
    @Test fun slabHasTwoPlacesAndNoPeoplePlace() {
        assertEquals(listOf(AtlasDestination.PHOTOS, AtlasDestination.ALBUMS), AtlasSlabPlaces)
        assertEquals("Pictures", AtlasDestination.PHOTOS.title)
        assertEquals("Collections", AtlasDestination.ALBUMS.title)
    }

    @Test fun peopleKeepsCollectionsCurrent() {
        assertEquals(AtlasDestination.ALBUMS, navigationSelection(AtlasDestination.PEOPLE))
        for (place in listOf(AtlasDestination.PHOTOS, AtlasDestination.ALBUMS, AtlasDestination.SEARCH)) {
            assertEquals(place, navigationSelection(place))
        }
    }

    @Test fun collectionsHostsAlbumsPeopleAndStories() {
        assertEquals(listOf("Albums", "People", "Stories"), AtlasCollectionsSection.entries.map { it.title })
    }

    @Test fun labelsFollowTheSystemFontUpTo200Percent() {
        assertEquals(1f, navigationLabelCap(1f) * 1f, 0.0001f)
        assertEquals(2f, navigationLabelCap(2f) * 2f, 0.0001f)
        // Beyond 200 % the effective scale stays at 200 %.
        assertEquals(2f, navigationLabelCap(3f) * 3f, 0.0001f)
    }

    @Test fun sphereShowsTheShortMonthForDateSortsOnly() {
        val zone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val november = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 15, 12, 0) }.timeInMillis
            assertEquals("Nov", fastScrollSphereLabel(true, november, Locale.ENGLISH))
            assertNull(fastScrollSphereLabel(false, november, Locale.ENGLISH))
            assertNull(fastScrollSphereLabel(true, 0L, Locale.ENGLISH))
        } finally {
            TimeZone.setDefault(zone)
        }
    }
}
