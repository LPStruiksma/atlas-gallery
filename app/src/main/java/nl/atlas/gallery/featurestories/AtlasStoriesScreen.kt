package nl.atlas.gallery.featurestories

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import android.graphics.Matrix
import android.view.TextureView
import androidx.media3.common.VideoSize
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay

/**
 * Where an open slideshow is: which story, which moment, paused or playing.
 * It is bound to the grant epoch and catalogue revision it was opened under,
 * so a replaced or revoked publication never reopens a stale story.
 */
@Immutable
data class StoryPlaybackPosition(
    val grantEpoch: String,
    val catalogRevision: String,
    val storyKey: String,
    val index: Int,
    val playing: Boolean = true
)

/**
 * Hoistable slideshow position (#444). A host that unmounts the Stories
 * screen to show the viewer keeps this above that route, so Back from the
 * viewer returns to the same story at the same moment.
 *
 * #451: [rememberStoryPlaybackState] also saves it across rotation and
 * process death. The tokens a position was bound to never survive a
 * recreate (a new catalogue revision is minted), so a restored position
 * waits in [restored] until a deck planned for the live tokens arrives; it is
 * re-bound only when that deck still has a story with the same key AND the
 * same items, otherwise it is dropped and the grid shows.
 */
@Stable
class StoryPlaybackState {
    private var live by mutableStateOf<StoryPlaybackPosition?>(null)

    /** A saved position not yet matched against a live deck (#451). */
    internal var restored by mutableStateOf<RestoredStoryPosition?>(null)
        private set

    /** Any explicit change (open, step, close, tab switch) replaces a pending restore. */
    var position: StoryPlaybackPosition?
        get() = live
        set(value) {
            live = value
            restored = null
        }

    /** The position only while it still belongs to the live tokens. */
    fun currentOrNull(grantEpoch: String?, catalogRevision: String?): StoryPlaybackPosition? =
        live?.takeIf {
            grantEpoch != null && catalogRevision != null &&
                it.grantEpoch == grantEpoch && it.catalogRevision == catalogRevision
        }

    /**
     * Read-only (safe during composition): the live position, or the restored
     * one re-bound to [deck] when that deck belongs to the live tokens and
     * still holds the same story with the same items.
     */
    fun resolvedOrNull(
        deck: StoryDeck?,
        grantEpoch: String?,
        catalogRevision: String?
    ): StoryPlaybackPosition? {
        currentOrNull(grantEpoch, catalogRevision)?.let { return it }
        val saved = restored ?: return null
        if (!deckIsLive(deck, grantEpoch, catalogRevision)) return null
        val card = deck!!.cards.firstOrNull { it.key == saved.storyKey } ?: return null
        if (storyFingerprint(card) != saved.fingerprint) return null
        return StoryPlaybackPosition(grantEpoch!!, catalogRevision!!, card.key,
            saved.index.coerceIn(card.items.indices), saved.playing)
    }

    /**
     * Commit a pending restore once a live deck is known: re-bind it, or drop
     * it when its story is gone or changed. Call from an effect, never from
     * composition itself.
     */
    fun settleRestored(deck: StoryDeck?, grantEpoch: String?, catalogRevision: String?) {
        if (restored == null || !deckIsLive(deck, grantEpoch, catalogRevision)) return
        position = resolvedOrNull(deck, grantEpoch, catalogRevision)
    }

    /**
     * #457: true while a restored position waits for a deck planned for the
     * live tokens. Without live tokens nothing will be planned, so this is
     * false and the screen shows its normal empty state instead of waiting.
     */
    fun awaitingDeck(deck: StoryDeck?, grantEpoch: String?, catalogRevision: String?): Boolean =
        restored != null && grantEpoch != null && catalogRevision != null &&
            !deckIsLive(deck, grantEpoch, catalogRevision)

    /** #457: closing Stories by any route forgets the open story and a pending restore. */
    fun close() {
        position = null
    }

