package nl.atlas.gallery.featureidentity.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import nl.atlas.gallery.featureaiedit.provider.EditImage
import nl.atlas.gallery.featureaiedit.provider.ImageFormat
import nl.atlas.gallery.featureidentity.engine.EngineRoute
import nl.atlas.gallery.featureidentity.engine.FaceBox
import nl.atlas.gallery.featureidentity.engine.FacePoint
import nl.atlas.gallery.featureidentity.engine.HairChoice
import nl.atlas.gallery.featureidentity.engine.IdentityCandidate
import nl.atlas.gallery.featureidentity.engine.IdentityCandidateSession
import nl.atlas.gallery.featureidentity.engine.IdentityEditEngine
import nl.atlas.gallery.featureidentity.engine.IdentityEditMode
import nl.atlas.gallery.featureidentity.engine.IdentityFailureKind
import nl.atlas.gallery.featureidentity.engine.IdentityPreferences
import nl.atlas.gallery.featureidentity.engine.IdentityReferenceSet
import nl.atlas.gallery.featureidentity.engine.IdentityRunStatus
import nl.atlas.gallery.featureidentity.engine.IdentitySessionInput
import nl.atlas.gallery.featureidentity.engine.IdentityVariation
import nl.atlas.gallery.featureidentity.engine.PerceptualFingerprint
import nl.atlas.gallery.featureidentity.engine.TargetFace
import nl.atlas.gallery.featureidentity.target.TargetFaceSelection
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.Executors

/*
 * IDS-8 (#477): the Swap person studio state machine (design brief rev 2) and its AndroidX
 * ViewModel. The machine is plain Kotlin with injected executors, so JVM tests drive the whole
 * flow on FakeIdentityEditEngine without Android, coroutines or a clock. No pixels, face boxes
 * or person names are logged anywhere in this file.
 */

/** A face crop the UI can draw: the image plus the box to crop (null means the whole image). */
data class SwapFace(val image: EditImage, val box: FaceBox?)

data class SwapLook(val id: String, val label: String)

/** One person the user can choose. [recentRank] 0 is the most recently used; null is never. */
data class SwapPersonChoice(
    val profileId: String,
    val name: String?,
    val photoCount: Int,
    val recentRank: Int?,
    val looks: List<SwapLook>,
    val face: SwapFace?
) {
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: "this person"
}

sealed interface SwapReferences {
    data class Ready(val set: IdentityReferenceSet) : SwapReferences
    data class TooFew(val have: Int) : SwapReferences
}

sealed interface SwapSetupStatus {
    data object Ready : SwapSetupStatus
    data class Needed(val megabytes: Int) : SwapSetupStatus
}

/** A photo offered in "Choose photos" (S8) and its detected faces. */
data class SwapPickPhoto(val id: String, val image: EditImage, val faces: List<FaceBox>)

/** A reference photo shown in "Photos used" (S7). */
data class SwapReferencePhoto(val id: String, val face: SwapFace)

/** Everything the studio needs from ATLAS. Methods run on the worker thread. */
interface SwapPersonHost {
    fun people(): List<SwapPersonChoice>
    fun references(profileId: String, lookId: String?): SwapReferences
    fun findFaces(): TargetFaceSelection
    /** Every usable face in the photo, for "Change face". */
    fun usableFaces(): List<TargetFace>
    fun setupStatus(): SwapSetupStatus
    /** Downloads the one-time setup; resumes after a dropped connection. True when done. */
    fun download(onProgress: (Float) -> Unit): Boolean
    fun photosToPick(): List<SwapPickPhoto>
    /** Index of the face matching [chosen] when its margin is clear, else null (ask). */
    fun matchFace(photo: SwapPickPhoto, chosen: List<Pair<SwapPickPhoto, FaceBox>>): Int?
    fun createProfile(faces: List<Pair<SwapPickPhoto, FaceBox>>, name: String?): SwapPersonChoice
    fun addReferences(profileId: String, faces: List<Pair<SwapPickPhoto, FaceBox>>)
    fun referencePhotos(profileId: String): List<SwapReferencePhoto>
    fun removeReference(profileId: String, referenceId: String)
    fun forget(profileId: String)
    fun markUsed(profileId: String)
}

sealed interface SwapSaveOutcome {
    data class Saved(val handle: String) : SwapSaveOutcome
    data object Failed : SwapSaveOutcome
}

/** Saves the chosen version as a copy (IDS-10 [nl.atlas.gallery.featureidentity.save.IdentityEditSave]). */
fun interface SwapPersonSaver {
    fun save(
        candidate: IdentityCandidate,
        profileId: String,
        variantId: String?,
        refinementParent: String?,
        done: (SwapSaveOutcome) -> Unit
    )
}

/** Keeps a finished version's file outside the session folder so it survives process death. */
interface SwapVersionKeeper {
    fun keep(key: Int, file: File): File?
    fun clear()
}

enum class SwapStage { PERSON, SETUP, TARGET, STUDIO, FINISHED }
enum class SwapSheet { NONE, CHOOSE_PHOTOS, PHOTOS_USED, WHICH_FACE }
enum class VersionStatus { PENDING, READY, FAILED }
enum class SwapRefine { MORE_NATURAL, STRONGER_LIKENESS, KEEP_EXPRESSION, HAIR }

