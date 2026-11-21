package nl.atlas.gallery.featureaiedit.ui

import nl.atlas.gallery.featuredesign.AtlasTokens
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import nl.atlas.gallery.featureaiedit.engine.AiEditGate
import nl.atlas.gallery.featureaiedit.engine.AiEditIssue
import nl.atlas.gallery.featureaiedit.engine.AiEditServices
import nl.atlas.gallery.featureaiedit.engine.AiEditStateCopy
import nl.atlas.gallery.featureaiedit.engine.IssueAction
import nl.atlas.gallery.featureaiedit.settings.AiEditSettingsDialog
import nl.atlas.gallery.featureaiedit.settings.LocalProviderRegistry
import nl.atlas.gallery.featureaiedit.settings.ProviderCredentialVault
import nl.atlas.gallery.featureaiedit.settings.ProviderSettingsStore
import kotlin.math.cos
import kotlin.math.sin

private const val PREFERENCES = "atlas-ai-edit"

/**
 * Quiet Frame v3 colours for the editor (docs/design/QUIET_FRAME.md). The viewer ground stays black
 * in both themes, so the dark-theme values apply; they move to shared tokens with BRAND-L2.
 */
internal val AiEditColors = AtlasTokens.Colors.AiEdit

private val Body = TextStyle(fontSize = 15.sp, lineHeight = 22.sp)
private val Secondary = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
private val Nav = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
/** Section title size in the top row (preflight note: the 38 sp screen title doesn't fit at 1.3). */
private val Title = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)

/** Everything the stateless body shows besides [AiEditUiState]. */
internal data class AiEditExtras(
    val services: List<AiEditServiceChoice> = emptyList(),
    val costLine: String? = null,
    val sizeNotice: String? = null,
    val gate: AiEditGate? = null,
    val showHoldHint: Boolean = true,
    val toast: String? = null
)

/** User actions of the stateless body. */
internal class AiEditActions(
    val onBack: () -> Unit = {},
    val onPromptChanged: (String) -> Unit = {},
    val onGenerate: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onAccept: () -> Unit = {},
    val onChangePrompt: () -> Unit = {},
    val onStartOver: () -> Unit = {},
    val onSelectCandidate: (String) -> Unit = {},
    val onChooseService: (String) -> Unit = {},
    val onIssueAction: (IssueAction) -> Unit = {},
    val onConnect: () -> Unit = {},
    val onGateConfirm: () -> Unit = {},
    val onGateDismiss: () -> Unit = {},
    val onGateOpenService: () -> Unit = {},
    val onHintSeen: () -> Unit = {},
    val onView: () -> Unit = {},
    val onToastShown: () -> Unit = {}
)

/**
 * Photo-first full-screen editor (#431). On recreation the ViewModel restores from SavedStateHandle,
 * the session's private files and its request journal. Nothing is sent until Generate and every
 * required confirmation.
 */
