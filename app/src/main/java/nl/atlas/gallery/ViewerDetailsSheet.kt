package nl.atlas.gallery

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import nl.atlas.gallery.featureaiedit.save.AiEditLineageStore
import nl.atlas.gallery.featureaiedit.save.AiEditDetails
import nl.atlas.gallery.featureaiedit.save.aiEditDetailsFor
import nl.atlas.gallery.featuredesign.darkGalleryColors

internal data class ViewerDimensions(val width: Int, val height: Int)

/**
 * Read-only and on-demand: never decodes source image bytes. A revoked
 * MediaStore grant or a provider refusing WIDTH/HEIGHT returns null.
 * Do not cache by URI across changed catalogue/permission epochs.
 */
internal fun viewerQueryDimensions(context: Context, uri: String): ViewerDimensions? {
    val parsed = Uri.parse(uri)
    if (parsed.scheme != "content") return null
    return try {
        context.contentResolver.query(
            parsed,
            arrayOf(MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT),
            null, null, null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val widthIndex = cursor.getColumnIndex(MediaStore.MediaColumns.WIDTH)
            val heightIndex = cursor.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
            if (widthIndex < 0 || heightIndex < 0 ||
                cursor.isNull(widthIndex) || cursor.isNull(heightIndex)
            ) return@use null
            val width = cursor.getInt(widthIndex)
            val height = cursor.getInt(heightIndex)
            if (width > 0 && height > 0) ViewerDimensions(width, height) else null
        }
    } catch (_: RuntimeException) {
        // MediaStore providers can throw on stale/revoked URIs or malformed
        // metadata. A missing resolution must never crash the gallery.
        null
    }
}

/**
 * Reads the Resolution row's dimensions. The only production reader is
 * [MediaStoreViewerDimensionsReader]; tests swap it through
 * [LocalViewerDimensionsReader] so UI timing never depends on a
 * cross-process provider lookup (atlas-native#430).
 */
internal fun interface ViewerDimensionsReader {
    suspend fun read(context: Context, uri: String): ViewerDimensions?
}

internal val MediaStoreViewerDimensionsReader = ViewerDimensionsReader { context, uri ->
    withContext(Dispatchers.IO) { viewerQueryDimensions(context, uri) }
}

internal val LocalViewerDimensionsReader =
    staticCompositionLocalOf { MediaStoreViewerDimensionsReader }

/**
 * #458: reads whether this URI is a saved AI edit, from app-private lineage
 * and the current MediaStore grant. Tests swap it through
 * [LocalAiEditDetailsReader] to hold the loading state or fail like a revoked
 * grant; the sheet treats any failure as "no marker".
 */
internal fun interface AiEditDetailsReader {
    suspend fun read(context: Context, uri: String): AiEditDetails?
}

internal val LineageAiEditDetailsReader = AiEditDetailsReader { context, uri ->
    withContext(Dispatchers.IO) {
        val store = AiEditLineageStore(context.applicationContext)
        // Only show media still readable under the current grant.
        fun visible(candidate: String): Boolean = try {
            context.contentResolver.query(
                Uri.parse(candidate), arrayOf(MediaStore.MediaColumns._ID),
                null, null, null
            )?.use { it.moveToFirst() } == true
        } catch (_: RuntimeException) { false }
        aiEditDetailsFor(uri, store.get(uri), store::all, ::visible)
    }
}

internal val LocalAiEditDetailsReader =
    staticCompositionLocalOf { LineageAiEditDetailsReader }

/**
 * Samsung-like ordering: capture date, file name/path, physical file facts,
 * then photo/video camera facts.
 *
 * #details-motion: this is the ONE details surface. The viewer composes it
 * from the first pixel of a swipe-up and keeps the same node until it is
 * closed, so nothing is swapped mid-gesture. Provider reads (dimensions,
 * EXIF, AI lineage) start only once [loadMetadata] is true, i.e. when the
 * user committed to opening Details; a gesture that merely starts never
 * touches MediaStore. Until then the rows come from the catalogue record.
 */
