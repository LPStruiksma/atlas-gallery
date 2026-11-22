package nl.atlas.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeopleui.NeedsSplittingRow
import nl.atlas.gallery.featurepeopleui.PeopleClusterReviewState
import nl.atlas.gallery.featurepeopleui.PersonPageTarget
import nl.atlas.gallery.featurepeopleui.PeopleHomeSummary
import nl.atlas.gallery.featurepeopleui.needsSplittingLists
import nl.atlas.gallery.featuredesign.AtlasGalleryHeader

/** #212 v3: Loading while the review snapshot settles; Unavailable on a stale/revoked grant or read error. */
internal enum class NeedsSplittingLoad { Loading, Ready, Unavailable }

/** A current authorized snapshot only; Hidden is never projected into People. */
@Composable
internal fun NeedsSplittingRoute(
    host: PeopleDiscoveryHost,
    people: PeopleHomeSummary?,
    hiddenOnly: Boolean,
    onBack: () -> Unit,
    onOpen: (PersonPageTarget) -> Unit
) {
    val version by host.changes.collectAsState()
    val epoch = people?.permissionEpoch
    var retry by remember { mutableIntStateOf(0) }
    // Re-read on every review change, scope change and Retry. The live snapshot
    // is used when present; otherwise the persisted state is read off the UI
    // thread. A read error or a grant that is no longer current fails closed.
    val loaded by produceState<Pair<NeedsSplittingLoad, PeopleClusterReviewState?>>(
        NeedsSplittingLoad.Loading to null, version, epoch, retry
    ) {
        if (epoch == null) { value = NeedsSplittingLoad.Unavailable to null; return@produceState }
        val read = host.liveReview(epoch) ?: runCatching {
            withContext(Dispatchers.IO) { host.reviewState(epoch) }
        }.getOrNull()
        value = read?.takeIf { it.permissionEpoch == epoch && host.scopeCurrent(it.permissionEpoch) }
            ?.let { NeedsSplittingLoad.Ready to it } ?: (NeedsSplittingLoad.Unavailable to null)
    }
    val state = loaded.second?.takeIf { host.scopeCurrent(it.permissionEpoch) }
    val load = if (loaded.first == NeedsSplittingLoad.Ready && state == null)
        NeedsSplittingLoad.Unavailable else loaded.first
    val rows = remember(state, hiddenOnly) {
        val lists = needsSplittingLists(state)
        if (hiddenOnly) lists.hidden else lists.visible
    }
    NeedsSplittingScreen(rows, hiddenOnly, authorized = state != null,
        load = load,
        onRetry = { retry++ },
        face = { face -> PersonFaceImage(host, state?.permissionEpoch, face) },
        onBack = onBack,
        onOpen = { row ->
            val live = host.liveReview(state?.permissionEpoch)
            if (live != null && host.scopeCurrent(live.permissionEpoch)) {
                val latest = needsSplittingLists(live)
                val current = if (hiddenOnly) latest.hidden else latest.visible
                if (current.any { it.target == row.target }) onOpen(row.target)
            }
        })
}

@Composable
internal fun NeedsSplittingScreen(
    rows: List<NeedsSplittingRow>,
    hiddenOnly: Boolean,
    authorized: Boolean,
    face: @Composable (NativeFaceObservationId) -> Unit,
    onBack: () -> Unit,
    onOpen: (NeedsSplittingRow) -> Unit,
    load: NeedsSplittingLoad =
        if (authorized) NeedsSplittingLoad.Ready else NeedsSplittingLoad.Unavailable,
    onRetry: (() -> Unit)? = null
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .testTag(if (hiddenOnly) "atlas-hidden-needs-splitting" else "atlas-needs-splitting")) {
        AtlasGalleryHeader(
            title = if (hiddenOnly) "Hidden · Needs splitting" else "Needs splitting",
            onBack = onBack)
        if (load == NeedsSplittingLoad.Loading) {
            // Quiet loading: never a "(0)" or stale rows while the snapshot settles.
            Box(Modifier.fillMaxSize().testTag("atlas-needs-splitting-loading"),
                contentAlignment = Alignment.Center) {
                Text("Finding groups\u2026", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (load == NeedsSplittingLoad.Unavailable || !authorized) {
            Column(Modifier.fillMaxSize().padding(24.dp).testTag("atlas-needs-splitting-error"),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Couldn\u2019t load groups.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (onRetry != null) {
                    TextButton(onClick = onRetry,
                        modifier = Modifier.heightIn(min = 48.dp)
                            .testTag("atlas-needs-splitting-retry")) { Text("Retry") }
                }
            }
        } else if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (hiddenOnly) "No hidden groups need splitting"
                    else "No groups need splitting",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(rows, key = { it.key }) { row ->
                    Row(Modifier.fillMaxWidth().clickable { onOpen(row) }
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                        .testTag("atlas-needs-splitting-row"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Box(Modifier.size(56.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                            contentAlignment = Alignment.Center) { face(row.cover) }
                        Column {
                            Text(row.name ?: "Unnamed group",
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyLarge)
                            Text(if (row.faces == 1) "1 face" else "${row.faces} faces",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}