@Composable
internal fun AiEditScreen(
    sourceUri: Uri,
    sessionId: String,
    onRefresh: () -> Unit,
    onSaved: (Uri) -> Unit,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext
    val registry = LocalProviderRegistry.current
    val store = remember(sessionId) { AiEditWorkingStore(app, sessionId) }
    val preferences = remember(app) { app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE) }
    val environment = remember(sessionId, registry) {
        AiEditEnvironment(app, store, ProviderSettingsStore(app), ProviderCredentialVault(app), preferences, registry)
    }
    val vm: AiEditViewModel = viewModel(key = "ai-edit:$sessionId")
    var settingsOpen by remember { mutableStateOf(false) }
    var hintSeen by remember { mutableStateOf(preferences.getBoolean("holdHintSeen", false)) }
    var savedUri by remember { mutableStateOf<Uri?>(null) }
    LaunchedEffect(sessionId, settingsOpen) { if (!settingsOpen) vm.initialize(environment, sourceUri) }
    var confirmDiscard by remember { mutableStateOf(false) }
    fun back() {
        when {
            vm.ui.phase == AiEditPhase.Accepting -> Unit // AIE-4 journal must finish.
            vm.ui.hasUnsavedCandidates -> confirmDiscard = true
            else -> vm.discard(store, onExit)
        }
    }
    fun open(url: String?) {
        if (url == null) return
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT))
        }
    }
    val profileKind = environment.settings.profiles().firstOrNull { it.id == vm.ui.providerId }?.kind
    BackHandler { back() }
    val selectedUri = vm.ui.selectedCandidate?.let { ref ->
        runCatching { Uri.fromFile(store.candidateFile(ref.fileName)) }.getOrNull()
    }
    AiEditScreenContent(
        state = vm.ui,
        extras = AiEditExtras(vm.services, vm.costLine, vm.sizeNotice, vm.gate, !hintSeen, vm.toast),
        originalUri = sourceUri,
        selectedCandidateUri = selectedUri,
        candidateUri = { item -> runCatching { Uri.fromFile(store.candidateFile(item.fileName)) }.getOrNull() },
        actions = AiEditActions(
            onBack = ::back,
            onPromptChanged = vm::promptChanged,
            onGenerate = vm::requestGenerate,
            onCancel = vm::cancel,
            onAccept = {
                vm.accept(app, sourceUri, store, onRefresh) { uri -> savedUri = uri; onSaved(uri) }
            },
            onChangePrompt = vm::changePrompt,
            onStartOver = { vm.selectBranch(null); vm.changePrompt() },
            onSelectCandidate = vm::selectCandidate,
            onChooseService = vm::chooseService,
            onIssueAction = { action ->
                when (action) {
                    IssueAction.CHANGE_PROMPT -> vm.changePrompt()
                    IssueAction.TRY_ANOTHER_SERVICE -> Unit // The body opens the chip menu.
                    IssueAction.OPEN_SETTINGS -> settingsOpen = true
                    IssueAction.OPEN_SERVICE -> open(profileKind?.let { AiEditServices.pricingUrl(it) })
                    IssueAction.TRY_AGAIN, IssueAction.TRY_AGAIN_CHARGED -> vm.requestGenerate()
                    IssueAction.CHECK_AGAIN -> vm.checkAgain()
                    IssueAction.DISCARD -> vm.discardIssue()
                }
            },
            onConnect = { settingsOpen = true },
            onGateConfirm = vm::confirmGate,
            onGateDismiss = vm::dismissGate,
            onGateOpenService = { open(profileKind?.let { AiEditServices.pricingUrl(it) }) },
            onHintSeen = { hintSeen = true; preferences.edit().putBoolean("holdHintSeen", true).apply() },
            onView = { savedUri?.let { onExit() } },
            onToastShown = vm::dismissToast
        )
    )
    if (settingsOpen) AiEditSettingsDialog(onClose = { settingsOpen = false })
    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text("Discard AI edits?") },
        text = { Text("Your original photo will stay unchanged. Unsaved results from this session will be removed.") },
        confirmButton = {
            TextButton(onClick = { confirmDiscard = false; vm.discard(store, onExit) },
                modifier = Modifier.testTag("atlas-ai-edit-discard")) { Text("Discard") }
        },
        dismissButton = {
            TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") }
        },
        modifier = Modifier.testTag("atlas-ai-edit-discard-confirm")
    )
}

