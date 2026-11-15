package nl.atlas.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nl.atlas.gallery.featurepeopleui.FaceDiscoveryPhase

/**
 * #664: one Samsung-style Settings destination for the gallery's existing
 * maintenance surfaces. This UI owns no preferences, scanning jobs or private
 * data; every action delegates to the same MainActivity handler as before.
 */
@Composable
internal fun AtlasSettingsScreen(
    scanPhase: FaceDiscoveryPhase?,
    onPauseScan: (() -> Unit)?,
    onResumeScan: (() -> Unit)?,
    visualIndexOn: Boolean?,
    showPhotoDetails: Boolean,
    versionName: String,
    onVisualIndex: () -> Unit,
    onPeopleNumbers: () -> Unit,
    onPhotoDetails: () -> Unit,
    onImportV4: () -> Unit,
    onLinkV4: () -> Unit,
    onDiagnostics: () -> Unit,
    scanLoading: Boolean = false,
    scanHasSavedOptIn: Boolean = false,
    scanPhotoAccess: Boolean = true,
    visualStatus: VisualIndexStatus? = null,
    onReadLastCrash: () -> String? = { null },
    onCopyLastCrash: (String) -> Unit = {},
    onShareLastCrash: (String) -> Unit = {},
    onExportNames: () -> Unit = {},
    onImportNames: () -> Unit = {},
    onScanNow: (() -> Unit)? = null,
    scanStatusNote: String? = null,
    performance: PerformanceAggregates = PerformanceSession.aggregate
) {
    var advanced by rememberSaveable { mutableStateOf(false) }
    var crashExpanded by rememberSaveable { mutableStateOf(false) }
    var performanceExpanded by rememberSaveable { mutableStateOf(false) }
    // Aggregate is not Compose state; refresh the read-only report once its
    // latest preview and heap samples have been forwarded.
    var performanceReadVersion by remember { mutableIntStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(performanceExpanded) {
        if (performanceExpanded) {
            PerformanceSession.updateViewer()
            PerformanceSession.sampleHeap()
            performanceReadVersion++
        }
    }
    val scanAction = when (scanPhase) {
        FaceDiscoveryPhase.SCANNING -> onPauseScan
        FaceDiscoveryPhase.PAUSED -> onResumeScan
        else -> null
    }
    val scanValue = scanStatusNote ?: if (scanLoading) "Restoring…" else faceScanSettingsValue(
        scanPhase, false, scanHasSavedOptIn, scanPhotoAccess)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .testTag("atlas-settings-screen")
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        SettingsGroup("People", "atlas-library-people-tools") {
            SettingsActionRow("Face scanning", "atlas-settings-face-scanning",
                scanAction, scanValue)
            SettingsActionRow("Scan now", "atlas-settings-scan-now", onScanNow,
                if (onScanNow == null) "Photo access required" else "Foreground · works on battery")
            if (visualIndexOn != null) {
                SettingsActionRow("Visual search index", "atlas-people-visual-index",
                    onVisualIndex.takeIf { visualStatus != null }, visualIndexStatusText(visualIndexOn, visualStatus,
                        scanPhase, scanLoading, scanPhotoAccess))
            }
            SettingsActionRow("People numbers", "atlas-people-numbers-open", onPeopleNumbers)
            SettingsActionRow("Export names", "atlas-people-names-export", onExportNames)
            SettingsActionRow("Import names", "atlas-people-names-import", onImportNames)
        }
        SettingsGroup("Display", "atlas-settings-display") {
            // #662 follows the phone theme. This is a status, not a new preference.
            SettingsActionRow("Theme", "atlas-settings-theme", value = "Follows system")
        }
        SettingsGroup("Advanced", "atlas-settings-advanced-group") {
            SettingsActionRow("Advanced options", "atlas-settings-advanced",
                onClick = { advanced = !advanced }, value = if (advanced) "Hide" else "Show")
            if (advanced) {
                SettingsActionRow("Import from Monochrome Studio",
                    "atlas-people-import-v4", onImportV4)
                SettingsActionRow("Link Monochrome Studio export",
                    "atlas-people-link-v4", onLinkV4)
                if (showPhotoDetails) {
                    SettingsActionRow("Photo details", "atlas-people-details", onPhotoDetails)
                }
                SettingsActionRow("Performance", "atlas-settings-performance",
                    onClick = { performanceExpanded = !performanceExpanded },
                    value = if (performanceExpanded) "Hide" else "Show")
                if (performanceExpanded) {
                    // Explicit read, not a hot polling loop or a MediaStore query.
                    val metrics = listOf(
                        Triple("Cold start → cached grid", "cold_start_ms", "ms"),
                        Triple("Viewer preview", "viewer_ms", "ms"),
                        Triple("Grid frame (active scroll)", "grid_frame_ms", "ms"),
                        Triple("Face scan", "face_photos_min", "photos/min"),
                        Triple("Visual index", "visual_photos_min", "photos/min"),
                        Triple("Java heap / maximum", "heap_fraction", "fraction")
                    )
                    val actionLabels = listOf(
                        "App open → cached grid", "Grid scroll / fling", "Open photo",
                        "Viewer swipe", "Face + visual indexing", "New photo appears",
                        "Return from viewer", "Memory / stability", "Open People",
                        "Open person / scroll", "Search", "Grid pinch zoom",
                        "Viewer full-res zoom", "Video first frame", "Tabs / albums",
                        "Selection", "Bulk actions", "People review", "Stories",
                        "Details / favourite", "Duplicate / trash review", "Sort / Settings"
                    )
                    val allMetrics = metrics + actionLabels.mapIndexed { index, title ->
                        Triple("A${index + 1} · $title", "action_a${index + 1}_ms", "ms")
                    }
                    val measurements = remember(performanceReadVersion) {
                        allMetrics.map { spec -> spec to performance.snapshot(spec.second) }
                    }
                    measurements.forEach { (spec, summary) ->
                        val (title, metric, unit) = spec
                        val value = if (summary.count == 0) "Not measured" else
                            "p50 %.2f · p90 %.2f · p95 %.2f %s · n=%d".format(
                                java.util.Locale.US, summary.p50, summary.p90, summary.p95, unit, summary.count)
                        SettingsMetricRow(title, "atlas-perf-$metric", value)
                    }
                    val jank = remember(performanceReadVersion) {
                        performance.positiveFraction("grid_frame_over_budget") to
                            performance.snapshot("grid_frame_over_budget").count
                    }
                    val jankLabel = if (jank.first == null) "Not measured" else
                        "%.1f%% over display frame budget · n=%d".format(
                            java.util.Locale.US, jank.first!! * 100.0, jank.second)
                    SettingsMetricRow("Grid frame budget violations",
                        "atlas-perf-grid-jank", jankLabel)
                }
                SettingsActionRow("Diagnostics", "atlas-settings-diagnostics", onDiagnostics)
                SettingsActionRow("Last crash", "atlas-settings-last-crash",
                    onClick = { crashExpanded = !crashExpanded },
                    value = if (crashExpanded) "Hide" else "Show")
                if (crashExpanded) {
                    // Read only when the user opens this section; never upload implicitly.
                    val last = remember(crashExpanded) { onReadLastCrash() }
                    Text(last ?: "No crash recorded yet",
                        modifier = Modifier.fillMaxWidth().padding(12.dp)
                            .testTag("atlas-settings-last-crash-text"),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 28, overflow = TextOverflow.Ellipsis)
                    if (last != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { onCopyLastCrash(last) },
                                modifier = Modifier.testTag("atlas-settings-last-crash-copy")) {
                                Text("Copy")
                            }
                            TextButton(onClick = { onShareLastCrash(last) },
                                modifier = Modifier.testTag("atlas-settings-last-crash-share")) {
                                Text("Share")
                            }
                        }
                    }
                }
            }
        }
        SettingsGroup("About", "atlas-settings-about") {
            SettingsActionRow("Version", "atlas-settings-version", value = versionName)
        }
    }
}

@Composable
private fun SettingsGroup(
    heading: String,
    tag: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        Modifier.fillMaxWidth().testTag(tag),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(heading, Modifier.padding(start = 10.dp),
            style = MaterialTheme.typography.titleMedium)
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 16.dp, vertical = 4.dp),
            content = content
        )
    }
}

@Composable
private fun SettingsActionRow(
    label: String,
    tag: String,
    onClick: (() -> Unit)? = null,
    value: String? = null
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .testTag(tag),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        if (value != null) {
            Spacer(Modifier.width(12.dp))
            Text(value, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SettingsMetricRow(label: String, tag: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag(tag)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
