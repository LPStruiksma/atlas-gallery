package nl.atlas.gallery.featuregrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PERF-26: validate deferred pinch commits without scanning a 250k catalogue. */
class GridAnchorCommitScaleTest {
    private val media = List(250_000) { "media:$it" }
    private val publication = GridCatalogIdentity(media, 42L)
    private val anchorIndex = 249_999
    private val anchor = GridAnchorCommit(
        publication, "pictures", GridDensity.SMALL, 393, 810,
        media[anchorIndex], anchorIndex, GridScrollTarget(249_998, 17, false)
    )

    private class IndexedKeys(private val keys: List<String>) {
        var lookups = 0
            private set

        fun at(index: Int): String? {
            lookups++
            return keys.getOrNull(index)
        }
    }

    private fun valid(
        command: GridAnchorCommit = anchor,
        currentPublication: GridCatalogIdentity = publication,
        view: String = "pictures",
        density: GridDensity = GridDensity.SMALL,
        width: Int = 393,
        height: Int = 810,
        keys: IndexedKeys = IndexedKeys(media)
    ): Boolean = command.isCurrent(
        currentPublication, view, density, width, height,
        keys.at(command.mediaIndex)
    )

    @Test fun validCommitAtLastOf250kKeepsSingleIndexedLookup() {
        val keys = IndexedKeys(media)
        repeat(32) {
            assertTrue(valid(keys = keys))
        }
        assertEquals(32, keys.lookups)
    }

    @Test fun publicationAndPermissionChangesInvalidateIdenticalMedia() {
        assertTrue(valid(currentPublication = GridCatalogIdentity(media, 42L)))
        assertFalse(valid(currentPublication = GridCatalogIdentity(media, 43L)))
        val sameValuesDifferentList = media.toList()
        assertEquals(media, sameValuesDifferentList)
        assertFalse(valid(currentPublication = GridCatalogIdentity(sameValuesDifferentList, 42L)))
        assertFalse(valid(currentPublication = GridCatalogIdentity(emptyList<String>(), 42L)))
    }

    @Test fun viewDensityAndViewportChangesCannotReplayDeferredAnchor() {
        assertFalse(valid(view = "album"))
        for (other in GridDensity.entries.filter { it != GridDensity.SMALL }) {
            assertFalse(valid(density = other))
        }
        assertFalse(valid(width = 392))
        assertFalse(valid(height = 809))
        assertFalse(valid(width = 810, height = 393))
    }

    @Test fun staleSlotOrKeyAndMissingLookupCannotAuthorizeCommit() {
        assertFalse(valid(command = anchor.copy(mediaIndex = 0)))
        assertFalse(valid(command = anchor.copy(mediaKey = "media:0")))
        assertFalse(valid(command = anchor.copy(mediaIndex = media.size)))
        assertFalse(valid(command = anchor.copy(mediaIndex = -1)))
        assertFalse(valid(command = anchor.copy(mediaKey = "   ")))
        assertFalse(valid(keys = IndexedKeys(emptyList())))
        val keys = IndexedKeys(media)
        assertFalse(valid(command = anchor.copy(mediaIndex = Int.MAX_VALUE), keys = keys))
        assertEquals(1, keys.lookups)
    }

    @Test fun invalidDestinationCannotAuthorizeCommit() {
        assertFalse(valid(command = anchor.copy(destination = GridScrollTarget(-1, 0, false))))
        assertFalse(valid(command = anchor.copy(destination = GridScrollTarget(0, -1, false))))
        assertTrue(valid(command = anchor.copy(destination = GridScrollTarget(0, 0, true))))
    }

    @Test fun allInvalidCasesUseOneOrFewerIndexedLookups() {
        val keys = IndexedKeys(media)
        val checks = listOf(
            anchor.copy(mediaIndex = -1),
            anchor.copy(mediaIndex = media.size),
            anchor.copy(mediaKey = "wrong"),
            anchor.copy(mediaKey = " "),
            anchor.copy(destination = GridScrollTarget(-1, 0, false)),
            anchor.copy(destination = GridScrollTarget(0, -1, false))
        )
        for (command in checks) assertFalse(valid(command = command, keys = keys))
        assertEquals(checks.size, keys.lookups)
    }
}
