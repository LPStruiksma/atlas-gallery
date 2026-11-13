package nl.atlas.gallery.featurecuration

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import nl.atlas.gallery.featureorganization.SelectionCheckCircle
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** A request for more verification, NOT authorization for a MediaStore operation. */
data class CurationReviewRequest(
    val scopeEpoch: String,
    val exactGroupKey: String,
    val keeperUri: String,
    val explicitlySelectedUris: Set<String>
)

/**
 * Photo-first, independent Samsung-style cleanup surface. Its thumbnail slot
 * leaves decoding/access control with the host. No files or permissions are
 * accessed here. Unreviewed visual pairs never expose a Trash action.
 *
 * Integration must call plan.prepareManualTrashReview with FRESH data, then
 * rehash the selected photos and keeper, run DeclutterTrashPreflight and obtain
 * separate Android system Trash consent. This composable does NONE of that.
 */
@Composable
fun PhotoDnaReviewScreen(
    plan: CurationReviewPlan,
    thumbnail: @Composable (CurationAsset, Modifier) -> Unit,
    onReviewRequested: (CurationReviewRequest) -> Unit,
    onComparePair: (CurationVisualPair) -> Unit,
    modifier: Modifier = Modifier,
    /** An explicit Keep tap (group key, keeper URI). It never stages anything for Trash. */
    onKeeperChosen: (String, String) -> Unit = { _, _ -> },
    /**
     * Per group key, bumped by the host when a Keep tap was refused or
     * ignored: the Keep badge goes back to the group's stored or suggested keeper.
     */
    keeperResets: Map<String, Int> = emptyMap()
) {
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("photo-dna-list"),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(PhotoDnaCopy.duplicatesHeader(plan.exactGroups.size),
                style = MaterialTheme.typography.titleMedium)
        }
        items(plan.exactGroups, key = { "exact:" + it.key }) { group ->
            var keeperUri by remember(plan.scopeEpoch, group.key, keeperResets[group.key] ?: 0) {
                mutableStateOf(group.suggestedKeeperUri)
            }
            var picked by remember(plan.scopeEpoch, group.key) {
                mutableStateOf(emptySet<String>())
            }
            // The keeper is never staged, including after a refused Keep tap.
            val selected = picked - keeperUri
            Card(Modifier.fillMaxWidth().testTag("photo-dna-exact-group")) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(PhotoDnaCopy.groupHeader(group.members.size, group.potentialBytes),
                        style = MaterialTheme.typography.titleMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(group.members, key = { it.uri }) { photo ->
                            Column(Modifier.width(144.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(Modifier.fillMaxWidth().height(112.dp)) {
                                    thumbnail(photo, Modifier.fillMaxSize())
                                    // Exactly one visible check circle on each copy.
                                    // A protected or kept copy cannot be staged.
                                    Box(
                                        Modifier.align(Alignment.TopEnd).size(44.dp)
                                            .toggleable(
                                                value = photo.uri in selected,
                                                enabled = !photo.protected && photo.uri != keeperUri,
                                                role = Role.Checkbox,
                                                onValueChange = { checked ->
                                                    picked = if (checked) picked + photo.uri
                                                        else picked - photo.uri
                                                }
                                            )
                                            .semantics {
                                                contentDescription = when {
                                                    photo.protected -> "${photo.title}, protected"
                                                    photo.uri == keeperUri -> "${photo.title}, kept"
                                                    else -> "Select ${photo.title}"
                                                }
                                            }
                                            .testTag("photo-dna-stage"),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        SelectionCheckCircle(photo.uri in selected, size = 24)
                                    }
                                    if (keeperUri == photo.uri) {
                                        Surface(
                                            modifier = Modifier.align(Alignment.BottomStart)
                                                .padding(6.dp),
                                            shape = RoundedCornerShape(10.dp),
                                            color = MaterialTheme.colorScheme.primaryContainer
                                        ) {
                                            Text(PhotoDnaCopy.KEEP,
                                                modifier = Modifier.padding(
                                                    horizontal = 8.dp, vertical = 3.dp)
                                                    .testTag("photo-dna-keep-badge"),
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                                // Tapping a copy's caption moves the Keep badge. The
                                // thumbnail still opens the viewer as before.
                                Text(photo.title, maxLines = 2,
                                    modifier = Modifier.fillMaxWidth()
                                        .clickable(onClickLabel = "Keep ${photo.title}") {
                                            keeperUri = photo.uri
                                            picked = picked - photo.uri
                                            onKeeperChosen(group.key, photo.uri)
                                        }
                                        .semantics { this.selected = keeperUri == photo.uri }
                                        .testTag("photo-dna-keeper"),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Button(
                        onClick = {
                            onReviewRequested(CurationReviewRequest(
                                plan.scopeEpoch, group.key, keeperUri, selected.toSet()))
                        },
                        enabled = selected.isNotEmpty() && selected.size <= 200,
                        modifier = Modifier.testTag("photo-dna-final-review")
                    ) { Text(PhotoDnaCopy.REVIEW_SELECTED) }
                }
            }
        }
        item {
            Text(PhotoDnaCopy.similarHeader(plan.visualPairs.size),
                style = MaterialTheme.typography.titleMedium)
        }
        items(plan.visualPairs, key = { "pair:" + it.left.uri + ":" + it.right.uri }) { pair ->
            Card(Modifier.fillMaxWidth().testTag("photo-dna-visual-pair")) {
                Column(Modifier.padding(12.dp)) {
                    Text(when (pair.kind) {
                        CurationPairKind.GEOMETRIC_CANDIDATE -> PhotoDnaCopy.PAIR_RELATED
                        CurationPairKind.GEOMETRIC_REVIEW -> PhotoDnaCopy.PAIR_REVIEW
                        CurationPairKind.UNKNOWN -> PhotoDnaCopy.PAIR_UNKNOWN
                    }, style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            thumbnail(pair.left, Modifier.fillMaxWidth().height(120.dp))
                            Text(pair.left.title, maxLines = 1)
                        }
                        Column(Modifier.weight(1f)) {
                            thumbnail(pair.right, Modifier.fillMaxWidth().height(120.dp))
                            Text(pair.right.title, maxLines = 1)
                        }
                    }
                    TextButton(onClick = { onComparePair(pair) }) { Text(PhotoDnaCopy.COMPARE) }
                }
            }
        }
        item { Spacer(Modifier.height(18.dp)) }
    }
}
