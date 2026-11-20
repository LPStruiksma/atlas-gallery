package nl.atlas.gallery.featureidentity.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.atlas.gallery.featureaiedit.provider.EditImage

/**
 * Compare all: every version cropped to the face, 2 columns, Original first, on black. Pinch zoom
 * is linked across all cells; tap a cell to pick it and return; Back or ✕ returns without a change.
 */
@Composable
internal fun CompareAllScreen(state: SwapPersonUiState, source: EditImage, machine: SwapPersonMachine) {
    BackHandler { machine.closeOverlay() }
    var zoom by remember { mutableFloatStateOf(1f) }
    val cells = state.pages.filter { key -> key == null || state.versions.any { it.key == key && it.status == VersionStatus.READY } }
    val grid = rememberLazyGridState()
    val selectedIndex = cells.indexOf(state.selected).coerceAtLeast(0)
    // More than 9 versions: keep the selected cell in view.
    LaunchedEffect(Unit) { grid.scrollToItem(selectedIndex) }
    Column(Modifier.fillMaxSize().background(SwapColors.Canvas).statusBarsPadding().navigationBarsPadding()
        .testTag("swap-compare-all")) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(machine::closeOverlay, Modifier.size(48.dp).testTag("swap-compare-close")) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = SwapColors.OnCanvas)
            }
            Text("Compare all", color = SwapColors.OnCanvas, fontSize = 18.sp)
        }
        LazyVerticalGrid(GridCells.Fixed(2), Modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                do {
                    val event = awaitPointerEvent()
                    if (event.changes.count { it.pressed } >= 2) {
                        zoom = (zoom * event.calculateZoom()).coerceIn(1f, 8f)
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                } while (event.changes.any { it.pressed })
            }
        }, state = grid, contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(cells, key = { _, k -> k ?: -1 }) { i, key ->
                val version = state.versions.firstOrNull { it.key == key }
                val selected = key == state.selected
                val label = versionLabel(state, key)
                Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp))
                    .then(if (selected) Modifier.border(2.dp, SwapColors.Accent, RoundedCornerShape(12.dp)) else Modifier)
                    .clickable { machine.select(key) }
                    .testTag(if (key == null) "swap-compare-original" else "swap-compare-cell-$i")
                    .semantics { contentDescription = label; this.selected = selected }) {
                    FaceCropImage(version?.image ?: source, (version?.request?.target ?: state.target)?.box,
                        Modifier.fillMaxSize(), zoom = zoom)
                    if (key == null) Surface(Modifier.align(Alignment.TopStart).padding(8.dp), shape = CircleShape,
                        color = SwapColors.Chrome, contentColor = SwapColors.OnCanvas) {
                        Text("Original", fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                    }
                }
            }
        }
    }
}
