package nl.atlas.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import nl.atlas.gallery.featuredesign.AtlasSpace
import nl.atlas.gallery.featurestories.StoryCard
import nl.atlas.gallery.featurestories.StoryCatalog
import nl.atlas.gallery.featurestories.StoryDeck
import nl.atlas.gallery.featurestories.StoryMedia
import nl.atlas.gallery.featurestories.StoryPlaybackState
import nl.atlas.gallery.featurestories.currentStoryCardsOrEmpty
import nl.atlas.gallery.featurestories.planStories
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong

/*
 * E2a (#439): host wiring that makes lane S Stories reachable from the Albums
 * tab. Everything here is read-only and derived from the already-authorized
 * MediaRecord publication; it never queries MediaStore or persists a deck.
 */

/**
 * The two tokens the Stories contract needs (featurestories/README.md). Both
 * are derived from the current authorized publication, so a grant change, a
 * catalogue refresh, a different source list or a new calendar day all yield
 * different tokens. Over-invalidating only costs one bounded re-plan.
 */
internal data class StoryPlanTokens(val grantEpoch: String, val catalogRevision: String)

/** One process-wide counter: two source lists never share a revision. */
private val storyCatalogRevisionCounter = AtomicLong()

/** A fresh, never-reused catalogue revision number (#444). */
internal fun nextStoryCatalogRevision(): Long = storyCatalogRevisionCounter.incrementAndGet()

/**
 * Collision-proof catalogue revision (#444): a new number whenever the host
 * hands over a different source list reference, even one of the same size
 * within the same publication epoch; O(1), keyed by identity, so no list
 * comparison on main. The number is minted inside remember() (#451), so
 * composition never writes a plain field that an abandoned frame could leave
 * behind.
 */
@Composable
internal fun rememberStoryCatalogRevision(source: List<MediaRecord>): Long =
    remember(CatalogSourceKey(source)) { nextStoryCatalogRevision() }

/** Null whenever the gallery is not currently authorized: then no deck is shown. */
internal fun storyPlanTokensOrNull(
    grant: GalleryMediaGrant,
    publicationEpoch: Long,
    authorized: Boolean,
    catalogRevision: Long,
    epochDay: Long
): StoryPlanTokens? {
    if (!authorized || !grant.hasAny || publicationEpoch < 0L) return null
    val scope = buildString {
        if (grant.images) append('i')
        if (grant.videos) append('v')
        if (grant.selected) append('s')
    }
    return StoryPlanTokens(
        grantEpoch = "grant-$publicationEpoch-$scope",
        // A rememberStoryCatalogRevision number, not List.equals or an identity hash:
        // never compare 150k records on main, and never collide on a list swap.
        catalogRevision = "catalogue-$publicationEpoch-r$catalogRevision-d$epochDay"
    )
}

/** Android names trashed and pending MediaStore files with these display-name prefixes. */
private const val TRASHED_PREFIX = ".trashed-"
private const val PENDING_PREFIX = ".pending-"

/**
 * Map the authorized inventory to the planner's projection. Rows the provider
 * marks trashed, pending, or stored under a dot-folder are flagged so the
 * planner vetoes every copy of that URI. A favorite counts only when the
 * provider actually reported the flag.
 */
internal fun storyMediaFromAuthorized(
    records: List<MediaRecord>,
    checkpoint: () -> Unit = {}
): List<StoryMedia> {
    val out = ArrayList<StoryMedia>(records.size)
    records.forEachIndexed { index, record ->
        if ((index and 1023) == 0) checkpoint()
        out += StoryMedia(
            uri = record.uri,
            takenAtMillis = record.takenAtMillis,
            isVideo = record.isVideo,
            isFavorite = record.isFavorite && record.favoriteKnown,
            isHidden = record.name.startsWith(PENDING_PREFIX) ||
                record.relativePath.split('/').any { it.startsWith(".") },
            isTrashed = record.name.startsWith(TRASHED_PREFIX)
        )
    }
    return out
}