/** What a version was asked to be. */
data class VersionRequest(
    val profileId: String,
    val lookId: String?,
    val target: TargetFace,
    val mode: IdentityEditMode,
    val preferences: IdentityPreferences,
    val refinedFrom: Int? = null
)

data class SwapVersion(
    val key: Int,
    val batch: Int,
    val status: VersionStatus,
    val request: VersionRequest,
    val candidate: IdentityCandidate? = null,
    val image: EditImage? = null
) {
    val hair: Boolean get() = request.mode == IdentityEditMode.HEAD
}

sealed interface SwapNotice {
    data object NoFace : SwapNotice
    data class FaceSmall(val face: TargetFace) : SwapNotice
    data class TooFewPhotos(val name: String) : SwapNotice
    data object AllFailed : SwapNotice
    data object OutOfMemory : SwapNotice
    data object SetupFailed : SwapNotice
}

sealed interface SwapSetupUi {
    data class Offer(val megabytes: Int) : SwapSetupUi
    data class Running(val megabytes: Int, val progress: Float) : SwapSetupUi
    data class Failed(val megabytes: Int) : SwapSetupUi
}

sealed interface SwapSave {
    data object Idle : SwapSave
    data object Saving : SwapSave
    data class Saved(val handle: String) : SwapSave
    data object Failed : SwapSave
}

sealed interface SwapOverlay {
    data object None : SwapOverlay
    data object CompareAll : SwapOverlay
    /** [older] has been shown longest and is replaced by a third pick. Keys; null is Original. */
    data class Split(val older: Int?, val newer: Int?) : SwapOverlay
}

/** S8 / "+ Add photos": picking 3–12 photos, then naming, with the multi-face question. */
data class SwapPickState(
    val forProfile: String?,
    val photos: List<SwapPickPhoto> = emptyList(),
    val loading: Boolean = true,
    val chosen: List<Pair<SwapPickPhoto, FaceBox>> = emptyList(),
    val asking: SwapPickPhoto? = null,
    val naming: Boolean = false,
    val name: String = ""
) {
    val canContinue: Boolean get() = chosen.size in MIN_PICK..MAX_PICK

    companion object {
        const val MIN_PICK = 3
        const val MAX_PICK = 12
    }
}

data class SwapPersonUiState(
    val stage: SwapStage = SwapStage.PERSON,
    val sheet: SwapSheet = SwapSheet.NONE,
    val peopleLoading: Boolean = true,
    val people: List<SwapPersonChoice> = emptyList(),
    val search: String = "",
    val person: SwapPersonChoice? = null,
    val lookId: String? = null,
    val faces: List<TargetFace> = emptyList(),
    val target: TargetFace? = null,
    val versions: List<SwapVersion> = emptyList(),
    /** Selected version key; null is the Original. */
    val selected: Int? = null,
    val headCapable: Boolean = false,
    val setup: SwapSetupUi? = null,
    val notice: SwapNotice? = null,
    val discardDialog: Boolean = false,
    val save: SwapSave = SwapSave.Idle,
    val overlay: SwapOverlay = SwapOverlay.None,
    val pick: SwapPickState? = null,
    /** The photo has more than one usable face, so S4 ⋮ offers "Change face". */
    val canChangeFace: Boolean = false,
    val photosUsed: List<SwapReferencePhoto>? = null,
    val forgetDialog: Boolean = false,
    val announcement: String? = null
) {
    val ready: List<SwapVersion> get() = versions.filter { it.status == VersionStatus.READY }
    val pending: Int get() = versions.count { it.status == VersionStatus.PENDING }
    val generating: Boolean get() = pending > 0
    /** The opening versions of the newest batch (4, or fewer after a restore), while any is still being made. */
    val firstBatchRunning: List<SwapVersion>? get() {
        val newest = versions.lastOrNull()?.batch ?: return null
        val first = versions.filter { it.batch == newest }.take(4)
        return first.takeIf { list -> list.any { it.status == VersionStatus.PENDING } }
    }
    val selectedVersion: SwapVersion? get() = versions.firstOrNull { it.key == selected }
    /** People as S1 lists them: recents first, then named People by photo count; never unnamed groups. */
    val listedPeople: List<SwapPersonChoice> get() {
        val named = people.filter { !it.name.isNullOrBlank() }
        val recent = named.filter { it.recentRank != null }.sortedBy { it.recentRank }
        val rest = named.filter { it.recentRank == null }.sortedByDescending { it.photoCount }
        val all = recent + rest
        val q = search.trim()
        return if (q.isEmpty()) all else all.filter { it.name!!.contains(q, ignoreCase = true) }
    }
    val showSearch: Boolean get() = people.count { !it.name.isNullOrBlank() } > SEARCH_THRESHOLD
    /** Strip/pager order: Original (position 0), then every version in order of creation. */
    val pages: List<Int?> get() = listOf<Int?>(null) + versions.map { it.key }
    val otherLooks: List<SwapLook> get() {
        val looks = person?.looks.orEmpty()
        val sel = selectedVersion?.request
        val current = if (sel != null && sel.profileId == person?.profileId) sel.lookId else lookId
        return looks.filter { it.id != current }.let { if (looks.size > 1) it else emptyList() }
    }

    companion object { const val SEARCH_THRESHOLD = 12 }
}

