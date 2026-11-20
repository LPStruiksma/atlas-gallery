package nl.atlas.gallery.featureaiedit.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.atlas.gallery.featuredesign.LocalAtlasDark
import java.util.UUID

/** Which kinds this build can use. Evidence tests provide a registry with every preset. */
val LocalProviderRegistry = staticCompositionLocalOf { ProviderRegistry.DEFAULT }

/** The key check. Evidence tests provide a fake, so no test ever reaches the network. */
val LocalKeyCheck = staticCompositionLocalOf<KeyCheck> { ProviderKeyChecker() }

/** AIE-19 copy (#430 brief rev 2). Words like endpoint, BYOK or provider stay out of it. */
internal object AiEditCopy {
    fun privacyLine(service: String) = "Sent to $service: the photo you choose to edit, " +
        "your instruction, and any mask or reference photo you add. Nothing else."

    fun costLine(preset: ProviderPreset, service: String): String = preset.cost
        ?.let { "${it.text} (checked ${it.checkedMonthYear})" }
        ?: "Charged by $service per edit."

    fun status(profile: ProviderProfile): String = when (profile.keyStatus) {
        KeyStatus.WORKS -> if (profile.authType == ProviderAuthType.NONE) "Works" else "Key works"
        KeyStatus.UNCHECKED -> "Not checked yet"
        KeyStatus.REJECTED -> "This key was not accepted"
        KeyStatus.NO_CHECK -> "Saved. Checked on your first edit."
    }

    fun outcome(outcome: KeyCheckOutcome, service: String): String = when (outcome) {
        KeyCheckOutcome.WORKS -> "Key works"
        KeyCheckOutcome.REJECTED -> "This key was not accepted"
        KeyCheckOutcome.NO_CONNECTION -> "Couldn't check. No connection."
        KeyCheckOutcome.NOT_RESPONDING -> "Couldn't check. $service didn't respond."
        KeyCheckOutcome.NO_CHECK -> "Saved. Checked on your first edit."
    }

    fun removed(service: String, newDefault: String?) =
        if (newDefault == null) "$service removed." else "$service removed. $newDefault is now the default."

    const val UNAVAILABLE = "Not available in this version"
}

/**
 * Display order for the services list: the default pinned first, then the rest in the order
 * they were added. Kinds this build cannot use follow, disabled.
 */
internal fun orderedServices(profiles: List<ProviderProfile>, defaultId: String?): List<ProviderProfile> =
    profiles.filter { it.id == defaultId } + profiles.filter { it.id != defaultId }

/** Settings row that opens the AI Edit sub-screen (full screen, own top bar and insets). */
@Composable
fun AiEditSettingsDialog(onClose: () -> Unit) {
    val dark = LocalAtlasDark.current
    // A dialog's composition re-provides LocalContext from its own window, so the store and
    // vault are bound here, to the caller's context (tests pass an isolated one).
    val context = LocalContext.current
    val store = remember(context) { ProviderSettingsStore(context) }
    val vault = remember(context) { ProviderCredentialVault(context) }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        DialogSystemBars(LocalView.current, dark)
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground) {
            AiEditSettingsScreen(onClose, store, vault)
        }
    }
}

