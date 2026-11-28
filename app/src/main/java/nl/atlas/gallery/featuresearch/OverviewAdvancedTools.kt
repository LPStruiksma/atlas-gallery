package nl.atlas.gallery.featuresearch

import androidx.compose.material3.AssistChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/**
 * FIND-2 (#181): the By face / By image chips an overview offers in the
 * AtlasSearch tool slots. Disabled, not hidden, when the screen shows
 * nothing to search in, so the tray keeps its shape.
 */
@Composable
fun OverviewAdvancedChip(
    mode: OverviewAdvancedMode,
    tag: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    AssistChip(
        onClick = onClick,
        enabled = enabled,
        label = { Text(if (mode == OverviewAdvancedMode.FACE) "By face" else "By image") },
        modifier = Modifier.testTag(tag)
    )
}
