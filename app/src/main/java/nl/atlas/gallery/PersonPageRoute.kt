package nl.atlas.gallery

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.featurepeopleui.NativePersonAlbum
import nl.atlas.gallery.featurepeopleui.PeopleClusterReviewCommand
import nl.atlas.gallery.featurepeopleui.PeopleClusterReviewState
import nl.atlas.gallery.featurepeopleui.PeopleHomeSummary
import nl.atlas.gallery.featurepeopleui.PeopleReviewAction
import nl.atlas.gallery.featurepeopleui.PersonNaming
import nl.atlas.gallery.featurepeopleui.PersonPageTarget
import nl.atlas.gallery.featurepeopleui.ReviewReferenceKey
import nl.atlas.gallery.featurepeopleui.SamePersonQuestion
import nl.atlas.gallery.featurepeopleui.mergeAction
import nl.atlas.gallery.featurepeopleui.mergeCandidates
import nl.atlas.gallery.featurepeopleui.nameSuggestions
import nl.atlas.gallery.featurepeopleui.peopleAlbumsForGrant
import nl.atlas.gallery.featurepeopleui.personNaming
import nl.atlas.gallery.featurepeopleui.personPageFacts
import nl.atlas.gallery.featurepeopleui.removeAction
import nl.atlas.gallery.featurepeopleui.samePersonAnswer
import nl.atlas.gallery.featurepeopleui.samePersonQuestion
import nl.atlas.gallery.featurepeopleui.targetAfter
import nl.atlas.gallery.featuresearch.AtlasSearch
import nl.atlas.gallery.featuresearch.SearchHistoryScope
import nl.atlas.gallery.featuresearch.AtlasSearchScope
import nl.atlas.gallery.featuresearch.ScopedMediaSearchSnapshot
import nl.atlas.gallery.featuresearch.currentScopedMediaSearchOrNull
import nl.atlas.gallery.featuresearch.scopedMediaMatches

/**
 * An opened person page: a named person or an unknown group (#551), and the
 * name shown when it was opened.
 */
internal data class NativePersonAlbumRequest(
    val target: PersonPageTarget,
    val name: String,
    /**
     * #584: one opening of the page. A new opening (from People, Hidden or the
     * viewer's People row) starts at the grid; a first name or a merge
     * retargets the same opening ([retargeted]) and keeps its zoom stop, as
     * Samsung Gallery keeps the view when you rename or merge on the page.
     */
    val opening: Long = nextPersonPageOpening()
) {
    constructor(person: PersonKey, name: String) : this(PersonPageTarget.Named(person), name)

    /** The same opening, following the surviving person. */
    fun retargeted(target: PersonPageTarget, name: String) =
        copy(target = target, name = name)

    /** The named person, or null for an unknown group. */
    val person: PersonKey? get() = (target as? PersonPageTarget.Named)?.person
}

private val personPageOpenings = java.util.concurrent.atomic.AtomicLong()
private fun nextPersonPageOpening(): Long = personPageOpenings.incrementAndGet()

/** A bounded pending person selection owned by an Activity-retained ViewModel.
 * The opening key prevents an abandoned page selection from entering another page.
 * The ViewModel is not a SavedStateHandle: process death drops this tuple.
 */
internal data class PersonSelectionRotationSnapshot(val opening: Long, val encoded: List<String>) {
    fun forOpening(currentOpening: Long): List<String> =
        encoded.takeIf { opening == currentOpening }.orEmpty()
}

/** A changed permission, People generation or person identity hides controls now,
 * rather than waiting for the coroutine that discards stale selection state. */
internal fun validatedPersonSelection(
    selected: Set<String>?,
    activeFence: List<String>?,
    currentFence: List<String>?
): Set<String>? = selected?.takeIf { currentFence != null && activeFence == currentFence }

/** Re-admit only visible person-scope keys after the matching identity was validated. */
internal fun eligiblePersonSelection(requested: Set<String>, visible: Set<String>): Set<String>? =
    requested.intersect(visible).takeIf { it.isNotEmpty() }

/**
 * Never keep a previous smaller snapshot when the live selection is too large
 * to encode, malformed, or fenced to another person/grant. In particular a
 * select-all of 501+ keys must not restore the earlier 2-3 keys on rotation.
 */
internal fun encodePersonSelectionForRotation(
    live: Set<String>,
    selectionFence: List<String>?,
    activeSelectionFence: List<String>?
): List<String> = if (live.isNotEmpty() && live.size <= 500 &&
    live.none { it.isBlank() } && selectionFence != null &&
    activeSelectionFence == selectionFence) {
    selectionFence + live.sorted()
} else emptyList()

