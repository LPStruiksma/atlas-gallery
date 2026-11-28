package nl.atlas.gallery

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test fun measuredReturnUsesSettledUriNotOpeningTileAndRejectsInvalidBounds() {
        val source = listOf(media(1), media(2))
        val pending = PendingViewerReturn(source, source[1].uri, 0, 42L)
        val bounds = androidx.compose.ui.geometry.Rect(10f, 20f, 100f, 110f)
        val target = MeasuredViewerReturn(pending)
        target.offer(source[0], 42L, bounds, 1L)
        assertTrue(target.measured == null)
        target.offer(source[1], 41L, bounds, 2L)
        assertTrue(target.measured == null)
        target.offer(source[1], 42L, androidx.compose.ui.geometry.Rect.Zero, 3L)
        assertTrue(target.measured == null)
        target.offer(source[1], 42L, bounds, 4L)
        assertTrue(target.measured?.media?.uri == source[1].uri)
        assertTrue(target.measured?.bounds == bounds)
        assertFalse(viewerReturnMatchesCurrentRoute(pending, source, 0, false, 42L))
        assertFalse(viewerReturnMatchesCurrentRoute(pending, source, 1, true, 42L))
        target.offer(source[1], 42L, androidx.compose.ui.geometry.Rect.Zero, 5L)
        assertTrue(target.measured == null)
        target.offer(source[1].copy(isVideo = true), 42L, bounds, 6L)
        assertTrue(target.measured == null)
        target.offer(source[1], 42L, bounds, 7L)
        target.clear()
        assertTrue(target.measured == null)
    }

    @Test fun unmeasuredGridReturnExpiresWithoutAnOpeningTileFallback() = runBlocking {
        val candidates = MutableStateFlow<SharedPhotoOrigin?>(null)
        // The same bounded wait as the committed host; no real media or grid required.
        assertNull(awaitMeasuredViewerReturn(candidates, timeoutMillis = 30L))
    }

    @Test fun aMeasuredTargetBeforeDeadlineIsReturnedUnchanged() = runBlocking {
        val target = SharedPhotoOrigin(media(2), 42L,
            androidx.compose.ui.geometry.Rect(10f, 20f, 100f, 110f), 1L)
        val candidates = MutableStateFlow<SharedPhotoOrigin?>(null)
        var actual: SharedPhotoOrigin? = null
        val waiter = launch(start = CoroutineStart.UNDISPATCHED) {
            actual = awaitMeasuredViewerReturn(candidates)
        }
        candidates.value = target
        waiter.join()
        assertEquals(target, actual)
    }

    @Test fun cancelledPreparationDoesNotReturnAnOriginToTheOldHost() = runBlocking {
        val candidates = MutableStateFlow<SharedPhotoOrigin?>(null)
        var completed = false
        val waiter = launch(start = CoroutineStart.UNDISPATCHED) {
            awaitMeasuredViewerReturn(candidates)
            completed = true
        }
        waiter.cancelAndJoin()
        assertFalse(completed)
    }

    @Test fun visibleItemKeepsScrollOffsetOffscreenItemSeeks() {
        assertTrue(viewerReturnOverlapsViewport(300, 200, 0, 1000))
        assertTrue(viewerReturnOverlapsViewport(900, 200, 0, 1000))
        assertFalse(viewerReturnOverlapsViewport(1000, 200, 0, 1000))
        assertFalse(viewerReturnOverlapsViewport(-300, 200, 0, 1000))
    }
}
