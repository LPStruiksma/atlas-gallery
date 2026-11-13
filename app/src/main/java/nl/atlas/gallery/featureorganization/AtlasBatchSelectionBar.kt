package nl.atlas.gallery.featureorganization

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Shared Samsung-class contextual selection chrome: controlled presentation.
 * A callback is required for every visible action. In particular, onTrash
 * MUST launch an OS-mediated trash request and may never directly delete.
 * Nothing here stores URIs, grant tokens, selections or private media data.
 */
@Composable
fun AtlasBatchSelectionBar(
    state: BatchSelectionUiState,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    onSelectAll: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onFavorite: (() -> Unit)? = null,
    onCopy: (() -> Unit)? = null,
    onMove: (() -> Unit)? = null,
    onTrash: (() -> Unit)? = null,
    onRestore: (() -> Unit)? = null
) {
    val offered = buildSet {
        if (onShare != null) add(BatchSelectionAction.SHARE)
        if (onFavorite != null) add(BatchSelectionAction.FAVORITE)
        if (onCopy != null) add(BatchSelectionAction.COPY)
        if (onMove != null) add(BatchSelectionAction.MOVE)
        if (onTrash != null) add(BatchSelectionAction.TRASH)
        if (onRestore != null) add(BatchSelectionAction.RESTORE)
    }
    val plan = planBatchSelection(state, offered)
    // Selection cancellation also works when hosted by another Albums route.
    BackHandler { onExit() }
    // Actions are disabled synchronously before this stale-state cleanup runs.
    LaunchedEffect(state.sourceCurrent, state.selectedCount, state.eligibleCount) {
        if (!state.sourceCurrent || state.selectedCount <= 0 ||
            state.eligibleCount < 0 || state.selectedCount > state.eligibleCount) {
            onExit()
        }
    }
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(plan.caption, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).testTag("atlas-selection-count"))
                IconButton(onClick = onExit,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .testTag("atlas-selection-close")) {
                    Icon(Icons.Filled.Close, contentDescription = "Exit selection")
                }
            }
            if ((plan.canSelectAll && onSelectAll != null) || plan.presentedActions.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (plan.canSelectAll && onSelectAll != null) {
                        TextButton(onClick = onSelectAll,
                            modifier = Modifier.heightIn(min = 48.dp)
                                .testTag("atlas-selection-all")) { Text("Select all") }
                    }
                    plan.presentedActions.forEach { action ->
                        val callback: (() -> Unit)? = when (action) {
                            BatchSelectionAction.SHARE -> onShare
                            BatchSelectionAction.FAVORITE -> onFavorite
                            BatchSelectionAction.COPY -> onCopy
                            BatchSelectionAction.MOVE -> onMove
                            BatchSelectionAction.TRASH -> onTrash
                            BatchSelectionAction.RESTORE -> onRestore
                        }
                        if (callback != null) {
                            TextButton(onClick = callback, enabled = action in plan.actions,
                                modifier = Modifier.heightIn(min = 48.dp)
                                    .testTag("atlas-selection-" + action.name.lowercase())) {
                                Text(when (action) {
                                    BatchSelectionAction.SHARE -> "Share"
                                    BatchSelectionAction.FAVORITE -> "Favorite"
                                    BatchSelectionAction.COPY -> "Copy"
                                    BatchSelectionAction.MOVE -> "Move"
                                    BatchSelectionAction.TRASH -> "Trash"
                                    BatchSelectionAction.RESTORE -> "Restore"
                                })
                            }
                        }
                    }
                }
            }
            if (BatchSelectionAction.TRASH in plan.consentRequired) {
                Text(
                    "Trash requires Android confirmation; cancel to keep originals.",
                    modifier = Modifier.testTag("atlas-selection-consent"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (state.selectedCount > MAX_BATCH_TRASH_ITEMS &&
                BatchSelectionAction.TRASH in plan.presentedActions) {
                Text("Trash supports up to $MAX_BATCH_TRASH_ITEMS selected items.",
                    style = MaterialTheme.typography.labelSmall)
            }
            if (state.selectedCount > MAX_BATCH_SHARE_ITEMS &&
                BatchSelectionAction.SHARE in plan.presentedActions) {
                Text("Share supports up to $MAX_BATCH_SHARE_ITEMS selected items.",
                    style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