/**
 * The person page (#434 album, #551 Samsung page and actions): the photos and
 * videos holding the person's faces, resolved through the live grant on IO
 * and narrowed on Default, newest first. Every action is computed from the
 * host's live review snapshot at tap time and committed through
 * [PeopleDiscoveryHost.review] (FaceIndexStore's one review transaction); a
 * stale snapshot sends nothing. After a merge or a first name the page
 * follows the surviving person through [onRetarget]. It closes itself
 * ([onBack]) once the person is gone or the opt-in is revoked (#438).
 */
@Composable
internal fun NativePersonAlbumRoute(
    host: PeopleDiscoveryHost,
    galleryGrant: String?,
    request: NativePersonAlbumRequest,
    people: PeopleHomeSummary?,
    sorted: List<MediaRecord>,
    gridState: LazyGridState,
    catalogueEpoch: Long,
    // App supplies the retained ViewModel holder; standalone Compose test routes
    // use an ephemeral composition-local holder rather than Android saved state.
    selectionRotationState: androidx.compose.runtime.MutableState<PersonSelectionRotationSnapshot?> =
        remember { mutableStateOf(null) },
    onBack: () -> Unit,
    onOpen: (List<MediaRecord>, MediaRecord) -> Unit,
    facetPeople: List<PersonEntry> = emptyList(),
    onSearchWholeLibrary: (() -> Unit)? = null,
    onRetarget: (NativePersonAlbumRequest) -> Unit = {}
) {
    val version by host.changes.collectAsState()
    val close by rememberUpdatedState(onBack)
    val retarget by rememberUpdatedState(onRetarget)
    // The host's live snapshot is updated inside review(), before the
    // published People state catches up; read it first so the page never
    // closes on its own rename or merge. People home (#532) is only a
    // summary, so a cold cache loads the full review snapshot on IO.
    val state = rememberReviewState(host, version, people)
    val epoch = state?.permissionEpoch
    val facts = remember(state, request) { personPageFacts(state, request.target) }
    val gone = !host.discoveryOptedIn() || (state != null && facts == null)
    LaunchedEffect(gone) { if (gone) close() }

    val faces = facts?.faces
    val resolved by produceState<PersonPageMedia?>(null, CatalogSourceKey(sorted),
        galleryGrant, request, ReviewReferenceKey(state)) {
        if (faces == null) return@produceState
        val byUri = withContext(Dispatchers.IO) { host.personPageFaces(galleryGrant, faces) }
        value = if (byUri == null) PersonPageMedia(emptyList(), emptyMap())
        else withContext(Dispatchers.Default) {
            val worker = currentCoroutineContext()
            PersonPageMedia(selectPersonPageMedia(sorted, byUri.keys) { worker.ensureActive() },
                byUri)
        }
    }
    // PERF (#119): a named person's photos straight from the per-person index
    // (one indexed read, each photo resolved through the live grant, no
    // People snapshot), shown until the full page above is ready. The full
    // snapshot can take long to load on a large library or while a scan is
    // publishing; selection actions still wait for it.
    val quickPerson = request.person
    val quick by produceState<List<MediaRecord>?>(null, CatalogSourceKey(sorted),
        galleryGrant, request) {
        value = null
        if (quickPerson == null) return@produceState
        val uris = withContext(Dispatchers.IO) { host.personAlbumUris(galleryGrant, quickPerson) }
            ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val worker = currentCoroutineContext()
            selectPersonPageMedia(sorted, uris) { worker.ensureActive() }
        }
    }
    // The portrait People shows for this person, known before the snapshot loads.
    val summaryCover = quickPerson?.let { people?.people?.get(it)?.coverFaceUid }
        ?.let { uid -> runCatching { NativeFaceObservationId(uid) }.getOrNull() }
    val cropEpoch = epoch ?: people?.permissionEpoch
    var dialog by remember(request) { mutableStateOf<PersonPageDialog?>(null) }
    // Save only a bounded tuple. A restored snapshot is never actionable
    // until the current grant, People scope and source have been validated.
    // Configuration changes keep the pending keys in the ViewModel; process
    // death must not deserialize a stale person selection from a Bundle.
    var pendingSelection by remember(request.opening, selectionRotationState) {
        mutableStateOf(selectionRotationState.value?.forOpening(request.opening).orEmpty())
    }
    var selected by remember(request) { mutableStateOf<Set<String>?>(null) }
    var choosingCover by remember(request) { mutableStateOf(false) }
    val chosenCover by produceState<String?>(null, galleryGrant, request.target, version, epoch) {
        value = request.person?.let { person ->
            withContext(Dispatchers.IO) { host.currentPersonCover(galleryGrant, person) }
        }
    }
    val scope = rememberCoroutineScope()

    /** Build the action from the live snapshot now; commit it on IO. */
    fun commit(build: (PeopleClusterReviewState) -> PeopleReviewAction?,
               after: (PeopleReviewAction) -> Unit = {}) {
        val live = epoch?.let { host.liveReview(it) } ?: return
        val action = build(live) ?: return
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                host.review(PeopleClusterReviewCommand(live, action))
            }
            if (ok) after(action)
        }
    }
    fun mergeInto(into: NativePersonAlbum) {
        dialog = null
        commit({ mergeAction(request.target, into.person) }) { action ->
            retarget(request.retargeted(targetAfter(request.target, action), into.name))
        }
    }

    // #580: the open "Same person?" question, found off the main thread from
    // the same snapshot the page shows; gone as soon as it is answered.
    val question by produceState<SamePersonQuestion?>(null, request, ReviewReferenceKey(state)) {
        value = null
        value = withContext(Dispatchers.Default) { samePersonQuestion(state, request.target) }
    }
    val media = resolved
    // Search only this grant-resolved person's ordered media, never the full catalogue.
    // Discard worker results from a changed grant, source identity or query.
    var searchText by remember(request.opening, galleryGrant) { mutableStateOf("") }
    val searchSnapshot by produceState<ScopedMediaSearchSnapshot?>(
        null, media, searchText, facetPeople, galleryGrant, catalogueEpoch
    ) {
        value = null
        val source = media?.items
        if (source != null && searchText.isNotBlank() && !galleryGrant.isNullOrBlank()) {
            val worker = currentCoroutineContext()
            val matches = withContext(Dispatchers.Default) {
                scopedMediaMatches(source, source, facetPeople, searchText,
                    cancellationCheckpoint = { worker.ensureActive() })
            }
            value = ScopedMediaSearchSnapshot(source, source, facetPeople,
                galleryGrant, searchText, AtlasSearchScope.THIS_SCREEN, matches)
        }
    }
    val visibleMedia = when {
        media == null -> quick?.takeIf { searchText.isBlank() }
        searchText.isBlank() -> media.items
        else -> currentScopedMediaSearchOrNull(searchSnapshot, media.items, media.items,
            facetPeople, galleryGrant, searchText, AtlasSearchScope.THIS_SCREEN)
    }
    val currentGrant = galleryGrant?.takeIf { it == catalogueEpoch.toString() }
    val personIdentity = request.target.toString()
    // An in-memory selection is no safer than a restored one: it must not
    // remain actionable after the grant, People epoch or person identity moves.
    // Capture the fence when selection begins and clear it on any mismatch.
    var activeSelectionFence by remember(request.opening) {
        mutableStateOf<List<String>?>(null)
    }
    val selectionFence = if (currentGrant != null && epoch != null)
        listOf(currentGrant, epoch, personIdentity) else null
    LaunchedEffect(selectionFence, selected) {
        if (selected != null && (selectionFence == null ||
                activeSelectionFence != selectionFence)) {
            selected = null
            pendingSelection = emptyList()
            activeSelectionFence = null
        }
    }
    androidx.compose.runtime.SideEffect {
        val live = selected
        val encoded = if (live != null) {
            encodePersonSelectionForRotation(live, selectionFence, activeSelectionFence)
        } else pendingSelection
        // An over-limit or invalid live selection invalidates the old snapshot,
        // rather than restoring a stale subset after Activity recreation.
        if (pendingSelection != encoded) pendingSelection = encoded
        val snapshot = PersonSelectionRotationSnapshot(request.opening, encoded)
        if (selectionRotationState.value != snapshot) selectionRotationState.value = snapshot
    }
    LaunchedEffect(request.opening, currentGrant, epoch, personIdentity,
        CatalogSourceKey(visibleMedia ?: emptyList<MediaRecord>()), pendingSelection) {
        if (selected != null) return@LaunchedEffect
        val saved = pendingSelection
        if (saved.isEmpty()) return@LaunchedEffect
        val visible = visibleMedia ?: return@LaunchedEffect
        if (state == null || facts == null || currentGrant == null ||
            saved.size < 3 || saved.size > 503 || saved[0] != currentGrant ||
            saved[1] != epoch || saved[2] != personIdentity ||
            saved.drop(3).any { it.isBlank() }) {
            pendingSelection = emptyList()
            return@LaunchedEffect
        }
        val keys = saved.drop(3).toSet()
        if (keys.size != saved.size - 3) {
            pendingSelection = emptyList()
            return@LaunchedEffect
        }
        val matching = withContext(Dispatchers.Default) {
            val worker = currentCoroutineContext()
            visible.mapIndexedNotNull { index, record ->
                if ((index and 511) == 0) worker.ensureActive()
                record.uri.takeIf { it in keys }
            }.toSet()
        }
        if (pendingSelection == saved && selected == null) {
            pendingSelection = emptyList()
            val eligible = eligiblePersonSelection(keys, matching)
            if (eligible != null) {
                activeSelectionFence = selectionFence
                selected = eligible
            }
        }
    }
    val actionableSelected = validatedPersonSelection(selected,
        activeSelectionFence, selectionFence)
    val asked = question
    val samePersonCard: (@Composable () -> Unit)? =
        if (asked == null) null
        else ({
            SamePersonCard(asked.otherFaces, { PersonFaceImage(host, epoch, asked.otherCover) }) { same ->
                commit({ samePersonAnswer(request.target, asked, same) }) { done ->
                    // A yes can rename the group (its smallest face): follow it.
                    val next = targetAfter(request.target, done)
                    if (next != request.target) {
                        retarget(request.retargeted(next, request.name))
                    }
                }
            }
        })
    val portrait: @Composable (NativeFaceObservationId?) -> Unit = { face ->
        PersonFaceImage(host, cropEpoch, face)
    }
    PersonPageScreen(
        ui = PersonPageUi(
            name = facts?.name ?: (request.target as? PersonPageTarget.Named)?.let { request.name },
            // Keep the timeline/header composition stable while the worker
            // narrows this person's already-authorized list.
            items = if (media == null) visibleMedia else visibleMedia.orEmpty(),
            hidden = facts?.hidden == true,
            mayHide = request.target is PersonPageTarget.Named
        ),
        gridState = gridState,
        catalogueEpoch = catalogueEpoch,
        searching = media != null && searchText.isNotBlank() && visibleMedia == null,
        opening = request.opening,
        portrait = { portrait(
            chosenCover?.let { uid -> faces?.firstOrNull { it.localHex == uid } }
                ?: summaryCover?.takeIf { faces == null || it in faces }
                ?: facts?.coverFace) },
        onBack = onBack,
        onOpen = { record ->
            visibleMedia?.takeIf { shown -> shown.any { it.uri == record.uri } }
                ?.let { onOpen(it, record) }
        },
        dialog = dialog,
        onDialog = { dialog = it },
        nameDialog = {
            PersonNameDialog(
                initial = facts?.name.orEmpty(),
                suggestions = { typed -> nameSuggestions(state, request.target, typed) },
                suggestionPortrait = { portrait(it.observations.firstOrNull()) },
                onDismiss = { dialog = null },
                onSave = { typed ->
                    val live = epoch?.let { host.liveReview(it) }
                    when (val naming = personNaming(live, request.target, typed)) {
                        is PersonNaming.OfferMerge -> dialog = PersonPageDialog.ConfirmMerge(naming.into)
                        is PersonNaming.Apply -> {
                            dialog = null
                            commit({ naming.action }) { action ->
                                retarget(request.retargeted(
                                    targetAfter(request.target, action), typed.trim()))
                            }
                        }
                        PersonNaming.Invalid -> dialog = null
                    }
                }
            )
        },
        mergePicker = {
            PersonMergePicker(
                people = mergeCandidates(state, request.target),
                portrait = { portrait(it.observations.firstOrNull()) },
                onDismiss = { dialog = null },
                onPick = { dialog = PersonPageDialog.ConfirmMerge(it) }
            )
        },
        confirmMerge = { into ->
            PersonMergeConfirm(facts?.name, into,
                onDismiss = { dialog = null }, onMerge = { mergeInto(into) })
        },
        onHide = { hide ->
            val person = (request.target as? PersonPageTarget.Named)?.person
            if (person != null) commit({ PeopleReviewAction.SetPersonHidden(person, hide) }) {
                // Samsung returns to People once a person is hidden.
                if (hide) close()
            }
        },
        selectedUris = actionableSelected,
        onSelectionChange = {
            selected = it
            activeSelectionFence = if (it != null) selectionFence else null
            if (it == null) pendingSelection = emptyList()
        },
        samePerson = samePersonCard,
        onChangePortrait = if (request.person != null && !facts?.faces.isNullOrEmpty())
            ({ choosingCover = true }) else null,
        searchAction = {
            AtlasSearch(query = searchText, onQueryChange = { searchText = it },
                historyScope = SearchHistoryScope.PERSON,
                onScopeChange = { next ->
                    if (next == AtlasSearchScope.LIBRARY) onSearchWholeLibrary?.invoke()
                })
        },
        onRemoveSelected = run {
            val chosen = actionableSelected
            val current = facts
            if (chosen == null || current == null || media == null ||
                removeAction(current, media.facesByUri, chosen) == null) null
            else ({
                commit({ live ->
                    personPageFacts(live, request.target)?.let {
                        removeAction(it, media.facesByUri, chosen)
                    }
                }) {
                    selected = null
                    pendingSelection = emptyList()
                }
            })
        }
    )
    val coverPerson = request.person
    if (choosingCover && coverPerson != null && facts != null && epoch != null) {
        PersonFaceCoverChooser(
            faces = facts.faces,
            selectedUid = chosenCover,
            portrait = { portrait(it) },
            onChoose = { face ->
                scope.launch {
                    val saved = withContext(Dispatchers.IO) {
                        host.choosePersonCover(galleryGrant, coverPerson, face.localHex)
                    }
                    if (saved) choosingCover = false
                }
            },
            onAutomatic = if (chosenCover != null) ({
                scope.launch {
                    val cleared = withContext(Dispatchers.IO) {
                        host.choosePersonCover(galleryGrant, coverPerson, null)
                    }
                    if (cleared) choosingCover = false
                }
            }) else null,
            onDismiss = { choosingCover = false }
        )
    }
}

