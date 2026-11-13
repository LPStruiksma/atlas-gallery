package nl.atlas.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import java.text.NumberFormat
import nl.atlas.gallery.featuredesign.AtlasHeaderMenuItem
import nl.atlas.gallery.featuredesign.AtlasSpace

/**
 * Photos route controls for the compact header (#492). They replace the
 * former count/sort toolbar row and the "Timeline & dates" / "Original
 * proportions" chip row, which together pushed photos ~110 dp down.
 * Original proportions has no menu entry: like Samsung Gallery, you pinch out
 * past the largest grid into it and pinch back in to leave (#494).
 */
internal fun photosHeaderStatus(isSorting: Boolean, isFiltering: Boolean, count: Int): String =
    when {
        isSorting -> "Ordering library…"
        isFiltering -> "Filtering selected album locally…"
        else -> "${NumberFormat.getIntegerInstance().format(count)} photos & videos"
    }

/** Overflow entries; the tags are the former chip/button tags so tests and tooling still find them. */
internal fun photosHeaderMenu(
    timelineSelected: Boolean,
    canDeclutter: Boolean,
    onToggleTimeline: () -> Unit,
    onDeclutter: () -> Unit
): List<AtlasHeaderMenuItem> = buildList {
    add(AtlasHeaderMenuItem("Timeline & dates", "atlas-timeline-toggle",
        checked = timelineSelected, onClick = onToggleTimeline))
    if (canDeclutter) add(AtlasHeaderMenuItem("Declutter", "atlas-open-declutter",
        onClick = onDeclutter))
}

internal fun SortOrder.label(): String = name.lowercase().replaceFirstChar { it.uppercase() }

/** Sort as one icon; the menu marks the current order and offers Reshuffle for Random. */
@Composable
internal fun PhotosSortAction(order: SortOrder, onSort: (SortOrder) -> Unit, onShuffle: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.size(AtlasSpace.MinimumTarget).testTag("atlas-photos-sort")
        ) {
            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort: ${order.label()}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SortOrder.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label()) },
                    onClick = { onSort(option); expanded = false },
                    trailingIcon = if (option == order) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else null,
                    modifier = Modifier.testTag("atlas-photos-sort-" + option.name.lowercase())
                        .semantics { selected = option == order }
                )
            }
            if (order == SortOrder.RANDOM) DropdownMenuItem(
                text = { Text("Reshuffle") },
                onClick = { onShuffle(); expanded = false },
                modifier = Modifier.testTag("atlas-photos-reshuffle")
            )
        }
    }
}