/**
 * The Swap person flow. Every host call and engine call runs on [worker]; every state change is
 * published on [main]. One version is made at a time (`more(1)` on a warm session), so tiles fill
 * in one by one, a single failure only marks its own tile, and face and hair versions alternate.
 */
class SwapPersonMachine(
    private val host: SwapPersonHost,
    private val engine: IdentityEditEngine,
    private val source: EditImage,
    private val workspaceRoot: File,
    private val saver: SwapPersonSaver,
    private val keeper: SwapVersionKeeper,
    private val worker: (Runnable) -> Unit,
    private val main: (Runnable) -> Unit,
    private val onState: (SwapPersonUiState) -> Unit = {},
    private val sessionSeed: () -> Long = { java.util.UUID.randomUUID().mostSignificantBits }
) {
    var state = SwapPersonUiState()
        private set

    private data class SessionKey(
        val profileId: String, val lookId: String?, val target: TargetFace,
        val mode: IdentityEditMode, val preferences: IdentityPreferences
    )

    private val sessions = LinkedHashMap<SessionKey, IdentityCandidateSession>()
    private val references = HashMap<Pair<String, String?>, IdentityReferenceSet>()
    private val queue = ArrayDeque<Int>()
    private var pumping = false
    private var nextKey = 1
    private var batch = 0
    private var closed = false
    /** Person waiting for setup or references before the flow can continue. */
    private var pendingPerson: SwapPersonChoice? = null
    private var appendNextBatch = false

    private fun update(change: (SwapPersonUiState) -> SwapPersonUiState) {
        state = change(state)
        onState(state)
    }

    private fun onMain(block: () -> Unit) = main(Runnable { if (!closed) block() })
    private fun background(block: () -> Unit) = worker(Runnable { if (!closed) block() })

    // ---- S1 person ----

    fun start() {
        update { it.copy(stage = SwapStage.PERSON, peopleLoading = true) }
        val head = IdentityEditMode.HEAD in engine.capabilities().supportedModes
        background {
            val people = runCatching { host.people() }.getOrDefault(emptyList())
            onMain { update { it.copy(peopleLoading = false, people = people, headCapable = head) } }
        }
    }

    fun search(text: String) = update { it.copy(search = text.take(60)) }

    fun pickPerson(person: SwapPersonChoice) {
        val look = person.looks.firstOrNull { it.label.equals("Current", ignoreCase = true) }
            ?: person.looks.firstOrNull()
        appendNextBatch = state.versions.isNotEmpty()
        pendingPerson = person
        update { it.copy(person = person, lookId = look?.id, search = "", notice = null) }
        background {
            val setup = runCatching { host.setupStatus() }.getOrDefault(SwapSetupStatus.Ready)
            onMain {
                if (setup is SwapSetupStatus.Needed) update {
                    it.copy(stage = SwapStage.SETUP, setup = SwapSetupUi.Offer(setup.megabytes))
                } else continueWithPerson()
            }
        }
    }

    // ---- S6 one-time setup ----

    fun download() {
        val mb = when (val s = state.setup) {
            is SwapSetupUi.Offer -> s.megabytes
            is SwapSetupUi.Failed -> s.megabytes
            else -> return
        }
        update { it.copy(setup = SwapSetupUi.Running(mb, 0f), notice = null) }
        background {
            val ok = runCatching {
                host.download { p -> onMain { update { s -> s.copy(setup = SwapSetupUi.Running(mb, p.coerceIn(0f, 1f))) } } }
            }.getOrDefault(false)
            onMain {
                if (ok) { update { it.copy(setup = null) }; continueWithPerson() }
                else update { it.copy(setup = SwapSetupUi.Failed(mb)) }
            }
        }
    }

    fun notNow() {
        pendingPerson = null
        update {
            if (it.versions.isEmpty()) it.copy(stage = SwapStage.FINISHED, setup = null)
            else it.copy(stage = SwapStage.STUDIO, setup = null)
        }
    }

    private fun continueWithPerson() {
        val person = pendingPerson ?: return
        val lookId = state.lookId
        background {
            val refs = runCatching { host.references(person.profileId, lookId) }.getOrDefault(SwapReferences.TooFew(0))
            onMain {
                when (refs) {
                    is SwapReferences.TooFew -> update {
                        it.copy(stage = if (it.versions.isEmpty()) SwapStage.PERSON else SwapStage.STUDIO,
                            notice = SwapNotice.TooFewPhotos(person.displayName))
                    }
                    is SwapReferences.Ready -> {
                        references[person.profileId to lookId] = refs.set
                        runCatching { host.markUsed(person.profileId) }
                        val target = state.target
                        if (target != null && state.versions.isNotEmpty()) startBatch(target)
                        else findTarget()
                    }
                }
            }
        }
    }

    private fun findTarget() {
        background {
            val selection = runCatching { host.findFaces() }.getOrDefault(TargetFaceSelection.NoFace)
            val multi = selection is TargetFaceSelection.Ask && selection.faces.size > 1 ||
                runCatching { host.usableFaces().size > 1 }.getOrDefault(false)
            onMain {
                update { it.copy(canChangeFace = multi) }
                when (selection) {
                    TargetFaceSelection.NoFace -> update { it.copy(stage = SwapStage.TARGET, faces = emptyList(), notice = SwapNotice.NoFace) }
                    is TargetFaceSelection.TooSmall -> update {
                        it.copy(stage = SwapStage.TARGET, faces = listOf(selection.face), notice = SwapNotice.FaceSmall(selection.face))
                    }
                    is TargetFaceSelection.Auto -> startBatch(selection.face)
                    is TargetFaceSelection.Ask -> update { it.copy(stage = SwapStage.TARGET, faces = selection.faces) }
                }
            }
        }
    }

    // ---- S2 target ----

    fun tapFace(face: TargetFace) {
        update { it.copy(notice = null) }
        startBatch(face)
    }

    /** "This face is small": Try anyway. */
    fun tryAnyway() {
        val notice = state.notice as? SwapNotice.FaceSmall ?: return
        update { it.copy(notice = null) }
        startBatch(notice.face)
    }

    fun changeFace() {
        background {
            val faces = runCatching { host.usableFaces() }.getOrDefault(emptyList())
            onMain {
                if (faces.size > 1) {
                    appendNextBatch = true
                    pendingPerson = state.person
                    update { it.copy(stage = SwapStage.TARGET, faces = faces, overlay = SwapOverlay.None) }
                }
            }
        }
    }

    fun changePerson() = update { it.copy(stage = SwapStage.PERSON, overlay = SwapOverlay.None, notice = null) }

    // ---- S3/S4 versions ----

    private val basePreferences = IdentityPreferences()

    private fun startBatch(target: TargetFace) {
        val person = state.person ?: return
        val lookId = state.lookId
        val newBatch = if (state.versions.isEmpty() || appendNextBatch || target != state.target ||
            state.versions.lastOrNull()?.request?.profileId != person.profileId) ++batch else batch
        appendNextBatch = false
        pendingPerson = null
        val modes = if (state.headCapable) listOf(IdentityEditMode.FACE, IdentityEditMode.HEAD,
            IdentityEditMode.FACE, IdentityEditMode.HEAD) else List(FIRST_BATCH) { IdentityEditMode.FACE }
        val requests = modes.map { VersionRequest(person.profileId, lookId, target, it, preferencesFor(it, basePreferences)) }
        val firstNew = nextKey
        enqueue(newBatch, requests)
        update {
            it.copy(stage = SwapStage.STUDIO, target = target, notice = null, faces = emptyList(),
                save = SwapSave.Idle, announcement = null)
        }
        jumpTo = firstNew
        pump()
    }

    /** First version of the newest batch; selected as soon as it is ready. */
    private var jumpTo: Int? = null

    private fun preferencesFor(mode: IdentityEditMode, base: IdentityPreferences) =
        base.copy(hair = if (mode == IdentityEditMode.HEAD) HairChoice.USE_PERSONS else HairChoice.KEEP_ORIGINAL)

    private fun enqueue(batchNo: Int, requests: List<VersionRequest>): List<Int> {
        val made = requests.map { SwapVersion(nextKey++, batchNo, VersionStatus.PENDING, it) }
        update { it.copy(versions = it.versions + made) }
        made.forEach { queue.addLast(it.key) }
        return made.map { it.key }
    }

    /** "More": 2 new versions of the selected kind of change, meaningfully different. */
    fun more() {
        val base = state.selectedVersion?.request ?: state.versions.lastOrNull()?.request ?: return
        val modes = if (state.headCapable) listOf(IdentityEditMode.FACE, IdentityEditMode.HEAD)
        else listOf(IdentityEditMode.FACE, IdentityEditMode.FACE)
        enqueue(batch, modes.map { base.copy(mode = it, preferences = preferencesFor(it, basePreferences), refinedFrom = null) })
        pump()
    }

    /** A refine chip: 2 versions from the selected version. */
    fun refine(kind: SwapRefine) {
        val from = state.selectedVersion ?: return
        val r = from.request
        val (mode, prefs) = when (kind) {
            SwapRefine.MORE_NATURAL -> r.mode to r.preferences.copy(moreNatural = true)
            SwapRefine.STRONGER_LIKENESS -> r.mode to r.preferences.copy(moreNatural = false, keepExpression = false)
            SwapRefine.KEEP_EXPRESSION -> r.mode to r.preferences.copy(keepExpression = true)
            SwapRefine.HAIR -> {
                if (!state.headCapable) return
                val m = if (r.mode == IdentityEditMode.HEAD) IdentityEditMode.FACE else IdentityEditMode.HEAD
                m to preferencesFor(m, r.preferences)
            }
        }
        enqueue(batch, List(2) { r.copy(mode = mode, preferences = prefs, refinedFrom = from.key) })
        pump()
    }

    /** A look chip: 2 versions from another saved look of this person. */
    fun useLook(look: SwapLook) {
        val person = state.person ?: return
        // The chips are this person's looks, so the request must be this person's too: after Change person
        // the selection can still be an earlier person's version (#615 review).
        val mine = state.selectedVersion?.request?.takeIf { it.profileId == person.profileId }
            ?: state.versions.lastOrNull { it.request.profileId == person.profileId }?.request
        val target = mine?.target ?: state.target ?: return
        val base = mine ?: VersionRequest(person.profileId, look.id, target, IdentityEditMode.FACE,
            preferencesFor(IdentityEditMode.FACE, basePreferences))
        background {
            val key = person.profileId to look.id
            val refs = references[key] ?: when (val r = runCatching { host.references(person.profileId, look.id) }
                .getOrDefault(SwapReferences.TooFew(0))) {
                is SwapReferences.Ready -> r.set.also { references[key] = it }
                is SwapReferences.TooFew -> null
            }
            onMain {
                if (refs == null) update { it.copy(notice = SwapNotice.TooFewPhotos(person.displayName)) }
                else {
                    enqueue(batch, List(2) { base.copy(lookId = look.id, refinedFrom = null) })
                    pump()
                }
            }
        }
    }

    fun retry(key: Int) {
        val v = state.versions.firstOrNull { it.key == key && it.status == VersionStatus.FAILED } ?: return
        update { s -> s.copy(notice = null, versions = s.versions.map { if (it.key == key) it.copy(status = VersionStatus.PENDING) else it }) }
        queue.addLast(v.key)
        pump()
    }

    /** "Couldn't make versions of this photo": Try again. */
    fun tryAgain() {
        val failed = state.versions.filter { it.status == VersionStatus.FAILED && it.batch == batch }.map { it.key }
        update { s -> s.copy(notice = null, versions = s.versions.map {
            if (it.key in failed) it.copy(status = VersionStatus.PENDING) else it }) }
        failed.forEach { queue.addLast(it) }
        pump()
    }

    private fun pump() {
        if (pumping || closed) return
        val key = queue.removeFirstOrNull() ?: return
        val version = state.versions.firstOrNull { it.key == key } ?: return pump()
        pumping = true
        background {
            // Copy the finished file out of the session folder here, on the worker: a full-size copy on
            // the main thread would stall the studio while versions fill in (#615 review).
            val outcome = when (val made = make(version.request)) {
                is Made.Ok -> made.copy(kept = made.candidate.file?.let { runCatching { keeper.keep(key, it) }.getOrNull() })
                else -> made
            }
            onMain {
                pumping = false
                finish(key, outcome)
                pump()
            }
        }
    }

    private sealed interface Made {
        data class Ok(val candidate: IdentityCandidate, val kept: File? = null) : Made
        data class Failed(val kind: IdentityFailureKind?) : Made
        data object Cancelled : Made
    }

    private fun make(request: VersionRequest): Made {
        val refs = references[request.profileId to request.lookId] ?: return Made.Failed(IdentityFailureKind.NO_REFERENCES)
        val key = SessionKey(request.profileId, request.lookId, request.target, request.mode, request.preferences)
        return try {
            var session = sessions[key]
            if (session != null && session.candidates.size >= IdentityCandidateSession.MAX_CANDIDATES) session = null
            if (session == null) {
                session = IdentityCandidateSession.open(
                    engine, IdentitySessionInput(source, request.target, refs, request.mode, request.preferences),
                    workspaceRoot, EngineRoute.LOCAL, sessionSeed = sessionSeed()
                )
                sessions[key]?.let { old -> retired += old }
                sessions[key] = session
            }
            val result = session.more(1)
            when {
                result.status == IdentityRunStatus.CANCELLED -> Made.Cancelled
                result.newCandidates.isNotEmpty() -> Made.Ok(result.newCandidates.first())
                else -> Made.Failed(result.problem?.kind)
            }
        } catch (e: Exception) {
            Made.Failed(IdentityFailureKind.UNKNOWN)
        }
    }

    /** Full sessions replaced by a fresh one; kept open until close so their files stay readable. */
    private val retired = ArrayList<IdentityCandidateSession>()

    private fun finish(key: Int, made: Made) {
        when (made) {
            is Made.Ok -> {
                val kept = made.kept
                val candidate = if (kept != null) made.candidate.copy(file = kept) else made.candidate
                val image = if (kept != null) EditImage(kept.path, candidate.image.width, candidate.image.height, ImageFormat.PNG)
                else candidate.image
                update { s ->
                    val versions = s.versions.map { if (it.key == key) it.copy(status = VersionStatus.READY, candidate = candidate, image = image) else it }
                    val number = versions.indexOfFirst { it.key == key } + 1
                    val jump = jumpTo
                    val select = when {
                        jump != null && key >= jump -> { jumpTo = null; key }
                        else -> s.selected
                    }
                    s.copy(versions = versions, selected = select, announcement = "Version $number ready")
                }
            }
            is Made.Failed -> {
                if (made.kind == IdentityFailureKind.OUT_OF_MEMORY) {
                    val stopped = queue.toSet() + key
                    queue.clear()
                    update { s -> s.copy(notice = SwapNotice.OutOfMemory, versions = s.versions.map {
                        if (it.key in stopped) it.copy(status = VersionStatus.FAILED) else it }) }
                } else {
                    update { s -> s.copy(versions = s.versions.map { if (it.key == key) it.copy(status = VersionStatus.FAILED) else it }) }
                }
                val thisBatch = state.versions.filter { it.batch == batch }
                if (thisBatch.isNotEmpty() && thisBatch.all { it.status == VersionStatus.FAILED } && state.notice == null)
                    update { it.copy(notice = SwapNotice.AllFailed) }
            }
            Made.Cancelled -> update { s -> s.copy(versions = s.versions.filterNot { it.key == key }) }
        }
    }

    fun select(key: Int?) {
        if (key != null && state.versions.none { it.key == key && it.status == VersionStatus.READY }) return
        jumpTo = null
        update { it.copy(selected = key, overlay = SwapOverlay.None) }
    }

    fun openCompareAll() = update { it.copy(overlay = SwapOverlay.CompareAll) }

    /** Long press on a strip tile: split of the selected version and that tile. */
    fun split(key: Int?) {
        if (key != null && state.versions.none { it.key == key && it.status == VersionStatus.READY }) return
        update { s ->
            val overlay = when (val o = s.overlay) {
                is SwapOverlay.Split -> if (key == o.older || key == o.newer) o else SwapOverlay.Split(o.newer, key)
                else -> SwapOverlay.Split(s.selected, key)
            }
            s.copy(overlay = overlay)
        }
    }

    fun closeOverlay() = update { it.copy(overlay = SwapOverlay.None) }
    fun dismissNotice() = update { it.copy(notice = null) }

    // ---- ✕ ----

    /** ✕: no version ready → leave at once; one or more ready and nothing saved → ask. */
    fun close() {
        val s = state
        if (s.overlay != SwapOverlay.None) return closeOverlay()
        if (s.sheet != SwapSheet.NONE) return closeSheet()
        if (s.stage == SwapStage.PERSON && s.versions.isNotEmpty()) return update { it.copy(stage = SwapStage.STUDIO, notice = null) }
        if (s.stage == SwapStage.TARGET && s.versions.isNotEmpty()) return update { it.copy(stage = SwapStage.STUDIO, faces = emptyList(), notice = null) }
        if (s.ready.isNotEmpty() && s.save !is SwapSave.Saved) update { it.copy(discardDialog = true) }
        else finishFlow()
    }

    fun keepEditing() = update { it.copy(discardDialog = false) }
    fun discard() = finishFlow()

    private fun finishFlow() {
        update { it.copy(discardDialog = false, stage = SwapStage.FINISHED) }
        release()
    }

    // ---- S5 save ----

    fun save() {
        val v = state.selectedVersion ?: return
        val candidate = v.candidate ?: return
        if (state.save == SwapSave.Saving) return
        update { it.copy(save = SwapSave.Saving) }
        val parent = v.request.refinedFrom?.let { p -> state.versions.firstOrNull { it.key == p }?.candidate?.id }
        saver.save(candidate, v.request.profileId, v.request.lookId, parent) { outcome ->
            onMain {
                update {
                    it.copy(save = when (outcome) {
                        is SwapSaveOutcome.Saved -> SwapSave.Saved(outcome.handle)
                        SwapSaveOutcome.Failed -> SwapSave.Failed
                    })
                }
            }
        }
    }

    // ---- S7 photos used / S8 choose photos ----

    fun openPhotosUsed() {
        val person = state.person ?: (state.notice as? SwapNotice.TooFewPhotos)?.let { pendingPerson } ?: return
        update { it.copy(sheet = SwapSheet.PHOTOS_USED, photosUsed = null, notice = null, person = person) }
        background {
            val photos = runCatching { host.referencePhotos(person.profileId) }.getOrDefault(emptyList())
            onMain { update { it.copy(photosUsed = photos) } }
        }
    }

    fun openPhotosUsed(person: SwapPersonChoice) {
        update { it.copy(person = person) }
        openPhotosUsed()
    }

    fun removeReference(id: String) {
        val person = state.person ?: return
        update { s -> s.copy(photosUsed = s.photosUsed?.filterNot { it.id == id }) }
        references.keys.removeAll { it.first == person.profileId }
        background { runCatching { host.removeReference(person.profileId, id) } }
    }

    fun askForget() = update { it.copy(forgetDialog = true) }
    fun cancelForget() = update { it.copy(forgetDialog = false) }
    fun forget() {
        val person = state.person ?: return
        references.keys.removeAll { it.first == person.profileId }
        update { s -> s.copy(forgetDialog = false, sheet = SwapSheet.NONE, photosUsed = null,
            people = s.people.filterNot { it.profileId == person.profileId },
            person = if (s.versions.isEmpty()) null else s.person) }
        background { runCatching { host.forget(person.profileId) } }
    }

    /** "Choose photos" (S8) for a new person, or "+ Add photos" for [forProfile]. */
    fun choosePhotos(forProfile: String? = null) {
        update { it.copy(sheet = SwapSheet.CHOOSE_PHOTOS, pick = SwapPickState(forProfile), notice = null) }
        background {
            val photos = runCatching { host.photosToPick() }.getOrDefault(emptyList())
            onMain { update { s -> s.copy(pick = s.pick?.copy(photos = photos, loading = false)) } }
        }
    }

    fun togglePick(photo: SwapPickPhoto) {
        val pick = state.pick ?: return
        if (pick.chosen.any { it.first.id == photo.id }) {
            return update { it.copy(pick = pick.copy(chosen = pick.chosen.filterNot { c -> c.first.id == photo.id })) }
        }
        if (pick.chosen.size >= SwapPickState.MAX_PICK || photo.faces.isEmpty()) return
        if (photo.faces.size == 1) return update { it.copy(pick = pick.copy(chosen = pick.chosen + (photo to photo.faces[0]))) }
        // The first photo has nothing to match against, so a multi-face photo always asks.
        background {
            val match = if (pick.chosen.isEmpty()) null else runCatching { host.matchFace(photo, pick.chosen) }.getOrNull()
            onMain {
                val p = state.pick ?: return@onMain
                if (match != null && match in photo.faces.indices)
                    update { it.copy(pick = p.copy(chosen = p.chosen + (photo to photo.faces[match]))) }
                else update { it.copy(sheet = SwapSheet.WHICH_FACE, pick = p.copy(asking = photo)) }
            }
        }
    }

    fun answerWhichFace(face: FaceBox?) {
        val pick = state.pick ?: return
        val photo = pick.asking ?: return
        val chosen = if (face == null) pick.chosen else pick.chosen + (photo to face)
        update { it.copy(sheet = SwapSheet.CHOOSE_PHOTOS, pick = pick.copy(asking = null, chosen = chosen)) }
    }

    fun pickNext() {
        val pick = state.pick ?: return
        if (!pick.canContinue) return
        if (pick.forProfile != null) {
            val profile = pick.forProfile
            references.keys.removeAll { it.first == profile }
            update { it.copy(sheet = SwapSheet.PHOTOS_USED, pick = null, photosUsed = null) }
            background {
                runCatching { host.addReferences(profile, pick.chosen) }
                val photos = runCatching { host.referencePhotos(profile) }.getOrDefault(emptyList())
                onMain { update { it.copy(photosUsed = photos) } }
            }
        } else update { it.copy(pick = pick.copy(naming = true)) }
    }

    fun nameChanged(text: String) = update { s -> s.copy(pick = s.pick?.copy(name = text.take(60))) }

    fun pickDone() {
        val pick = state.pick ?: return
        update { it.copy(sheet = SwapSheet.NONE, pick = null) }
        background {
            val person = runCatching { host.createProfile(pick.chosen, pick.name.trim().ifEmpty { null }) }.getOrNull()
            onMain { if (person != null) pickPerson(person) }
        }
    }

    fun closeSheet() {
        val s = state
        when (s.sheet) {
            SwapSheet.WHICH_FACE -> answerWhichFace(null)
            SwapSheet.CHOOSE_PHOTOS -> {
                val pick = s.pick
                update {
                    if (pick?.naming == true) it.copy(pick = pick.copy(naming = false))
                    else if (pick?.forProfile != null) it.copy(sheet = SwapSheet.PHOTOS_USED, pick = null)
                    else it.copy(sheet = SwapSheet.NONE, pick = null)
                }
            }
            SwapSheet.PHOTOS_USED -> {
                update { it.copy(sheet = SwapSheet.NONE, photosUsed = null, forgetDialog = false) }
                // Changes to the photos take effect for the next versions.
            }
            SwapSheet.NONE -> Unit
        }
    }

    // ---- lifecycle ----

    fun release() {
        if (closed) return
        closed = true
        queue.clear()
        (sessions.values + retired).forEach { runCatching { it.close() } }
        sessions.clear(); retired.clear()
        runCatching { keeper.clear() }
    }

    /** Releases warm engines that have been idle (the host calls this on a timer). */
    fun releaseIdle() = (sessions.values).forEach { runCatching { it.releaseIfIdle() } }

    // ---- process death ----

    /** Small text snapshot for SavedStateHandle: ids, geometry and kept-file paths only. */
    fun snapshot(): String {
        val s = state
        val lines = ArrayList<String>()
        lines += "stage=${if (s.stage == SwapStage.FINISHED) "FINISHED" else if (s.ready.isEmpty()) "PERSON" else "STUDIO"}"
        s.person?.let { lines += "person=${enc(it.profileId)}" }
        lines += "look=${enc(s.lookId)}"
        lines += "selected=${s.selected ?: ""}"
        lines += "batch=$batch"
        lines += "next=$nextKey"
        s.target?.let { lines += "target=${face(it)}" }
        s.ready.forEach { v -> v.candidate?.file?.let { file -> lines += "v=" + version(v, v.candidate, file) } }
        return lines.joinToString("\n")
    }

    /** Rebuilds versions from [snapshot]. Never starts an engine run. */
    fun restore(snapshot: String) {
        val map = snapshot.lines().filter { '=' in it && !it.startsWith("v=") }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        val versions = snapshot.lines().filter { it.startsWith("v=") }.mapNotNull { runCatching { parseVersion(it.substring(2)) }.getOrNull() }
            .filter { it.candidate?.file?.isFile == true }
        batch = map["batch"]?.toIntOrNull() ?: 0
        nextKey = maxOf(map["next"]?.toIntOrNull() ?: 1, (versions.maxOfOrNull { it.key } ?: 0) + 1)
        val personId = map["person"]?.let { dec(it) }
        val selected = map["selected"]?.toIntOrNull()?.takeIf { k -> versions.any { it.key == k } }
        val target = map["target"]?.let { runCatching { parseFace(it) }.getOrNull() }
        val stage = if (map["stage"] == "FINISHED") SwapStage.FINISHED
            else if (versions.isEmpty()) SwapStage.PERSON else SwapStage.STUDIO
        val head = IdentityEditMode.HEAD in engine.capabilities().supportedModes
        update {
            it.copy(stage = stage, versions = versions, selected = selected ?: versions.firstOrNull()?.key,
                target = target, lookId = map["look"]?.let { l -> dec(l) }, headCapable = head)
        }
        background {
            val people = runCatching { host.people() }.getOrDefault(emptyList())
            onMain {
                val person = people.firstOrNull { it.profileId == personId }
                update { it.copy(peopleLoading = false, people = people, person = person) }
                // Refines and More after a restart need the person's references again.
                if (person != null) background {
                    versions.map { it.request.profileId to it.request.lookId }.distinct().forEach { key ->
                        val r = runCatching { host.references(key.first, key.second) }.getOrNull()
                        if (r is SwapReferences.Ready) onMain { references[key] = r.set }
                    }
                }
            }
        }
    }

    private fun enc(v: String?) = if (v == null) "" else URLEncoder.encode(v, "UTF-8")
    private fun dec(v: String) = if (v.isEmpty()) null else URLDecoder.decode(v, "UTF-8")

    private fun face(f: TargetFace) = listOf(f.box.left, f.box.top, f.box.right, f.box.bottom).joinToString(",") +
        ";" + f.landmarks.joinToString(",") { "${it.x}:${it.y}" }

    private fun parseFace(s: String): TargetFace {
        val (box, marks) = s.split(';')
        val b = box.split(',').map { it.toInt() }
        return TargetFace(FaceBox(b[0], b[1], b[2], b[3]),
            marks.split(',').map { p -> p.split(':').let { FacePoint(it[0].toFloat(), it[1].toFloat()) } })
    }

    private fun version(v: SwapVersion, c: IdentityCandidate, file: File): String {
        val r = v.request
        val p = r.preferences
        return listOf(
            v.key, v.batch, enc(r.profileId), enc(r.lookId), face(r.target), r.mode.name,
            p.hair.name, p.keepExpression, p.moreNatural, r.refinedFrom ?: "",
            enc(c.id), c.number, enc(file.path), c.image.width, c.image.height,
            enc(c.engineId), enc(c.engineVersion), c.route.name, c.variation.seed,
            c.variation.referenceIndices.joinToString(","), c.variation.blendStrength,
            c.fingerprint.luma.joinToString(","), c.mode.name
        ).joinToString("|")
    }

    private fun parseVersion(s: String): SwapVersion {
        val f = s.split('|')
        val request = VersionRequest(
            profileId = dec(f[2])!!, lookId = dec(f[3]), target = parseFace(f[4]),
            mode = IdentityEditMode.valueOf(f[5]),
            preferences = IdentityPreferences(HairChoice.valueOf(f[6]), f[7].toBoolean(), f[8].toBoolean()),
            refinedFrom = f[9].toIntOrNull()
        )
        val file = File(dec(f[12])!!)
        val image = EditImage(file.path, f[13].toInt(), f[14].toInt(), ImageFormat.PNG)
        val candidate = IdentityCandidate(
            id = dec(f[10])!!, number = f[11].toInt(), image = image, file = file,
            variation = IdentityVariation(f[18].toLong(), f[19].split(',').map { it.toInt() }, f[20].toDouble()),
            fingerprint = PerceptualFingerprint(f[21].split(',').map { it.toInt() }),
            engineId = dec(f[15])!!, engineVersion = dec(f[16])!!, route = EngineRoute.valueOf(f[17]),
            mode = IdentityEditMode.valueOf(f[22])
        )
        return SwapVersion(f[0].toInt(), f[1].toInt(), VersionStatus.READY, request, candidate, image)
    }

    companion object {
        const val FIRST_BATCH = 4
    }
}