/** One resolved person page: its records and the faces each one holds. */
internal class PersonPageMedia(
    val items: List<MediaRecord>,
    val facesByUri: Map<String, Set<NativeFaceObservationId>>
)

/** A face crop filling its round frame; the neutral icon while none is available. */
@Composable
internal fun PersonFaceImage(host: PeopleDiscoveryHost, epoch: String?, face: NativeFaceObservationId?) {
    val version by host.changes.collectAsState()
    val crop by produceState<ImageBitmap?>(null, epoch, face, version) {
        value = if (face == null) null
        else withContext(Dispatchers.IO) { host.faceCrop(epoch, face.localHex) }?.asImageBitmap()
    }
    val image = crop
    if (image != null) {
        Image(image, "Face", Modifier.fillMaxSize().testTag("atlas-person-face"),
            contentScale = ContentScale.Crop)
    } else PersonPortraitFallback()
}

/**
 * People ⋮ → Hidden: the hidden named people of the live snapshot. Tapping
 * one opens its page through [onOpen].
 */
@Composable
internal fun HiddenPeopleRoute(
    host: PeopleDiscoveryHost,
    people: PeopleHomeSummary?,
    onBack: () -> Unit,
    onOpen: (NativePersonAlbumRequest) -> Unit,
    onSearchWholeLibrary: (() -> Unit)? = null
) {
    val version by host.changes.collectAsState()
    val state = rememberReviewState(host, version, people)
    val hidden = remember(state) {
        state?.let { peopleAlbumsForGrant(it, it.permissionEpoch, it.sourceGeneration,
            hiddenOnly = true) }.orEmpty()
    }
    HiddenPeopleScreen(
        people = hidden,
        portrait = { PersonFaceImage(host, state?.permissionEpoch, it.observations.firstOrNull()) },
        onBack = onBack,
        onSearchWholeLibrary = onSearchWholeLibrary,
        onOpen = { onOpen(NativePersonAlbumRequest(it.person, it.name)) }
    )
}

/**
 * The full review snapshot behind [people]'s epoch: the host's live copy when
 * it is current (set synchronously by review()), otherwise read on IO. Null
 * while there is no current People summary.
 */
@Composable
internal fun rememberReviewState(
    host: PeopleDiscoveryHost,
    version: Long,
    people: PeopleHomeSummary?
): PeopleClusterReviewState? {
    val epoch = people?.permissionEpoch
    val loaded by produceState(epoch?.let { host.liveReview(it) }, version, people) {
        value = epoch?.let { host.liveReview(it) ?: withContext(Dispatchers.IO) { host.reviewState(it) } }
    }
    return epoch?.let { host.liveReview(it) } ?: loaded?.takeIf { it.permissionEpoch == epoch }
}
