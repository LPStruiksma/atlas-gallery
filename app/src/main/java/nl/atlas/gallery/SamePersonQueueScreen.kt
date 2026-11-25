package nl.atlas.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeople.cluster.FaceClusterId
import nl.atlas.gallery.featurepeopleui.PeopleClusterReviewState
import nl.atlas.gallery.featurepeopleui.QueueNameRequest
import nl.atlas.gallery.featurepeopleui.NativePersonAlbum
import nl.atlas.gallery.featurepeopleui.PersonNaming
import nl.atlas.gallery.featurepeopleui.captureQueueName
import nl.atlas.gallery.featurepeopleui.matchesNamingPair
import nl.atlas.gallery.featurepeopleui.PeopleClusterReviewCommand
import nl.atlas.gallery.featurepeopleui.PeopleReviewAction
import nl.atlas.gallery.featurepeopleui.samePersonQueueMixedSides
import nl.atlas.gallery.featurepeopleui.samePersonSkipFingerprint
import nl.atlas.gallery.featurepeopleui.SamePersonSkipPreferences
import nl.atlas.gallery.featurepeopleui.PeopleHomeSummary
import nl.atlas.gallery.featurepeopleui.PersonPageTarget
import nl.atlas.gallery.featurepeopleui.ReviewReferenceKey
import nl.atlas.gallery.featurepeopleui.SamePersonQueueCursor
import nl.atlas.gallery.featurepeopleui.SamePersonQueueItem
import nl.atlas.gallery.featurepeopleui.samePersonQueueAnswer
import nl.atlas.gallery.featurepeopleui.samePersonQueueStillAsks

/**
 * #580 People ⋮ → Same person?: every open question of the library, one at a
 * time, the one that would move the most faces first. Yes and No are the
 * answers a person's page sends (same reducer, same atomic commit through
 * [PeopleDiscoveryHost.review]); Skip is stored as a grant-scoped opaque
 * membership fingerprint preference and survives closing or relaunching the
 * screen until a membership change or Show skipped reset. Nothing is joined
 * without an answer.
 *
 * PERF: the list is read through a [SamePersonQueueCursor], so the first
 * question shows once the largest groups are scored, and the rest of the
 * list (and the open count) follows in the background. An answer moves on
 * at once: it is committed in tap order off the screen
 * ([SamePersonQueueCommits]), and the list on screen keeps
 * serving the next questions (leaving out any about a group a pending Yes
 * changes) until the answers settle and the list is read again from the
 * new snapshot. The next question's faces are cropped before it shows.
 */
