package nl.atlas.gallery.featuregrid

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.Interaction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #698: the delayed second scroll-to-top never overrides a user's scroll.
 * Each test waits until the guard listens before it emits, so no emit is
 * lost and nothing depends on wall-clock delays.
 */
class ScrollResetGuardTest {
    private fun source() = MutableSharedFlow<Interaction>(extraBufferCapacity = 16)

    private suspend fun MutableSharedFlow<Interaction>.awaitListener() {
        subscriptionCount.first { it > 0 }
    }

    @Test fun userDragBeforeTheNewOrderCancelsTheReset() = runBlocking<Unit> {
        val grid = source()
        val orderShown = CompletableDeferred<Unit>()
        val result = async {
            awaitReadyUnlessUserDrags(listOf(grid), 15_000L) { orderShown.await() }
        }
        grid.awaitListener()
        grid.emit(DragInteraction.Start())
        // The drag alone decides it: no reset, before the new order is shown.
        assertFalse(result.await())
        // The new order arriving afterwards changes nothing.
        orderShown.complete(Unit)
    }

    @Test fun theNewOrderArrivingFirstStillResets() = runBlocking<Unit> {
        val grid = source()
        val orderShown = CompletableDeferred<Unit>()
        val result = async {
            awaitReadyUnlessUserDrags(listOf(grid), 15_000L) { orderShown.await() }
        }
        grid.awaitListener()
        orderShown.complete(Unit)
        assertTrue(result.await())
        // A drag after the reset decided is the user's own and changes nothing.
        grid.emit(DragInteraction.Start())
    }

    @Test fun aDragOnEitherTimelineCancels() = runBlocking<Unit> {
        val photos = source()
        val album = source()
        val result = async {
            awaitReadyUnlessUserDrags(listOf(photos, album), 15_000L) {
                CompletableDeferred<Unit>().await()
            }
        }
        photos.awaitListener(); album.awaitListener()
        album.emit(DragInteraction.Start())
        assertFalse(result.await())
    }

    @Test fun timingOutDoesNotReset() = runBlocking<Unit> {
        val grid = source()
        assertFalse(awaitReadyUnlessUserDrags(listOf(grid), 50L) {
            CompletableDeferred<Unit>().await()
        })
    }
}
