package nl.atlas.gallery.featuresearch

import nl.atlas.gallery.MediaRecord
import nl.atlas.gallery.PersonEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopedSearchTest {
    @Test fun namesAreAccentInsensitiveAndContainMatches() {
        assertTrue(scopedNameMatches("Jóse de Vries", "jose"))
        assertTrue(scopedNameMatches("Jóse de Vries", "VRIE"))
        assertFalse(scopedNameMatches("Jóse de Vries", "someone else"))
        assertFalse(scopedNameMatches("", "jose"))
    }

    @Test fun namesRetainIncomingSortAndHideUnnamed() {
        val ordered = listOf("Zoë", "", "José", "Anna")
        assertEquals(listOf("José"), scopedNamedMatches(ordered, "jos") { it })
        assertEquals(listOf("Zoë", "José"), scopedNamedMatches(ordered, "e") { it })
        assertSame(ordered, scopedNamedMatches(ordered, "") { it })
    }

    @Test fun scopedSnapshotRejectsChangedSourceAndPermission() {
        val screen = emptyList<MediaRecord>()
        val library = emptyList<MediaRecord>()
        val people = emptyList<PersonEntry>()
        val snapshot = ScopedMediaSearchSnapshot(screen, library, people, "epoch-1", "2019",
            AtlasSearchScope.THIS_SCREEN, screen)
        assertSame(screen, currentScopedMediaSearchOrNull(snapshot, screen, library,
            people, "epoch-1", "2019", AtlasSearchScope.THIS_SCREEN))
        assertEquals(null, currentScopedMediaSearchOrNull(snapshot, screen, library,
            people, "epoch-2", "2019", AtlasSearchScope.THIS_SCREEN))
        assertEquals(null, currentScopedMediaSearchOrNull(snapshot, screen, library,
            people, "epoch-1", "2019", AtlasSearchScope.LIBRARY))
        assertEquals(null, currentScopedMediaSearchOrNull(snapshot,
            emptyList<MediaRecord>(), library, people, "epoch-1", "2019",
            AtlasSearchScope.THIS_SCREEN))
    }
}
