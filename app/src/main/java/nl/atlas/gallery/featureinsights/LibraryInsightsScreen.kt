package nl.atlas.gallery.featureinsights

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nl.atlas.gallery.featurecuration.cleanupSize
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.max

/** What the screen shows; the host computes it off the main thread from the catalogue snapshot. */
sealed interface InsightsUiState {
    /** First computation and nothing cached: a nonblocking "checking" cue, never an invented total. */
    data object Checking : InsightsUiState

    /** [updating] keeps the last good numbers on screen while a newer snapshot is read. */
    data class Ready(val insights: LibraryInsights, val updating: Boolean = false) : InsightsUiState

    /** [lastKnown] is shown, labelled, when an earlier snapshot exists. */
    data class Failed(val lastKnown: LibraryInsights? = null) : InsightsUiState
}

/** How many folders the first view lists before "All folders". */
internal const val INSIGHTS_TOP_FOLDERS = 3

@Composable
fun LibraryInsightsScreen(
    state: InsightsUiState,
    onOpenRow: (InsightsRow) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    /** Estimate from a completed Photo DNA scan of this same catalogue; null hides the row. */
    possibleDuplicateBytes: Long? = null,
    onReviewDuplicates: (() -> Unit)? = null,
    listState: LazyListState = rememberLazyListState(),
    locale: Locale = Locale.getDefault()
) {
    var allFolders by rememberSaveable { mutableStateOf(false) }
    val shown = when (state) {
        is InsightsUiState.Ready -> state.insights
        is InsightsUiState.Failed -> state.lastKnown
        InsightsUiState.Checking -> null
    }
    Box(modifier.fillMaxSize().testTag("atlas-insights-screen")) {
        when {
            state is InsightsUiState.Checking ||
                (state is InsightsUiState.Ready && state.updating && state.insights.isEmpty) -> InsightsNotice(
                "Checking your library", held = true, tag = "atlas-insights-checking")
            shown != null && shown.isEmpty && state is InsightsUiState.Ready && !state.updating ->
                InsightsNotice("No photos or videos in your library yet.", held = false,
                    tag = "atlas-insights-empty")
            state is InsightsUiState.Failed && shown == null -> InsightsFailure(null, onRetry)
            shown != null -> LazyColumn(
                Modifier.fillMaxSize().testTag("atlas-insights-list"),
                state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)
            ) {
                if (state is InsightsUiState.Failed) item(key = "failed") {
                    InsightsFailure(shown, onRetry)
                }
                if (state is InsightsUiState.Ready && state.updating) item(key = "updating") {
                    InsightsStatusLine("Updating library", "atlas-insights-updating")
                }
                summary(shown, locale)
                if (shown.folders.isNotEmpty()) {
                    sectionHeading("Folders")
                    val folders = if (allFolders) shown.folders else shown.folders.take(INSIGHTS_TOP_FOLDERS)
                    val largest = shown.folders.first().bytes
                    items(folders, key = { "folder:" + it.scope.encode() }) {
                        InsightsRowItem(it, largest, onOpenRow, locale)
                    }
                    if (shown.folders.size > INSIGHTS_TOP_FOLDERS) item(key = "all-folders") {
                        AllFoldersRow(allFolders, shown.folders.size, locale) {
                            allFolders = !allFolders
                        }
                    }
                }
                section("Types", shown.types, "type", onOpenRow, locale)
                section("Years", shown.years, "year", onOpenRow, locale)
                if (possibleDuplicateBytes != null && possibleDuplicateBytes > 0L &&
                    onReviewDuplicates != null) item(key = "duplicates") {
                    DuplicateSpaceRow(possibleDuplicateBytes, onReviewDuplicates, locale)
                }
            }
        }
    }
}

