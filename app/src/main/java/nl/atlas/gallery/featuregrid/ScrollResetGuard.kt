package nl.atlas.gallery.featuregrid

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.Interaction
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/**
 * #698: waits for [awaitReady] (a new order's timeline is displayed), but
 * gives up as soon as the user starts dragging any of the grids whose
 * [interactions] are given, or after [timeoutMs]. Returns true only when the
 * new order arrived first, so a delayed "back to the top" never pulls back a
 * user who has already started scrolling.
 *
 * The drag watch starts when this is called: call it right after the first
 * reset, while the grid is at the top.
 */
internal suspend fun awaitReadyUnlessUserDrags(
    interactions: List<Flow<Interaction>>,
    timeoutMs: Long,
    awaitReady: suspend () -> Unit
): Boolean = withTimeoutOrNull(timeoutMs) {
    coroutineScope {
        val ready = async { awaitReady(); true }
        val dragged = async {
            if (interactions.isEmpty()) kotlinx.coroutines.awaitCancellation()
            interactions.merge().first { it is DragInteraction.Start }
            false
        }
        // A drag wins a tie: select prefers its first clause.
        val outcome = select {
            dragged.onAwait { it }
            ready.onAwait { it }
        }
        coroutineContext.cancelChildren()
        outcome
    }
} ?: false
