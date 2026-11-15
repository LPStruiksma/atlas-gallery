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

/** A screen may provide its own selectable key without changing the shared enum. */
internal data class MediaSortOption(
    val key: String,
    val label: String,
    val directionSupported: Boolean = true
) {
    constructor(key: MediaSortKey, label: String) : this(key.name.lowercase(), label)
}
internal data class MediaSortExtra(val id: String, val label: String, val onClick: () -> Unit)

internal fun mediaSortOptionLabel(key: MediaSortKey): String = when (key) {
    MediaSortKey.DATE_CREATED -> "Date created"
    MediaSortKey.DATE_MODIFIED -> "Date modified"
    MediaSortKey.FILE_NAME -> "File name"
    MediaSortKey.SIZE -> "Size"
    MediaSortKey.RANDOM -> "Random"
}

/** The selection contract used by an extensible sheet, including custom keys. */
internal data class MediaSortSelection(val key: String, val direction: SortDirection)
internal fun MediaSort.asSelection() = MediaSortSelection(key.name.lowercase(), direction)
internal fun MediaSortSelection.asStandardSort(): MediaSort? =
    MediaSortKey.entries.firstOrNull { it.name.lowercase() == key }
        ?.let { MediaSort(it, direction) }

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun MediaSortSheet(
    selected: MediaSortSelection,
    options: List<MediaSortOption> = MediaSort.standardOptions.map {
        MediaSortOption(it, mediaSortOptionLabel(it))
    },
    extras: List<MediaSortExtra> = emptyList(),
    onSort: (MediaSortSelection) -> Unit,
    onShuffle: () -> Unit,
    onDismiss: () -> Unit
) {
    val selectedOption = options.firstOrNull { it.key == selected.key }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.testTag("atlas-photos-sort-sheet")
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text("Sort by", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            options.forEach { option ->
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    .selectable(selected = selected.key == option.key,
                        role = Role.RadioButton,
                        onClick = { onSort(selected.copy(key = option.key)) })
                    .testTag("atlas-media-sort-" + option.key),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(option.label, style = MaterialTheme.typography.bodyLarge)
                    RadioButton(selected = selected.key == option.key, onClick = null)
                }
            }
            if (selectedOption?.directionSupported != false &&
                selected.key != MediaSortKey.RANDOM.name.lowercase()) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Direction", style = MaterialTheme.typography.titleMedium)
                SortDirection.entries.forEach { direction ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
                        .selectable(selected = selected.direction == direction,
                            role = Role.RadioButton,
                            onClick = { onSort(selected.copy(direction = direction)) })
                        .testTag("atlas-media-sort-direction-" + direction.name.lowercase()),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(if (direction == SortDirection.ASCENDING) "Ascending" else "Descending",
                            style = MaterialTheme.typography.bodyLarge)
                        RadioButton(selected = selected.direction == direction, onClick = null)
                    }
                }
            } else if (selected.key == MediaSortKey.RANDOM.name.lowercase()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    .clickable { onShuffle() }
                    .semantics { role = Role.Button }
                    .testTag("atlas-photos-reshuffle"),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Shuffle again", style = MaterialTheme.typography.bodyLarge)
                }
            }
            extras.forEach { extra ->
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    .clickable { extra.onClick(); onDismiss() }
                    .testTag("atlas-media-sort-extra-" + extra.id),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(extra.label, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

/** Standard Pictures adapts the extensible selector to its built-in comparator. */
@Composable
internal fun MediaSortSheet(
    selected: MediaSort,
    options: List<MediaSortOption> = MediaSort.standardOptions.map {
        MediaSortOption(it, mediaSortOptionLabel(it))
    },
    extras: List<MediaSortExtra> = emptyList(),
    onSort: (MediaSort) -> Unit,
    onShuffle: () -> Unit,
    onDismiss: () -> Unit
) = MediaSortSheet(selected.asSelection(), options, extras,
    onSort = { choice -> choice.asStandardSort()?.let(onSort) },
    onShuffle = onShuffle, onDismiss = onDismiss)

/** Pictures' new sort action; the historical SortOrder overload remains for other tests. */
@Composable
internal fun PhotosSortAction(sort: MediaSort, onSort: (MediaSort) -> Unit, onShuffle: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true },
        modifier = Modifier.size(AtlasSpace.MinimumTarget).testTag("atlas-photos-sort")) {
        Icon(Icons.AutoMirrored.Filled.Sort,
            contentDescription = "Sort: ${mediaSortOptionLabel(sort.key)}")
    }
    if (expanded) MediaSortSheet(
        selected = sort,
        onSort = { onSort(it); expanded = false },
        onShuffle = { onShuffle(); expanded = false },
        onDismiss = { expanded = false }
    )
}