    internal fun restore(saved: RestoredStoryPosition) {
        live = null
        restored = saved
    }

    /** What survives a recreate: the open story's identity and moment, never tokens. */
    internal fun savedOrNull(deck: StoryDeck?): RestoredStoryPosition? {
        restored?.let { return it }
        val p = live ?: return null
        val card = deck?.takeIf {
            it.grantEpoch == p.grantEpoch && it.catalogRevision == p.catalogRevision
        }?.cards?.firstOrNull { it.key == p.storyKey } ?: return null
        return RestoredStoryPosition(p.storyKey, storyFingerprint(card), p.index, p.playing)
    }

    private fun deckIsLive(deck: StoryDeck?, grantEpoch: String?, catalogRevision: String?) =
        deck != null && grantEpoch != null && catalogRevision != null &&
            deck.grantEpoch == grantEpoch && deck.catalogRevision == catalogRevision
}

/** The saved form of an open slideshow (#451): no grant or catalogue token. */
@Immutable
internal data class RestoredStoryPosition(
    val storyKey: String,
    val fingerprint: String,
    val index: Int,
    val playing: Boolean
)

/**
 * Same key is not enough: "On this day" tomorrow or after a library change
 * keeps its key but holds other moments. The fingerprint is the ordered item
 * URIs, so a changed story never reopens at a now-different moment.
 */
internal fun storyFingerprint(card: StoryCard): String =
    card.items.size.toString() + ":" +
        Integer.toHexString(card.items.map { it.uri }.hashCode())

private fun storyPlaybackSaver(deck: () -> StoryDeck?) = listSaver<StoryPlaybackState, Any>(
    save = { state ->
        state.savedOrNull(deck())?.let {
            listOf(it.storyKey, it.fingerprint, it.index, it.playing)
        } ?: emptyList()
    },
    restore = { saved ->
        StoryPlaybackState().apply {
            if (saved.size == 4) restore(RestoredStoryPosition(saved[0] as String,
                saved[1] as String, saved[2] as Int, saved[3] as Boolean))
        }
    }
)

/**
 * Saveable (#451). Pass the deck the host renders so a save can fingerprint
 * the open story; without it nothing is saved and a recreate shows the grid.
 */
@Composable
fun rememberStoryPlaybackState(deck: StoryDeck? = null): StoryPlaybackState {
    val currentDeck by rememberUpdatedState(deck)
    return rememberSaveable(saver = storyPlaybackSaver { currentDeck }) { StoryPlaybackState() }
}

/**
 * Independent, read-only Samsung-style Stories surface. Lane I owns nav + grant renewal;
 * it must pass the current epoch/revision (not a cached value) every time they change.
 * No media mutation, auto-upload, story music, or hidden album access.
 */
