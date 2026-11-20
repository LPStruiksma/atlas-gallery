package nl.atlas.gallery.featureidentity.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import nl.atlas.gallery.featureaiedit.provider.EditImage
import nl.atlas.gallery.featureidentity.engine.TargetFace
import kotlin.math.max

/**
 * S2 target: only when the photo has several possible faces (IDS-7 `Ask`). Each face gets a 2 dp
 * white outline (12 dp corners, 4 dp outset) with a soft 1.2 s pulse; a tap fills an accent ring
 * (150 ms) and starts making versions. Faces are hit-tested with at least a 48 dp target.
 */
@Composable
internal fun TargetPickerScreen(state: SwapPersonUiState, source: EditImage, machine: SwapPersonMachine) {
    val reduced = rememberReducedMotion()
    val pulse = if (reduced) 1f else rememberInfiniteTransition(label = "pulse").animateFloat(
        0.55f, 1f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "pulse").value
    val ring = remember { Animatable(0f) }
    var tapped by remember { mutableStateOf<TargetFace?>(null) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val outset = with(density) { 4.dp.toPx() }
    val stroke = with(density) { 2.dp.toPx() }
    val corner = with(density) { 12.dp.toPx() }
    val minTarget = with(density) { 48.dp.toPx() }

    fun choose(face: TargetFace) {
        if (tapped != null) return
        tapped = face
        scope.launch {
            ring.animateTo(1f, tween(if (reduced) 0 else SwapMotion.SELECT_MS))
            machine.tapFace(face)
            tapped = null
            ring.snapTo(0f)
        }
    }

    Box(Modifier.fillMaxSize().testTag("swap-target-screen")) {
        BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            val w = constraints.maxWidth.toFloat()
            val h = constraints.maxHeight.toFloat()
            val rect = fit(source.width, source.height, w, h)
            FittedImage(source, Modifier.fillMaxSize())
            fun hit(face: TargetFace, p: Offset): Boolean {
                val tl = rect.toView(face.box.left.toFloat(), face.box.top.toFloat())
                val br = rect.toView(face.box.right.toFloat(), face.box.bottom.toFloat())
                val cx = (tl.x + br.x) / 2; val cy = (tl.y + br.y) / 2
                val hw = max(br.x - tl.x + 2 * outset, minTarget) / 2
                val hh = max(br.y - tl.y + 2 * outset, minTarget) / 2
                return p.x in (cx - hw)..(cx + hw) && p.y in (cy - hh)..(cy + hh)
            }
            Canvas(Modifier.fillMaxSize().pointerInput(state.faces) {
                detectTapGestures { p -> state.faces.firstOrNull { hit(it, p) }?.let(::choose) }
            }) {
                state.faces.forEach { face ->
                    val tl = rect.toView(face.box.left.toFloat(), face.box.top.toFloat())
                    val br = rect.toView(face.box.right.toFloat(), face.box.bottom.toFloat())
                    val topLeft = Offset(tl.x - outset, tl.y - outset)
                    val size = Size(br.x - tl.x + 2 * outset, br.y - tl.y + 2 * outset)
                    if (face == tapped) drawRoundRect(SwapColors.Accent.copy(alpha = .35f * ring.value), topLeft, size, CornerRadius(corner))
                    drawRoundRect(if (face == tapped) SwapColors.Accent else Color.White.copy(alpha = pulse),
                        topLeft, size, CornerRadius(corner),
                        style = Stroke(if (face == tapped) stroke * (1f + ring.value) else stroke))
                }
            }
            // Accessible face targets for TalkBack and tests, laid over each outline.
            state.faces.forEachIndexed { i, face ->
                val tl = rect.toView(face.box.left.toFloat(), face.box.top.toFloat())
                val br = rect.toView(face.box.right.toFloat(), face.box.bottom.toFloat())
                val sizePx = max(max(br.x - tl.x, br.y - tl.y) + 2 * outset, minTarget)
                val cx = (tl.x + br.x) / 2; val cy = (tl.y + br.y) / 2
                Box(Modifier.padding(start = with(density) { (cx - sizePx / 2).coerceAtLeast(0f).toDp() },
                    top = with(density) { (cy - sizePx / 2).coerceAtLeast(0f).toDp() })
                    .size(with(density) { sizePx.toDp() })
                    .testTag("swap-target-face-$i")
                    .semantics {
                        role = Role.Button
                        contentDescription = "Face ${i + 1} of ${state.faces.size}"
                        onClick { choose(face); true }
                    })
            }
        }
        if (state.faces.size > 1 && state.notice == null) Surface(
            Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 16.dp).testTag("swap-target-chip"),
            shape = CircleShape, color = SwapColors.Chrome, contentColor = SwapColors.OnCanvas) {
            Text("Tap the face to change", fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        }
        IconButton(machine::close, Modifier.statusBarsPadding().padding(4.dp).size(48.dp).testTag("swap-close")) {
            Icon(Icons.Filled.Close, contentDescription = "Close", tint = SwapColors.OnCanvas)
        }
        when (val notice = state.notice) {
            SwapNotice.NoFace -> CanvasMessage("We couldn't find a face in this photo",
                Modifier.align(Alignment.Center), primary = "Close" to machine::close, tag = "swap-no-face")
            is SwapNotice.FaceSmall -> AlertDialog(
                onDismissRequest = machine::close,
                text = { Text("This face is small, results may look soft") },
                confirmButton = { TextButton(machine::tryAnyway, Modifier.testTag("swap-try-anyway")) { Text("Try anyway") } },
                dismissButton = { TextButton(machine::close, Modifier.testTag("swap-small-cancel")) { Text("Cancel") } },
                modifier = Modifier.testTag("swap-face-small")
            )
            else -> Unit
        }
    }
}
