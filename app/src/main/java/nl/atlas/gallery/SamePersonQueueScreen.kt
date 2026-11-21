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
 * [PeopleDiscoveryHost.review]); Skip only moves on and is forgotten when the
 * screen closes. Nothing is joined without an answer.
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
    val context = LocalContext.current
    val skipPreferences = remember(context) { SamePersonSkipPreferences.forApp(context) }
    var cursor by remember { mutableStateOf(state?.let(SamePersonQueueMemo::cursor)) }
    // How many of this screen's answers [cursor]'s snapshot already holds.
    var cursorSeq by remember { mutableIntStateOf(0) }
    var found by remember { mutableStateOf(cursor?.items.orEmpty()) }
    var listDone by remember { mutableStateOf(cursor?.done == true) }
    // Restored for the currently authorized grant before rendering questions.
    var skipped by remember(epoch) {
        mutableStateOf(epoch?.let(skipPreferences::read).orEmpty())
    }
    var mixedBusy by remember { mutableStateOf(false) }
    var undoBusy by remember { mutableStateOf(false) }
    var lastMixedGeneration by remember { mutableStateOf<String?>(null) }
    var lastMixedQuestion by remember { mutableStateOf<String?>(null) }
    // Every answer of this screen in tap order; the first `resolved` are committed (or refused).
    var answers by remember { mutableStateOf(emptyList<SamePersonQueueAnswer>()) }
    var resolved by remember { mutableIntStateOf(0) }
    val skipSnapshot = cursor?.state?.takeIf { it.permissionEpoch == epoch }
    val visible = remember(found, skipped, answers, cursorSeq, lastMixedQuestion, skipSnapshot) {
        val notInList = answers.drop(cursorSeq)
        found.filter { item ->
            val token = skipSnapshot?.let { samePersonSkipFingerprint(it, item) }
            token != null && token !in skipped && item.key != lastMixedQuestion &&
                notInList.none { it.hides(item) }
        }
    }
    val item = visible.firstOrNull()
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
    val open = counted?.let { (n, at) ->
        maxOf(0, n - (answers.size - at) - if (pendingMarked) 1 else 0)
    } ?: 0
    val canUndoMixed = lastMixedGeneration != null &&
        state?.sourceGeneration == lastMixedGeneration && state.undoHistory.isNotEmpty() &&
        !mixedBusy && !undoBusy && resolved == answers.size
    Dialog(onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        SamePersonQueueScreen(
            item = item,
            open = open,
            loaded = people == null ||
                (listDone && resolved == answers.size && cursor?.state === state),
            skippedSome = found.any { question ->
                skipSnapshot?.let { samePersonSkipFingerprint(it, question) } in skipped
            },
            onShowSkipped = {
                epoch?.let { skipPreferences.clear(it); skipped = emptySet() }
            },
            markSubject = markSubject,
            markOther = markOther,
            actionBusy = mixedBusy || undoBusy,
            canUndoMixed = canUndoMixed,
            onToggleSubject = { if (!mixedBusy && !undoBusy) markSubject = !markSubject },
            onToggleOther = { if (!mixedBusy && !undoBusy) markOther = !markOther },
            onMarkMixed = {
                val asked = item
                val liveEpoch = epoch
                if (asked != null && liveEpoch != null && (markSubject || markOther) &&
                    !mixedBusy && !undoBusy && resolved == answers.size) {
                    mixedBusy = true
                    SamePersonQueueCommits.mark(host, liveEpoch, asked, markSubject, markOther) { generation ->
                        mixedBusy = false
                        if (generation != null) {
                            lastMixedGeneration = generation
                            lastMixedQuestion = asked.key
                        }
                    }
                }
            },
            onUndoMixed = {
                val generation = lastMixedGeneration
                val liveEpoch = epoch
                if (generation != null && liveEpoch != null && canUndoMixed) {
                    undoBusy = true
                    SamePersonQueueCommits.undoMixed(host, liveEpoch, generation) { success ->
                        undoBusy = false
                        lastMixedGeneration = null
                        if (success) lastMixedQuestion = null
                    }
                }
            },
            face = { PersonFaceImage(host, epoch, it) },
            onAnswer = { same ->
                val asked = item
                if (asked != null && epoch != null && !mixedBusy && !undoBusy) {
                    val answer = SamePersonQueueAnswer(epoch, asked, same)
                    answers = answers + answer
                    SamePersonQueueCommits.submit(host, answer) { resolved++ }
                }
            },
            onSkip = {
                val asked = item
                val token = if (asked != null) skipSnapshot?.let {
                    samePersonSkipFingerprint(it, asked)
                } else null
                if (epoch != null && token != null && !mixedBusy && !undoBusy) {
                    skipped = skipPreferences.add(epoch, token)
                }
            },
            onClose = onClose
        )
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
internal object SamePersonQueueCommits {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "atlas-same-person").apply { isDaemon = true }
    }

    /** Serialized with Yes/No to prevent races with earlier questions. */
    fun mark(
        host: PeopleDiscoveryHost, epoch: String, item: SamePersonQueueItem,
        subject: Boolean, other: Boolean, done: (String?) -> Unit
    ) {
        worker.execute {
            val generation = try {
                val live = host.liveReview(epoch)
                    ?: host.reviewState(epoch)?.let { host.liveReview(epoch) }
                val action = samePersonQueueMixedSides(item, subject, other)
                if (live != null && action != null && samePersonQueueStillAsks(live, item) &&
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
    onClose: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize().testTag("atlas-same-queue")) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose, modifier = Modifier.testTag("atlas-same-queue-close")) {
                    Icon(Icons.Filled.Close, contentDescription = "Close")
                }
                Text("Same person?", style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).padding(start = 4.dp))
                if (canUndoMixed) TextButton(onClick = onUndoMixed,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("atlas-same-queue-undo")) {
                    Text("Undo")
                }
                if (open > 0) Text(
                    if (open == 1) "1 open" else "%,d open".format(Locale.US, open),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("atlas-same-queue-open"))
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when {
                    item != null -> Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SamePersonSide(item.subjectName ?: "Unnamed person",
                            faceCount(item.subjectFaces), item.subjectSamples, face,
                            "atlas-same-queue-subject")
                        TextButton(onClick = onToggleSubject, enabled = !actionBusy,
                            modifier = Modifier.heightIn(min = 48.dp)
                                .testTag("atlas-same-queue-subject-mixed")) {
                            Text(if (markSubject) "Not one person (selected)" else "Not one person")
                        }
                        Text("and", style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SamePersonSide("Unnamed group", faceCount(item.otherFaces),
                            item.otherSamples, face, "atlas-same-queue-other")
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
    tag: String
) {
    Column(Modifier.fillMaxWidth().testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
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