@Composable
internal fun ViewerDetailsContent(
    media: MediaRecord,
    /** #553: who is in the photo; null (or no faces) hides the People row. */
    photoPeople: ViewerPhotoPeople? = null,
    loadMetadata: Boolean = true,
    scrollState: ScrollState = rememberScrollState(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val dimensionsReader = LocalViewerDimensionsReader.current
    val metadataReader = LocalViewerMetadataReader.current
    val facts = remember(media) { viewerDetailsFacts(media) }
    // #458: ordinary Details render at once; the AI marker is added in place
    // once app-private lineage confirms it. Any failure means no marker.
    val aiEditReader = LocalAiEditDetailsReader.current
    var aiEdit by remember(media.uri) { mutableStateOf<AiEditDetails?>(null) }
    LaunchedEffect(media.uri, aiEditReader, loadMetadata) {
        if (!loadMetadata) return@LaunchedEffect
        aiEdit = null
        aiEdit = try {
            aiEditReader.read(context, media.uri)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null
        }
    }
    var dimensions by remember(media.uri, media.modifiedAtSeconds) {
        mutableStateOf<ViewerDimensions?>(null)
    }
    var queryFinished by remember(media.uri, media.modifiedAtSeconds) {
        mutableStateOf(false)
    }
    var metadata by remember(media.uri, media.modifiedAtSeconds, media.generationModified) {
        mutableStateOf<ViewerExtendedMetadata?>(null)
    }
    LaunchedEffect(media.uri, media.modifiedAtSeconds, dimensionsReader, loadMetadata) {
        if (!loadMetadata || queryFinished) return@LaunchedEffect
        dimensions = dimensionsReader.read(context, media.uri)
        queryFinished = true
    }
    LaunchedEffect(media.uri, media.modifiedAtSeconds, media.generationModified,
        metadataReader, loadMetadata) {
        if (!loadMetadata || metadata != null) return@LaunchedEffect
        metadata = metadataReader.read(context, media)
    }
    val displayedDimensions = dimensions ?: media.takeIf {
        it.widthPixels > 0 && it.heightPixels > 0
    }?.let { ViewerDimensions(it.widthPixels, it.heightPixels) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val capture = viewerCaptureDateMillis(facts, metadata)
    val cameraRows = if (media.isVideo) emptyList() else viewerCameraRows(metadata)
    val videoRows = if (media.isVideo) {
        viewerVideoRows(metadata, media.durationMillis)
    } else emptyList()
    Column(
        modifier.fillMaxSize().verticalScroll(scrollState)
            .padding(horizontal = 24.dp)
            .padding(top = 20.dp, bottom = 36.dp)
    ) {
            Text("Details", color = muted, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(12.dp))
            capture?.let {
                Text(
                    remember(it) {
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                            .format(Date(it))
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.testTag("atlas-details-capture-date")
                )
                Spacer(Modifier.height(16.dp))
            }
            ViewerDetailRow("Name", facts.filename)
            if (aiEdit?.editedWithAi == true) ViewerAiEditedRow()
            aiEdit?.group?.let { group ->
                if (group.originalUri == media.uri) {
                    ViewerDetailRow("AI versions (${group.editedUris.size})", "Saved AI edits")
                } else {
                    ViewerDetailRow("Edited from…", "Original photo (AI edit)")
                }
            }
            facts.relativeFolder?.let { ViewerDetailRow("Path", it) }
            facts.fileSize?.let { ViewerDetailRow("Size", it) }
            val resolution = when {
                displayedDimensions != null ->
                    "${displayedDimensions.width} × ${displayedDimensions.height}"
                !queryFinished -> "Reading…"
                else -> "Unavailable"
            }
            ViewerDetailRow("Resolution", resolution)
            viewerMegapixels(displayedDimensions)?.let { ViewerDetailRow("Megapixels", it) }

            // #553 preserved: the same interactive People section and callbacks.
            photoPeople?.let { ViewerPhotoPeopleSection(media, it) }

            if (cameraRows.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Camera details", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag("atlas-details-camera"))
                cameraRows.forEach { ViewerDetailRow(it.label, it.value) }
            }
            if (videoRows.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Video details", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag("atlas-details-video"))
                videoRows.forEach { ViewerDetailRow(it.label, it.value) }
            }
            Spacer(Modifier.height(12.dp))
            ViewerDetailRow("Type", facts.kind)
            ViewerDetailRow("Album", facts.album)
            facts.modifiedAtMillis?.let {
                ViewerDetailRow("Date modified", remember(it) {
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                        .format(Date(it))
                })
            }
    }
}

/**
 * The details surface itself: the viewer's black ground, continuing below
 * the photo (Quiet Frame: viewer ground #000000), with the Details rows on
 * it. No sheet window, scrim, handle or corner shape that could change
 * between the drag and the settled state. The rows' scroll viewport ends
 * above the navigation bar while the ground runs to the screen edge (#680).
 * [contentAlpha] is read in the draw phase only, so a drag never recomposes
 * the rows (Samsung fades its rows in over the second half of the slide).
 */
@Composable
internal fun ViewerDetailsSurface(
    media: MediaRecord,
    modifier: Modifier = Modifier,
    photoPeople: ViewerPhotoPeople? = null,
    loadMetadata: Boolean = true,
    contentAlpha: () -> Float = { 1f }
) {
    MaterialTheme(colorScheme = darkGalleryColors, typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Box(modifier.background(VIEWER_DETAILS_GROUND)
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))) {
                ViewerDetailsContent(media, photoPeople, loadMetadata,
                    modifier = Modifier.graphicsLayer { alpha = contentAlpha() }
                        .testTag("atlas-details-content"))
            }
        }
    }
}

/** Quiet Frame viewer ground: the details continue the viewer's black. */
internal val VIEWER_DETAILS_GROUND = Color.Black

@Composable
private fun ViewerDetailRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(3.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * #458: one plain-text row, read by TalkBack as the whole phrase. Not a
 * button and not colour-coded; it sits between Name and "Edited from…".
 */
@Composable
private fun ViewerAiEditedRow() {
    // One TalkBack stop that reads the whole phrase.
    Column(Modifier.fillMaxWidth().padding(vertical = 9.dp)
        .semantics(mergeDescendants = true) {}
        .testTag("atlas-details-ai-edited")) {
        Text(AI_EDITED_LABEL, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** #458: the one user-facing phrase for a saved AI edit (Details and Photo DNA). */
internal const val AI_EDITED_LABEL = "Edited with AI"