@Composable
private fun DialogSystemBars(view: View, dark: Boolean) {
    LaunchedEffect(view, dark) {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@LaunchedEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
}

private const val SERVICES = "services"
private const val ADD = "add"
private const val CUSTOM = "custom"
private const val NEW = "new:"
private const val SERVICE = "service:"

/**
 * Settings → AI Edit (#430 brief rev 2): services as rows, "Add service" with presets, and a
 * screen per service. Keys never enter saved-instance state, logs or exports: the key field
 * uses a plain remembered [TextFieldState], and a saved key is only ever shown as "••••".
 */
@Composable
fun AiEditSettingsScreen(
    onClose: () -> Unit,
    store: ProviderSettingsStore,
    vault: ProviderCredentialVault
) {
    val registry = LocalProviderRegistry.current
    var version by remember { mutableIntStateOf(0) }
    val profiles = remember(version) { store.profiles() }
    val unknown = remember(version) { store.unavailableProfiles() }
    val defaultId = remember(version) { store.defaultProviderId() }
    val keepLocation = remember(version) { store.keepLocationInUploads }
    var page by rememberSaveable { mutableStateOf(SERVICES) }
    var lastMessage by remember { mutableStateOf<Pair<String, String>?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showExplanation by remember { mutableStateOf(false) }

    fun back() {
        page = when {
            page == SERVICES -> { onClose(); SERVICES }
            page.startsWith(NEW) || page == CUSTOM -> ADD
            else -> SERVICES
        }
    }
    BackHandler { back() }
    val changed: () -> Unit = { version++ }
    val onRemoved: (String, String?) -> Unit = { name, next ->
        page = SERVICES
        changed()
        scope.launch { snackbar.showSnackbar(AiEditCopy.removed(name, next)) }
    }

    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
        .testTag("aie-settings-screen")) {
        when {
            page == ADD -> AddServicePage(registry, onBack = ::back) { preset ->
                page = if (preset.baseUrl == null) CUSTOM else NEW + preset.kind.name
            }
            page == CUSTOM -> NewServicePage(registry.preset(ProviderKind.ATLAS_PROTOCOL),
                store, vault, onBack = ::back) { id, message ->
                changed()
                lastMessage = message?.let { id to it }
                page = if (message == null) SERVICES else SERVICE + id
            }
            page.startsWith(NEW) -> {
                val kind = ProviderKind.entries.firstOrNull { it.name == page.removePrefix(NEW) }
                if (kind == null || !registry.supports(kind)) { page = ADD } else {
                    NewServicePage(registry.preset(kind), store, vault, onBack = ::back) { id, message ->
                        changed()
                        lastMessage = message?.let { id to it }
                        page = if (message == null) SERVICES else SERVICE + id
                    }
                }
            }
            page.startsWith(SERVICE) -> {
                val id = page.removePrefix(SERVICE)
                val profile = profiles.firstOrNull { it.id == id }
                val stranger = unknown.firstOrNull { it.id == id }
                when {
                    profile != null && registry.supports(profile.kind) -> ServicePage(
                        profile, registry.preset(profile.kind), isDefault = profile.id == defaultId,
                        message = lastMessage?.takeIf { it.first == id }?.second,
                        store = store, vault = vault, onBack = ::back, onChanged = changed,
                        onMessage = { lastMessage = id to it },
                        onRemove = { next -> onRemoved(profile.name, next) },
                        eligible = { registry.supports(it.kind) })
                    profile != null || stranger != null -> UnavailablePage(
                        id, profile?.name ?: stranger!!.name, store, onBack = ::back,
                        onRemove = { next -> onRemoved(profile?.name ?: stranger!!.name, next) },
                        eligible = { registry.supports(it.kind) })
                    else -> LaunchedEffect(id) { page = SERVICES }
                }
            }
            else -> ServicesPage(
                services = orderedServices(profiles.filter { registry.supports(it.kind) }, defaultId),
                unavailable = profiles.filterNot { registry.supports(it.kind) }
                    .map { UnavailableProfile(it.id, it.name) } + unknown,
                defaultId = defaultId,
                keepLocation = keepLocation,
                onKeepLocation = { store.keepLocationInUploads = it; changed() },
                onOpen = { page = SERVICE + it },
                onAdd = { page = ADD },
                onWhatIsSent = { showExplanation = true },
                onBack = ::back
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
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

@Composable
private fun TopBar(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.testTag("aie-back")) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Spacer(Modifier.width(4.dp))
        Text(title, Modifier.weight(1f).testTag("aie-title").semantics { heading() },
            style = MaterialTheme.typography.titleLarge, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun GroupHeader(text: String) {
    Text(text, Modifier.fillMaxWidth().heightIn(min = 40.dp)
        .padding(start = 26.dp, top = 16.dp, bottom = 6.dp).semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun GroupCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        .clip(RoundedCornerShape(20.dp))
        .background(MaterialTheme.colorScheme.surfaceContainer), content = content)
}

@Composable
private fun SettingRow(
    title: String,
    tag: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    badge: String? = null,
    enabled: Boolean = true,
    chevron: Boolean = true,
    onClick: (() -> Unit)?
) {
    val alpha = if (enabled) 1f else 0.5f
    Row(Modifier.fillMaxWidth().heightIn(min = if (subtitle == null) 56.dp else 72.dp)
        .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick)
            else Modifier)
        .padding(horizontal = 16.dp, vertical = 10.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, contentDescription = null, Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(badge, Modifier.testTag("aie-default-badge")
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
        }
        if (chevron && onClick != null) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun iconFor(kind: ProviderKind?): ImageVector =
    if (kind == ProviderKind.ATLAS_PROTOCOL) Icons.Outlined.Dns else Icons.Outlined.Cloud

@Composable
private fun ServicesPage(
    services: List<ProviderProfile>,
    unavailable: List<UnavailableProfile>,
    defaultId: String?,
    keepLocation: Boolean,
    onKeepLocation: (Boolean) -> Unit,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    onWhatIsSent: () -> Unit,
    onBack: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        TopBar("AI Edit", onBack)
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
            .testTag("aie-services")) {
            if (services.isEmpty() && unavailable.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .heightIn(min = 120.dp).padding(20.dp).testTag("aie-services-empty"),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("No service connected", style = MaterialTheme.typography.titleMedium)
                    Text("AI Edit needs a service to make edits. You pay the service per edit. " +
                        "There is no subscription.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 16.dp).height(48.dp).testTag("aie-add-service")) {
                    Text("Add service")
                }
            } else {
                GroupHeader("Services")
                GroupCard {
                    for (profile in services) {
                        SettingRow(profile.name, "aie-service-row-" + profile.id,
                            subtitle = AiEditCopy.status(profile), icon = iconFor(profile.kind),
                            badge = if (profile.id == defaultId) "Default" else null,
                            onClick = { onOpen(profile.id) })
                    }
                    for (item in unavailable) {
                        SettingRow(item.name, "aie-unavailable-" + item.id,
                            subtitle = AiEditCopy.UNAVAILABLE, icon = Icons.Outlined.Cloud,
                            enabled = false, onClick = { onOpen(item.id) })
                    }
                    SettingRow("Add service", "aie-add-service", icon = Icons.Outlined.Add,
                        chevron = false, onClick = onAdd)
                }
            }
            GroupHeader("Privacy")
            GroupCard {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                    .toggleable(keepLocation, role = Role.Switch, onValueChange = onKeepLocation)
                    .padding(horizontal = 16.dp, vertical = 10.dp).testTag("aie-keep-location"),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Keep location in photos you send",
                            style = MaterialTheme.typography.bodyLarge)
                        Text("Off by default: the photo is re-encoded from its pixels to remove " +
                            "location and other metadata. Turning this on may share them with " +
                            "your chosen service.", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(12.dp))
                    // The whole row is the toggle (one TalkBack stop); the switch only draws it.
                    Switch(checked = keepLocation, onCheckedChange = null)
                }
                SettingRow("What is sent?", "aie-what-is-sent", onClick = onWhatIsSent)
            }
            Spacer(Modifier.height(88.dp))
        }
    }
}

@Composable
private fun AddServicePage(
    registry: ProviderRegistry,
    onBack: () -> Unit,
    onPick: (ProviderPreset) -> Unit
) {
    val presets = registry.offeredPresets()
    Column(Modifier.fillMaxSize()) {
        TopBar("Add service", onBack)
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            val hosted = presets.filterNot { it.advanced }
            if (hosted.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                GroupCard {
                    for (preset in hosted) SettingRow(preset.title, "aie-preset-" + preset.kind.name,
                        subtitle = preset.summary, icon = iconFor(preset.kind),
                        onClick = { onPick(preset) })
                }
            }
            val advanced = presets.filter { it.advanced }
            if (advanced.isNotEmpty()) {
                GroupHeader("Advanced")
                GroupCard {
                    for (preset in advanced) SettingRow(preset.title, "aie-preset-" + preset.kind.name,
                        subtitle = preset.summary, icon = iconFor(preset.kind),
                        onClick = { onPick(preset) })
                }
            }
        }
    }
}

@Composable
private fun GetKeyLine(preset: ProviderPreset, service: String) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(AiEditCopy.costLine(preset, service), Modifier.weight(1f).testTag("aie-cost-line"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        val url = preset.getKeyUrl
        if (url != null) TextButton(onClick = {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: ActivityNotFoundException) { }
        }, modifier = Modifier.testTag("aie-get-key")) {
            Text("Get a key")
            Spacer(Modifier.width(4.dp))
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null,
                Modifier.size(16.dp))
        }
    }
}

