package nl.atlas.gallery.featurealbums

import nl.atlas.gallery.MediaRecord
import org.junit.Assert.*
import org.junit.Test

class AlbumCoverPageSessionTest {
    private fun media(id: Long, date: Long = id, path: String = "DCIM/Camera/") =
        MediaRecord(
            id = id, uri = "content://atlas/image/$id", name = "Synthetic $id",
            relativePath = path, sizeBytes = 512L,
            takenAtMillis = date, isVideo = false,
            volumeName = "external_primary"
        )

    private val route = AlbumRoute(
        "DCIM/Camera/", AlbumMediaFilter.ALL, "external_primary"
    )

    @Test fun unchangedEpochButChangedGrantInvalidatesHistory() {
        val source = (1L..6L).map { media(it) }
        val original = AlbumCoverPermissionScope(700L)
        val regranted = AlbumCoverPermissionScope(701L)
        val session = AlbumCoverPageSession.start(source, 29L, route, original)
        val page = buildAlbumCoverChoices(
            source, route, 29L, limit = 2, permissionScope = original)
        val second = session.olderOrNull(page, source, 29L, route, original)!!
        assertTrue(second.isCurrent(source, 29L, route, original))
        assertFalse(second.isCurrent(source, 29L, route, regranted))
        assertNull(second.currentOrNull(source, 29L, route, regranted))
        assertNull(second.newerOrNull(source, 29L, route, regranted))
        assertNull(second.olderOrNull(page, source, 29L, route, regranted))
        assertNull(second.displayedCandidatesOrNull(
            page, source, 29L, route, regranted))
        assertNull(second.currentOrNull(source, 29L, route))
        assertSame(session, second.newerOrNull(source, 29L, route, original))
    }

    @Test fun advancingAndBackingAcrossAllPagesPreservesExactPage() {
        val items = (1L..103L).map { media(it, it % 7L) }
        var session = AlbumCoverPageSession.start(items, 42L, route)
        val all = mutableListOf<String>()
        var pages = 0
        while (true) {
            val result = buildAlbumCoverChoices(
                items, route, 42L, limit = 12, olderThan = session.cursor
            )
            val visible = session.displayedCandidatesOrNull(
                result, items, 42L, route
            )!!
            assertEquals(result.candidates, visible)
            all.addAll(visible.map { it.uri })
            pages++
            val old = session.olderOrNull(result, items, 42L, route) ?: break
            assertEquals(pages + 1, old.pageNumber)
            assertSame(session, old.newerOrNull(items, 42L, route))
            session = old
            assertTrue("Non-advancing cursor", pages <= items.size)
        }
        assertEquals(9, pages)
        assertEquals(items.size, all.size)
        assertEquals(items.map { it.uri }.toSet(), all.toSet())
        while (session.pageNumber > 1) {
            session = session.newerOrNull(items, 42L, route)!!
        }
        assertNull(session.cursor)
        assertNull(session.newerOrNull(items, 42L, route))
    }

    @Test fun sourceIdentityOrGrantEpochChangeInvalidatesEntireHistory() {
        val source = (1L..4L).map { media(it) }
        val session = AlbumCoverPageSession.start(source, 5L, route)
        val first = buildAlbumCoverChoices(source, route, 5L, limit = 2)
        val second = session.olderOrNull(first, source, 5L, route)!!
        val equalListNewPublication = source.toList()
        assertEquals(source, equalListNewPublication)
        assertFalse(source === equalListNewPublication)
        assertNull(second.currentOrNull(equalListNewPublication, 5L, route))
        assertNull(second.newerOrNull(equalListNewPublication, 5L, route))
        assertNull(second.olderOrNull(first, equalListNewPublication, 5L, route))
        assertNull(second.displayedCandidatesOrNull(
            first, equalListNewPublication, 5L, route))
        assertNull(second.currentOrNull(source, 6L, route))
        assertNull(second.newerOrNull(source, 6L, route))
        assertNull(second.currentOrNull(source, 5L, route.copy(
            volumeName = "ABCD-1234"
        )))
    }

    @Test fun outOfOrderPageCallbacksCannotAdvanceOrSkip() {
        val source = (1L..72L).map { media(it) }
        val firstSession = AlbumCoverPageSession.start(source, 12L, route)
        val first = buildAlbumCoverChoices(source, route, 12L, limit = 12)
        val secondSession = firstSession.olderOrNull(first, source, 12L, route)!!
        assertNull(secondSession.olderOrNull(first, source, 12L, route))
        val second = buildAlbumCoverChoices(
            source, route, 12L, limit = 12, olderThan = secondSession.cursor
        )
        assertEquals(12, secondSession.displayedCandidatesOrNull(
            second, source, 12L, route
        )!!.size)
        val third = secondSession.olderOrNull(second, source, 12L, route)!!
        assertEquals(3, third.pageNumber)
        assertNull(third.olderOrNull(second, source, 12L, route))
    }

    @Test fun cursorsRejectForeignGrantRouteOrUnscopedLegacyValues() {
        val source = (1L..5L).map { media(it) }
        val first = buildAlbumCoverChoices(source, route, 23L, limit = 2)
        val cursor = first.nextOlderCursor!!
        assertEquals(23L, cursor.accessEpoch)
        assertEquals(route, cursor.route)
        for (foreign in listOf(
            cursor.copy(accessEpoch = 24L),
            cursor.copy(route = route.copy(relativePath = "Pictures/Camera/")),
            cursor.copy(accessEpoch = null),
            cursor.copy(route = null)
        )) {
            try {
                buildAlbumCoverChoices(source, route, 23L, olderThan = foreign)
                fail("Accepted foreign/unscoped cursor")
            } catch (_: IllegalArgumentException) { }
        }
        val second = buildAlbumCoverChoices(
            source, route, 23L, limit = 2, olderThan = cursor
        )
        assertNull(currentAlbumCoverChoicesOrNull(
            second, source, 23L, route, cursor.copy(accessEpoch = 24L)
        ))
        assertNull(chooseAlbumCoverOrNull(
            second, source, 23L, route, second.candidates.first().uri,
            cursor.copy(route = route.copy(mediaFilter = AlbumMediaFilter.VIDEOS))
        ))
    }

    @Test fun largeSyntheticSourceDoesNotRequireStructuralListComparison() {
        val source = (0L until 150_000L).map { media(it, it % 23L) }
        val session = AlbumCoverPageSession.start(source, 51L, route)
        val first = buildAlbumCoverChoices(source, route, 51L, limit = 36)
        val second = session.olderOrNull(first, source, 51L, route)!!
        assertEquals(2, second.pageNumber)
        assertNotNull(second.cursor)
        assertSame(session, second.newerOrNull(source, 51L, route))
        assertTrue(second.isCurrent(source, 51L, route))
        assertFalse(second.isCurrent(source.toList(), 51L, route))
    }
}