@Composable
internal fun SamePersonQueueRoute(
    host: PeopleDiscoveryHost,
    people: PeopleHomeSummary?,
    onClose: () -> Unit
) {
    val version by host.changes.collectAsState()
    val state = rememberReviewState(host, version, people)
    val epoch = state?.permissionEpoch
    val skipScope = epoch?.let(host::stableSamePersonSkipScope)
    val context = LocalContext.current
    val skipPreferences = remember(context) { SamePersonSkipPreferences.forApp(context) }
    var cursor by remember { mutableStateOf(state?.let(SamePersonQueueMemo::cursor)) }
    // How many of this screen's answers [cursor]'s snapshot already holds.
    var cursorSeq by remember { mutableIntStateOf(0) }
    var found by remember { mutableStateOf(cursor?.items.orEmpty()) }
    var listDone by remember { mutableStateOf(cursor?.done == true) }
    // Restored for the currently authorized grant before rendering questions.
    var skipped by remember(skipScope) {
        mutableStateOf(skipScope?.let(skipPreferences::read).orEmpty())
    }
    var mixedBusy by remember { mutableStateOf(false) }
    var undoBusy by remember { mutableStateOf(false) }
    var nameRequest by remember { mutableStateOf<QueueNameRequest?>(null) }
    var nameDraft by remember { mutableStateOf("") }
    var nameCandidate by remember { mutableStateOf<NativePersonAlbum?>(null) }
    var nameBusy by remember { mutableStateOf(false) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var queueNameError by remember { mutableStateOf<String?>(null) }
    var refreshFrom by remember { mutableStateOf<String?>(null) }
    var preferredPair by remember { mutableStateOf<Set<PersonPageTarget>?>(null) }
    var undoNamePair by remember { mutableStateOf<Set<PersonPageTarget>?>(null) }
    var lastMixedGeneration by remember { mutableStateOf<String?>(null) }
    var lastMixedQuestion by remember { mutableStateOf<String?>(null) }
    // Every answer of this screen in tap order; the first `resolved` are committed (or refused).
    var answers by remember { mutableStateOf(emptyList<SamePersonQueueAnswer>()) }
    var resolved by remember { mutableIntStateOf(0) }
    val skipSnapshot = cursor?.state?.takeIf {
        it.permissionEpoch == epoch && skipScope != null && host.scopeCurrent(it.permissionEpoch)
    }
    val skipTokenFor: (SamePersonQueueItem) -> String? = { question ->
        if (skipSnapshot != null && skipScope != null)
            samePersonSkipFingerprint(skipSnapshot, question, skipScope)
        else null
    }
    val visible = remember(found, skipped, answers, cursorSeq, lastMixedQuestion, skipSnapshot, skipScope) {
        val notInList = answers.drop(cursorSeq)
        found.filter { item ->
            val token = skipTokenFor(item)
            token != null && token !in skipped && item.key != lastMixedQuestion &&
                notInList.none { it.hides(item) }
        }
    }
    // Do not offer a question from the pre-naming snapshot. Prefer the
    // transformed pair, if it still asks, rather than jumping to a different
    // question just because its ranking changed.
    val refreshing = refreshFrom != null &&
        (cursor?.state?.sourceGeneration == refreshFrom || cursor?.state !== state)
    val item = if (refreshing) null else preferredPair?.let { pair ->
        visible.firstOrNull { it.matchesNamingPair(pair) }
            ?: if (listDone) visible.firstOrNull() else null
    } ?: if (preferredPair == null && !refreshing) visible.firstOrNull() else null
    var markSubject by remember(item?.key) { mutableStateOf(false) }
    var markOther by remember(item?.key) { mutableStateOf(false) }
    val ahead by rememberUpdatedState(visible.size)

    // A new snapshot (after answers, or a new publication) is read again once
    // the answers settle; the list on screen keeps serving meanwhile and is
    // replaced only when the new list has its first questions.
    LaunchedEffect(ReviewReferenceKey(state)) {
        val snapshot = state ?: return@LaunchedEffect
        if (cursor?.state === snapshot) return@LaunchedEffect
        if (answers.isEmpty()) SamePersonQueueMemo.cursor(snapshot)?.let {
            cursor = it; cursorSeq = 0; found = it.items; listDone = it.done
            return@LaunchedEffect
        }
        if (cursor != null) {
            while (resolved < answers.size && ahead >= SAME_PERSON_QUEUE_AHEAD) delay(100)
            if (ahead >= SAME_PERSON_QUEUE_AHEAD) delay(SAME_PERSON_QUEUE_SETTLE_MS)
        }
        val seq = resolved
        val next = SamePersonQueueCursor(host.liveReview(snapshot.permissionEpoch) ?: snapshot)
        withContext(Dispatchers.Default) {
            val worker = currentCoroutineContext()
            while (!next.done && next.items.isEmpty()) next.step { worker.ensureActive() }
        }
        if (seq == answers.size) SamePersonQueueMemo.keep(next)
        cursor = next
        cursorSeq = seq
        found = next.items
        listDone = next.done
    }
    LaunchedEffect(refreshFrom, cursor, ReviewReferenceKey(state)) {
        if (refreshFrom != null && cursor?.state === state &&
            cursor?.state?.sourceGeneration != refreshFrom) refreshFrom = null
    }
    // Read the rest of the list in the background, publishing as it grows.
    LaunchedEffect(cursor) {
        val reading = cursor ?: return@LaunchedEffect
        var published = SystemClock.uptimeMillis()
        while (!reading.done) {
            withContext(Dispatchers.Default) {
                val worker = currentCoroutineContext()
                reading.step { worker.ensureActive() }
            }
            val now = SystemClock.uptimeMillis()
            if (found.isEmpty() || now - published >= 250) {
                found = reading.items
                published = now
            }
        }
        found = reading.items
        listDone = true
    }
    // Crop the next question's faces before it shows.
    val upcoming = visible.getOrNull(1)
    LaunchedEffect(upcoming?.key, epoch) {
        val next = upcoming ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            for (face in next.subjectSamples + next.otherSamples) host.faceCrop(epoch, face.localHex)
        }
    }
    // The last finished count, less the answers since: shown while a new list is read.
    var counted by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(listDone, cursor) {
        if (listDone) counted = (found.size - answers.drop(cursorSeq).size) to answers.size
    }
    val pendingMarked = lastMixedQuestion != null && found.any { it.key == lastMixedQuestion }
    val open = if (skipSnapshot == null || refreshing) 0 else counted?.let { (n, at) ->
        maxOf(0, n - (answers.size - at) - if (pendingMarked) 1 else 0)
    } ?: 0
    val canUndoMixed = skipSnapshot != null && lastMixedGeneration != null &&
        state?.sourceGeneration == lastMixedGeneration && (state?.undoHistory?.isNotEmpty() == true) &&
        !mixedBusy && !undoBusy && resolved == answers.size
    fun closeName() {
        if (!nameBusy) {
            nameRequest = null
            nameCandidate = null
            nameError = null
        }
    }
    fun openName(subject: Boolean) {
        val asked = item ?: return
        val scope = skipScope ?: return
        if (mixedBusy || undoBusy || nameBusy || nameRequest != null ||
            resolved != answers.size || cursor?.state !== state) return
        val live = epoch?.let(host::liveReview)
        val captured = captureQueueName(live, asked, subject,
            epoch?.let(host::stableSamePersonSkipScope)?.takeIf { it == scope })
        if (captured == null || live !== state) {
            queueNameError = "Group changed. Try again."
            return
        }
        queueNameError = null
        nameRequest = captured
        nameDraft = captured.facts.name.orEmpty()
        nameCandidate = null
        nameError = null
    }
    fun saveName(typed: String, into: NativePersonAlbum? = null) {
        val request = nameRequest ?: return
        if (nameBusy) return
        nameDraft = typed
        nameError = null
        nameBusy = true
        SamePersonQueueCommits.name(host, request, typed, into) { result ->
            nameBusy = false
            when (result) {
                is QueueNameResult.Offer -> nameCandidate = result.person
                is QueueNameResult.Saved -> {
                    lastMixedGeneration = result.generation
                    undoNamePair = setOf(request.question.subject,
                        PersonPageTarget.Unnamed(request.question.other))
                    preferredPair = request.pairAfter(result.action).takeIf { pair ->
                        pair.size == 2 && pair.any { it is PersonPageTarget.Unnamed }
                    }
                    counted = null
                    refreshFrom = request.generation
                    closeName()
                }
                QueueNameResult.Refused -> nameError = "Couldn’t save name. Try again."
                QueueNameResult.Stale -> nameError = "Group changed. Return to the question."
            }
        }
    }
    Dialog(onDismissRequest = { if (nameRequest == null && !nameBusy) onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        SamePersonQueueScreen(
            item = item,
            open = open,
            loaded = people == null ||
                (!refreshing && listDone && resolved == answers.size && cursor?.state === state),
            skippedSome = found.any { question ->
                val token = skipTokenFor(question)
                token != null && token in skipped
            },
            onShowSkipped = {
                if (nameRequest == null && !nameBusy && epoch != null && skipScope != null &&
                    host.stableSamePersonSkipScope(epoch) == skipScope) {
                    skipPreferences.clear(skipScope)
                    skipped = emptySet()
                }
            },
            markSubject = markSubject,
            markOther = markOther,
            actionBusy = mixedBusy || undoBusy || nameBusy || nameRequest != null,
            nameEnabled = resolved == answers.size && cursor?.state === state,
            onNameSubject = { openName(true) },
            onNameOther = { openName(false) },
            nameError = queueNameError,
            canUndoMixed = canUndoMixed && nameRequest == null && !nameBusy,
            onToggleSubject = {
                if (!mixedBusy && !undoBusy && nameRequest == null && !nameBusy) markSubject = !markSubject
            },
            onToggleOther = {
                if (!mixedBusy && !undoBusy && nameRequest == null && !nameBusy) markOther = !markOther
            },
            onMarkMixed = {
                val asked = item
                val liveEpoch = epoch
                val fingerprint = asked?.let { skipTokenFor(it) }
                if (asked != null && liveEpoch != null && skipScope != null &&
                    fingerprint != null &&
                    (markSubject || markOther) && !mixedBusy && !undoBusy &&
                    nameRequest == null && !nameBusy && resolved == answers.size) {
                    mixedBusy = true
                    SamePersonQueueCommits.mark(host, liveEpoch, asked, markSubject, markOther,
                        skipScope, fingerprint) { generation ->
                        mixedBusy = false
                        if (generation != null) {
                            undoNamePair = null
                            preferredPair = null
                            lastMixedGeneration = generation
                            lastMixedQuestion = asked.key
                        }
                    }
                }
            },
            onUndoMixed = {
                val generation = lastMixedGeneration
                val liveEpoch = epoch
                if (generation != null && liveEpoch != null && canUndoMixed &&
                    nameRequest == null && !nameBusy) {
                    undoBusy = true
                    SamePersonQueueCommits.undoMixed(host, liveEpoch, generation) { success ->
                        undoBusy = false
                        if (success && undoNamePair != null) {
                            counted = null
                            refreshFrom = generation
                            preferredPair = undoNamePair
                        }
                        undoNamePair = null
                        // Even a refused Undo must not leave a queue question hidden.
                        lastMixedGeneration = null
                        lastMixedQuestion = null
                    }
                }
            },
            face = { PersonFaceImage(host, epoch, it) },
            onAnswer = { same ->
                val asked = item
                if (asked != null && epoch != null && !mixedBusy && !undoBusy &&
                    nameRequest == null && !nameBusy) {
                    preferredPair = null
                    queueNameError = null
                    val answer = SamePersonQueueAnswer(epoch, asked, same)
                    answers = answers + answer
                    SamePersonQueueCommits.submit(host, answer) { resolved++ }
                }
            },
            onSkip = {
                val asked = item
                val token = asked?.let { skipTokenFor(it) }
                if (epoch != null && skipScope != null && token != null &&
                    host.stableSamePersonSkipScope(epoch) == skipScope &&
                    !mixedBusy && !undoBusy && nameRequest == null && !nameBusy) {
                    preferredPair = null
                    queueNameError = null
                    skipped = skipPreferences.add(skipScope, token)
                }
            },
            onClose = onClose
        )
    }
    nameRequest?.let { request ->
        // The preview and candidates are no longer displayed when the grant
        // or captured snapshot changes. A stale dialog can only be closed.
        val current = request.current(state, skipScope) &&
            host.scopeCurrent(request.epoch) &&
            request.current(host.liveReview(request.epoch),
                host.stableSamePersonSkipScope(request.epoch))
        val candidate = nameCandidate
        if (!current && !nameBusy) {
            androidx.compose.material3.AlertDialog(onDismissRequest = { closeName() },
                title = { Text("Group changed") },
                text = { Text("Return to the question and try again.") },
                confirmButton = { TextButton(onClick = { closeName() }) { Text("Return") } })
        } else if (candidate == null) {
            key(request) {
                var sourceReady by remember { mutableStateOf(false) }
                var sourceFailed by remember { mutableStateOf(false) }
                var sourceAttempt by remember { mutableIntStateOf(0) }
                LaunchedEffect(request, current, sourceAttempt) {
                    sourceReady = false
                    sourceFailed = false
                    if (current) {
                        sourceReady = withContext(Dispatchers.IO) {
                            request.samples.firstOrNull()?.let {
                                host.faceCrop(request.epoch, it.localHex) != null
                            } == true
                        } && host.scopeCurrent(request.epoch)
                        sourceFailed = !sourceReady
                    }
                }
                PersonNameDialog(initial = nameDraft,
                    suggestions = { typed ->
                        if (current) request.suggestions(state, skipScope, typed) else emptyList()
                    },
                    suggestionPortrait = { album ->
                        PersonFaceImage(host, request.epoch.takeIf { current },
                            album.observations.firstOrNull())
                    },
                    onDismiss = { closeName() },
                    onSave = { saveName(it) },
                    sourcePreview = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                                request.samples.forEach { sample ->
                                Box(Modifier.size(72.dp).clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                    .testTag("atlas-same-queue-name-source"),
                                    contentAlignment = Alignment.Center) {
                                    if (current) PersonFaceImage(host, request.epoch, sample)
                                }
                                }
                            }
                            if (current) Text(request.facts.name ?: "Unnamed group",
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    },
                    busy = nameBusy, valid = current, editing = request.facts.name != null,
                    loading = !sourceReady && !sourceFailed,
                    error = nameError ?: if (sourceFailed) "Face unavailable. Try again." else null,
                    onRetry = { typed ->
                        if (sourceFailed) sourceAttempt++ else saveName(typed)
                    })
            }
        } else if (current) {
            var portraitsReady by remember(request, candidate) { mutableStateOf(false) }
            var portraitAttempt by remember(request, candidate) { mutableIntStateOf(0) }
            var portraitFailed by remember(request, candidate) { mutableStateOf(false) }
            LaunchedEffect(request, candidate, portraitAttempt) {
                portraitsReady = false
                portraitFailed = false
                val ready = withContext(Dispatchers.IO) {
                    request.samples.firstOrNull()?.let {
                        host.faceCrop(request.epoch, it.localHex) != null
                    } == true &&
                        candidate.observations.firstOrNull()?.let {
                            host.faceCrop(request.epoch, it.localHex) != null
                        } == true
                }
                portraitsReady = ready && host.scopeCurrent(request.epoch)
                portraitFailed = !portraitsReady
            }
            PersonMergeConfirm(request.facts.name, candidate,
                onDismiss = { if (!nameBusy) { nameCandidate = null; nameError = null } },
                onMerge = { saveName(nameDraft, candidate) },
                portraits = {
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(request.samples.firstOrNull() to
                            (request.facts.name ?: "Unnamed group"),
                            candidate.observations.firstOrNull() to candidate.name)
                            .forEachIndexed { index, (sample, label) ->
                                Column(Modifier.weight(1f),
                                    horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(Modifier.size(112.dp).clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                        .testTag("atlas-same-queue-merge-face-$index"),
                                        contentAlignment = Alignment.Center) {
                                        PersonFaceImage(host, request.epoch, sample)
                                    }
                                    Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                    }
                }, enabled = portraitsReady || portraitFailed, busy = nameBusy,
                error = nameError ?: if (portraitFailed) "Face unavailable. Try again." else null,
                onRetry = {
                    if (portraitFailed) portraitAttempt++ else saveName(nameDraft, candidate)
                })
        } else {
            // A write is in flight; don't expose captured labels or pixels.
            androidx.compose.material3.AlertDialog(onDismissRequest = {},
                title = { Text("Saving name…") }, confirmButton = {})
        }
    }
}

/** One answer of the queue screen, waiting for or past its commit. */
internal class SamePersonQueueAnswer(
    val epoch: String,
    val item: SamePersonQueueItem,
    val same: Boolean
) {
    // A yes changes both groups it joins: questions about them wait for the new snapshot.
    private val changed: Set<FaceClusterId> = if (!same) emptySet() else buildSet {
        add(item.other)
        (item.subject as? PersonPageTarget.Unnamed)?.let { add(it.cluster) }
    }

    /** True when [question] may not be asked from a snapshot without this answer. */
    fun hides(question: SamePersonQueueItem): Boolean =
        question.key == item.key || question.other in changed ||
            (question.subject as? PersonPageTarget.Unnamed)?.cluster in changed
}

/**
 * The queue's answers, committed one at a time in tap order on one
 * background thread that outlives the screen, so closing the list right
 * after a tap never drops the answer. Each answer is sent only while the
 * live snapshot still asks its question unchanged ([samePersonQueueStillAsks]);
 * [PeopleDiscoveryHost.review] then applies it atomically or refuses it.
 */
internal sealed interface QueueNameResult {
    data class Offer(val person: NativePersonAlbum) : QueueNameResult
    data class Saved(val generation: String, val action: PeopleReviewAction) : QueueNameResult
    data object Refused : QueueNameResult
    data object Stale : QueueNameResult
}

internal object SamePersonQueueCommits {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "atlas-same-person").apply { isDaemon = true }
    }

    /** Name, rename or assign via the same reducer and atomic host commit.
     * A suggestion only offers a merge; the second explicit tap confirms it.
     */
    fun name(host: PeopleDiscoveryHost, request: QueueNameRequest, typed: String,
             into: NativePersonAlbum?, done: (QueueNameResult) -> Unit) {
        worker.execute {
            val result = try {
                val live = host.liveReview(request.epoch)
                val scope = host.stableSamePersonSkipScope(request.epoch)
                if (live == null || !host.scopeCurrent(request.epoch) ||
                    !request.current(live, scope)) QueueNameResult.Stale
                else if (request.samples.firstOrNull()?.let {
                        host.faceCrop(request.epoch, it.localHex) != null
                    } != true) QueueNameResult.Refused
                else {
                    val naming = request.naming(live, scope, typed)
                    val action = if (into == null) (naming as? PersonNaming.Apply)?.action
                        else request.confirmedAction(live, scope, typed, into)?.takeIf {
                            into.observations.firstOrNull()?.let { face ->
                                host.faceCrop(request.epoch, face.localHex) != null
                            } == true
                        }
                    if (into == null && naming is PersonNaming.OfferMerge)
                        QueueNameResult.Offer(naming.into)
                    else if (action != null && host.review(PeopleClusterReviewCommand(live, action))) {
                        val generation = host.liveReview(request.epoch)?.sourceGeneration
                        if (generation == null) QueueNameResult.Stale
                        else QueueNameResult.Saved(generation, action)
                    } else QueueNameResult.Refused
                }
            } catch (_: RuntimeException) { QueueNameResult.Refused }
            main.post { done(result) }
        }
    }

    /** Serialized with Yes/No to prevent races with earlier questions. */
    fun mark(
        host: PeopleDiscoveryHost, epoch: String, item: SamePersonQueueItem,
        subject: Boolean, other: Boolean, expectedScope: String,
        expectedFingerprint: String, done: (String?) -> Unit
    ) {
        worker.execute {
            val generation = try {
                val live = host.liveReview(epoch)
                    ?: host.reviewState(epoch)?.let { host.liveReview(epoch) }
                val action = samePersonQueueMixedSides(item, subject, other)
                if (live != null && action != null && samePersonQueueStillAsks(live, item) &&
                    host.stableSamePersonSkipScope(epoch) == expectedScope &&
                    samePersonSkipFingerprint(live, item, expectedScope) == expectedFingerprint &&
                    host.review(PeopleClusterReviewCommand(live, action))) {
                    host.liveReview(epoch)?.sourceGeneration
                } else null
            } catch (_: RuntimeException) { null }
            main.post { done(generation) }
        }
    }

    /** Only Undo the latest acknowledged change of this queue session. */
    fun undoMixed(
        host: PeopleDiscoveryHost, epoch: String, generation: String,
        done: (Boolean) -> Unit
    ) {
        worker.execute {
            val success = try {
                val live = host.liveReview(epoch)
                live != null && live.sourceGeneration == generation &&
                    live.undoHistory.isNotEmpty() &&
                    host.review(PeopleClusterReviewCommand(live, PeopleReviewAction.Undo))
            } catch (_: RuntimeException) { false }
            main.post { done(success) }
        }
    }

    fun submit(host: PeopleDiscoveryHost, answer: SamePersonQueueAnswer, done: () -> Unit) {
        worker.execute {
            try {
                val live = host.liveReview(answer.epoch)
                    ?: host.reviewState(answer.epoch)?.let { host.liveReview(answer.epoch) }
                if (live != null && samePersonQueueStillAsks(live, answer.item)) {
                    host.review(PeopleClusterReviewCommand(live,
                        samePersonQueueAnswer(answer.item, answer.same)))
                }
            } catch (_: RuntimeException) {
                // Refused like a stale snapshot: the question is asked again.
            } finally {
                // Screen state is only written on the main thread.
                main.post(done)
            }
        }
    }
}

