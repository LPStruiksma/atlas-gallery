package nl.atlas.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeopleui.PeopleClusterReviewCommand
import nl.atlas.gallery.featurepeopleui.PeopleHomeSummary
import nl.atlas.gallery.featurepeopleui.ReviewReferenceKey
import nl.atlas.gallery.featurepeopleui.SamePersonQueue
import nl.atlas.gallery.featurepeopleui.SamePersonQueueItem
import nl.atlas.gallery.featurepeopleui.samePersonQueue
import nl.atlas.gallery.featurepeopleui.samePersonQueueAnswer

/**
 * #580 People ⋮ → Same person?: every open question of the library, one at a
 * time, the one that would move the most faces first. Yes and No are the
 * answers a person's page sends (same reducer, same atomic commit through
 * [PeopleDiscoveryHost.review]); Skip only moves on and is forgotten when the
 * screen closes. Nothing is joined without an answer.
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
    var skipped by remember { mutableStateOf(emptySet<String>()) }
    // The question being committed: its buttons stay off until the list moves on.
    var answered by remember { mutableStateOf<String?>(null) }
    val queue by produceState<SamePersonQueue?>(null, ReviewReferenceKey(state), skipped) {
        if (state == null) return@produceState
        value = withContext(Dispatchers.Default) { samePersonQueue(state, skipped, limit = 1) }
    }
    val scope = rememberCoroutineScope()
    val item = queue?.items?.firstOrNull()
    Dialog(onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        SamePersonQueueScreen(
            item = item,
            open = queue?.open ?: 0,
            loaded = queue != null || people == null,
            busy = item != null && item.key == answered,
            face = { PersonFaceImage(host, epoch, it) },
            onAnswer = { same ->
                val asked = item
                val live = epoch?.let { host.liveReview(it) }
                if (asked != null && live != null) {
                    answered = asked.key
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            host.review(PeopleClusterReviewCommand(live,
                                samePersonQueueAnswer(asked, same)))
                        }
                        if (!ok) answered = null
                    }
                }
            },
            onSkip = { item?.let { skipped = skipped + it.key } },
            onClose = onClose
        )
    }
}

@Composable
internal fun SamePersonQueueScreen(
    item: SamePersonQueueItem?,
    open: Int,
    loaded: Boolean,
    busy: Boolean,
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
                        Text("and", style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SamePersonSide("Unnamed group", faceCount(item.otherFaces),
                            item.otherSamples, face, "atlas-same-queue-other")
                    }
                    !loaded -> CircularProgressIndicator(
                        Modifier.size(32.dp).testTag("atlas-same-queue-loading"))
                    else -> Text(
                        if (open > 0) "You skipped the rest. Close and open again to see them."
                        else "No open questions.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(32.dp).testTag("atlas-same-queue-empty"))
                }
            }
            if (item != null) Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = { onAnswer(false) }, enabled = !busy,
                    modifier = Modifier.weight(1f).testTag("atlas-same-queue-no")) { Text("No") }
                TextButton(onClick = onSkip, enabled = !busy,
                    modifier = Modifier.testTag("atlas-same-queue-skip")) { Text("Skip") }
                Button(onClick = { onAnswer(true) }, enabled = !busy,
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
            for (sample in samples) Box(
                Modifier.size(72.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center
            ) { face(sample) }
        }
    }
}
