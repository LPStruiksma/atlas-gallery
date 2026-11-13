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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
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
    onReadLastCrash: () -> String? = { null },
    onCopyLastCrash: (String) -> Unit = {},
    onShareLastCrash: (String) -> Unit = {},
    onReadDiagnostics: suspend () -> String = { "ATLAS diagnostics unavailable" },
    onCopyDiagnostics: (String) -> Unit = {},
    onShareDiagnostics: (String) -> Unit = {}
) {
    var advanced by rememberSaveable { mutableStateOf(false) }
    var crashExpanded by rememberSaveable { mutableStateOf(false) }
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
    val scanValue = if (scanLoading) "Restoring…" else when (scanPhase) {
        FaceDiscoveryPhase.SCANNING -> "On · Pause"
        FaceDiscoveryPhase.QUEUED -> "Starting"
        FaceDiscoveryPhase.PAUSED -> "Paused · Resume"
        FaceDiscoveryPhase.PERMISSION_REQUIRED -> "Permission required"
        FaceDiscoveryPhase.BATTERY_LOW -> "Battery low"
        FaceDiscoveryPhase.STORAGE_LOW -> "Storage low"
        FaceDiscoveryPhase.FAILED -> "Needs attention"
        FaceDiscoveryPhase.UNAVAILABLE, null -> "Unavailable"
        FaceDiscoveryPhase.READY -> "Not started"
        FaceDiscoveryPhase.FINISHED, FaceDiscoveryPhase.REVIEW_AVAILABLE -> "Finished"
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .testTag("atlas-settings-screen")
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        SettingsGroup("People", "atlas-library-people-tools") {
            SettingsActionRow("Face scanning", "atlas-settings-face-scanning",
                scanAction, scanValue)
            if (visualIndexOn != null) {
                SettingsActionRow("Visual search index", "atlas-people-visual-index",
                    onVisualIndex, if (visualIndexOn) "On" else "Off")
            }
            SettingsActionRow("People numbers", "atlas-people-numbers-open", onPeopleNumbers)
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
                        maxLines = 12, overflow = TextOverflow.Ellipsis)
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
