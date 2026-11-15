package nl.atlas.gallery.featurepeople.splitpreview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Pure presentation input. Handles are ephemeral, host-scoped opaque tokens;
 * never place private identity or media keys in semantics or test tags.
 * The host owns the draft reducer, grant checking and transactional commit.
 */
data class SplitPreviewFace(val handle: String, val sampleHandle: String? = null)
data class SplitPreviewGroup(
    val id: String,
    val label: String,
    val faces: List<SplitPreviewFace>
)
data class SplitPreviewUiModel(
    val groups: List<SplitPreviewGroup>,
    val unsure: List<SplitPreviewFace>,
    val totalCount: Int = groups.sumOf { it.faces.size } + unsure.size
) {
    init {
        require(groups.isNotEmpty()) { "At least one proposed group is required" }
        require(groups.map { it.id }.distinct().size == groups.size) { "Duplicate group" }
        require(groups.all { it.id.isNotBlank() && it.label.isNotBlank() && it.faces.isNotEmpty() })
        val handles = groups.flatMap { it.faces } + unsure
        require(handles.all { it.handle.isNotBlank() })
        require(handles.map { it.handle }.distinct().size == handles.size) { "Duplicate face" }
        require(totalCount == handles.size) { "Count must cover every preview face" }
    }
}

/**
 * This component is intentionally stateless. Face selection and destination
 * changes are requests, not edits: the host passes back a new model.
 * No face pixels, saved state, persisted data, or navigation are owned here.
 */
@Composable
fun SplitPreviewScreen(
    model: SplitPreviewUiModel,
    onAccept: () -> Unit,
    onAdjust: () -> Unit,
    onCancel: () -> Unit,
    onManualSplit: () -> Unit,
    onSelectFace: (String) -> Unit,
    onMoveFace: (String, String?) -> Unit,
    modifier: Modifier = Modifier,
    selectedFaceHandle: String? = null,
    facePreview: @Composable (String?) -> Unit = { SyntheticSplitFacePreview() }
) {
    val selectedFace = (model.groups.flatMap { it.faces } + model.unsure)
        .firstOrNull { it.handle == selectedFaceHandle }
    LazyColumn(
        modifier = modifier.testTag("split-preview-screen"),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("Split this group", style = MaterialTheme.typography.headlineMedium)
            Text(
                if (model.groups.size == 1) "No clear split"
                else "${model.groups.size} suggested people · ${model.totalCount} faces",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Suggestions only. Review every face before confirming.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (model.groups.size == 1) {
            item {
                Text("These faces may belong together. You can split them manually.")
                Button(onClick = onManualSplit, modifier = Modifier.testTag("split-preview-manual")) {
                    Text("Split manually")
                }
            }
        } else {
            itemsIndexed(model.groups) { index, group ->
                Card(Modifier.fillMaxWidth().testTag("split-preview-group-${index + 1}")) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(group.label, style = MaterialTheme.typography.titleMedium)
                        Text("${group.faces.size} faces", style = MaterialTheme.typography.bodySmall)
                        group.faces.forEachIndexed { faceIndex, face ->
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable(role = Role.Button) { onSelectFace(face.handle) }
                                    .semantics { contentDescription = "Select face ${faceIndex + 1} in ${group.label}" }
                                    .testTag("split-preview-face-${index + 1}-${faceIndex + 1}"),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                facePreview(face.sampleHandle)
                                Text(
                                    if (selectedFace?.handle == face.handle) "Selected face ${faceIndex + 1}"
                                    else "Face ${faceIndex + 1}",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                        if (selectedFace != null) {
                            OutlinedButton(
                                onClick = { onMoveFace(selectedFace.handle, group.id) },
                                modifier = Modifier.testTag("split-preview-move-to-${index + 1}")
                            ) { Text("Move selected face here") }
                        }
                    }
                }
            }
            if (model.unsure.isNotEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth().testTag("split-preview-unsure")) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Unsure · ${model.unsure.size} faces", style = MaterialTheme.typography.titleMedium)
                            Text("Not assigned to a suggested person")
                            model.unsure.forEachIndexed { index, face ->
                                Row(
                                    modifier = Modifier.fillMaxWidth()
                                        .clickable(role = Role.Button) { onSelectFace(face.handle) }
                                        .semantics { contentDescription = "Select unsure face ${index + 1}" }
                                        .testTag("split-preview-unsure-face-${index + 1}"),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    facePreview(face.sampleHandle)
                                    Text(if (selectedFace?.handle == face.handle) "Selected unsure face ${index + 1}" else "Unsure face ${index + 1}")
                                }
                            }
                            if (selectedFace != null) {
                                OutlinedButton(
                                    onClick = { onMoveFace(selectedFace.handle, null) },
                                    modifier = Modifier.testTag("split-preview-move-to-unsure")
                                ) { Text("Move selected face to Unsure") }
                            }
                        }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.testTag("split-preview-cancel")) {
                    Text("Cancel")
                }
                if (model.groups.size > 1) {
                    OutlinedButton(onClick = onAdjust, modifier = Modifier.testTag("split-preview-adjust")) {
                        Text("Adjust")
                    }
                    Button(onClick = onAccept, modifier = Modifier.testTag("split-preview-accept")) {
                        Text("Accept")
                    }
                }
            }
        }
    }
}

/** Neutral synthetic placeholder. Real cropped previews are injected by the host. */
@Composable
fun SyntheticSplitFacePreview(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.size(48.dp).clip(CircleShape).testTag("split-preview-synthetic-face"),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text("◌", color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}