private fun LazyListScope.summary(insights: LibraryInsights, locale: Locale) {
    item(key = "summary") {
        Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp)
            .testTag("atlas-insights-summary")) {
            Text(cleanupSize(insights.totalBytes, locale),
                Modifier.testTag("atlas-insights-total-size"),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground)
            Text(countText(insights.totalCount, "photo or video", "photos and videos", locale),
                Modifier.testTag("atlas-insights-total-count"),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            insightsDuration(insights.videoDurationMillis)?.let { duration ->
                Text((if (insights.videoDurationExact) "Video duration " else "Video duration at least ") +
                    duration,
                    Modifier.testTag("atlas-insights-duration"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun LazyListScope.sectionHeading(title: String) {
    item(key = "heading:$title") {
        Text(title,
            Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp)
                .semantics { heading() }.testTag("atlas-insights-heading-$title"),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground)
    }
}

private fun LazyListScope.section(
    title: String, rows: List<InsightsRow>, keyPrefix: String,
    onOpenRow: (InsightsRow) -> Unit, locale: Locale
) {
    if (rows.isEmpty()) return
    sectionHeading(title)
    // Each section scales its bars to its own largest row.
    val largest = rows.maxOf { it.bytes }
    items(rows, key = { "$keyPrefix:" + it.scope.encode() }) {
        InsightsRowItem(it, largest, onOpenRow, locale)
    }
}

@Composable
private fun InsightsRowItem(
    row: InsightsRow, largest: Long, onOpen: (InsightsRow) -> Unit, locale: Locale
) {
    val count = countText(row.count, "item", "items", locale)
    val size = cleanupSize(row.bytes, locale)
    val spoken = "${row.fullPath?.trimEnd('/') ?: row.label}, ${spokenSize(size)}, $count"
    Column(
        Modifier.fillMaxWidth().heightIn(min = 72.dp)
            .testTag("atlas-insights-row")
            .clickable(onClickLabel = "Open largest files first") { onOpen(row) }
            // One TalkBack stop per row: the spoken form replaces the visual pieces.
            .clearAndSetSemantics {
                contentDescription = "$spoken, opens largest files first"
                role = Role.Button
                onClick(label = "Open largest files first") { onOpen(row); true }
            }
            .padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(row.label, Modifier.weight(1f).testTag("atlas-insights-row-label"),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(12.dp))
            Text(size, style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        InsightsBar(insightsBarFraction(row.bytes, largest))
        Text(count, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Supporting visual only: the size and count beside it carry the meaning. */
@Composable
private fun InsightsBar(fraction: Float) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(8.dp)
        .clip(RoundedCornerShape(4.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .testTag("atlas-insights-bar")) {
        if (fraction > 0f) {
            // A positive row always shows at least 2 dp of fill.
            Box(Modifier.width(max(2.dp.value, maxWidth.value * fraction).dp).height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.primary))
        }
    }
}

@Composable
private fun AllFoldersRow(expanded: Boolean, folderCount: Int, locale: Locale, onToggle: () -> Unit) {
    val label = if (expanded) "Fewer folders" else "All folders"
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .clickable(onClick = onToggle).testTag("atlas-insights-all-folders")
            .semantics { role = Role.Button },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary)
        Text(NumberFormat.getIntegerInstance(locale).format(folderCount),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DuplicateSpaceRow(bytes: Long, onReview: () -> Unit, locale: Locale) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text("Possible duplicate space: ${cleanupSize(bytes, locale)}",
            Modifier.testTag("atlas-insights-duplicate-space"),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground)
        TextButton(onClick = onReview, modifier = Modifier.heightIn(min = 48.dp)
            .testTag("atlas-insights-duplicate-review")) { Text("Review") }
    }
}

@Composable
private fun InsightsStatusLine(text: String, tag: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically) {
        HeldSphereMark(Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InsightsNotice(text: String, held: Boolean, tag: String) {
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp).testTag(tag),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        if (held) HeldSphereMark(Modifier.size(56.dp))
        if (held) Spacer(Modifier.height(16.dp))
        Text(text, style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun InsightsFailure(lastKnown: LibraryInsights?, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp, horizontal = if (lastKnown == null) 32.dp else 0.dp)
        .then(if (lastKnown == null) Modifier.fillMaxSize() else Modifier)
        .testTag("atlas-insights-error"),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Couldn't read library insights.", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground)
        if (lastKnown != null) Text("Showing the last known numbers.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)
            .testTag("atlas-insights-retry")) { Text("Retry") }
    }
}

/**
 * A still sphere resting on an arc: the loading cue. It never moves, so it is
 * already the reduced-motion form, and it carries no progress it does not know.
 */
@Composable
private fun HeldSphereMark(modifier: Modifier) {
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    val brand = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val stroke = size.minDimension * 0.1f
        val inset = stroke / 2f + size.minDimension * 0.1f
        val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
        drawArc(track, startAngle = 180f, sweepAngle = 180f, useCenter = false,
            topLeft = Offset(inset, inset + size.height * 0.15f), size = arcSize,
            style = Stroke(stroke, cap = StrokeCap.Round))
        drawCircle(brand, radius = size.minDimension * 0.14f,
            center = Offset(size.width / 2f, inset + size.height * 0.15f))
    }
}

internal fun countText(count: Int, one: String, many: String, locale: Locale): String =
    NumberFormat.getIntegerInstance(locale).format(count) + " " + if (count == 1) one else many

/** "25,4 GB" → "25,4 gigabytes" for TalkBack. */
internal fun spokenSize(size: String): String = size
    .replace(" KB", " kilobytes").replace(" MB", " megabytes")
    .replace(" GB", " gigabytes").replace(" TB", " terabytes")