@Composable
fun AtlasStoriesScreen(
    deck: StoryDeck?,
    currentGrantEpoch: String?,
    currentCatalogRevision: String?,
    onBack: () -> Unit,
    onOpenMedia: (String) -> Unit,
    modifier: Modifier = Modifier,
    playback: StoryPlaybackState = rememberStoryPlaybackState(deck),
    /** Optional observer invoked after Media3 release (useful for lifecycle diagnostics). */
    onVideoPlayerReleased: () -> Unit = {}
) {
    val cards = currentStoryCardsOrEmpty(deck, currentGrantEpoch, currentCatalogRevision)
    // A position restored after a recreate (#451) settles once a live deck arrives.
    LaunchedEffect(playback, playback.restored, deck, currentGrantEpoch, currentCatalogRevision) {
        playback.settleRestored(deck, currentGrantEpoch, currentCatalogRevision)
    }
    // An expired permission or catalogue publication closes the player, not just its image.
    val position = playback.resolvedOrNull(deck, currentGrantEpoch, currentCatalogRevision)
    val opened = position?.let { p -> cards.firstOrNull { it.key == p.storyKey } }
    if (position != null && opened != null) {
        StoryPlayback(
            story = opened,
            index = position.index.coerceIn(opened.items.indices),
            playing = position.playing,
            onPositionChange = { index, playing ->
                playback.position = position.copy(index = index, playing = playing)
            },
            onClose = { playback.position = null },
            onOpenMedia = onOpenMedia,
            onVideoPlayerReleased = onVideoPlayerReleased,
            modifier = modifier
        )
        return
    }
    // #457: Back from the grid (also while a restore waits) closes Stories for
    // good, so the next opening shows the grid, not the old slideshow.
    val close = {
        playback.close()
        onBack()
    }
    BackHandler(onBack = close)
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            IconButton(onClick = close, modifier = Modifier.testTag("atlas-stories-back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to gallery")
            }
            Text("Stories", style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 8.dp))
        }
        if (cards.isEmpty() &&
            playback.awaitingDeck(deck, currentGrantEpoch, currentCatalogRevision)) {
            // #457: a restored slideshow waits for the re-planned deck.
            StoriesPlanningPlaceholder()
        } else if (cards.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(32.dp).testTag("atlas-stories-empty"),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Your stories will appear here", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(10.dp))
                Text(
                    "When enough accessible photos share a date or a month, ATLAS can create a private recap. Nothing is uploaded.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Text(
                "Moments from your library",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, bottom = 12.dp)
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize().testTag("atlas-stories-grid"),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                items(cards, key = { it.key }) { story ->
                    StoryTile(story, onClick = {
                        if (currentGrantEpoch != null && currentCatalogRevision != null) {
                            playback.position = StoryPlaybackPosition(
                                currentGrantEpoch, currentCatalogRevision, story.key, 0)
                        }
                    })
                }
            }
        }
    }
}