/** Answers for one review snapshot, kept while the app runs (the list is costly). */
internal object SamePersonQueueMemo {
    private var kept: SamePersonQueueCursor? = null

    @Synchronized fun cursor(state: PeopleClusterReviewState): SamePersonQueueCursor? =
        kept?.takeIf { it.state === state }

    @Synchronized fun keep(cursor: SamePersonQueueCursor) { kept = cursor }
}

/** Questions kept ready beyond the one on screen before the list is read again. */
private const val SAME_PERSON_QUEUE_AHEAD = 3
/** Quiet time after the last answer before the list is read again. */
private const val SAME_PERSON_QUEUE_SETTLE_MS = 1_500L

@Composable
internal fun SamePersonQueueScreen(
    item: SamePersonQueueItem?,
    open: Int,
    loaded: Boolean,
    skippedSome: Boolean,
    onShowSkipped: () -> Unit,
    markSubject: Boolean,
    markOther: Boolean,
    actionBusy: Boolean,
    canUndoMixed: Boolean,
    onToggleSubject: () -> Unit,
    onToggleOther: () -> Unit,
    onMarkMixed: () -> Unit,
    onUndoMixed: () -> Unit,
    face: @Composable (NativeFaceObservationId) -> Unit,
    onAnswer: (Boolean) -> Unit,
    onSkip: () -> Unit,
    onClose: () -> Unit,
    nameEnabled: Boolean = true,
    onNameSubject: (() -> Unit)? = null,
    onNameOther: (() -> Unit)? = null,
    nameError: String? = null
) {
    Surface(color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize().testTag("atlas-same-queue")) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose, enabled = !actionBusy,
                    modifier = Modifier.testTag("atlas-same-queue-close")) {
                    Icon(Icons.Filled.Close, contentDescription = "Close")
                }
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text("Same person?", style = MaterialTheme.typography.titleMedium)
                    if (open > 0) Text(
                        if (open == 1) "1 open" else "%,d open".format(Locale.US, open),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("atlas-same-queue-open"))
                }
                if (canUndoMixed) TextButton(onClick = onUndoMixed,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("atlas-same-queue-undo")) {
                    Text("Undo")
                }
            }
            if (nameError != null) Text(nameError, Modifier.padding(horizontal = 24.dp),
                color = MaterialTheme.colorScheme.error)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when {
                    item != null -> Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SamePersonSide(item.subjectName ?: "Unnamed person",
                            faceCount(item.subjectFaces), item.subjectSamples, face,
                            "atlas-same-queue-subject", "atlas-same-queue-subject-name",
                            if (item.subjectName == null) "Name first group" else "Edit name ${item.subjectName}",
                            nameEnabled && !actionBusy, onNameSubject)
                        TextButton(onClick = onToggleSubject, enabled = !actionBusy,
                            modifier = Modifier.heightIn(min = 48.dp)
                                .testTag("atlas-same-queue-subject-mixed")) {
                            Text(if (markSubject) "Not one person (selected)" else "Not one person")
                        }
                        Text("and", style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SamePersonSide("Unnamed group", faceCount(item.otherFaces),
                            item.otherSamples, face, "atlas-same-queue-other",
                            "atlas-same-queue-other-name", "Name second group",
                            nameEnabled && !actionBusy, onNameOther)
                        TextButton(onClick = onToggleOther, enabled = !actionBusy,
                            modifier = Modifier.heightIn(min = 48.dp)
                                .testTag("atlas-same-queue-other-mixed")) {
                            Text(if (markOther) "Not one person (selected)" else "Not one person")
                        }
                        if (markSubject || markOther) TextButton(onClick = onMarkMixed,
                            enabled = !actionBusy,
                            modifier = Modifier.heightIn(min = 48.dp)
                                .testTag("atlas-same-queue-confirm-mixed")) {
                            Text(if (markSubject && markOther) "Mark both groups"
                                else "Mark this group")
                        }
                    }
                    !loaded -> CircularProgressIndicator(
                        Modifier.size(32.dp).testTag("atlas-same-queue-loading"))
                    else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (skippedSome) "No unskipped questions."
                            else "No open questions.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(32.dp).testTag("atlas-same-queue-empty"))
                        if (skippedSome) TextButton(onClick = onShowSkipped,
                            modifier = Modifier.heightIn(min = 48.dp)
                                .testTag("atlas-same-queue-show-skipped")) {
                            Text("Show skipped")
                        }
                    }
                }
            }
            if (item != null) Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = { onAnswer(false) }, enabled = !actionBusy,
                    modifier = Modifier.weight(1f).testTag("atlas-same-queue-no")) { Text("No") }
                TextButton(onClick = onSkip, enabled = !actionBusy,
                    modifier = Modifier.testTag("atlas-same-queue-skip")) { Text("Skip") }
                Button(onClick = { onAnswer(true) }, enabled = !actionBusy,
                    modifier = Modifier.weight(1f).testTag("atlas-same-queue-yes")) { Text("Yes") }
            }
        }
    }
}

private fun faceCount(n: Int): String =
    if (n == 1) "1 face" else "%,d faces".format(Locale.US, n)

/** One side of the question: who, how many faces, and a few of them. */
@Composable
private fun SamePersonSide(
    title: String,
    count: String,
    samples: List<NativeFaceObservationId>,
    face: @Composable (NativeFaceObservationId) -> Unit,
    tag: String,
    nameTag: String,
    nameDescription: String,
    nameEnabled: Boolean,
    onName: (() -> Unit)?
) {
    Column(Modifier.fillMaxWidth().testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (onName != null) IconButton(onClick = onName, enabled = nameEnabled,
                modifier = Modifier.size(48.dp).testTag(nameTag)) {
                Icon(Icons.Filled.Edit, contentDescription = nameDescription)
            }
        }
        Text(count, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Keyed by face, so a slot never shows the previous question's crop.
            for (sample in samples) key(sample) {
                Box(
                    Modifier.size(72.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center
                ) { face(sample) }
            }
        }
    }
}
