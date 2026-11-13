package nl.atlas.gallery.featuregrid

import org.junit.Assert.*
import org.junit.Test

class GridCatalogIdentityTest {
    @Test fun identicalMediaValuesFromSeparatePublicationsInvalidateCaches() {
        val a = List(100) { "media://$it" }
        val b = a.toList()
        assertEquals(a, b)
        assertTrue(GridCatalogIdentity(a) != GridCatalogIdentity(b))
        assertEquals(GridCatalogIdentity(a), GridCatalogIdentity(a))
    }

    @Test fun aSharedImmutablePublicationKeepsStableIdentity() {
        val media = List(150_000) { it }
        val a = GridCatalogIdentity(media)
        val b = GridCatalogIdentity(media)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test fun sameListReferenceWithNewGrantEpochInvalidatesCache() {
        val media = List(150_000) { "uri:$it" }
        val old = GridCatalogIdentity(media, 27L)
        val newer = GridCatalogIdentity(media, 28L)
        assertFalse(old == newer)
        assertEquals(old, GridCatalogIdentity(media, 27L))
        assertEquals(old.hashCode(), GridCatalogIdentity(media, 27L).hashCode())
    }

    @Test fun unchangedLegacyCallerStillGetsIdentityBasedCacheKeys() {
        val one = listOf("a", "b")
        assertEquals(GridCatalogIdentity(one), GridCatalogIdentity(one, 0L))
        assertTrue(GridCatalogIdentity(one) != GridCatalogIdentity(one, 1L))
        assertTrue(GridCatalogIdentity(one) != GridCatalogIdentity(one.toList()))
    }
}
