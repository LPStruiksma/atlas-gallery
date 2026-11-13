package nl.atlas.gallery

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import androidx.compose.ui.unit.dp
import nl.atlas.gallery.featurecuration.cleanupSize
import nl.atlas.gallery.featuredesign.AtlasGalleryHeader
import nl.atlas.gallery.featuredesign.AtlasPageColor
import kotlinx.coroutines.delay

/** #663: the same phone-style sizes as Photo DNA ("1 KB", "4.2 MB"). */
private fun readableBytes(size: Long): String = cleanupSize(size)

/**
 * Left = stage for trash, right = keep, both reversible until the Android OS
 * approval request. No direct file or MediaStore mutations occur here.
 */
@androidx.compose.runtime.NonSkippableComposable
@Composable
internal fun DeclutterScreen(
    folder: String,
    deck: List<MediaRecord>,
    onExit: () -> Unit,
    onTrashRequest: (List<MediaRecord>) -> Unit,
    verifying: Boolean = false
) {
    // The review deck may contain 150k entries; never ask Compose to
    // structurally compare List contents in remember/effect keys. Fresh
    // immutable deck generations reset safely even with equal metadata.
    var session by remember(CatalogSourceKey(deck)) { mutableStateOf(DeclutterSession()) }
    var reviewing by remember(CatalogSourceKey(deck)) { mutableStateOf(false) }
    var confirming by remember(CatalogSourceKey(deck)) { mutableStateOf(false) }
    var dragX by remember(CatalogSourceKey(deck), session.position) { mutableFloatStateOf(0f) }
    // A held swipe is not a settled card: postpone hires/player allocation.
    var dragging by remember(CatalogSourceKey(deck), session.position) { mutableStateOf(false) }
    val threshold = with(LocalDensity.current) { 96.dp.toPx() }
    val context = LocalContext.current
    // Status stays O(1) on each swipe. Build the actual bounded Trash
    // review list only on review/confirm, not after every Keep decision.
    val pending = remember(CatalogSourceKey(deck), session, reviewing, confirming) {
        if (reviewing || confirming) session.staged() else emptyList()
    }
    val pendingCount = session.stagedCount
    val pendingBytes = session.stagedBytes()
    val current = deck.getOrNull(session.position)
    // Rapid left/right swipes should not instantiate a new ExoPlayer
    // for each briefly focused video. Show the existing thumbnail and
    // instantiate the actual player only after the video settles.
    // Card-local state: an earlier card's settled=true MUST NEVER cause
    // an eagerly allocated ExoPlayer (or 1600px decode) on the next card's
    // first Compose frame, before its LaunchedEffect has restarted.
    var mediaSettled by remember(CatalogSourceKey(deck), current?.uri, reviewing, dragging, verifying) {
        mutableStateOf(false)
    }
    // Source replacement with the same URI resets mediaSettled above; its
    // settle effect must restart for that same new deck generation as well.
    // Otherwise a fresh deck can remain permanently stuck on the low-res
    // poster (or never mount the paused video player).
    LaunchedEffect(CatalogSourceKey(deck), current?.uri, reviewing, dragging, verifying) {
        if (current != null && !reviewing && !dragging && !verifying) {
            // Low-res poster appears immediately. Only decode full-size
            // photos or allocate Media3 after the user's swipe settles.
            delay(if (current.isVideo) 180L else 110L)
            mediaSettled = true
        }
    }
    LaunchedEffect(CatalogSourceKey(deck), session.position, reviewing, verifying, dragging) {
        if (reviewing || verifying || dragging) return@LaunchedEffect
        val neighbors = nextDeclutterPreviewIndices(session.position, deck.size)
        if (neighbors.isEmpty()) return@LaunchedEffect
        // The pending request is canceled when the user moves to another
        // card. Never decode a whole album or pre-create ExoPlayers.
        delay(65)
        val loader = SingletonImageLoader.get(context)
        prewarmDeclutterPreviewIndices(neighbors) { index ->
            loader.execute(
                ImageRequest.Builder(context)
                    .data(deck[index].uri)
                    .size(800, 800)
                    .crossfade(false)
                    .build()
            )
        }
    }
    val heavyReady = shouldRenderDeclutterHeavy(
        mediaSettled, dragging, reviewing, verifying
    )
    val maxTrashBatch = 200

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Move to Android Trash?") },
            text = { Text(
                "Stage complete: ${pendingCount} photos/videos, approximately " +
                "${readableBytes(pendingBytes)}. Android will ask for permission " +
                "before moving anything to Trash. You can cancel without changes."
            ) },
            confirmButton = {
                TextButton(
                    enabled = !verifying,
                    onClick = {
                        confirming = false
                        onTrashRequest(pending)
                    },
                    modifier = Modifier.testTag("atlas-declutter-confirm")
                ) { Text("Continue to Android confirmation") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Cancel") }
            }
        )
    }

    // The page and its header must inherit the same light/dark ink as other
    // gallery routes. A background modifier alone does not set LocalContentColor.
    Surface(Modifier.fillMaxSize(), color = AtlasPageColor,
        contentColor = MaterialTheme.colorScheme.onBackground) {
        Column(
            Modifier.fillMaxSize()
                .testTag("atlas-declutter-surface")
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
        AtlasGalleryHeader(
            title = "Declutter",
            onBack = onExit,
            backDescription = "Back to Gallery"
        )
        if (verifying) {
            LinearProgressIndicator(Modifier.fillMaxWidth()
                .testTag("atlas-declutter-verifying"))
        }
        if (reviewing) {
            Text("Review staged files", style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground)
            LazyColumn(Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(pending, key = { it.uri }) { media ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MediaPreview(media, Modifier.size(64.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .testTag("atlas-declutter-thumbnail"), ContentScale.Crop, 160)
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(media.name, maxLines = 1,
                                color = MaterialTheme.colorScheme.onSurface)
                            Text(readableBytes(media.sizeBytes),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(
                            enabled = !verifying,
                            onClick = { session = session.unstage(media.uri) }
                        ) {
                            Text("Keep")
                        }
                    }
                }
            }
            TextButton(onClick = { reviewing = false }) { Text("Return to swiping") }
            Button(
                onClick = { confirming = true },
                enabled = !verifying && pending.isNotEmpty() &&
                    pending.size <= maxTrashBatch,
                modifier = Modifier.fillMaxWidth().testTag("atlas-declutter-trash")
            ) { Text("Move ${pendingCount} to Android Trash…") }
            if (pending.size > maxTrashBatch) {
                Text("Review at most $maxTrashBatch files per Trash request. " +
                     "Unstage others or split into another session.")
            }
        } else if (current == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Folder review complete", style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground)
                    Text("${pendingCount} staged for review",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Button(onClick = { reviewing = true },
                modifier = Modifier.fillMaxWidth().testTag("atlas-declutter-review")) {
                Text("Review staged (${pendingCount})")
            }
            TextButton(onClick = { session = session.undo() },
                enabled = !verifying && session.position > 0) { Text("Undo last decision") }
        } else {
            Text(
                "${current.name} · ${readableBytes(current.sizeBytes)}" +
                (if (current.isVideo) " · video" else ""),
                maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.fillMaxSize()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .graphicsLayer { translationX = dragX }
                        .pointerInput(current.uri, session.position, verifying) {
                            if (!verifying) detectHorizontalDragGestures(
                                onDragStart = { dragging = true },
                                onDragCancel = { dragX = 0f; dragging = false },
                                onDragEnd = {
                                    val delta = dragX
                                    dragX = 0f
                                    dragging = false
                                    if (delta < -threshold) session = session.decide(deck, true)
                                    else if (delta > threshold)
                                        session = session.decide(deck, false)
                                },
                                onHorizontalDrag = { change, amount ->
                                    change.consume()
                                    dragX += amount
                                }
                            )
                        }
                        .testTag("atlas-declutter-card")
                ) {
                    if (current.isVideo) {
                        if (heavyReady) {
                            VideoPage(current.uri)
                        } else {
                            Box(
                                Modifier.fillMaxSize()
                                    .testTag("atlas-declutter-video-poster"),
                                contentAlignment = Alignment.Center
                            ) {
                                MediaPreview(current, Modifier.fillMaxSize(),
                                    ContentScale.Fit, 800)
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    contentDescription = "Video preview",
                                    modifier = Modifier.size(64.dp),
                                    tint = Color.White
                                )
                            }
                        }
                    } else {
                        // Let Coil show the inexpensive prefetched preview
                        // immediately. A stationary card upgrades to a
                        // sharper image after the gesture settles.
                        Box(Modifier.fillMaxSize()
                            .testTag("atlas-declutter-photo-progressive")) {
                            MediaPreview(current, Modifier.fillMaxSize(),
                                ContentScale.Fit, 800)
                            if (heavyReady) {
                                MediaPreview(current, Modifier.fillMaxSize()
                                    .testTag("atlas-declutter-photo-hires"),
                                    ContentScale.Fit, 1600)
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { session = session.decide(deck, true) },
                    enabled = !verifying,
                    modifier = Modifier.weight(1f).testTag("atlas-declutter-discard")
                ) {
                    Text("← Stage Trash")
                }
                Button(
                    onClick = { session = session.decide(deck, false) },
                    enabled = !verifying,
                    modifier = Modifier.weight(1f).testTag("atlas-declutter-keep")
                ) {
                    Text("Keep →")
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    enabled = !verifying && session.position > 0,
                    onClick = { session = session.undo() },
                    modifier = Modifier.testTag("atlas-declutter-undo")
                ) { Text("Undo") }
                TextButton(onClick = { reviewing = true },
                    enabled = !verifying,
                    modifier = Modifier.testTag("atlas-declutter-review")) {
                    Text("Review staged (${pendingCount})")
                }
            }
        }
        }
    }
}
