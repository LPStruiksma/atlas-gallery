package nl.atlas.gallery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerReturnAdmissionTest {
    private fun media(id: Long) = MediaRecord(id, "content://media/$id",
        "fiction-$id.jpg", "DCIM/", 123L, 1000L, false)

    @Test fun exactSourceReferenceAndRouteAreRequired() {
        val source = listOf(media(1), media(2))
        val intent = PendingViewerReturn(source, source[1].uri, 0, 42L)
        assertTrue(viewerReturnMatchesCurrentRoute(intent, source, 0, true, 42L))
        assertFalse(viewerReturnMatchesCurrentRoute(intent, source.toList().toMutableList(), 0, true, 42L))
        assertFalse(viewerReturnMatchesCurrentRoute(intent, source, 1, true, 42L))
        assertFalse(viewerReturnMatchesCurrentRoute(intent, source, 0, false, 42L))
        // A new grant with a REUSED List instance must not revive prior access.
        assertFalse(viewerReturnMatchesCurrentRoute(intent, source, 0, true, 43L))
        assertFalse(viewerReturnCanApply(intent, source, 0, true, 1, 2, 43L))
    }

    @Test fun noScrollBeforeDestinationListIsMounted() {
        val source = listOf(media(1), media(2))
        val intent = PendingViewerReturn(source, source[1].uri, 0, 42L)
        assertFalse(viewerReturnCanApply(intent, source, 0, true, 1, 0, 42L))
        assertFalse(viewerReturnCanApply(intent, source, 0, true, 1, 1, 42L))
        assertTrue(viewerReturnCanApply(intent, source, 0, true, 1, 2, 42L))
    }

    @Test fun noFallbackToWrongUriOrRevokedMedia() {
        val source = listOf(media(1), media(2))
        val intent = PendingViewerReturn(source, source[1].uri, 0, 42L)
        assertFalse(viewerReturnCanApply(intent, source, 0, true, 0, 2, 42L))
        assertFalse(viewerReturnCanApply(intent, source, 0, true, -1, 2, 42L))
        assertFalse(viewerReturnCanApply(intent, source, 0, false, 1, 2, 42L))
    }

    @Test fun largeEqualContentCatalogueIsNotTheSamePublication() {
        val source = (0 until 150_000).map { media(it.toLong()) }
        val copied = source.toList().toMutableList()
        val intent = PendingViewerReturn(source, source.last().uri, 0, 42L)
        assertTrue(viewerReturnCanApply(intent, source, 0, true, source.lastIndex, source.size, 42L))
        assertFalse(viewerReturnCanApply(intent, copied, 0, true, copied.lastIndex, copied.size, 42L))
    }
}
