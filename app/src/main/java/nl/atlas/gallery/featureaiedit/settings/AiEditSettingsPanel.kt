package nl.atlas.gallery.featureaiedit.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.util.UUID

/** No keys are copied into saved-instance state, diagnostics or exported usage data. */
@Composable
fun AiEditSettingsPanel() {
    val context = LocalContext.current
    val store = remember(context) { ProviderSettingsStore(context) }
    val vault = remember(context) { ProviderCredentialVault(context) }
    var profiles by remember { mutableStateOf(store.profiles()) }
    var defaultId by remember { mutableStateOf(store.defaultProvider()?.id) }
    var keepLocation by remember { mutableStateOf(store.keepLocationInUploads) }
    var showExplanation by remember { mutableStateOf(!store.firstUseAcknowledged) }
    var name by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("") }
    var authType by remember { mutableStateOf(ProviderAuthType.BEARER) }
    var headerName by remember { mutableStateOf("X-Atlas-Token") }
    var secret by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth().testTag("aie-provider-settings"),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Synthetic mock", style = MaterialTheme.typography.titleSmall)
        Text("Offline · instruction edit · no API key · no upload",
            style = MaterialTheme.typography.bodySmall)
        Text("Configured HTTPS endpoints", style = MaterialTheme.typography.titleSmall)
        if (profiles.isEmpty()) Text("No remote provider configured. Nothing can be uploaded.",
            style = MaterialTheme.typography.bodySmall)
        for (profile in profiles) {
            Column(Modifier.fillMaxWidth().testTag("aie-provider-" + profile.id)) {
                Text(profile.name, style = MaterialTheme.typography.titleSmall)
                Text("HTTPS · ATLAS protocol v1 · asynchronous jobs",
                    style = MaterialTheme.typography.bodySmall)
                Text("Actual mask, reference and model capabilities are checked from the server.",
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        store.setDefault(profile.id)
                        defaultId = profile.id
                    }) {
                        Text(if (defaultId == profile.id) "Default ✓" else "Make default")
                    }
                    TextButton(onClick = {
                        store.delete(profile.id)
                        profiles = store.profiles()
                        defaultId = store.defaultProvider()?.id
                    }) { Text("Remove") }
                }
            }
            HorizontalDivider()
        }

        Text("Add custom ATLAS-compatible endpoint", style = MaterialTheme.typography.titleSmall)
        Text("HTTPS only. Generic commercial API endpoints need their own adapters; this form uses the ATLAS v1 protocol.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth()
            .testTag("aie-provider-name"), singleLine = true, label = { Text("Provider name") })
        OutlinedTextField(endpoint, { endpoint = it }, modifier = Modifier.fillMaxWidth()
            .testTag("aie-provider-url"), singleLine = true,
            label = { Text("HTTPS URL ending in / (e.g. https://host/v1/)") })
        Text("Authentication", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (type in ProviderAuthType.entries) {
                FilterChip(selected = type == authType,
                    onClick = { authType = type },
                    label = { Text(when (type) {
                        ProviderAuthType.NONE -> "None"
                        ProviderAuthType.BEARER -> "Bearer"
                        ProviderAuthType.CUSTOM_HEADER -> "Header"
                    }) })
            }
        }
        if (authType == ProviderAuthType.CUSTOM_HEADER) {
            OutlinedTextField(headerName, { headerName = it },
                modifier = Modifier.fillMaxWidth().testTag("aie-auth-header"),
                singleLine = true, label = { Text("Header name") })
        }
        if (authType != ProviderAuthType.NONE) {
            OutlinedTextField(secret, { secret = it },
                modifier = Modifier.fillMaxWidth().testTag("aie-byok-key"),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true, label = { Text("API key (stored only in Android Keystore vault)") })
        }
        Button(onClick = {
            try {
                val profile = ProviderProfile(
                    id = UUID.randomUUID().toString(), name = name.trim(),
                    baseUrl = endpoint.trim(), authType = authType,
                    headerName = headerName.trim()
                )
                if (authType != ProviderAuthType.NONE && secret.isBlank())
                    throw IllegalArgumentException("key required")
                if (authType != ProviderAuthType.NONE) vault.put(profile.id, secret)
                try { store.save(profile) } catch (failure: Exception) {
                    vault.delete(profile.id)
                    throw failure
                }
                if (store.defaultProvider() == null) store.setDefault(profile.id)
                profiles = store.profiles()
                defaultId = store.defaultProvider()?.id
                secret = ""
                name = ""
                endpoint = ""
                feedback = "Provider saved locally. Requests require a deliberate edit action."
            } catch (_: Exception) {
                feedback = "Could not save. Check HTTPS URL, header name and key."
            }
        }, modifier = Modifier.testTag("aie-provider-save")) { Text("Save provider") }
        feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Keep location in uploads", modifier = Modifier.weight(1f))
            Switch(checked = keepLocation, onCheckedChange = {
                keepLocation = it
                store.keepLocationInUploads = it
            }, modifier = Modifier.testTag("aie-keep-location"))
        }
        Text("Off by default: uploaded JPEG/PNG is re-encoded from pixels to remove EXIF/GPS. " +
            "Turning this on may share photo metadata with your chosen provider.",
            style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { showExplanation = true }) { Text("What is sent to a provider?") }
    }

    if (showExplanation) {
        AlertDialog(onDismissRequest = { showExplanation = false },
            title = { Text("Before your first AI Edit") },
            text = { Text("Only the image you explicitly select, your edit instruction and any " +
                "optional mask or reference images are sent to the provider you choose. " +
                "Location metadata is removed by default. Your gallery is never scanned " +
                "or uploaded automatically. The remote provider may retain data or charge for a request. " +
                "You can cancel a job; that may not refund remote processing.") },
            confirmButton = {
                TextButton(onClick = {
                    store.firstUseAcknowledged = true
                    showExplanation = false
                }, modifier = Modifier.testTag("aie-consent-acknowledge")) { Text("I understand") }
            },
            dismissButton = {
                TextButton(onClick = { showExplanation = false }) { Text("Not now") }
            })
    }
}
