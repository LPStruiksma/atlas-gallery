package nl.atlas.gallery

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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

/**
 * Samsung parity §2 (#19): Sort opens a compact, dismissible bottom sheet.
 * All existing SortOrder values (including ATLAS size sorts) remain available.
 * Radio semantics expose the active order to accessibility and UI tests.
 * Applying a choice or explicitly reshuffling Random closes the sheet before
 * notifying the existing asynchronous sort pipeline.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
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
    }
    if (expanded) {
        ModalBottomSheet(
            onDismissRequest = { expanded = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag("atlas-photos-sort-sheet")
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)
            ) {
                Text("Sort by", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                SortOrder.entries.forEach { option ->
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                            .selectable(
                                selected = option == order,
                                role = Role.RadioButton,
                                onClick = { expanded = false; onSort(option) }
                            )
                            .testTag("atlas-photos-sort-" + option.name.lowercase()),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(option.label(), style = MaterialTheme.typography.bodyLarge)
                        RadioButton(selected = option == order, onClick = null)
                    }
                }
                if (order == SortOrder.RANDOM) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                            .clickable { expanded = false; onShuffle() }
                            .semantics { role = Role.Button }
                            .testTag("atlas-photos-reshuffle"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Reshuffle", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}
