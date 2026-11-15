package nl.atlas.gallery

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import nl.atlas.gallery.featureface.NativeFaceObservationId

/**
 * COVER-3: the Album cover chooser's grid/checked-choice pattern, adapted to
 * individual face crops (including video-frame observations). The host provides
 * only current permission-gated faces. No URI, crop or identity is saveable.
 */
@Composable
internal fun PersonFaceCoverChooser(
    faces: List<NativeFaceObservationId>,
    selectedUid: String?,
    portrait: @Composable (NativeFaceObservationId) -> Unit,
    onChoose: (NativeFaceObservationId) -> Unit,
    onAutomatic: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(
        usePlatformDefaultWidth = false
    )) {
        Surface(Modifier.fillMaxSize().testTag("atlas-person-cover-chooser"),
            color = MaterialTheme.colorScheme.background) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss, modifier = Modifier
                        .size(48.dp).testTag("atlas-person-cover-back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
                    }
                    Text("Change profile picture", style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f))
                }
                if (selectedUid != null && onAutomatic != null) {
                    TextButton(onClick = onAutomatic,
                        modifier = Modifier.padding(horizontal = 16.dp)
                            .heightIn(min = 48.dp).testTag("atlas-person-cover-automatic")) {
                        Text("Use automatic")
                    }
                }
                LazyVerticalGrid(columns = GridCells.Adaptive(112.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize().testTag("atlas-person-cover-grid")) {
                    itemsIndexed(faces, key = { _, face -> face.localHex }) { index, face ->
                        val selected = face.localHex == selectedUid
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable(role = Role.Button,
                                onClickLabel = "Use as profile picture") { onChoose(face) }
                                .semantics {
                                    contentDescription = if (selected) "Selected profile picture"
                                        else "Choose profile picture"
                                }
                                .testTag("atlas-person-cover-choice-$index")) {
                            Box(Modifier.size(104.dp).clip(CircleShape),
                                contentAlignment = Alignment.Center) {
                                portrait(face)
                                if (selected) {
                                    Surface(shape = CircleShape,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.align(Alignment.BottomEnd)
                                            .size(30.dp).testTag("atlas-person-cover-checked")) {
                                        Icon(Icons.Filled.Check, contentDescription = "Selected",
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.padding(5.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
