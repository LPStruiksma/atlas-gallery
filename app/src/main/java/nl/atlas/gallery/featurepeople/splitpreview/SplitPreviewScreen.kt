package nl.atlas.gallery.featurepeople.splitpreview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import nl.atlas.gallery.featuredesign.AtlasTokens

/** Host-scoped opaque handles only; never expose private media keys in semantics. */
data class SplitPreviewFace(val handle: String, val sampleHandle: String? = null)
data class SplitPreviewGroup(val id: String, val label: String, val faces: List<SplitPreviewFace>)
data class SplitPreviewUiModel(
    val groups: List<SplitPreviewGroup>,
    val unsure: List<SplitPreviewFace>,
    val totalCount: Int = groups.sumOf { it.faces.size } + unsure.size
) {
    init {
        require(groups.map { it.id }.distinct().size == groups.size) { "Duplicate group" }
        require(groups.all { it.id.isNotBlank() && it.label.isNotBlank() && it.faces.isNotEmpty() })
        val faces = groups.flatMap { it.faces } + unsure
        require(faces.all { it.handle.isNotBlank() })
        require(faces.map { it.handle }.distinct().size == faces.size) { "Duplicate face" }
        require(totalCount == faces.size) { "Count must cover every preview face" }
    }
}

/** Stateless review surface. The host owns selection, moves, navigation and commit. */
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
    Scaffold(
        modifier = modifier.fillMaxSize().testTag("split-preview-screen"),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            DecisionRail(model.groups.size > 1, onCancel, onAdjust, onAccept)
        }
    ) { scaffoldPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = AtlasTokens.Spacing.Gutter,
                top = scaffoldPadding.calculateTopPadding() + 16.dp,
                end = AtlasTokens.Spacing.Gutter,
                bottom = scaffoldPadding.calculateBottomPadding() + 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text("Split this group", style = MaterialTheme.typography.headlineMedium)
                Text(
                    when (model.groups.size) {
                        0 -> "No faces to split."
                        1 -> "No clear split"
                        else -> "${model.groups.size} suggested people · ${model.totalCount} faces"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (model.groups.isNotEmpty()) Text(
                    "Suggestions only. Review every face before confirming.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (model.groups.size == 1) item {
                Text("These faces may belong together. You can split them manually.")
                Button(onClick = onManualSplit, modifier = Modifier.testTag("split-preview-manual")) {
                    Text("Split manually")
                }
            }
            itemsIndexed(model.groups) { groupIndex, group ->
                GroupCard(groupIndex, group, selectedFace, facePreview, onSelectFace, onMoveFace)
            }
            if (model.unsure.isNotEmpty()) item {
                Card(Modifier.fillMaxWidth().testTag("split-preview-unsure")) {
                    Column(Modifier.padding(AtlasTokens.Spacing.Gutter), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Unsure · ${model.unsure.size} faces", style = MaterialTheme.typography.titleMedium)
                        Text("Not assigned to a suggested person", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        model.unsure.forEachIndexed { index, face ->
                            FaceRow(face, "Unsure face ${index + 1}", selectedFace?.handle == face.handle,
                                "split-preview-unsure-face-${index + 1}", onSelectFace, facePreview)
                        }
                        if (selectedFace != null) OutlinedButton(
                            onClick = { onMoveFace(selectedFace.handle, null) },
                            modifier = Modifier.testTag("split-preview-move-to-unsure")
                        ) { Text("Move selected face to Unsure") }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupCard(
    index: Int,
    group: SplitPreviewGroup,
    selectedFace: SplitPreviewFace?,
    facePreview: @Composable (String?) -> Unit,
    onSelectFace: (String) -> Unit,
    onMoveFace: (String, String?) -> Unit
) {
    Card(Modifier.fillMaxWidth().testTag("split-preview-group-${index + 1}")) {
        Column(Modifier.padding(AtlasTokens.Spacing.Gutter), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(AtlasTokens.Spacing.MinimumTarget).clip(CircleShape), contentAlignment = Alignment.Center) {
                    facePreview(group.faces.first().sampleHandle)
                }
                Column {
                    Text(group.label, style = MaterialTheme.typography.titleMedium)
                    Text("${group.faces.size} faces", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            group.faces.forEachIndexed { faceIndex, face ->
                FaceRow(face, "Face ${faceIndex + 1} in ${group.label}", selectedFace?.handle == face.handle,
                    "split-preview-face-${index + 1}-${faceIndex + 1}", onSelectFace, facePreview)
            }
            if (selectedFace != null) OutlinedButton(
                onClick = { onMoveFace(selectedFace.handle, group.id) },
                modifier = Modifier.testTag("split-preview-move-to-${index + 1}")
            ) { Text("Move selected face here") }
        }
    }
}

@Composable
private fun FaceRow(
    face: SplitPreviewFace,
    label: String,
    isSelected: Boolean,
    tag: String,
    onSelectFace: (String) -> Unit,
    facePreview: @Composable (String?) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(role = Role.Button) { onSelectFace(face.handle) }
            .semantics { contentDescription = "Select $label"; selected = isSelected }
            .testTag(tag).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        facePreview(face.sampleHandle)
        Text(if (isSelected) "Selected · $label" else label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DecisionRail(
    canConfirm: Boolean,
    onCancel: () -> Unit,
    onAdjust: () -> Unit,
    onAccept: () -> Unit
) {
    Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding()
                .padding(horizontal = AtlasTokens.Spacing.PillEdge, vertical = AtlasTokens.Spacing.TileGap),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).testTag("split-preview-cancel")) {
                Text("Cancel", maxLines = 1)
            }
            if (canConfirm) {
                OutlinedButton(onClick = onAdjust, modifier = Modifier.weight(1f).testTag("split-preview-adjust")) {
                    Text("Adjust", maxLines = 1)
                }
                Button(onClick = onAccept, modifier = Modifier.weight(1f).testTag("split-preview-accept")) {
                    Text("Accept", maxLines = 1)
                }
            }
        }
    }
}

/** Test-safe illustrated exemplar; production hosts inject cropped previews. */
@Composable
fun SyntheticSplitFacePreview(modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onSecondaryContainer
    Surface(
        modifier = modifier.size(AtlasTokens.Spacing.MinimumTarget).clip(CircleShape)
            .testTag("split-preview-synthetic-face"),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Canvas(Modifier.fillMaxSize().padding(9.dp)) {
            drawCircle(ink, radius = size.minDimension * .24f, center = Offset(size.width / 2, size.height * .32f))
            drawCircle(ink, radius = size.width * .38f, center = Offset(size.width / 2, size.height * .94f))
        }
    }
}