@Composable
private fun SaveButton(label: String, checking: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = !checking,
        modifier = Modifier.fillMaxWidth().padding(16.dp).height(48.dp)
            .testTag("aie-save")) {
        if (checking) CircularProgressIndicator(Modifier.size(20.dp).testTag("aie-checking"),
            strokeWidth = 2.dp)
        else Text(label)
    }
}

@Composable
private fun KeyField(state: TextFieldState, service: String, error: String?, enabled: Boolean) {
    OutlinedSecureTextField(
        state = state,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            .testTag("aie-key-field").semantics { contentDescription = "Key for $service" },
        enabled = enabled,
        placeholder = { Text("Paste your key") },
        isError = error != null,
        supportingText = if (error == null) null else {
            { Text(error, Modifier.testTag("aie-key-message")) }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password,
            autoCorrectEnabled = false)
    )
}

/**
 * Wireframe 3 for a hosted preset (one secure field, Save above the keyboard), and the
 * Advanced form for a custom ATLAS-protocol server. Save checks the key first: a rejected
 * key saves nothing; "couldn't check" keeps the key as "Not checked yet".
 * [onSaved] gets the new id and, when the user must see the outcome, its message.
 */
@Composable
private fun NewServicePage(
    preset: ProviderPreset,
    store: ProviderSettingsStore,
    vault: ProviderCredentialVault,
    onBack: () -> Unit,
    onSaved: (String, String?) -> Unit
) {
    val checker = LocalKeyCheck.current
    val scope = rememberCoroutineScope()
    val custom = preset.baseUrl == null
    // Not rememberSaveable: a key must never reach saved-instance state.
    val keyState = remember { TextFieldState() }
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var authType by rememberSaveable { mutableStateOf(ProviderAuthType.BEARER) }
    var headerName by rememberSaveable { mutableStateOf("X-Atlas-Token") }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val service = if (custom) name.trim().ifBlank { preset.title } else preset.title

    fun save() {
        error = null
        val needsKey = !custom || authType != ProviderAuthType.NONE
        val key = if (needsKey) ProviderKeyChecker.normalizeKey(keyState.text.toString()) else null
        if (needsKey && key == null) {
            error = "This key was not accepted"
            return
        }
        val profile = ProviderProfile(
            id = UUID.randomUUID().toString(),
            name = if (custom) name.trim() else preset.title,
            baseUrl = if (custom) url.trim() else requireNotNull(preset.baseUrl),
            authType = if (custom) authType else preset.authType,
            headerName = if (custom) headerName.trim() else preset.headerName,
            kind = preset.kind,
            modelId = preset.models.firstOrNull()?.id
        )
        if (!ProviderSettingsStore.valid(profile)) {
            error = "Check the name, server address and header name."
            return
        }
        checking = true
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                try { checker.check(profile, key, preset.keyCheck) }
                catch (_: Exception) { KeyCheckOutcome.NOT_RESPONDING }
            }
            if (outcome == KeyCheckOutcome.REJECTED) {
                checking = false
                error = AiEditCopy.outcome(outcome, service)
                return@launch
            }
            val saved = withContext(Dispatchers.IO) {
                try {
                    if (key != null) vault.put(profile.id, key)
                    try { store.save(profile.copy(keyStatus = outcome.toStatus())) }
                    catch (failure: Exception) { vault.delete(profile.id); throw failure }
                    if (store.defaultProviderId() == null) store.setDefault(profile.id)
                    true
                } catch (_: Exception) { false }
            }
            checking = false
            if (!saved) {
                error = "Couldn't save. Try again."
                return@launch
            }
            keyState.clearText()
            val showOutcome = outcome == KeyCheckOutcome.NO_CONNECTION ||
                outcome == KeyCheckOutcome.NOT_RESPONDING
            onSaved(profile.id, if (showOutcome) AiEditCopy.outcome(outcome, service) else null)
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(preset.title, onBack)
        Column(Modifier.fillMaxWidth().weight(1f)
            .then(if (custom) Modifier.verticalScroll(rememberScrollState()) else Modifier)) {
            Spacer(Modifier.height(8.dp))
            Text(AiEditCopy.privacyLine(service), Modifier.fillMaxWidth()
                .padding(horizontal = 16.dp).testTag("aie-privacy-line"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            if (custom) {
                OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 16.dp).testTag("aie-custom-name"), singleLine = true,
                    enabled = !checking, label = { Text("Name") })
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(url, { url = it }, modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 16.dp).testTag("aie-custom-url"), singleLine = true,
                    enabled = !checking,
                    label = { Text("HTTPS address ending in / (e.g. https://host/v1/)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri,
                        autoCorrectEnabled = false))
                Spacer(Modifier.height(8.dp))
                Row(Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (type in ProviderAuthType.CUSTOM_SERVER) {
                        FilterChip(selected = type == authType, onClick = { authType = type },
                            modifier = Modifier.testTag("aie-custom-auth-" + type.name),
                            label = { Text(when (type) {
                                ProviderAuthType.NONE -> "No key"
                                ProviderAuthType.BEARER -> "Bearer key"
                                ProviderAuthType.CUSTOM_HEADER -> "Key in header"
                                ProviderAuthType.KEY_SCHEME -> "fal key"
                            }) })
                    }
                }
                if (authType == ProviderAuthType.CUSTOM_HEADER) {
                    OutlinedTextField(headerName, { headerName = it }, modifier = Modifier
                        .fillMaxWidth().padding(horizontal = 16.dp).testTag("aie-custom-header"),
                        singleLine = true, enabled = !checking, label = { Text("Header name") })
                }
                Spacer(Modifier.height(8.dp))
            }
            if (!custom || authType != ProviderAuthType.NONE) {
                KeyField(keyState, service, error, enabled = true)
            } else if (error != null) {
                Text(error!!, Modifier.padding(horizontal = 16.dp).testTag("aie-key-message"),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            if (!custom) GetKeyLine(preset, service)
        }
        SaveButton("Save", checking, ::save)
    }
}

/** An existing service: "Key saved ••••" (never the key), Check key, Make default, Remove. */
@Composable
private fun ServicePage(
    profile: ProviderProfile,
    preset: ProviderPreset,
    isDefault: Boolean,
    message: String?,
    store: ProviderSettingsStore,
    vault: ProviderCredentialVault,
    onBack: () -> Unit,
    onChanged: () -> Unit,
    onMessage: (String) -> Unit,
    onRemove: (String?) -> Unit,
    eligible: (ProviderProfile) -> Boolean
) {
    val checker = LocalKeyCheck.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val rule = preset.keyCheck

    Column(Modifier.fillMaxSize()) {
        TopBar(profile.name, onBack)
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(8.dp))
            Text(AiEditCopy.privacyLine(profile.name), Modifier.fillMaxWidth()
                .padding(horizontal = 16.dp).testTag("aie-privacy-line"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            GroupCard {
                SettingRow(if (profile.authType == ProviderAuthType.NONE) "No key needed"
                    else "Key saved ••••", "aie-key-saved",
                    subtitle = message ?: AiEditCopy.status(profile), chevron = false,
                    onClick = null)
            }
            Spacer(Modifier.height(8.dp))
            if (profile.kind != ProviderKind.ATLAS_PROTOCOL) GetKeyLine(preset, profile.name)
            if (preset.models.size > 1) {
                GroupHeader("Model")
                GroupCard {
                    val selected = profile.modelId ?: preset.models.first().id
                    for (model in preset.models) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .selectable(model.id == selected, role = Role.RadioButton) {
                                store.save(profile.copy(modelId = model.id)); onChanged()
                            }.padding(horizontal = 16.dp).testTag("aie-model-" + model.id),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(model.label, Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge)
                            RadioButton(selected = model.id == selected, onClick = null)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            GroupCard {
                if (rule != null) SettingRow(if (checking) "Checking…" else "Check key",
                    "aie-check-key", chevron = false, enabled = !checking,
                    onClick = if (checking) null else ({
                        checking = true
                        scope.launch {
                            val outcome = withContext(Dispatchers.IO) {
                                try { checker.check(profile, vault.get(profile.id), rule) }
                                catch (_: Exception) { KeyCheckOutcome.NOT_RESPONDING }
                            }
                            withContext(Dispatchers.IO) {
                                store.setKeyStatus(profile.id, outcome.toStatus())
                            }
                            checking = false
                            onMessage(AiEditCopy.outcome(outcome, profile.name))
                            onChanged()
                        }
                    }))
                SettingRow(if (isDefault) "Default service" else "Make default", "aie-make-default",
                    chevron = false, enabled = !isDefault,
                    onClick = if (isDefault) null else ({ store.setDefault(profile.id); onChanged() }))
                SettingRow("Remove", "aie-remove", chevron = false,
                    onClick = { confirmRemove = true })
            }
        }
    }
    if (confirmRemove) RemoveDialog(profile.name, onDismiss = { confirmRemove = false }) {
        confirmRemove = false
        val next = store.delete(profile.id, eligible)
        onRemove(next?.name)
    }
}

@Composable
private fun UnavailablePage(
    id: String,
    name: String,
    store: ProviderSettingsStore,
    onBack: () -> Unit,
    onRemove: (String?) -> Unit,
    eligible: (ProviderProfile) -> Boolean
) {
    var confirmRemove by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TopBar(name, onBack)
        Spacer(Modifier.height(8.dp))
        Text("${AiEditCopy.UNAVAILABLE}. It is kept on this phone and never used. " +
            "Remove it, or update ATLAS to use it again.",
            Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        GroupCard {
            SettingRow("Remove", "aie-remove", chevron = false, onClick = { confirmRemove = true })
        }
    }
    if (confirmRemove) RemoveDialog(name, onDismiss = { confirmRemove = false }) {
        confirmRemove = false
        onRemove(store.delete(id, eligible)?.name)
    }
}

@Composable
private fun RemoveDialog(service: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Remove $service?") },
        text = { Text("Its key is deleted from this phone.") },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("aie-remove-confirm")) {
                Text("Remove")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
