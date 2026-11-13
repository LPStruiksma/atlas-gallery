package nl.atlas.gallery.featurevisual.search

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import nl.atlas.gallery.featurevisual.store.VisualMedia

/**
 * VS-S2a (#468): results of "find similar", not yet wired into the host
 * (VS-S2b adds the viewer and Search entry points). Shows typed states for
 * an index that is not ready, a version mismatch and a revoked grant; else a
 * grid of hits with their band and the "emphasise pose" switch. A video hit
 * (VS-V2) carries a badge with its best moment's time.
 *
 * Stateless: the host runs [VisualQueryEngine] off the main thread and passes
 * the [state]. [poseEnabled] is the switch; while it is on and the shown result
 * is still the semantic one, the host is re-ranking. [thumbnail] loads a
 * grant-checked preview, or null.
 */
@Composable
fun VisualSearchResultsScreen(
    state: VisualSearchUiState,
    poseEnabled: Boolean,
    onPoseToggle: (Boolean) -> Unit,
    onOpen: (VisualMedia) -> Unit,
    onBack: () -> Unit,
    thumbnail: suspend (VisualMedia) -> ImageBitmap?,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize().padding(16.dp).testTag("atlas-visual-search")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, modifier = Modifier.testTag("atlas-visual-search-back")) {
                Text("Back")
            }
            Text("Similar photos", style = MaterialTheme.typography.headlineSmall)
        }
        Spacer(Modifier.height(8.dp))
        when (state) {
            VisualSearchUiState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.testTag("atlas-visual-search-loading"))
                Spacer(Modifier.width(12.dp))
                Text("Comparing photos on this device…")
            }
            VisualSearchUiState.NotIndexed -> Message("atlas-visual-search-not-indexed",
                "Your photos are still being prepared for similar-photo search. " +
                    "Try again once indexing has finished.")
            VisualSearchUiState.VersionMismatch -> Message("atlas-visual-search-version-mismatch",
                "Similar-photo search is being rebuilt for an updated model. Try again later.")
            VisualSearchUiState.Revoked -> Message("atlas-visual-search-revoked",
                "Photo access was withdrawn, so nothing is shown. Allow access again to search.")
            is VisualSearchUiState.Results -> Results(state.result, poseEnabled, onPoseToggle,
                onOpen, thumbnail)
        }
    }
}

@Composable
private fun Message(tag: String, text: String) {
    Text(text, Modifier.testTag(tag), style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun Results(
    result: VisualQueryResult,
    poseEnabled: Boolean,
    onPoseToggle: (Boolean) -> Unit,
    onOpen: (VisualMedia) -> Unit,
    thumbnail: suspend (VisualMedia) -> ImageBitmap?
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Emphasise pose", Modifier.weight(1f))
        Switch(checked = poseEnabled, onCheckedChange = onPoseToggle,
            modifier = Modifier.testTag("atlas-visual-search-pose-toggle"))
    }
    if (poseEnabled) when (result.pose) {
        VisualPoseState.OFF -> Text("Matching body poses…",
            Modifier.testTag("atlas-visual-search-pose-running"))
        VisualPoseState.NO_BODY -> Text("No body found in this photo, so the order is unchanged.",
            Modifier.testTag("atlas-visual-search-pose-no-body"))
        VisualPoseState.UNAVAILABLE -> Text("Pose matching is unavailable, so the order is unchanged.",
            Modifier.testTag("atlas-visual-search-pose-unavailable"))
        VisualPoseState.APPLIED -> Unit
    }
    if (result.nearDuplicates > 0) {
        Text(if (result.nearDuplicates == 1) "1 copy of this photo is left out."
            else "${result.nearDuplicates} copies of this photo are left out.",
            Modifier.testTag("atlas-visual-search-duplicates"),
            style = MaterialTheme.typography.bodySmall)
    }
    Spacer(Modifier.height(8.dp))
    if (result.hits.isEmpty()) {
        Message("atlas-visual-search-empty", "No similar photos found.")
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(104.dp),
        modifier = Modifier.fillMaxSize().testTag("atlas-visual-search-grid"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        itemsIndexed(result.hits, key = { _, hit -> hit.media.key + "#" + (hit.media.frameMs ?: -1L) }) { index, hit ->
            HitTile(index, hit, onOpen, thumbnail)
        }
    }
}

@Composable
private fun HitTile(
    index: Int,
    hit: VisualHit,
    onOpen: (VisualMedia) -> Unit,
    thumbnail: suspend (VisualMedia) -> ImageBitmap?
) {
    val image by produceState<ImageBitmap?>(null, hit.media) { value = thumbnail(hit.media) }
    val label = bandLabel(hit.band)
    val moment = hit.frameMs?.let(::formatMomentTime)
    val description = if (moment == null) "Result ${index + 1}, $label"
        else "Result ${index + 1}, $label, video at $moment"
    Box(
        Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onOpen(hit.media) }
            .semantics { contentDescription = description }
            .testTag("atlas-visual-hit-$index")
    ) {
        image?.let {
            Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        // VS-V2: a video hit shows its best moment's time; opening passes
        // hit.media (with frame_ms) so the viewer can seek there (VS-S2b).
        if (moment != null) {
            val more = if (hit.secondary.isEmpty()) "" else " · +${hit.secondary.size}"
            Text("▶ $moment$more",
                Modifier.align(Alignment.TopEnd).padding(4.dp)
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.7f),
                        RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp)
                    .testTag("atlas-visual-hit-$index-video"),
                color = MaterialTheme.colorScheme.inverseOnSurface,
                style = MaterialTheme.typography.labelSmall)
        }
        Text(label,
            Modifier.align(Alignment.BottomStart).padding(4.dp)
                .background(bandColor(hit.band), RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp)
                .testTag("atlas-visual-hit-$index-${hit.band.name.lowercase()}"),
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.labelSmall)
    }
}

private fun bandLabel(band: VisualMatchBand): String = when (band) {
    VisualMatchBand.STRONG -> "Very similar"
    VisualMatchBand.RELATED -> "Related"
}

@Composable
private fun bandColor(band: VisualMatchBand) = when (band) {
    VisualMatchBand.STRONG -> MaterialTheme.colorScheme.primary
    VisualMatchBand.RELATED -> MaterialTheme.colorScheme.secondary
}
