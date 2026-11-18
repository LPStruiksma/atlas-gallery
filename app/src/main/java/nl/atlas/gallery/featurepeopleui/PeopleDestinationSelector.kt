package nl.atlas.gallery.featurepeopleui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeople.cluster.PersonKey

/** A non-saveable Compose group key that changes even for equal-valued new snapshots.
 * It contains no raw person/observation identifier in a persisted LazyColumn item key.
 */
internal class ReviewReferenceKey(private val snapshot: Any?) {
    override fun equals(other: Any?): Boolean =
        other is ReviewReferenceKey && other.snapshot === snapshot

    override fun hashCode(): Int = System.identityHashCode(snapshot)
}

/**
 * The ONLY bridge from a human-selected catalogue position to a PersonKey.
 * The caller must pass the exact rendered candidate list (excluding the source
 * for a merge). Never auto-pick a single option, resolve by name, or use a
 * selection retained across a new grant, source, or committed review object.
 *
 * The UI then sends a PeopleClusterReviewCommand with expectedState=rendered;
 * the storage owner still checks that object's identity in its transaction.
 */
fun currentSelectedReviewedPersonOrNull(
    rendered: PeopleClusterReviewState?,
    candidates: List<NativePersonAlbum>,
    selectedPosition: Int?,
    excludedPerson: PersonKey?,
    currentPermissionEpoch: String?,
    currentSourceGeneration: String?,
    liveAtAction: PeopleClusterReviewState?
): PersonKey? {
    if (rendered == null || liveAtAction !== rendered || selectedPosition == null ||
        currentPermissionEpoch.isNullOrBlank() ||
        currentSourceGeneration.isNullOrBlank()
    ) return null
    // Hidden people (#551) stay valid destinations in review, as listed there.
    val current = peopleAlbumsForGrant(
        rendered, currentPermissionEpoch, currentSourceGeneration, includeHidden = true
    ).filter { it.person != excludedPerson }
    // Prevent a stale or forged row from being interpreted as a new PersonKey.
    if (current.isEmpty() || current != candidates ||
        current.map { it.person }.toSet().size != current.size
    ) return null
    return current.getOrNull(selectedPosition)?.person
}

/**
 * A suggestion is not the catalogue. At present FaceClusterer exposes one
 * scored, policy-thresholded and ambiguity-checked person per group. Show only
 * that one when the current grant has an actual confirmed-face preview. Until
 * a calibrated multi-person scorer exists we MUST NOT invent rank 2 or 3
 * from alphabetical album order or the auto-add score.
 */
fun eligibleScoredGroupSuggestions(
    group: nl.atlas.gallery.featurepeople.cluster.UnknownFaceCluster,
    albums: List<NativePersonAlbum>,
    threshold: Float,
    rejected: Set<nl.atlas.gallery.featurepeople.cluster.RejectedPerson>
): List<NativePersonAlbum> {
    val evidence = group.suggestion ?: return emptyList()
    if (!evidence.similarity.isFinite() || evidence.similarity < threshold ||
        group.members.any {
            nl.atlas.gallery.featurepeople.cluster.RejectedPerson(it, evidence.person) in rejected
        }
    ) return emptyList()
    return albums.filter {
        it.person == evidence.person && it.observations.isNotEmpty()
    }.take(3)
}

/**
 * On-demand name search; zero matches at rest, five visible matches per query,
 * with an explicit expansion if someone has many similar names. Never show a
 * paginated catalogue, order numbers, or unscored "similar" people.
 *
 * Choice indices always refer to the FULL grant-current catalogue so existing
 * currentSelectedReviewedPersonOrNull guards still verify identity at tap time.
 */
@Composable
internal fun PeopleDestinationSelector(
    selectionScope: ReviewReferenceKey,
    candidates: List<NativePersonAlbum>,
    selectedPosition: Int?,
    onSelect: (Int?) -> Unit,
    enabled: Boolean,
    tagPrefix: String,
    preview: @Composable (NativeFaceObservationId) -> Unit
) {
    var filter by remember(selectionScope, candidates) { mutableStateOf("") }
    var visibleLimit by remember(selectionScope, candidates) { mutableIntStateOf(5) }
    val query = filter.trim()
    val matching = if (query.isBlank()) emptyList() else candidates.withIndex()
        .filter { it.value.name.contains(query, ignoreCase = true) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = filter,
            onValueChange = {
                if (it.length <= 80) {
                    filter = it
                    visibleLimit = 5
                    onSelect(null)
                }
            },
            label = { Text("Search by name") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
                .testTag("${tagPrefix}-search")
        )
        if (query.isNotBlank() && matching.isEmpty()) {
            Text("No people match that name.",
                style = MaterialTheme.typography.bodySmall)
        }
        matching.take(visibleLimit).forEach { choice ->
            val album = choice.value
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                RadioButton(
                    selected = selectedPosition == choice.index,
                    onClick = { onSelect(choice.index) },
                    enabled = enabled,
                    modifier = Modifier.testTag("${tagPrefix}-option-${choice.index + 1}")
                )
                Column(Modifier.weight(1f).padding(top = 8.dp)) {
                    Text(album.name)
                    Text("${album.observations.size} confirmed faces",
                        style = MaterialTheme.typography.bodySmall)
                }
                album.observations.firstOrNull()?.let { face ->
                    androidx.compose.foundation.layout.Box(
                        Modifier.padding(top = 4.dp)
                            .then(Modifier.size(48.dp))
                    ) { preview(face) }
                }
            }
        }
        if (matching.size > visibleLimit) {
            TextButton(onClick = { visibleLimit += 5 }) {
                Text("Show more matches")
            }
        }
        if (selectedPosition != null) {
            candidates.getOrNull(selectedPosition)?.let { chosen ->
                Text("Selected: ${chosen.name}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("${tagPrefix}-selected"))
            }
        }
    }
}
