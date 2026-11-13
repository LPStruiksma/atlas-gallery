package nl.atlas.gallery

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

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
 * Samsung-like ordering: capture date, file name/path, physical file facts,
 * then photo/video camera facts. Only this fully opened sheet reads metadata;
 * the drag preview below stays entirely free of content-provider IO.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ViewerDetailsSheet(
    media: MediaRecord, onDismiss: () -> Unit,
    /** #553: who is in the photo; null (or no faces) hides the People row. */
    photoPeople: ViewerPhotoPeople? = null
) {
    val context = LocalContext.current
    val dimensionsReader = LocalViewerDimensionsReader.current
    val metadataReader = LocalViewerMetadataReader.current
    val facts = remember(media) { viewerDetailsFacts(media) }
    var dimensions by remember(media.uri, media.modifiedAtSeconds) {
        mutableStateOf<ViewerDimensions?>(null)
    }
    var queryFinished by remember(media.uri, media.modifiedAtSeconds) {
        mutableStateOf(false)
    }
    var metadata by remember(media.uri, media.modifiedAtSeconds, media.generationModified) {
        mutableStateOf<ViewerExtendedMetadata?>(null)
    }
    LaunchedEffect(media.uri, media.modifiedAtSeconds, dimensionsReader) {
        queryFinished = false
        dimensions = dimensionsReader.read(context, media.uri)
        queryFinished = true
    }
    LaunchedEffect(media.uri, media.modifiedAtSeconds, media.generationModified, metadataReader) {
        metadata = null
        metadata = metadataReader.read(context, media)
    }
    val displayedDimensions = dimensions ?: media.takeIf {
        it.widthPixels > 0 && it.heightPixels > 0
    }?.let { ViewerDimensions(it.widthPixels, it.heightPixels) }
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val capture = viewerCaptureDateMillis(facts, metadata)
    val cameraRows = if (media.isVideo) emptyList() else viewerCameraRows(metadata)
    val videoRows = if (media.isVideo) {
        viewerVideoRows(metadata, media.durationMillis)
    } else emptyList()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = ink,
        modifier = Modifier.testTag("atlas-viewer-details")
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(bottom = 36.dp)
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
}

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
 * The same presentation facts as the full details sheet, without a provider
 * query: a live drag must show content without triggering MediaStore access.
 */
@Composable
internal fun ViewerDetailsPreview(media: MediaRecord) {
    val facts = remember(media) { viewerDetailsFacts(media) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)
        .testTag("atlas-details-drag-content")) {
        Text("Details", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        ViewerDetailRow("Name", facts.filename)
        ViewerDetailRow("Type", facts.kind)
        ViewerDetailRow("Album", facts.album)
    }
}
