package nl.atlas.gallery.featuregrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GridFocalIdentityTest {
    @Test fun currentIndexAdmitsExactMatchingMedia() {
        assertEquals(37, currentFocalIndexOrNull(37, "media:37", 150_000) { "media:$it" })
    }

    @Test fun staleReorderedIndexDoesNotScan150kMedia() {
        var lookups = 0
        val source = List(150_000) { "photo:$it" }.reversed()
        assertNull(currentFocalIndexOrNull(37, "photo:37", source.size) {
            lookups++
            source[it]
        })
        assertEquals(1, lookups)
    }

    @Test fun negativeOrOutOfRangeOrEmptyCatalogueNeverLookUpAnything() {
        var lookups = 0
        val read: (Int) -> String? = { lookups++; "id:$it" }
        assertNull(currentFocalIndexOrNull(-1, "id:-1", 100, read))
        assertNull(currentFocalIndexOrNull(100, "id:100", 100, read))
        assertNull(currentFocalIndexOrNull(0, "id:0", 0, read))
        assertNull(currentFocalIndexOrNull(0, "", 100, read))
        assertNull(currentFocalIndexOrNull(0, "  ", 100, read))
        assertEquals(0, lookups)
    }

    @Test fun missingAndReplacedUrisRejectCurrentIndex() {
        assertNull(currentFocalIndexOrNull(4, "old", 100) { null })
        assertNull(currentFocalIndexOrNull(4, "old", 100) { "new" })
    }

    @Test fun evenThousandsOfInterleavedGestureChecksRemainOneLookupEach() {
        var checked = 0
        for (i in 0 until 10_000) {
            val index = (i * 197) % 150_000
            val got = currentFocalIndexOrNull(index, "p:$index", 150_000) {
                checked++
                "p:$it"
            }
            assertEquals(index, got)
        }
        assertEquals(10_000, checked)
        assertTrue(checked < 150_000)
    }
}
