package nl.atlas.gallery.featurepeopleui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonSkippableComposable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.featurepeople.cluster.UnknownFaceCluster

// Diagnostic semantics use a synthetic-only scope token; no private IDs are exposed.
internal val ReviewRowDiagnosticKey = SemanticsPropertyKey<String>("ReviewRowDiagnostic")
internal var SemanticsPropertyReceiver.reviewRowDiagnostic by ReviewRowDiagnosticKey

/**
 * Separate native face-DETECTION review route; NOT an identity picker.
 *
 * I must read a bounded, committed X FaceIndexStore page on IO and pass its
 * current permission epoch + source generation from the authorized catalogue.
 * This component never opens media directly, imports V4 CSV, or edits a DB.
 * Default thumbnails are neutral: I supplies the revision-aware MediaPreview.
 */
// A new committed review object can be value-equal to the old one. The caller
// must re-enter this boundary so reference-scoped row state can be discarded;
// Compose's structural-equality skip would otherwise retain a stale choice.
@NonSkippableComposable
@Composable
fun UnknownFacesReviewScreen(
    queue: NativeFaceReviewQueue?,
    currentPermissionEpoch: String?,
    currentSourceGeneration: String?,
    /**
     * Mandatory live-state reader supplied by Lane I. Read during onClick,
     * not composition, so an old button cannot apply a decision after revoke.
     * Read current scope AND exact current page in a single snapshot. Returning
     * a previous page reference or null always disables the action.
     */
    liveReviewStateAtAction: () -> NativeReviewActionSnapshot?,
    reviewBackendReady: Boolean,
    onDecide: (NativeFaceReviewCommand) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onNextPage: (() -> Unit)? = null,
    /** Committed identity review snapshot, separate from detection decisions. */
    clusterReview: PeopleClusterReviewState? = null,
    /** Must read current committed review snapshot at button/transaction time. */
    liveClusterReviewAtAction: (() -> PeopleClusterReviewState?)? = null,
    onClusterReviewCommand: ((PeopleClusterReviewCommand) -> Unit)? = null,
    /** A grant-aware crop supplied by the host; no raw URI requests here. */
    clusterFacePreview: @Composable (NativeFaceObservationId) -> Unit = {
        Icon(Icons.Filled.PhotoLibrary, "Face preview unavailable")
    },
    sourcePreview: @Composable (DetectedFaceReviewCandidate) -> Unit = {
        Icon(Icons.Filled.PhotoLibrary, "Photo preview unavailable")
    }
) {
    // Suppress a whole stale page; never briefly render old private previews.
    val current = currentNativeFaceReviewQueueOrNull(
        queue, currentPermissionEpoch, currentSourceGeneration
    )
    // Reset *all* remembered previews and row UI state on a new committed
    // page object, including a regrant that keeps the same native face UID.
    // Never use that private UID as a LazyColumn saveable-item key: Compose
    // may persist lazy keys into Android saved-instance-state Bundles.
    // The review page object is identity-based and is NOT a saveable key.
    val visiblePeople = clusterReview?.takeIf {
        it.permissionEpoch == currentPermissionEpoch &&
            it.sourceGeneration == currentSourceGeneration
    }
    // Use snapshot identity, not data-class equality, as the lifecycle of row UI state.
    // An equal-valued committed copy is still a new review transaction.
    val selectionScope = ReviewReferenceKey(visiblePeople)
    val albums = remember(selectionScope) {
        // Hidden people (#551) are still people you can assign faces to.
        peopleAlbumsForGrant(visiblePeople, currentPermissionEpoch, currentSourceGeneration,
            includeHidden = true)
    }
    // #579: like Samsung, groups with fewer than PEOPLE_MIN_GROUP_FACES faces
    // wait behind "Show all". A generic layout choice; nothing private.
    var showSmallGroups by rememberSaveable { mutableStateOf(false) }
    val canEditPeople = reviewBackendReady && liveClusterReviewAtAction != null &&
        onClusterReviewCommand != null
    fun resolveDestination(
        candidates: List<NativePersonAlbum>, selectedPosition: Int?, excluded: PersonKey?
    ): PersonKey? = currentSelectedReviewedPersonOrNull(
        visiblePeople, candidates, selectedPosition, excluded,
        currentPermissionEpoch, currentSourceGeneration,
        liveClusterReviewAtAction?.invoke()
    )

    fun sendPeopleDecision(action: PeopleReviewAction) {
        val rendered = visiblePeople ?: return
        val live = liveClusterReviewAtAction?.invoke()
        currentPeopleReviewActionOrNull(
            rendered, action, currentPermissionEpoch, currentSourceGeneration, live
        )?.let { permitted ->
            onClusterReviewCommand?.invoke(PeopleClusterReviewCommand(rendered, permitted))
        }
    }

    key(ReviewReferenceKey(current), selectionScope,
        currentPermissionEpoch, currentSourceGeneration) {
        LazyColumn(
            modifier = modifier.fillMaxSize().testTag("atlas-unknown-face-review-screen"),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onClose) { Text("Back to People") }
                    Text("Unknown faces", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Detection finds faces in your own photos; it does not recognize or name people.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            if (visiblePeople != null) {
                item {
                    Text("Unknown identity groups",
                        style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Groups are suggestions for review, never named people. " +
                            "Name, split or attach faces only when you recognize them.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!canEditPeople) {
                        Text("Identity edits are read-only until a durable review backend is connected.",
                            modifier = Modifier.testTag("atlas-cluster-review-read-only"))
                    } else {
                        Text(
                            "A confirmation belongs to one version of a photo: after you edit " +
                                "a photo, its faces are scanned again and return to Unknown.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("atlas-cluster-review-rescan-note")
                        )
                    }
                }
                val shownGroups = peopleGroupsToShow(
                    visiblePeople.clusters.unknownClusters, showSmallGroups)
                itemsIndexed(shownGroups) { index, group ->
                    UnknownIdentityGroupCard(
                        group = group, albums = albums, selectionScope = selectionScope,
                        enabled = canEditPeople,
                        onAction = ::sendPeopleDecision,
                        resolveDestination = ::resolveDestination,
                        destinationTagPrefix = "atlas-group-${index + 1}-destination",
                        preview = clusterFacePreview
                    )
                }
                val hiddenSmall = visiblePeople.clusters.unknownClusters.size - shownGroups.size
                if (hiddenSmall > 0 || showSmallGroups) {
                    item {
                        TextButton(
                            onClick = { showSmallGroups = !showSmallGroups },
                            modifier = Modifier.testTag("atlas-people-show-small-groups")
                        ) {
                            Text(if (showSmallGroups) "Hide small groups"
                                else "Show all ($hiddenSmall smaller groups)")
                        }
                    }
                }
                if (albums.isNotEmpty()) {
                    item {
                        Text("Reviewed person albums",
                            style = MaterialTheme.typography.titleLarge)
                        Text("Each album contains only faces you explicitly confirmed.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    itemsIndexed(albums) { index, album ->
                        ReviewedIdentityCard(
                            album = album, allAlbums = albums, selectionScope = selectionScope,
                            enabled = canEditPeople,
                            onAction = ::sendPeopleDecision,
                            resolveDestination = ::resolveDestination,
                            destinationTagPrefix = "atlas-album-${index + 1}-destination",
                            preview = clusterFacePreview
                        )
                    }
                }
                if (visiblePeople.undoHistory.isNotEmpty()) {
                    item {
                        OutlinedButton(
                            enabled = canEditPeople,
                            onClick = { sendPeopleDecision(PeopleReviewAction.Undo) }
                        ) { Text("Undo last People review change") }
                    }
                }
            }
            if (current == null) {
                item {
                    Text(
                        "Review is unavailable until the current photo selection and face index are confirmed.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("atlas-review-scope-pending")
                    )
                }
            } else {
                item {
                    Text(
                        "${current.totalVisibleObservations} face observations awaiting review. " +
                            "These are not confirmed identities.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (!reviewBackendReady) {
                        Text(
                            "Review decisions are unavailable until native storage is connected.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("atlas-review-read-only")
                        )
                    }
                }
                if (current.candidates.isEmpty()) {
                    item {
                        Text(
                            if (current.totalVisibleObservations == 0)
                                "No unknown-face observations are awaiting review."
                            else "No more unknown faces on this page.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                // Numeric lazy positions are safe for saved UI state. The enclosing
                // key(current) discards positional slots on ANY page replacement,
                // so a retained native face UID cannot resurrect an old-grant crop.
                // No UUID, media key or raw path is a Compose saveable-item key.
                items(current.candidates) { candidate ->
                    if (reviewBackendReady) {
                        UnknownFaceReviewCard(
                            candidate = candidate,
                            currentPermissionEpoch = currentPermissionEpoch,
                            onDecide = { attempted ->
                                // Revalidate against *this* visible review page.
                                currentNativeReviewCommandWithLiveScopeOrNull(
                                    current, candidate.key, attempted.decision,
                                    currentPermissionEpoch, currentSourceGeneration,
                                    liveReviewStateAtAction()
                                )?.let(onDecide)
                            },
                            sourcePreview = { sourcePreview(candidate) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth()
                                .testTag("atlas-review-placeholder"),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            sourcePreview(candidate)
                            Text(candidate.title, style = MaterialTheme.typography.titleMedium)
                            Text("Read-only · no review action is connected.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (current.hasMore) {
                    item {
                        if (onNextPage != null) {
                            TextButton(onClick = {
                                if (nativeReviewNextPageAllowed(
                                        current, currentPermissionEpoch,
                                        currentSourceGeneration, liveReviewStateAtAction()
                                    )) onNextPage()
                            }) { Text("More unknown faces") }
                        } else {
                            Text(
                                "More observations are available once paging is connected.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Position-based review controls. A private face UID is never a Compose saveable
 * key, semantic tag, filename or log. The host supplies a CURRENT grant-aware
 * crop or the safe neutral preview stays visible.
 */
@Composable
private fun UnknownIdentityGroupCard(
    group: UnknownFaceCluster,
    albums: List<NativePersonAlbum>,
    selectionScope: ReviewReferenceKey,
    enabled: Boolean,
    onAction: (PeopleReviewAction) -> Unit,
    resolveDestination: (List<NativePersonAlbum>, Int?, PersonKey?) -> PersonKey?,
    destinationTagPrefix: String,
    preview: @Composable (NativeFaceObservationId) -> Unit
) {
    var name by remember(selectionScope, group) { mutableStateOf("") }
    var selectedPosition by remember(selectionScope, group, albums) {
        mutableStateOf<Int?>(null)
    }
    var selected by remember(selectionScope, group) {
        mutableStateOf(emptySet<NativeFaceObservationId>())
    }
    val personKey = PersonKey("native-" + group.id.anchor.localHex)
    Card(Modifier.fillMaxWidth().testTag("atlas-unknown-identity-group")
        .semantics {
            // Synthetic API35 tests can prove a freshly composed row owns the marker.
            // No person or observation identifier is exported to semantics.
            reviewRowDiagnostic = "Review row scope ${selectionScope.hashCode()}; " +
                "selected choice ${selectedPosition ?: "none"}"
        }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Unknown group · ${group.members.size} faces",
                style = MaterialTheme.typography.titleMedium)
            val suggestion = group.suggestion
            if (suggestion != null) {
                val named = albums.firstOrNull { it.person == suggestion.person }
                if (named != null) {
                    Text(
                        "Possible match: ${named.name} · reviewed choice " +
                            "${albums.indexOfFirst { it.person == named.person } + 1} " +
                            "of ${albums.size}. Not assigned.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    named.observations.firstOrNull()?.let { preview(it) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = enabled, onClick = {
                            onAction(PeopleReviewAction.AssignCluster(group.id, named.person))
                        }) { Text("Confirm match") }
                        OutlinedButton(enabled = enabled, onClick = {
                            onAction(PeopleReviewAction.RejectSuggestion(group.id, named.person))
                        }) { Text("Not this person") }
                    }
                }
            }
            OutlinedTextField(
                value = name, onValueChange = { if (it.length <= 80) name = it },
                singleLine = true, label = { Text("New person's name") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                enabled = enabled && name.trim().isNotEmpty() &&
                    albums.none { it.person == personKey },
                onClick = {
                    onAction(PeopleReviewAction.NameCluster(group.id, personKey, name.trim()))
                }
            ) { Text("Name this group") }
            PeopleDestinationSelector(
                selectionScope = selectionScope,
                candidates = albums,
                selectedPosition = selectedPosition,
                onSelect = { selectedPosition = it },
                enabled = enabled,
                tagPrefix = destinationTagPrefix,
                preview = preview
            )
            OutlinedButton(
                enabled = enabled && selectedPosition != null,
                modifier = Modifier.testTag("${destinationTagPrefix}-assign"),
                onClick = {
                resolveDestination(albums, selectedPosition, null)?.let { person ->
                    selectedPosition = null
                    onAction(PeopleReviewAction.AssignCluster(group.id, person))
                }
            }) { Text("Add group to reviewed person") }
            Text("Select individual faces to split or move (first 20 shown).",
                style = MaterialTheme.typography.bodySmall)
            group.members.take(20).forEachIndexed { index, face ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(
                        checked = face in selected,
                        onCheckedChange = { checked ->
                            selected = if (checked) selected + face else selected - face
                        },
                        enabled = enabled,
                        modifier = Modifier.testTag("${destinationTagPrefix}-face-${index + 1}")
                    )
                    Text("Face ${index + 1}", modifier = Modifier.padding(top = 12.dp))
                    preview(face)
                }
            }
            OutlinedButton(
                enabled = enabled && selected.isNotEmpty() &&
                    selected.size < group.members.size,
                onClick = {
                    onAction(PeopleReviewAction.SplitUnknownCluster(group.id, selected))
                }
            ) { Text("Split selected faces") }
            OutlinedButton(
                enabled = enabled && selected.size == 1 && selectedPosition != null,
                modifier = Modifier.testTag("${destinationTagPrefix}-reassign"),
                onClick = {
                    val face = selected.singleOrNull()
                    val person = resolveDestination(albums, selectedPosition, null)
                    if (face != null && person != null) {
                        selectedPosition = null
                        onAction(PeopleReviewAction.ReassignFace(face, person))
                    }
                }
            ) { Text("Move selected face to reviewed person") }
        }
    }
}

@Composable
private fun ReviewedIdentityCard(
    album: NativePersonAlbum,
    allAlbums: List<NativePersonAlbum>,
    selectionScope: ReviewReferenceKey,
    enabled: Boolean,
    onAction: (PeopleReviewAction) -> Unit,
    resolveDestination: (List<NativePersonAlbum>, Int?, PersonKey?) -> PersonKey?,
    destinationTagPrefix: String,
    preview: @Composable (NativeFaceObservationId) -> Unit
) {
    val destinations = allAlbums.filter { it.person != album.person }
    var selectedPosition by remember(selectionScope, album, allAlbums) {
        mutableStateOf<Int?>(null)
    }
    var selected by remember(selectionScope, album) {
        mutableStateOf(emptySet<NativeFaceObservationId>())
    }
    Card(Modifier.fillMaxWidth().testTag("atlas-reviewed-identity-album")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(album.name, style = MaterialTheme.typography.titleMedium)
            Text("${album.observations.size} explicitly confirmed faces",
                style = MaterialTheme.typography.bodySmall)
            PeopleDestinationSelector(
                selectionScope = selectionScope,
                candidates = destinations,
                selectedPosition = selectedPosition,
                onSelect = { selectedPosition = it },
                enabled = enabled,
                tagPrefix = destinationTagPrefix,
                preview = preview
            )
            OutlinedButton(
                enabled = enabled && selectedPosition != null,
                modifier = Modifier.testTag("${destinationTagPrefix}-merge"),
                onClick = {
                resolveDestination(destinations, selectedPosition, album.person)?.let { person ->
                    selectedPosition = null
                    onAction(PeopleReviewAction.MergePeople(album.person, person))
                }
            }) { Text("Merge this person into selected person") }
            Text("Select faces to split or reassign (first 20 shown).",
                style = MaterialTheme.typography.bodySmall)
            album.observations.take(20).forEachIndexed { index, face ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(checked = face in selected,
                        onCheckedChange = { checked ->
                            selected = if (checked) selected + face else selected - face
                        }, enabled = enabled,
                        modifier = Modifier.testTag("${destinationTagPrefix}-face-${index + 1}"))
                    Text("Face ${index + 1}", modifier = Modifier.padding(top = 12.dp))
                    preview(face)
                }
            }
            OutlinedButton(
                enabled = enabled && selected.isNotEmpty() &&
                    selected.size < album.observations.size,
                onClick = {
                    onAction(PeopleReviewAction.SplitPerson(album.person, selected))
                }
            ) { Text("Split selected from this person") }
            OutlinedButton(enabled = enabled && selected.size == 1 &&
                selectedPosition != null,
                modifier = Modifier.testTag("${destinationTagPrefix}-reassign"), onClick = {
                val face = selected.singleOrNull()
                val person = resolveDestination(destinations, selectedPosition, album.person)
                if (face != null && person != null) {
                    selectedPosition = null
                    onAction(PeopleReviewAction.ReassignFace(face, person))
                }
            }) { Text("Reassign selected face") }
            OutlinedButton(enabled = enabled && selected.size == 1, onClick = {
                selected.singleOrNull()?.let {
                    onAction(PeopleReviewAction.UnassignFace(it))
                }
            }) { Text("Return selected face to Unknown") }
        }
    }
}

/** #579: Samsung shows a person once they are in at least this many photos. */
const val PEOPLE_MIN_GROUP_FACES = 3

/**
 * Unknown groups the review list shows: every group of at least
 * [PEOPLE_MIN_GROUP_FACES] faces (largest first, as clustering orders them),
 * or all of them with [showAll]. If no group is that large, all are shown,
 * so a small library never looks empty.
 */
fun peopleGroupsToShow(
    groups: List<UnknownFaceCluster>, showAll: Boolean
): List<UnknownFaceCluster> {
    if (showAll) return groups
    val large = groups.filter { it.members.size >= PEOPLE_MIN_GROUP_FACES }
    return if (large.isEmpty()) groups else large
}