/** Shown while a restored slideshow waits for its re-planned deck (#457). */
@Composable
private fun StoriesPlanningPlaceholder() {
    Column(
        Modifier.fillMaxSize().padding(32.dp).testTag("atlas-stories-planning"),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Filled.AutoStories, contentDescription = null,
            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(14.dp))
        Text("Getting your stories ready…", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(10.dp))
        Text(
            "ATLAS is looking through your library again so you can continue where you left off.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StoryTile(story: StoryCard, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)
        .testTag("atlas-story-" + story.kind.name.lowercase())) {
        Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(18.dp))) {
            AsyncImage(
                model = Uri.parse(story.cover.uri), contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
            )
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.57f))
                .padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(story.items.size.toString() + " moments", color = Color.White,
                    style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(story.title, style = MaterialTheme.typography.titleMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(story.caption, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (story.missingCount > 0) {
            Text(story.missingCount.toString() + " items unavailable", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error)
        }
    }
}

/**
 * A photo timer and an inline, single-moment Media3 player share the same deck
 * position. Neither the Player nor its URI is persisted in the story state.
 */
@Composable
private fun StoryPlayback(
    story: StoryCard,
    index: Int,
    playing: Boolean,
    onPositionChange: (index: Int, playing: Boolean) -> Unit,
    onClose: () -> Unit,
    onOpenMedia: (String) -> Unit,
    onVideoPlayerReleased: () -> Unit,
    modifier: Modifier = Modifier
) {
    val item = story.items[index]
    BackHandler(onBack = onClose)
    LaunchedEffect(story.key, index, playing) {
        if (playing && !item.isVideo) {
            delay(5_000L)
            if (index < story.items.lastIndex) onPositionChange(index + 1, true)
            else onPositionChange(index, false)
        }
    }
    Surface(modifier.fillMaxSize().testTag("atlas-story-player"), color = Color.Black) {
        Box(Modifier.fillMaxSize()) {
            if (item.isVideo) {
                StoryVideoStage(
                    uri = item.uri,
                    playing = playing,
                    onPaused = { onPositionChange(index, false) },
                    onReleased = onVideoPlayerReleased,
                    onCompleted = {
                        if (index < story.items.lastIndex) onPositionChange(index + 1, true)
                        else onPositionChange(index, false)
                    }
                )
            } else {
                AsyncImage(
                    model = Uri.parse(item.uri),
                    contentDescription = "Moment " + (index + 1) + " of " + story.items.size,
                    contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()
                )
            }
            // The video surface has no platform controller: the same accessible
            // story controls stay on top of either media kind.
            Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.68f)).padding(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    story.items.indices.forEach { step ->
                        Box(Modifier.weight(1f).height(3.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (step <= index) Color.White else Color.Gray))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(story.title, style = MaterialTheme.typography.titleMedium,
                        color = Color.White, modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { onPositionChange(index, !playing) },
                        modifier = Modifier.testTag("atlas-story-pause")) {
                        Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (item.isVideo) {
                                if (playing) "Pause video" else "Resume video"
                            } else if (playing) "Pause slideshow" else "Resume slideshow",
                            tint = Color.White)
                    }
                    IconButton(onClick = onClose,
                        modifier = Modifier.testTag("atlas-story-close")) {
                        Icon(Icons.Filled.Close, contentDescription = "Close story", tint = Color.White)
                    }
                }
            }
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.68f)).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = {
                    if (index > 0) onPositionChange(index - 1, playing)
                }, enabled = index > 0, modifier = Modifier.testTag("atlas-story-previous")) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous moment", tint = Color.White)
                }
                TextButton(onClick = {
                    // The optional Viewer route never leaves a playing video behind.
                    onPositionChange(index, false)
                    onOpenMedia(item.uri)
                }, modifier = Modifier.testTag("atlas-story-open-gallery")) {
                    Icon(Icons.Filled.OpenInFull, null, tint = Color.White,
                        modifier = Modifier.size(16.dp))
                    Text(if (item.isVideo) "Open video" else "Open photo", color = Color.White,
                        modifier = Modifier.padding(start = 8.dp))
                }
                IconButton(onClick = {
                    if (index < story.items.lastIndex) onPositionChange(index + 1, playing)
                    else onClose()
                }, modifier = Modifier.testTag("atlas-story-next")) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Next moment", tint = Color.White)
                }
            }
            // Video frames are a platform Surface, not Compose semantics. Keep
            // the same explicit moment description available to accessibility.
            if (item.isVideo) {
                Box(Modifier.align(Alignment.Center).size(1.dp)
                    .testTag("atlas-story-video-moment-" + (index + 1))
                    .semantics { contentDescription = "Moment " + (index + 1) + " of " + story.items.size })
            }
        }
    }
}

/**
 * One local media URI at a time. The player belongs to this composition only:
 * stepping, Back, Viewer handoff, token revocation and screen unmount all
 * dispose it. Pausing the app pauses the saved Story position as well, so a
 * foreground return never starts audio automatically.
 */
