package nl.atlas.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
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
    scanStatusNote: String? = null
) {
    var advanced by rememberSaveable { mutableStateOf(false) }
    var crashExpanded by rememberSaveable { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val showLargeTitle = scrollState.value < 64
    val scanAction = when (scanPhase) {
        FaceDiscoveryPhase.SCANNING -> onPauseScan
        FaceDiscoveryPhase.PAUSED -> onResumeScan
        else -> null
    }
    val scanValue = scanStatusNote ?: if (scanLoading) "Restoring…" else faceScanSettingsValue(
        scanPhase, false, scanHasSavedOptIn, scanPhotoAccess)

    Box(Modifier.fillMaxSize().testTag("atlas-settings-screen")) {
        Column(
            Modifier.fillMaxSize().verticalScroll(scrollState)
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
        // Keep the expanded title in the scroll layout even after collapse,
        // otherwise removing it would shift content and oscillate at the threshold.
        Text("Settings",
                Modifier.fillMaxWidth().padding(start = 8.dp, top = 12.dp, bottom = 8.dp)
                    .testTag("atlas-settings-large-title"),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Light,
                color = MaterialTheme.colorScheme.onBackground)
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
        if (!showLargeTitle) {
            // The app shell owns its Back action; this is a compact in-page
            // title only, so the originating tab and navigation remain untouched.
            Text("Settings",
                Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = 24.dp, vertical = 12.dp)
                    .testTag("atlas-settings-compact-title"),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground)
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
        Text(heading,
            Modifier.padding(start = 10.dp, bottom = 2.dp),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .testTag("atlas-settings-group-card")
                .padding(horizontal = 16.dp),
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
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .testTag(tag)
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface)
                if (value != null) {
                    Spacer(Modifier.height(3.dp))
                    Text(value,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                }
            }
        }
        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.38f))
    }
}
