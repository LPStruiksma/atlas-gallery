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
import androidx.compose.ui.graphics.Color
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
 * An intentionally restrained viewer detail surface. MediaStore metadata is
 * queried ONLY while visible, on Dispatchers.IO; no separate storage
 * permission, camera/vendor API, full decode, image export, or cloud access.
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
    val facts = remember(media) { viewerDetailsFacts(media) }
    var dimensions by remember(media.uri) { mutableStateOf<ViewerDimensions?>(null) }
    var queryFinished by remember(media.uri) { mutableStateOf(false) }
    LaunchedEffect(media.uri, dimensionsReader) {
        queryFinished = false
        dimensions = dimensionsReader.read(context, media.uri)
        queryFinished = true
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF171A20),
        contentColor = Color.White,
        modifier = Modifier.testTag("atlas-viewer-details")
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                // The scrollable rows clear the 3-button navigation area
                // even with a viewer sheet drawn edge-to-edge (#680).
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                .padding(bottom = 36.dp)
        ) {
            Text("Details", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(18.dp))
            // #553: who is in the photo, first, so the half-open sheet shows it.
            photoPeople?.let { ViewerPhotoPeopleSection(media, it) }
            ViewerDetailRow("Name", facts.filename)
            ViewerDetailRow("Type", facts.kind)
            ViewerDetailRow("Album", facts.album)
            facts.relativeFolder?.let { ViewerDetailRow("Folder", it) }
            facts.fileSize?.let { ViewerDetailRow("Size", it) }
            facts.duration?.let { ViewerDetailRow("Duration", it) }
            facts.takenAtMillis?.let {
                ViewerDetailRow("Date taken", remember(it) {
                    DateFormat.getDateTimeInstance(
                        DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
                })
            }
            facts.modifiedAtMillis?.let {
                ViewerDetailRow("Date modified", remember(it) {
                    DateFormat.getDateTimeInstance(
                        DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
                })
            }
            val resolution = when {
                !queryFinished -> "Reading…"
                dimensions != null -> "${dimensions!!.width} × ${dimensions!!.height}"
                else -> "Unavailable"
            }
            ViewerDetailRow("Resolution", resolution)
        }
    }
}

@Composable
private fun ViewerDetailRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = Color(0xFFADBAC7))
        Spacer(Modifier.height(3.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium,
            color = Color.White)
    }
    HorizontalDivider(color = Color(0xFF303640))
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