/**
 * Stateless body (Quiet Frame v3, #431 rev 4 A–F). Usable by synthetic emulator evidence.
 * Black viewer ground, photo dominant, one card above the lapis action slab.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AiEditScreenContent(
    state: AiEditUiState,
    extras: AiEditExtras,
    originalUri: Uri,
    selectedCandidateUri: Uri?,
    candidateUri: (AiEditWorkingCandidate) -> Uri?,
    actions: AiEditActions
) {
    var menuOpen by remember { mutableStateOf(false) }
    var showOriginal by remember { mutableStateOf(false) }
    val hasPreview = selectedCandidateUri != null && state.phase in
        setOf(AiEditPhase.Result, AiEditPhase.Accepting, AiEditPhase.Saved)
    val service = state.serviceName ?: "the service"
    val noService = extras.services.isEmpty()
    val busy = state.phase in setOf(AiEditPhase.Generating, AiEditPhase.Accepting)

    Box(
        Modifier.fillMaxSize().background(AiEditColors.Ground)
            .windowInsetsPadding(WindowInsets.safeDrawing).imePadding()
            .testTag("atlas-ai-edit-screen")
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 4.dp)) {
            // Top row: back, title, service chip.
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions.onBack, enabled = state.phase != AiEditPhase.Accepting,
                    modifier = Modifier.size(48.dp).testTag("atlas-ai-edit-back")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = AiEditColors.Ink)
                }
                Text("AI Edit", style = Title, color = AiEditColors.Ink,
                    modifier = Modifier.padding(start = 4.dp).semantics { heading() })
                Spacer(Modifier.weight(1f))
                if (!noService) Box {
                    ServiceChip(state, enabled = !busy && state.phase != AiEditPhase.Saved,
                        onClick = { menuOpen = true })
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        for (choice in extras.services) DropdownMenuItem(
                            text = { Text(listOfNotNull(choice.serviceName, choice.modelName).joinToString(", ")) },
                            onClick = { menuOpen = false; actions.onChooseService(choice.profileId) },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("atlas-ai-edit-service-${choice.profileId}")
                        )
                    }
                }
            }
            // Photo: fit on black, never hidden. Hold (or the "Show original" action) shows the original.
            Box(
                Modifier.fillMaxWidth().weight(1f).heightIn(min = 160.dp)
                    .testTag("atlas-ai-edit-compare")
                    .pointerInput(hasPreview) {
                        detectTapGestures(onPress = {
                            if (hasPreview) { showOriginal = true; actions.onHintSeen() }
                            tryAwaitRelease()
                            showOriginal = false
                        })
                    }
                    .semantics {
                        if (hasPreview) customActions = listOf(CustomAccessibilityAction("Show original") {
                            showOriginal = !showOriginal; true
                        })
                    },
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(model = if (hasPreview && !showOriginal) selectedCandidateUri else originalUri,
                    contentDescription = if (hasPreview && !showOriginal) "Edited photo" else "Original photo",
                    contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                if (hasPreview && (extras.showHoldHint || showOriginal)) Text(
                    if (showOriginal) "Original" else "Hold to see original",
                    style = Secondary, color = AiEditColors.Ink,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)
                        .background(AiEditColors.Surface, RoundedCornerShape(22.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                        .testTag("atlas-ai-edit-compare-hint")
                )
            }
            if (state.candidates.size > 1 && state.phase == AiEditPhase.Result) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("atlas-ai-edit-candidates")) {
                    items(state.candidates, key = { it.id }) { c ->
                        AsyncImage(model = candidateUri(c), contentDescription = "Earlier result",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp))
                                .background(if (c.id == state.selectedId) AiEditColors.OnBrand else AiEditColors.Surface)
                                .padding(if (c.id == state.selectedId) 2.dp else 0.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { actions.onSelectCandidate(c.id) }
                                .testTag("atlas-ai-edit-candidate-${c.id}"))
                    }
                }
            }
            // One short line under the photo: cost/currency/unknown, or the size notice.
            if (!noService && state.phase in setOf(AiEditPhase.Idle, AiEditPhase.Composing, AiEditPhase.Cancelled)) {
                extras.costLine?.let {
                    Text(it, style = Secondary, color = AiEditColors.InkSecondary, maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("atlas-ai-edit-cost-line"))
                }
                extras.sizeNotice?.let {
                    Text(it, style = Secondary, color = AiEditColors.InkSecondary,
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp).testTag("atlas-ai-edit-size-notice"))
                }
            }
            // A card for a decline, error or outcome unknown, right above the slab.
            val card = state.issue?.let { AiEditStateCopy.card(it, service, state.issueDetail) }
            if (card != null && state.phase in setOf(AiEditPhase.Failed, AiEditPhase.Refused)) {
                Column(Modifier.fillMaxWidth().padding(top = 8.dp)
                    .background(AiEditColors.Surface, RoundedCornerShape(22.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .testTag("atlas-ai-edit-issue-${state.issue!!.name}")) {
                    Text(card.title, style = Nav, color = AiEditColors.Ink)
                    Text(card.detail, style = Secondary, color = AiEditColors.InkSecondary)
                }
            } else if (state.phase in setOf(AiEditPhase.Failed, AiEditPhase.Result) && state.error != null) {
                Text(state.error, style = Secondary, color = AiEditColors.Ink,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        .background(AiEditColors.Surface, RoundedCornerShape(22.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp).testTag("atlas-ai-edit-failed"))
            }
            Spacer(Modifier.size(8.dp))
            Slab {
                when {
                    noService -> {
                        Column(Modifier.weight(1f).testTag("atlas-ai-edit-no-service")) {
                            Text(AiEditStateCopy.NO_SERVICE_TITLE, style = Nav, color = AiEditColors.OnBrand)
                            Text(AiEditStateCopy.NO_SERVICE_DETAIL, style = Secondary, color = AiEditColors.OnBrandSecondary)
                        }
                        PrimaryPill("Connect a service", "atlas-ai-edit-connect", onClick = actions.onConnect)
                    }
                    state.phase == AiEditPhase.Generating -> {
                        HeldSphereArc(state.progress, state.determinate, Modifier.size(48.dp))
                        Text(AiEditStateCopy.working(state.sending, service), style = Body, color = AiEditColors.OnBrand,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                                .semantics { liveRegion = LiveRegionMode.Polite }.testTag("atlas-ai-edit-working"))
                        SecondaryPill("Cancel", "atlas-ai-edit-cancel", onClick = actions.onCancel)
                    }
                    state.phase == AiEditPhase.Accepting -> {
                        HeldSphereArc(null, false, Modifier.size(48.dp).testTag("atlas-ai-edit-accepting"))
                        Text("Saving a copy…", style = Body, color = AiEditColors.OnBrand, modifier = Modifier.weight(1f))
                    }
                    state.phase == AiEditPhase.Saved -> {
                        Text("Saved as a copy", style = Body, color = AiEditColors.OnBrand,
                            modifier = Modifier.weight(1f).testTag("atlas-ai-edit-saved"))
                        SecondaryPill("View", "atlas-ai-edit-view", onClick = actions.onView)
                        PrimaryPill("Done", "atlas-ai-edit-done", onClick = actions.onBack)
                    }
                    state.phase == AiEditPhase.Result -> {
                        Column(Modifier.weight(1f)) {
                            Text("“${state.selectedCandidate?.prompt ?: state.prompt}”", style = Secondary,
                                color = AiEditColors.OnBrandSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("atlas-ai-edit-result"))
                            // Wraps instead of clipping at large font scales or narrow widths.
                            FlowRow(Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                SecondaryPill("Change", "atlas-ai-edit-change", onClick = actions.onChangePrompt)
                                SecondaryPill("Start over", "atlas-ai-edit-start-over", onClick = actions.onStartOver)
                                PrimaryPill("Save copy", "atlas-ai-edit-accept",
                                    enabled = state.selectedCandidate != null, onClick = actions.onAccept)
                            }
                        }
                    }
                    card != null && state.phase in setOf(AiEditPhase.Failed, AiEditPhase.Refused) -> {
                        FlowRow(Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        card.actions.forEachIndexed { index, action ->
                            val label = AiEditStateCopy.actionLabel(action, service)
                            val tag = "atlas-ai-edit-action-${action.name}"
                            val click = {
                                if (action == IssueAction.TRY_ANOTHER_SERVICE) menuOpen = true
                                actions.onIssueAction(action)
                            }
                            if (index == card.actions.lastIndex) PrimaryPill(label, tag, onClick = click)
                            else SecondaryPill(label, tag, onClick = click)
                        }
                        }
                    }
                    else -> {
                        PromptField(state.prompt, actions.onPromptChanged, Modifier.weight(1f))
                        val mayGenerate = state.prompt.isNotBlank() && state.phase in setOf(
                            AiEditPhase.Idle, AiEditPhase.Composing, AiEditPhase.Cancelled, AiEditPhase.Failed)
                        PrimaryPill("Generate", "atlas-ai-edit-generate", enabled = mayGenerate,
                            onClick = actions.onGenerate)
                    }
                }
            }
            Spacer(Modifier.size(8.dp))
        }
        extras.toast?.let { message ->
            Text(message, style = Secondary, color = AiEditColors.Ink,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp, start = 16.dp, end = 16.dp)
                    .background(AiEditColors.Surface, RoundedCornerShape(22.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .testTag("atlas-ai-edit-toast"))
            LaunchedEffect(message) { kotlinx.coroutines.delay(4_000); actions.onToastShown() }
        }
    }
    extras.gate?.let { GateDialog(it, service, actions) }
}

@Composable
private fun ServiceChip(state: AiEditUiState, enabled: Boolean, onClick: () -> Unit) {
    val name = state.serviceName ?: "Service"
    val described = listOfNotNull(name, state.modelName).joinToString(", ")
    Column(
        Modifier.sizeIn(minHeight = 48.dp, minWidth = 48.dp).widthIn(max = 200.dp)
            .clip(RoundedCornerShape(24.dp)).background(AiEditColors.Surface)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .semantics { contentDescription = "Service: $described. Double tap to change" }
            .testTag("atlas-ai-edit-provider"),
        verticalArrangement = Arrangement.Center
    ) {
        Text(name, style = Nav, color = if (enabled) AiEditColors.Ink else AiEditColors.InkSecondary,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        state.modelName?.let {
            Text(it, style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp), color = AiEditColors.InkSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Slab(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(RoundedCornerShape(24.dp))
            .background(AiEditColors.Slab).padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("atlas-ai-edit-slab"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        content = content
    )
}

@Composable
private fun PrimaryPill(label: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp))
            .background(if (enabled) AiEditColors.OnBrand else AiEditColors.OnBrand.copy(alpha = .45f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp).testTag(tag),
        contentAlignment = Alignment.Center
    ) { Text(label, style = Nav, color = AiEditColors.Slab, maxLines = 1) }
}

@Composable
private fun SecondaryPill(label: String, tag: String, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp).testTag(tag),
        contentAlignment = Alignment.Center
    ) { Text(label, style = Nav, color = AiEditColors.OnBrandSecondary, maxLines = 1) }
}

@Composable
private fun PromptField(value: String, onChange: (String) -> Unit, modifier: Modifier) {
    Box(modifier.heightIn(min = 48.dp).padding(horizontal = 6.dp), contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty()) Text("Describe your edit…", style = Body, color = AiEditColors.OnBrandSecondary)
        BasicTextField(
            value = value, onValueChange = { onChange(it.take(16_384)) },
            textStyle = Body.copy(color = AiEditColors.OnBrand),
            cursorBrush = SolidColor(AiEditColors.OnBrand),
            maxLines = 4,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Describe your edit" }
                .testTag("atlas-ai-edit-prompt")
        )
    }
}

/**
 * The held sphere climbing an arc (Quiet Frame loading). It moves only when real progress changes,
 * so nothing animates on its own and reduced motion needs no special case. Indeterminate work
 * keeps the sphere at a fixed start position; no invented percentage.
 */
