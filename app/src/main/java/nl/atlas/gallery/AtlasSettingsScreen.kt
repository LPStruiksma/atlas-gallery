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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import nl.atlas.gallery.featuredesign.LocalAtlasDark
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import nl.atlas.gallery.featurepeopleui.FaceDiscoveryPhase
import nl.atlas.gallery.featureaiedit.settings.AiEditSettingsDialog
import nl.atlas.gallery.featureaiedit.settings.ProviderSettingsStore

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
    onReadDiagnostics: suspend () -> String = { "ATLAS diagnostics unavailable" },
    onCopyDiagnostics: (String) -> Unit = {},
    onShareDiagnostics: (String) -> Unit = {},
    onExportNames: () -> Unit = {},
    onImportNames: () -> Unit = {},
    onScanNow: (() -> Unit)? = null,
    scanStatusNote: String? = null,
    /** FIND-3a (#182): "Save search history" switch; null hides the Search group. */
    searchHistoryOn: Boolean? = null,
    onSearchHistoryOn: (Boolean) -> Unit = {},
    onClearSearchHistory: () -> Unit = {},
    /** INSIGHT-1 (#587): opens Library insights; null hides the row. */
    onLibraryInsights: (() -> Unit)? = null,
    performance: PerformanceAggregates = PerformanceSession.aggregate,
    /** #675: release notes bundled by the signed release job; null in debug builds. */
    changelog: AtlasChangelog? = LocalContext.current.let { c -> remember(c) { AtlasChangelog.load(c) } }
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
    var exportExpanded by rememberSaveable { mutableStateOf(false) }
    var exported by remember { mutableStateOf<String?>(null) }
    var exportFailed by remember { mutableStateOf(false) }
    if (exportExpanded) {
        LaunchedEffect(exportExpanded) {
            exported = null
            exportFailed = false
            try {
                exported = onReadDiagnostics()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                exportFailed = true
            }
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
        if (onLibraryInsights != null) SettingsGroup("Library", "atlas-settings-library") {
            SettingsActionRow("Library insights", "atlas-settings-library-insights",
                onLibraryInsights, "Size by folder, type and year")
        }
        SettingsGroup("Display", "atlas-settings-display") {
            // #662 follows the phone theme. This is a status, not a new preference.
            SettingsActionRow("Theme", "atlas-settings-theme", value = "Follows system")
        }
        SettingsGroup("Editing", "atlas-settings-ai-edit") {
            // #430: the row opens the "AI Edit" sub-screen; its label matches that title and
            // its value is the status (the default service), not a description.
            var aiEditOpen by rememberSaveable { mutableStateOf(false) }
            val context = LocalContext.current
            val defaultService = remember(context, aiEditOpen) {
                ProviderSettingsStore(context).defaultProvider()?.name
            }
            SettingsActionRow("AI Edit", "atlas-settings-ai-edit-open",
                onClick = { aiEditOpen = true }, value = defaultService ?: "Not set up")
            if (aiEditOpen) AiEditSettingsDialog(onClose = { aiEditOpen = false })
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
                    // #121 brief rev 2: six headline rows first, action details below.
                    val overview = remember(performanceReadVersion) {
                        PerformanceReport.overview(performance)
                    }
                    val actions = remember(performanceReadVersion) {
                        PerformanceReport.actions(performance)
                    }
                    overview.forEach { SettingsMetricRow(it) }
                    Text("Action details",
                        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp)
                            .testTag("atlas-perf-actions-heading"),
                        style = MaterialTheme.typography.titleSmall)
                    actions.forEach { SettingsMetricRow(it) }
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
                SettingsActionRow("Export diagnostics", "atlas-settings-export-diagnostics",
                    onClick = { exportExpanded = !exportExpanded },
                    value = if (exportExpanded) "Hide" else "Show")
                if (exportExpanded) {
                    // Explicit user action only. No identifier or media leaves the device
                    // without the user's separate Copy or Share tap.
                    val report = exported
                    Text(when {
                        exportFailed -> "Could not prepare diagnostics"
                        report == null -> "Preparing aggregate diagnostics…"
                        else -> report
                    }, modifier = Modifier.fillMaxWidth().padding(12.dp)
                        .testTag("atlas-settings-export-diagnostics-text"),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 18, overflow = TextOverflow.Ellipsis)
                    if (report != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { onCopyDiagnostics(report) },
                                modifier = Modifier.testTag("atlas-settings-export-diagnostics-copy")) {
                                Text("Copy")
                            }
                            TextButton(onClick = { onShareDiagnostics(report) },
                                modifier = Modifier.testTag("atlas-settings-export-diagnostics-share")) {
                                Text("Share text file")
                            }
                        }
                    }
                }
            }
        }
        // #427 review: below Advanced, so the existing groups keep their place on screen.
        if (searchHistoryOn != null) SettingsGroup("Search", "atlas-settings-search") {
            // Off stops saving and clears every scope (Undo on the snackbar).
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp)
                    .toggleable(value = searchHistoryOn, role = Role.Switch,
                        onValueChange = onSearchHistoryOn)
                    .testTag("atlas-settings-search-history"),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text("Save search history", style = MaterialTheme.typography.bodyLarge)
                    Text("Recent searches stay on this phone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = searchHistoryOn, onCheckedChange = null)
            }
            SettingsActionRow("Clear search history", "atlas-settings-search-history-clear",
                onClearSearchHistory)
        }
        SettingsGroup("About", "atlas-settings-about") {
            SettingsActionRow("Version", "atlas-settings-version", value = versionName)
            var whatsNewOpen by rememberSaveable { mutableStateOf(false) }
            SettingsActionRow("What's new", "atlas-settings-whats-new",
                onClick = { whatsNewOpen = true }, value = whatsNewRowValue(changelog))
            if (whatsNewOpen) AtlasWhatsNewDialog(changelog, versionName) { whatsNewOpen = false }
        }
    }
}

