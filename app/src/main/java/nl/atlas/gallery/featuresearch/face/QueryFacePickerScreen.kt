package nl.atlas.gallery.featuresearch.face

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Standalone VS-F2a face picker; VS-F2b will bind this route to viewer/Search.
 * For one face it still asks for confirmation; multiple faces must be chosen
 * explicitly. Only [onFaceChosen] receives a versioned vector, never a bitmap.
 */
@Composable
fun QueryFacePickerScreen(
    source: QueryFaceSource,
    capture: QueryFaceCapture,
    onFaceChosen: (QueryFace) -> Unit,
    onBack: () -> Unit
) {
    val state by capture.state.collectAsState()
    // A selection belongs to one Ready snapshot, not merely a media source.
    // A new capture (including a retry) or source change must clear it.
    val readyForSelection = state as? QueryFaceCaptureState.Ready
    var selected by remember(capture, source, readyForSelection) {
        mutableIntStateOf(if (readyForSelection != null &&
            readyForSelection.source == source && readyForSelection.faces.size == 1) 0 else -1)
    }
    val scope = rememberCoroutineScope()
    LaunchedEffect(capture, source) { capture.capture(source) }
    DisposableEffect(capture) { onDispose { capture.clear() } }

    Column(Modifier.fillMaxSize().padding(16.dp).testTag("atlas-query-face-picker")) {
        Text("Find this person", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        when (val current = state) {
            QueryFaceCaptureState.Idle, is QueryFaceCaptureState.Loading -> {
                CircularProgressIndicator(Modifier.testTag("atlas-query-face-loading"))
                Text("Finding faces on this device…")
            }
            is QueryFaceCaptureState.Empty -> {
                Text("No faces detected in this photo.", Modifier.testTag("atlas-query-face-empty"))
            }
            is QueryFaceCaptureState.Busy -> {
                Text("Face scanning is busy. Try again shortly.", Modifier.testTag("atlas-query-face-busy"))
                Button(onClick = { scope.launch { capture.capture(source) } }) { Text("Try again") }
            }
            is QueryFaceCaptureState.Unavailable -> {
                Text("This photo is no longer available. Select it again.",
                    Modifier.testTag("atlas-query-face-unavailable"))
            }
            is QueryFaceCaptureState.Failure -> {
                Text("Unable to read faces from this photo.", Modifier.testTag("atlas-query-face-failure"))
                Button(onClick = { scope.launch { capture.capture(source) } }) { Text("Try again") }
            }
            is QueryFaceCaptureState.Ready -> {
                if (current.source == source) {
                    Text(if (current.faces.size == 1) "One face detected. Confirm it to search."
                        else "Choose which face to search for.")
                    Spacer(Modifier.height(8.dp))
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.weight(1f).testTag("atlas-query-face-grid"),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(current.faces.size) { index ->
                            val face = current.faces[index]
                            Card(
                                modifier = Modifier.testTag("atlas-query-face-${index}").clickable { selected = index },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (index == selected)
                                        MaterialTheme.colorScheme.secondaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(Modifier.padding(8.dp)) {
                                    val crop = face.preview
                                    if (crop != null) {
                                        Image(crop.asImageBitmap(), "Face ${index + 1}",
                                            Modifier.size(88.dp).clip(RoundedCornerShape(8.dp)),
                                            contentScale = ContentScale.Crop)
                                    } else {
                                        Text("Face ${index + 1}", Modifier.height(88.dp))
                                    }
                                    Text("Face ${index + 1}" + if (index == selected) " · selected" else "")
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            // Do not apply a tap from an obsolete Ready snapshot.
                            if (capture.state.value === current && selected in current.faces.indices) {
                                capture.choose(selected)?.let(onFaceChosen)
                            }
                        },
                        enabled = selected in current.faces.indices,
                        modifier = Modifier.fillMaxWidth().testTag("atlas-query-face-confirm")
                    ) { Text("Search with this face") }
                } else {
                    CircularProgressIndicator()
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = { capture.clear(); onBack() },
                modifier = Modifier.testTag("atlas-query-face-back")) {
                Text("Back")
            }
        }
    }
}
