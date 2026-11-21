package nl.atlas.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeopleui.NeedsSplittingRow
import nl.atlas.gallery.featurepeopleui.PersonPageTarget
import nl.atlas.gallery.featurepeopleui.PeopleHomeSummary
import nl.atlas.gallery.featurepeopleui.needsSplittingLists
import nl.atlas.gallery.featuredesign.AtlasGalleryHeader

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
    val loaded = rememberReviewState(host, version, people)
    val state = loaded?.takeIf { host.scopeCurrent(it.permissionEpoch) }
    val rows = remember(state, hiddenOnly) {
        val lists = needsSplittingLists(state)
        if (hiddenOnly) lists.hidden else lists.visible
    }
    NeedsSplittingScreen(rows, hiddenOnly, authorized = state != null,
        face = { face -> PersonFaceImage(host, state?.permissionEpoch, face) },
        onBack = onBack,
        onOpen = { row ->
            val epoch = state?.permissionEpoch
            val live = host.liveReview(epoch)
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
    onOpen: (NeedsSplittingRow) -> Unit
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .testTag(if (hiddenOnly) "atlas-hidden-needs-splitting" else "atlas-needs-splitting")) {
        AtlasGalleryHeader(
            title = if (hiddenOnly) "Hidden · Needs splitting" else "Needs splitting",
            onBack = onBack)
        if (!authorized || rows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(when {
                    !authorized -> "People review not available"
                    hiddenOnly -> "No hidden groups need splitting"
                    else -> "No groups need splitting"
                }, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