@Composable
private fun StoryVideoStage(
    uri: String,
    playing: Boolean,
    onPaused: () -> Unit,
    onCompleted: () -> Unit,
    onReleased: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestPlaying by rememberUpdatedState(playing)
    val latestPaused by rememberUpdatedState(onPaused)
    val latestCompleted by rememberUpdatedState(onCompleted)
    val latestReleased by rememberUpdatedState(onReleased)
    var fitApplied by remember(uri) { mutableStateOf(false) }
    var firstFrame by remember(uri) { mutableStateOf(false) }
    var isPlaying by remember(uri) { mutableStateOf(false) }
    var failed by remember(uri) { mutableStateOf(false) }
    var ended by remember(uri) { mutableStateOf(false) }
    val player = remember(uri) {
        ExoPlayer.Builder(context)
            .setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .build().apply {
                setMediaItem(MediaItem.fromUri(Uri.parse(uri)))
                playWhenReady = false
            }
    }

    // Render decoded frames in the same hierarchy as the Story controls.
    // SurfaceView can occlude Compose chrome and yield a black emulator capture.
    val textureView = remember(uri) {
        TextureView(context).apply {
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                fitApplied = fitStoryVideoTexture(this, player.videoSize.width, player.videoSize.height)
            }
        }
    }
    DisposableEffect(player, lifecycleOwner) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                fitApplied = fitStoryVideoTexture(textureView, videoSize.width, videoSize.height)
            }
            override fun onRenderedFirstFrame() {
                fitApplied = fitStoryVideoTexture(textureView, player.videoSize.width, player.videoSize.height)
                firstFrame = true
            }
            override fun onIsPlayingChanged(value: Boolean) {
                isPlaying = value
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && latestPlaying && !ended) {
                    ended = true
                    latestCompleted()
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                failed = true
                player.pause()
                latestPaused()
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // Audio-focus loss or a disconnected headset must not leave the
                // UI advertising "Pause video" when playback has stopped.
                if (!playWhenReady && latestPlaying &&
                    (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS ||
                        reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)) {
                    latestPaused()
                }
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                player.pause()
                latestPaused()
            }
        }
        player.addListener(listener)
        // Observe video size before preparing, then reconcile known dimensions.
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        fitApplied = fitStoryVideoTexture(textureView, player.videoSize.width, player.videoSize.height)
        lifecycleOwner.lifecycle.addObserver(observer)
        if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            player.pause()
            latestPaused()
        }
        onDispose {
            // An owner can change while the same video remains mounted.
            // Rewire observers only; do not release the remembered player.
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.removeListener(listener)
        }
    }
    DisposableEffect(player, textureView) {
        player.setVideoTextureView(textureView)
        onDispose {
            player.clearVideoTextureView(textureView)
            player.pause()
            player.release()
            latestReleased()
        }
    }
    LaunchedEffect(player, playing, failed, lifecycleOwner) {
        if (playing && !failed &&
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            if (player.playbackState == Player.STATE_ENDED) {
                ended = false
                player.seekTo(0L)
            }
            player.play()
        } else {
            player.pause()
        }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { textureView },
            modifier = Modifier.fillMaxSize().testTag("atlas-story-video-surface")
        )
        if (firstFrame && fitApplied) {
            Box(Modifier.align(Alignment.TopStart).size(1.dp)
                .testTag("atlas-story-video-first-frame"))
        }
        if (isPlaying) {
            Box(Modifier.align(Alignment.TopStart).size(1.dp)
                .testTag("atlas-story-video-is-playing"))
        }
        if (failed) {
            Column(Modifier.align(Alignment.Center)
                .background(Color.Black.copy(alpha = 0.86f))
                .padding(24.dp).testTag("atlas-story-video-error"),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text("This video could not be played", color = Color.White)
                TextButton(onClick = {
                    failed = false
                    ended = false
                    player.prepare()
                    // Explicit retry: never silently loop an inaccessible URI.
                    latestPaused()
                }, modifier = Modifier.testTag("atlas-story-video-retry")) {
                    Text("Retry video", color = Color.White)
                }
            }
        }
    }
}

/** Letterbox the decoded frame without stretching square media to a phone display. */
private fun fitStoryVideoTexture(view: TextureView, videoWidth: Int, videoHeight: Int): Boolean {
    if (view.width == 0 || view.height == 0 || videoWidth <= 0 || videoHeight <= 0) return false
    val frameRatio = videoWidth.toFloat() / videoHeight
    val viewRatio = view.width.toFloat() / view.height
    val scaleX = if (frameRatio < viewRatio) frameRatio / viewRatio else 1f
    val scaleY = if (frameRatio > viewRatio) viewRatio / frameRatio else 1f
    view.setTransform(Matrix().apply {
        setScale(scaleX, scaleY, view.width / 2f, view.height / 2f)
    })
    return true
}
