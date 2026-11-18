package nl.atlas.gallery.featureorganization

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** How long a selection result stays up before the accessibility adjustment. */
enum class SelectionResultDuration(val millis: Long) { SHORT(4_000L), LONG(8_000L) }

/** One result of a batch selection action. [generation] is unique per [SelectionResultMessages.show]. */
data class SelectionResultMessage(
    val text: String,
    val duration: SelectionResultDuration,
    val generation: Long,
    val shownAtMillis: Long
)

/**
 * #365 DESIGN-10c: the single, replace-only queue for selection batch results
 * (Trash, Copy/Move, Share, Favourite). A new result replaces the visible one
 * at once and restarts its timer; there is never a queue or a second message.
 * Results are statements of what already happened: there is no action and no
 * Undo, because none of these operations has a tested inverse in ATLAS.
 */
@Stable
class SelectionResultMessages(private val now: () -> Long = { System.nanoTime() / 1_000_000L }) {
    var current: SelectionResultMessage? by mutableStateOf(null)
        private set
    private var lastGeneration = 0L

    fun show(text: String, duration: SelectionResultDuration = SelectionResultDuration.LONG) {
        require(text.isNotBlank()) { "A selection result needs text" }
        current = SelectionResultMessage(text, duration, ++lastGeneration, now())
    }

    /** Clears [generation] only: an older timer never removes a newer result. */
    fun dismiss(generation: Long) {
        if (current?.generation == generation) current = null
    }

    /** Time left for [message] of [timeoutMillis], so a result shown while hidden never lingers. */
    fun remainingMillis(message: SelectionResultMessage, timeoutMillis: Long): Long =
        (timeoutMillis - (now() - message.shownAtMillis)).coerceAtLeast(0L)
}

/**
 * Extra clearance on top of the bars' own 8 dp top padding, so the snackbar's
 * bottom edge sits 14 dp (inside the 12–16 dp brief) above the visible pill.
 * Material 3's Scaffold places its snackbar slot on top of whichever bottom
 * bar is measured (selection pill or nav pill) and above the system navigation
 * inset when no bar is shown, so the anchor follows the live pill by itself.
 */
val SelectionResultBarClearance = 6.dp
val SelectionResultMaxWidth = 352.dp
private val SelectionResultSideMargin = 16.dp

/** Goes in the app Scaffold's `snackbarHost` slot; draws nothing while there is no result. */
@Composable
fun SelectionResultSnackbarHost(messages: SelectionResultMessages, modifier: Modifier = Modifier) {
    val message = messages.current ?: return
    val accessibility = LocalAccessibilityManager.current
    LaunchedEffect(message.generation) {
        // TalkBack users get Android's recommended (longer) reading time.
        val timeout = accessibility?.calculateRecommendedTimeoutMillis(
            message.duration.millis, containsIcons = false, containsText = true,
            containsControls = false) ?: message.duration.millis
        delay(messages.remainingMillis(message, timeout))
        messages.dismiss(message.generation)
    }
    Box(
        modifier.fillMaxWidth()
            .padding(start = SelectionResultSideMargin, end = SelectionResultSideMargin,
                bottom = SelectionResultBarClearance),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            modifier = Modifier.widthIn(max = SelectionResultMaxWidth)
                .testTag("atlas-selection-result")
                .semantics { liveRegion = LiveRegionMode.Polite },
            // One UI: a fully rounded, compact inverse-surface message.
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shadowElevation = 6.dp
        ) {
            // Wraps instead of ellipsizing: a cut-off partial result could read as a full success.
            Text(
                message.text,
                Modifier.padding(horizontal = 20.dp, vertical = 14.dp)
                    .testTag("atlas-selection-result-text"),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
        }
    }
}
