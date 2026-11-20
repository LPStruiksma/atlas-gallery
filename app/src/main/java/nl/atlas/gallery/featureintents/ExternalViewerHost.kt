package nl.atlas.gallery.featureintents

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.atlas.gallery.GalleryViewer
import nl.atlas.gallery.MediaRecord
import nl.atlas.gallery.ViewerAccess
import nl.atlas.gallery.featuredesign.AtlasEmptyState
import nl.atlas.gallery.featuredesign.AtlasGalleryLoading
import nl.atlas.gallery.featuredesign.AtlasGalleryTheme
import nl.atlas.gallery.featuredesign.AtlasSystemBarIcons

/** Test tags for the external entry (#573). */
internal object ExternalViewerTags {
    const val ROOT = "atlas-external-viewer"
    const val LOADING = "atlas-external-loading"
    const val ERROR = "atlas-external-error"
    /** [AtlasEmptyState]'s own action tag: the error state's Back button. */
    const val ERROR_BACK = "atlas-empty-action"
}

/** Plain copy from the #573 brief; one line, no blame, one way out. */
internal const val EXTERNAL_CANT_OPEN = "Can't open this item"

/**
 * BENCH-2 (#573): the single-item viewer for an item another app handed ATLAS
 * (`ACTION_VIEW`, `MediaStore.ACTION_REVIEW`, `MediaStore.ACTION_REVIEW_SECURE`).
 *
 * - The item is probed on IO first: provider type must be image/video and the
 *   grant must open. Anything else is the plain "Can't open this item" state.
 * - It reuses [GalleryViewer] with [ViewerAccess.EXTERNAL] or [ViewerAccess.SECURE]:
 *   same black ground, zoom and video controls, but no favourite, share, trash,
 *   edit or People. Secure review also loses Details.
 * - Ordinary view/review of a MediaStore row that ATLAS can read with its own
 *   grant swipes through that row's folder ([ExternalNeighbours]); a filmstrip
 *   appears only then. Secure review always stays on the one capture.
 * - Nothing here reads the catalogue, starts a full scan or asks for a permission;
 *   the caller's URI is never persisted. [onClose] finishes the Activity, so Back
 *   (button, gesture or swipe-down) returns straight to the caller or camera.
 */
@Composable
internal fun ExternalViewerHost(
    entry: ExternalEntry,
    onClose: () -> Unit
) {
    // The viewer is always on its black ground, whatever the phone's theme.
    AtlasGalleryTheme(dark = true) {
        AtlasSystemBarIcons(dark = true)
        Box(Modifier.fillMaxSize().background(Color.Black).testTag(ExternalViewerTags.ROOT)) {
            when (entry) {
                is ExternalEntry.Viewer -> ProbedExternalViewer(entry, onClose)
                else -> ExternalCantOpen(onClose)
            }
        }
    }
}

private sealed interface ExternalViewerState {
    data object Probing : ExternalViewerState
    class Ready(val items: List<MediaRecord>) : ExternalViewerState
    data object Failed : ExternalViewerState
}

@Composable
private fun ProbedExternalViewer(entry: ExternalEntry.Viewer, onClose: () -> Unit) {
    val context = LocalContext.current
    val resolver = remember(context) { context.applicationContext.contentResolver }
    val state by produceState<ExternalViewerState>(ExternalViewerState.Probing, entry) {
        value = withContext(Dispatchers.IO) {
            when (val probe = ExternalItemProbe.probe(resolver, entry)) {
                is ExternalItemProbeResult.Readable -> {
                    val opened = externalRecord(probe)
                    // A2: ordinary view/review of a MediaStore row ATLAS can read
                    // itself gets its folder as neighbours; secure review never.
                    ExternalViewerState.Ready(
                        ExternalNeighbours.load(context.applicationContext, entry, opened) ?: listOf(opened))
                }
                ExternalItemProbeResult.Unreadable -> ExternalViewerState.Failed
            }
        }
    }
    when (val current = state) {
        // Black ground; the quiet indicator appears only if the provider is slow (>300 ms).
        ExternalViewerState.Probing -> {
            BackHandler(onBack = onClose)
            AtlasGalleryLoading(message = "Opening…", bottomInset = 0.dp,
                modifier = Modifier.testTag(ExternalViewerTags.LOADING))
        }
        is ExternalViewerState.Ready -> GalleryViewer(
            items = current.items,
            startUri = current.items.first().uri,
            access = if (entry.kind.secure) ViewerAccess.SECURE else ViewerAccess.EXTERNAL,
            onBack = onClose
        )
        ExternalViewerState.Failed -> ExternalCantOpen(onClose)
    }
}

@Composable
private fun ExternalCantOpen(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    AtlasEmptyState(
        title = EXTERNAL_CANT_OPEN,
        icon = Icons.Outlined.BrokenImage,
        actionLabel = "Back",
        onAction = onClose,
        bottomInset = 0.dp,
        modifier = Modifier.testTag(ExternalViewerTags.ERROR),
        titleTag = "atlas-external-error-title"
    )
}

/**
 * A viewer row for one caller-granted item. Id -1 keeps it out of every
 * MediaStore-row path (prefix table, share eligibility, favourite/trash).
 */
internal fun externalRecord(probe: ExternalItemProbeResult.Readable): MediaRecord {
    val isVideo = probe.kind == ExternalMediaKind.VIDEO
    return MediaRecord(
        id = -1L,
        uri = probe.uri,
        name = probe.displayName ?: if (isVideo) "Video" else "Photo",
        relativePath = "",
        sizeBytes = probe.sizeBytes.coerceAtLeast(0L),
        takenAtMillis = 0L,
        isVideo = isVideo
    )
}
