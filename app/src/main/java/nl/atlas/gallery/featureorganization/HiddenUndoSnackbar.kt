package nl.atlas.gallery.featureorganization

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * HIDE-1 (#585): the only result that carries Undo. Hide and Unhide change a
 * stored key set and nothing else, so unlike Trash/Copy/Move (see
 * [SelectionResultMessages], which stays Undo-free) they have a tested inverse.
 * Replace-only, transient, never saved: a newer result replaces this one.
 */
@Stable
class HiddenUndoMessages {
    class Message(val text: String, val undo: (() -> Unit)?, val generation: Long)

    var current: Message? by mutableStateOf(null)
        private set
    private var lastGeneration = 0L

    fun show(text: String, undo: () -> Unit) {
        require(text.isNotBlank())
        current = Message(text, undo, ++lastGeneration)
    }

    /** A failure or refusal: same replace-only slot, no Undo button. */
    fun showError(text: String) {
        require(text.isNotBlank())
        current = Message(text, null, ++lastGeneration)
    }

    fun dismiss(generation: Long) {
        if (current?.generation == generation) current = null
    }
}

/** Goes beside [SelectionResultSnackbarHost]; draws nothing while there is no result. */
@Composable
fun HiddenUndoSnackbarHost(messages: HiddenUndoMessages, modifier: Modifier = Modifier) {
    val message = messages.current ?: return
    val accessibility = LocalAccessibilityManager.current
    LaunchedEffect(message.generation) {
        val timeout = accessibility?.calculateRecommendedTimeoutMillis(
            6_000L, containsIcons = false, containsText = true, containsControls = true) ?: 6_000L
        delay(timeout)
        messages.dismiss(message.generation)
    }
    Box(modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp,
        bottom = SelectionResultBarClearance), contentAlignment = Alignment.BottomCenter) {
        Surface(
            modifier = Modifier.widthIn(max = SelectionResultMaxWidth)
                .testTag("atlas-hidden-result")
                .semantics { liveRegion = LiveRegionMode.Polite },
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shadowElevation = 6.dp
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(message.text,
                    Modifier.padding(start = 20.dp, top = 14.dp, bottom = 14.dp, end = 8.dp)
                        .testTag("atlas-hidden-result-text"),
                    style = MaterialTheme.typography.bodyMedium)
                val undo = message.undo
                if (undo != null) TextButton(
                    onClick = { messages.dismiss(message.generation); undo() },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("atlas-hidden-result-undo")
                ) { Text("Undo", color = MaterialTheme.colorScheme.inversePrimary) }
                else Spacer(Modifier.width(12.dp))
            }
        }
    }
}
