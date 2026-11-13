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
    onBack: () -> Unit,
    onOpen: (List<MediaRecord>, MediaRecord) -> Unit,
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
    var dialog by remember(request) { mutableStateOf<PersonPageDialog?>(null) }
    var selected by remember(request) { mutableStateOf<Set<String>?>(null) }
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
        PersonFaceImage(host, epoch, face)
    }
    PersonPageScreen(
        ui = PersonPageUi(
            name = facts?.name ?: (request.target as? PersonPageTarget.Named)?.let { request.name },
            items = media?.items,
            hidden = facts?.hidden == true,
            mayHide = request.target is PersonPageTarget.Named
        ),
        gridState = gridState,
        catalogueEpoch = catalogueEpoch,
        opening = request.opening,
        portrait = { portrait(facts?.coverFace) },
        onBack = onBack,
        onOpen = { record -> media?.let { onOpen(it.items, record) } },
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
        selectedUris = selected,
        onSelectionChange = { selected = it },
        samePerson = samePersonCard,
        onRemoveSelected = run {
            val chosen = selected
            val current = facts
            if (chosen == null || current == null || media == null ||
                removeAction(current, media.facesByUri, chosen) == null) null
            else ({
                commit({ live ->
                    personPageFacts(live, request.target)?.let {
                        removeAction(it, media.facesByUri, chosen)
                    }
                }) { selected = null }
            })
        }
    )
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
    onOpen: (NativePersonAlbumRequest) -> Unit
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