@Composable
internal fun HeldSphereArc(progress: Float?, determinate: Boolean, modifier: Modifier) {
    val fraction = if (determinate) (progress ?: 0f).coerceIn(0f, 1f) else 0.15f
    Canvas(modifier.testTag("atlas-ai-edit-sphere")) {
        val stroke = 3.dp.toPx()
        val radius = size.minDimension / 2 - 8.dp.toPx()
        val center = Offset(size.width / 2, size.height / 2)
        drawArc(AiEditColors.OnBrandSecondary.copy(alpha = .45f), startAngle = 180f, sweepAngle = 180f,
            useCenter = false, topLeft = Offset(center.x - radius, center.y - radius),
            size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2), style = Stroke(stroke))
        val angle = Math.toRadians((180.0 + 180.0 * fraction))
        val sphere = Offset(center.x + radius * cos(angle).toFloat(), center.y + radius * sin(angle).toFloat())
        drawCircle(AiEditColors.OnBrand, radius = 6.dp.toPx(), center = sphere)
    }
}

@Composable
private fun GateDialog(gate: AiEditGate, service: String, actions: AiEditActions) {
    val (title, text) = when (gate) {
        AiEditGate.FIRST_USE -> AiEditStateCopy.FIRST_USE_TITLE to AiEditStateCopy.FIRST_USE_TEXT
        AiEditGate.PRICE_UNKNOWN -> AiEditStateCopy.priceConsentTitle(service) to AiEditStateCopy.PRICE_CONSENT_DETAIL
        AiEditGate.CHARGE_AGAIN -> AiEditStateCopy.chargeAgainTitle(service) to AiEditStateCopy.CHARGE_AGAIN_DETAIL
    }
    val confirm = when (gate) {
        AiEditGate.FIRST_USE -> "I understand"
        AiEditGate.PRICE_UNKNOWN -> "Continue"
        AiEditGate.CHARGE_AGAIN -> "Try again"
    }
    val dismiss = if (gate == AiEditGate.FIRST_USE) "Not now" else "Cancel"
    AlertDialog(
        onDismissRequest = actions.onGateDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text)
                if (gate != AiEditGate.CHARGE_AGAIN) TextButton(onClick = actions.onGateOpenService,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("atlas-ai-edit-gate-open-service")) {
                    Text("Open $service")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = actions.onGateConfirm,
                modifier = Modifier.heightIn(min = 48.dp).testTag("atlas-ai-edit-gate-confirm")) { Text(confirm) }
        },
        dismissButton = {
            TextButton(onClick = actions.onGateDismiss,
                modifier = Modifier.heightIn(min = 48.dp).testTag("atlas-ai-edit-gate-dismiss")) { Text(dismiss) }
        },
        modifier = Modifier.testTag("atlas-ai-edit-gate-${gate.name}")
    )
}