/** Keeps finished versions in an app-private folder per flow; [clear] deletes it. */
class FolderVersionKeeper(private val dir: File) : SwapVersionKeeper {
    override fun keep(key: Int, file: File): File? {
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val target = File(dir, "version-$key.png")
        file.copyTo(target, overwrite = true)
        return target
    }
    override fun clear() { dir.deleteRecursively() }
}

/**
 * AndroidX wrapper: the machine survives rotation as a ViewModel and process death through a
 * text snapshot in [SavedStateHandle]. Engine work runs on one background thread, so versions
 * are made one at a time.
 */
class SwapPersonViewModel(private val handle: SavedStateHandle) : ViewModel() {
    var ui by mutableStateOf(SwapPersonUiState())
        private set
    private var machine: SwapPersonMachine? = null
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "atlas-swap-person").apply { isDaemon = true } }
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Idempotent: the first call builds the machine, restoring a snapshot after process death. */
    fun initialize(
        host: SwapPersonHost,
        engine: IdentityEditEngine,
        source: EditImage,
        cacheDir: File,
        saver: SwapPersonSaver
    ): SwapPersonMachine {
        machine?.let { return it }
        val flowId = handle.get<String>(KEY_FLOW) ?: java.util.UUID.randomUUID().toString().also { handle[KEY_FLOW] = it }
        val m = SwapPersonMachine(
            host = host, engine = engine, source = source,
            workspaceRoot = File(cacheDir, "atlas-identity"),
            saver = saver,
            keeper = FolderVersionKeeper(File(cacheDir, "atlas-identity-kept/$flowId")),
            worker = { executor.execute(it) },
            main = { mainHandler.post(it) },
            onState = { s ->
                ui = s
                machine?.let { handle[KEY_SNAPSHOT] = it.snapshot() }
            }
        )
        machine = m
        val saved = handle.get<String>(KEY_SNAPSHOT)
        if (saved == null) m.start() else m.restore(saved)
        return m
    }

    override fun onCleared() {
        machine?.release()
        executor.shutdownNow()
    }

    companion object {
        private const val KEY_FLOW = "swap-person-flow"
        private const val KEY_SNAPSHOT = "swap-person-snapshot"
    }
}
