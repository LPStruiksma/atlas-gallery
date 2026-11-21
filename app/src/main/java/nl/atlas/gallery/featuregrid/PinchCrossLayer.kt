package nl.atlas.gallery.featuregrid

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import nl.atlas.gallery.LocalGalleryPublicationEpoch
import nl.atlas.gallery.MediaRecord
import nl.atlas.gallery.featureorganization.SelectionCheckCircle
import nl.atlas.gallery.featureorganization.groupFullySelected
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * FLOW-1 (#711): one grid level drawn as a still picture for the two-layer
 * pinch (Samsung GRID:G6, "fake view holders" for the layout being entered).
 *
 * It draws exactly what the LazyGrid of that level would draw at rest for the
 * given visible [cells]: rounded tiles on the surface colour, the photo
 * centre-cropped from whatever size the memory cache already holds, the
 * video mark, and date headers. It never requests a decode (GRID:G7). The
 * caller scales, moves and fades it with a graphicsLayer, so a gesture frame
 * redraws nothing here: the drawing is recorded once per set of cells.
 *
 * While selecting, the check circles are the same [SelectionCheckCircle]
 * composable the grid uses, so a selection restyle reaches both layers.
 */
@Composable
internal fun PinchCrossLayer(
    cells: List<CrossCell>,
    /** The photo a cell's slot shows (null for a header or an out-of-range slot). */
    mediaAt: (slotIndex: Int) -> MediaRecord?,
    /** The date header a cell's slot shows, if any (plain grids have none). */
    headerAt: (slotIndex: Int) -> TimelineSlot.Header?,
    candidatePixels: IntArray,
    selectedMediaUris: Set<String>?,
    selecting: Boolean,
    modifier: Modifier = Modifier,
    /** URI of catalogue item i, for a header's "whole group selected" circle. */
    mediaUriAt: ((mediaIndex: Int) -> String?)? = null
) {
    val context = LocalContext.current
    val epoch = LocalGalleryPublicationEpoch.current
    val density = LocalDensity.current
    val surface = MaterialTheme.colorScheme.surfaceVariant
    val headerStyle = MaterialTheme.typography.titleSmall.merge(color = LocalContentColor.current)
    val markStyle = LocalTextStyle.current.merge(color = LocalContentColor.current)
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val bitmaps: List<ImageBitmap?> = remember(cells, mediaAt, epoch, candidatePixels) {
        PinchCrossTiming.measure("bitmaps") { cells.map { cell ->
            if (cell.header) null
            else mediaAt(cell.slotIndex)
                ?.let { cachedGridTileBitmap(context, it, epoch, candidatePixels)?.asImageBitmap() }
        } }
    }
    val cornerPx = with(density) { 3.dp.toPx() }
    val headerStartPx = with(density) { (if (selecting) 36.dp else 8.dp).toPx() }
    val markPadPx = with(density) { 6.dp.toPx() }
    Box(modifier.testTag("atlas-pinch-cross-layer")) {
        Canvas(Modifier.fillMaxSize()) { PinchCrossTiming.measure("draw") {
            val path = Path()
            cells.forEachIndexed { i, cell ->
                if (cell.header) {
                    val label = headerAt(cell.slotIndex)?.label ?: return@forEachIndexed
                    val layout = measurer.measure(label, headerStyle, maxLines = 1)
                    drawText(layout, topLeft = Offset(cell.left + headerStartPx,
                        cell.top + (cell.height - layout.size.height) / 2f))
                    return@forEachIndexed
                }
                val topLeft = Offset(cell.left.toFloat(), cell.top.toFloat())
                val size = Size(cell.width.toFloat(), cell.height.toFloat())
                val corner = CornerRadius(cornerPx, cornerPx)
                drawRoundRect(surface, topLeft, size, corner)
                val image = bitmaps.getOrNull(i)
                if (image != null && image.width > 0 && image.height > 0) {
                    path.reset()
                    path.addRoundRect(RoundRect(topLeft.x, topLeft.y, topLeft.x + size.width,
                        topLeft.y + size.height, corner))
                    // ContentScale.Crop: fill the cell, crop the centre.
                    val scale = max(size.width / image.width, size.height / image.height)
                    val srcW = (size.width / scale).roundToInt().coerceIn(1, image.width)
                    val srcH = (size.height / scale).roundToInt().coerceIn(1, image.height)
                    clipPath(path) {
                        drawImage(image,
                            srcOffset = IntOffset((image.width - srcW) / 2, (image.height - srcH) / 2),
                            srcSize = IntSize(srcW, srcH),
                            dstOffset = IntOffset(cell.left, cell.top),
                            dstSize = IntSize(cell.width, cell.height))
                    }
                }
                val media = mediaAt(cell.slotIndex)
                if (media?.isVideo == true) {
                    val mark = measurer.measure("▶", markStyle, maxLines = 1)
                    drawText(mark, topLeft = Offset(cell.left + cell.width - markPadPx - mark.size.width,
                        cell.top + cell.height - markPadPx - mark.size.height))
                }
            }
        } }
        if (selecting) {
            val selected = selectedMediaUris.orEmpty()
            val headerCirclePx = with(density) { 8.dp.roundToPx() }
            cells.forEach { cell ->
                val header = if (cell.header) headerAt(cell.slotIndex) else null
                val media = if (cell.header) null else mediaAt(cell.slotIndex)
                when {
                    media != null -> {
                        Box(Modifier.offset { IntOffset(cell.left, cell.top) }.padding(5.dp)) {
                            SelectionCheckCircle(media.uri in selected)
                        }
                    }
                    header != null -> {
                        val slot = header
                        val checked = groupFullySelected(slot.firstMediaIndex, slot.mediaCount,
                            selected) { mediaUriAt?.invoke(it) }
                        Box(Modifier.offset { IntOffset(cell.left + headerCirclePx, cell.top) }
                            .padding(top = with(density) { ((cell.height - 20.dp.roundToPx()) / 2)
                                .coerceAtLeast(0).toDp() })) {
                            SelectionCheckCircle(checked, size = 20, onPage = true)
                        }
                    }
                    else -> Unit
                }
            }
        }
    }
}