@Composable
private fun AtlasWhatsNewDialog(changelog: AtlasChangelog?, installedVersion: String,
                                onClose: () -> Unit) {
    // Same full-screen sub-screen shape as AI Edit settings (#430).
    val dark = LocalAtlasDark.current
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false, dismissOnClickOutside = false)
    ) {
        val view = LocalView.current
        LaunchedEffect(view, dark) {
            val window = (view.parent as? DialogWindowProvider)?.window ?: return@LaunchedEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground) {
            AtlasWhatsNewScreen(changelog, installedVersion, onBack = onClose)
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
    SettingsRowLayout(
        Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .testTag(tag),
        label = { Text(label, Modifier.testTag("$tag-label"), style = MaterialTheme.typography.bodyLarge) },
        value = value?.let { text ->
            {
                Text(text, Modifier.testTag("$tag-value"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    )
}

/**
 * Settings row: label left, value right on one line when both fit at full width;
 * otherwise the value moves under the label. The label is never squeezed, so a long
 * status (e.g. "Waiting for charging · use Scan now while unplugged") cannot push the
 * title into a one-letter column.
 */
@Composable
internal fun SettingsRowLayout(
    modifier: Modifier,
    label: @Composable () -> Unit,
    value: (@Composable () -> Unit)?
) {
    val gap = 12.dp
    val stackedGap = 2.dp
    val vertical = 8.dp
    Layout(
        content = { label(); value?.invoke() },
        modifier = modifier
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val pad = vertical.roundToPx()
        val labelM = measurables[0]
        val valueM = measurables.getOrNull(1)
        val gapPx = gap.roundToPx()
        val loose = Constraints(maxWidth = width)
        val stack = valueM != null && settingsRowStacks(
            labelM.maxIntrinsicWidth(Constraints.Infinity), gapPx,
            valueM.maxIntrinsicWidth(Constraints.Infinity), width)
        val labelP = labelM.measure(loose)
        if (valueM == null) {
            val h = maxOf(constraints.minHeight, labelP.height + 2 * pad)
            return@Layout layout(width, h) { labelP.place(0, (h - labelP.height) / 2) }
        }
        if (stack) {
            val valueP = valueM.measure(loose)
            val inner = labelP.height + stackedGap.roundToPx() + valueP.height
            val h = maxOf(constraints.minHeight, inner + 2 * pad)
            val top = (h - inner) / 2
            layout(width, h) {
                labelP.place(0, top)
                valueP.place(0, top + labelP.height + stackedGap.roundToPx())
            }
        } else {
            val valueP = valueM.measure(Constraints(maxWidth = maxOf(0, width - labelP.width - gapPx)))
            val h = maxOf(constraints.minHeight, maxOf(labelP.height, valueP.height) + 2 * pad)
            layout(width, h) {
                labelP.place(0, (h - labelP.height) / 2)
                valueP.place(width - valueP.width, (h - valueP.height) / 2)
            }
        }
    }
}

/** True when label and value do not fit side by side at their natural one-line widths. */
internal fun settingsRowStacks(labelWidth: Int, gap: Int, valueWidth: Int, available: Int): Boolean =
    labelWidth + gap + valueWidth > available

@Composable
private fun SettingsMetricRow(row: PerformanceReport.Row) {
    // Label above, value below: a long value wraps under the label and never
    // squeezes it (#610), also at 200% font. Read-only, no click target.
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag(row.tag)) {
        Text(row.label, style = MaterialTheme.typography.bodyLarge)
        Text(row.value, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
