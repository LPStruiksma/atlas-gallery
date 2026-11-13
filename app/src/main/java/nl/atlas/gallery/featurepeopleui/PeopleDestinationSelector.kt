package nl.atlas.gallery.featurepeopleui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
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
 * Explicit destination choice, including for homonyms. Only position-based
 * accessibility tags are exposed. The host's grant-aware face preview is the
 * sole image source; the host's neutral fallback remains the fallback.
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
    // Search/pagination must also reset when an equal-valued review is regranted.
    var filter by remember(selectionScope, candidates) { mutableStateOf("") }
    var page by remember(selectionScope, candidates) { mutableIntStateOf(0) }
    val matching = candidates.withIndex().filter {
        it.value.name.contains(filter.trim(), ignoreCase = true)
    }
    val pageSize = 6
    val pageCount = (matching.size + pageSize - 1) / pageSize
    val safePage = if (pageCount == 0) 0 else page.coerceIn(0, pageCount - 1)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Choose a reviewed person. People with the same name are different choices; " +
                "check the confirmed face preview before proceeding.",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedTextField(
            value = filter,
            onValueChange = {
                if (it.length <= 80) {
                    filter = it
                    page = 0
                    onSelect(null)
                }
            },
            label = { Text("Find reviewed person") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        )
        if (matching.isEmpty()) {
            Text("No reviewed people match. No destination selected.",
                style = MaterialTheme.typography.bodySmall)
        }
        matching.drop(safePage * pageSize).take(pageSize).forEach { choice ->
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
                    Text("${album.name} · reviewed choice ${choice.index + 1} of ${candidates.size}")
                    Text("${album.observations.size} confirmed faces",
                        style = MaterialTheme.typography.bodySmall)
                }
                album.observations.firstOrNull()?.let { preview(it) }
            }
        }
        if (pageCount > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = enabled && safePage > 0,
                    onClick = { page = safePage - 1; onSelect(null) }
                ) { Text("Previous choices") }
                OutlinedButton(
                    enabled = enabled && safePage < pageCount - 1,
                    onClick = { page = safePage + 1; onSelect(null) }
                ) { Text("Next choices") }
            }
            Text("Choice page ${safePage + 1} of $pageCount",
                style = MaterialTheme.typography.bodySmall)
        }
        if (selectedPosition != null) {
            val chosen = candidates.getOrNull(selectedPosition)
            if (chosen != null) {
                Text("Selected: ${chosen.name} · reviewed choice ${selectedPosition + 1}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("${tagPrefix}-selected"))
            }
        }
    }
}