/** One cancellable, bounded planning pass bound to [tokens]. */
internal fun planStoryDeck(
    source: List<MediaRecord>,
    tokens: StoryPlanTokens,
    nowMillis: Long,
    zone: ZoneId,
    checkpoint: () -> Unit = {}
): StoryDeck = planStories(
    StoryCatalog(tokens.grantEpoch, tokens.catalogRevision,
        storyMediaFromAuthorized(source, checkpoint)),
    currentGrantEpoch = tokens.grantEpoch,
    nowMillis = nowMillis,
    zone = zone,
    cancellationCheckpoint = checkpoint
)

/** True when the held deck does not belong to the current tokens (or there is none). */
internal fun storyDeckNeedsPlan(deck: StoryDeck?, tokens: StoryPlanTokens?): Boolean =
    tokens != null && (deck == null || deck.grantEpoch != tokens.grantEpoch ||
        deck.catalogRevision != tokens.catalogRevision)

/** Cards for the entry row and screen: empty for a stale or absent deck. */
internal fun currentStoryCards(deck: StoryDeck?, tokens: StoryPlanTokens?): List<StoryCard> =
    currentStoryCardsOrEmpty(deck, tokens?.grantEpoch, tokens?.catalogRevision)

/**
 * Re-check at click time: the deck must still match the LIVE tokens, it must
 * contain the URI, and the URI must be in the viewer's current source list.
 * Returns the URI to open, or null when the item is no longer authorized.
 */
internal fun authorizedStoryViewerUriOrNull(
    uri: String,
    deck: StoryDeck?,
    liveTokens: StoryPlanTokens?,
    viewerSource: List<MediaRecord>
): String? {
    val cards = currentStoryCards(deck, liveTokens)
    if (cards.none { card -> card.items.any { it.uri == uri } }) return null
    return uri.takeIf { viewerSource.any { it.uri == uri } }
}

/**
 * Plans off main, only while [enabled], and only when the tokens or the
 * source list change, never per recomposition. A deck from older tokens is
 * kept but never rendered, because every reader goes through
 * [currentStoryCards] with the live tokens.
 */
@Composable
internal fun rememberStoryDeck(
    source: List<MediaRecord>,
    tokens: StoryPlanTokens?,
    enabled: Boolean
): StoryDeck? {
    var deck by remember { mutableStateOf<StoryDeck?>(null, referentialEqualityPolicy()) }
    LaunchedEffect(tokens, CatalogSourceKey(source), enabled) {
        if (tokens == null) {
            deck = null
            return@LaunchedEffect
        }
        if (!enabled || !storyDeckNeedsPlan(deck, tokens)) return@LaunchedEffect
        val planned = withContext(Dispatchers.Default) {
            planStoryDeck(source, tokens, System.currentTimeMillis(),
                ZoneId.systemDefault()) { ensureActive() }
        }
        deck = planned
    }
    return deck
}

/**
 * #457: whenever Stories stops being shown (Back, bottom nav, a tab change
 * by any route), drop the open story and any restored position still waiting
 * for its deck, so the next opening shows the grid.
 */
@Composable
internal fun ClearStoryPlaybackWhenHidden(shown: Boolean, playback: StoryPlaybackState) {
    LaunchedEffect(shown, playback) {
        if (!shown) playback.close()
    }
}

/** The Stories row at the top of the Albums tab. */
@Composable
internal fun StoriesAlbumsEntry(
    cards: List<StoryCard>,
    planning: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val subtitle = when {
        cards.isNotEmpty() -> if (cards.size == 1) "1 story from your library"
            else "${cards.size} stories from your library"
        planning -> "Looking for moments…"
        else -> "Recaps of your moments appear here"
    }
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = AtlasSpace.Gutter, vertical = 8.dp)
            .clip(RoundedCornerShape(AtlasSpace.CoverCorner))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(role = Role.Button, onClick = onOpen)
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("atlas-albums-stories-entry"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.AutoStories, contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary)
        }
        Column(Modifier.weight(1f)) {
            Text("Stories", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
