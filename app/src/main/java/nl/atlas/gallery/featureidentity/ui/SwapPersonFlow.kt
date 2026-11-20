package nl.atlas.gallery.featureidentity.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.atlas.gallery.featureaiedit.provider.EditImage
import nl.atlas.gallery.featuredesign.AtlasTokens
import nl.atlas.gallery.featureidentity.engine.FaceBox
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * IDS-8 (#477): the Swap person flow host. It switches between S1 person, S6 setup, S2 target and
 * S3/S4 studio, and shows S7/S8 sheets and the plain-language dialogs. Entry wiring into the
 * viewer ⋮ is IDS-11 (#478); nothing here touches GalleryViewer.kt or MainActivity.kt.
 */

/** Canvas is always black; sheets and dialogs follow the system theme. */
internal object SwapColors {
    val Canvas = Color.Black
    val OnCanvas = Color.White
    val OnCanvasMuted = AtlasTokens.Colors.WarmGray
    val Accent = AtlasTokens.Colors.Viewer.SelectionAccent
    val Chrome = AtlasTokens.Colors.Viewer.ActionPill
    val Scrim = AtlasTokens.Colors.Viewer.Scrim
    val StrongScrim = AtlasTokens.Colors.Viewer.ChromeScrimBottom
    val Placeholder = AtlasTokens.Colors.Viewer.FilmstripTrack
    val ChipContainer = AtlasTokens.Colors.Dark.SurfaceHigh
    val ChipBorder = AtlasTokens.Colors.Dark.Outline
}

internal object SwapMotion {
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    const val SHEET_MS = 300
    const val CROSSFADE_MS = 200
    const val SELECT_MS = 150
}

@Composable
internal fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}

/** Decodes an [EditImage] for display: synthetic pixels directly, files sampled to ≤ [maxEdge]. */
internal fun decodeSwapImage(image: EditImage, maxEdge: Int = 2048): ImageBitmap? = runCatching {
    if (image.pixels.isNotEmpty()) {
        Bitmap.createBitmap(image.pixels.toIntArray(), image.width, image.height, Bitmap.Config.ARGB_8888)
            .asImageBitmap()
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(image.handle, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        BitmapFactory.decodeFile(image.handle, BitmapFactory.Options().apply { inSampleSize = sample })
            ?.asImageBitmap()
    }
}.getOrNull()

private val swapImageCache = object : android.util.LruCache<String, ImageBitmap>(24) {}

private fun cacheKey(image: EditImage) = "${image.handle}#${image.width}x${image.height}#${image.pixels.size}"

/** Small files and synthetic pixels decode at once (stable renders); large photos decode off the main thread. */
@Composable
internal fun rememberSwapBitmap(image: EditImage?): ImageBitmap? {
    if (image == null) return null
    val key = cacheKey(image)
    var bitmap by remember(key) {
        mutableStateOf(swapImageCache.get(key) ?: if (image.pixels.isNotEmpty() ||
            File(image.handle).let { it.isFile && it.length() < 1_500_000L })
            decodeSwapImage(image)?.also { swapImageCache.put(key, it) } else null)
    }
    if (bitmap == null) LaunchedEffect(key) {
        bitmap = withContext(Dispatchers.IO) { decodeSwapImage(image) }?.also { swapImageCache.put(key, it) }
    }
    return bitmap
}

/** Where an image of [w]×[h] lands when fitted (centre, no crop) into a [viewW]×[viewH] box. */
internal data class FitRect(val scale: Float, val left: Float, val top: Float) {
    fun toView(x: Float, y: Float) = Offset(left + x * scale, top + y * scale)
    fun toImage(p: Offset) = Offset((p.x - left) / scale, (p.y - top) / scale)
}

internal fun fit(w: Int, h: Int, viewW: Float, viewH: Float): FitRect {
    val scale = min(viewW / w, viewH / h)
    return FitRect(scale, (viewW - w * scale) / 2f, (viewH - h * scale) / 2f)
}

/** Square crop around [box] grown [factor]× (brief rev 2: 1.6×), clamped to the image. */
internal fun faceCrop(box: FaceBox, imageW: Int, imageH: Int, factor: Float = 1.6f): FaceBox {
    val side = (max(box.width, box.height) * factor).roundToInt().coerceAtMost(min(imageW, imageH)).coerceAtLeast(1)
    val cx = (box.left + box.right) / 2
    val cy = (box.top + box.bottom) / 2
    val left = (cx - side / 2).coerceIn(0, imageW - side)
    val top = (cy - side / 2).coerceIn(0, imageH - side)
    return FaceBox(left, top, left + side, top + side)
}

/** Draws [image] cropped to the face square around [box] (whole image when [box] is null). */
@Composable
internal fun FaceCropImage(image: EditImage?, box: FaceBox?, modifier: Modifier = Modifier, zoom: Float = 1f) {
    val bitmap = rememberSwapBitmap(image)
    Canvas(modifier) {
        if (bitmap == null || image == null) {
            drawRect(SwapColors.Placeholder)
            return@Canvas
        }
        val crop = if (box != null) faceCrop(box, image.width, image.height)
        else min(image.width, image.height).let { s ->
            FaceBox((image.width - s) / 2, (image.height - s) / 2, (image.width - s) / 2 + s, (image.height - s) / 2 + s)
        }
        // Linked zoom (Compare all, split): shrink the crop around its centre.
        val z = zoom.coerceIn(1f, 8f)
        val side = crop.width / z
        val cx = (crop.left + crop.right) / 2f
        val cy = (crop.top + crop.bottom) / 2f
        val ratio = bitmap.width.toFloat() / image.width
        val srcLeft = ((cx - side / 2f) * ratio).roundToInt().coerceIn(0, bitmap.width - 1)
        val srcTop = ((cy - side / 2f) * ratio).roundToInt().coerceIn(0, bitmap.height - 1)
        val srcSide = (side * ratio).roundToInt().coerceAtLeast(1)
            .coerceAtMost(min(bitmap.width - srcLeft, bitmap.height - srcTop))
        drawImage(bitmap, IntOffset(srcLeft, srcTop), IntSize(srcSide, srcSide),
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
    }
}

/** A photo fitted into its box on the black canvas. */
@Composable
internal fun FittedImage(image: EditImage?, modifier: Modifier = Modifier) {
    val bitmap = rememberSwapBitmap(image)
    if (bitmap != null) androidx.compose.foundation.Image(bitmap, contentDescription = null,
        modifier = modifier, contentScale = ContentScale.Fit)
    else Box(modifier)
}

/**
 * A modal bottom sheet over the photo: 60% height, drags to full. Built in-tree (not a dialog
 * window) so the canvas stays visible and evidence renders are deterministic.
 */
@Composable
internal fun SwapBottomSheet(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val reduced = rememberReducedMotion()
    val fraction by animateFloatAsState(if (expanded) 1f else 0.6f,
        if (reduced) tween(0) else tween(SwapMotion.SHEET_MS, easing = SwapMotion.EmphasizedDecelerate), label = "sheet")
    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(SwapColors.Scrim)
            .clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss))
        BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
            val height = maxHeight * fraction
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(height).testTag(tag),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                Column(Modifier.fillMaxSize().then(if (expanded) Modifier.statusBarsPadding() else Modifier)
                    .navigationBarsPadding()) {
                    val drag = rememberDraggableState { delta ->
                        if (delta < -6f && !expanded) onExpandedChange(true)
                        if (delta > 6f && expanded) onExpandedChange(false)
                    }
                    Box(Modifier.fillMaxWidth().height(24.dp).draggable(drag, Orientation.Vertical)
                        .testTag("$tag-handle"), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(32.dp, 4.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .4f)))
                    }
                    content()
                }
            }
        }
    }
}

/** A small rounded message over the canvas, with up to two actions. */
@Composable
internal fun CanvasMessage(
    text: String,
    modifier: Modifier = Modifier,
    primary: Pair<String, () -> Unit>? = null,
    secondary: Pair<String, () -> Unit>? = null,
    tag: String = "swap-message"
) {
    Surface(modifier.padding(horizontal = 24.dp).testTag(tag), shape = RoundedCornerShape(20.dp),
        color = SwapColors.Chrome, contentColor = SwapColors.OnCanvas) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, fontSize = 16.sp, textAlign = TextAlign.Center, lineHeight = 22.sp)
            if (primary != null || secondary != null) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    secondary?.let { (label, action) ->
                        TextButton(onClick = action, modifier = Modifier.testTag("$tag-secondary")) {
                            Text(label, color = SwapColors.OnCanvas)
                        }
                    }
                    primary?.let { (label, action) ->
                        TextButton(onClick = action, modifier = Modifier.testTag("$tag-primary")) {
                            Text(label, color = SwapColors.Accent, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

/** A polite TalkBack announcement ("Version 3 ready"); visually empty. */
@Composable
internal fun PoliteAnnouncement(text: String?) {
    if (text == null) return
    Text(text, color = Color.Transparent, fontSize = 1.sp, modifier = Modifier.size(1.dp)
        .semantics { liveRegion = LiveRegionMode.Polite })
}

/**
 * The whole Swap person flow for one photo. [source] is the photo being edited; [onFinished]
 * runs when the flow ends (✕, Discard, Not now with nothing made); [onView] opens a saved copy.
 */
@Composable
fun SwapPersonFlow(
    state: SwapPersonUiState,
    source: EditImage,
    machine: SwapPersonMachine,
    onFinished: () -> Unit,
    onView: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(state.stage) { if (state.stage == SwapStage.FINISHED) onFinished() }
    if (state.stage == SwapStage.FINISHED) return
    BackHandler { machine.close() }
    Box(modifier.fillMaxSize().background(SwapColors.Canvas).testTag("swap-person-flow")) {
        when (state.stage) {
            SwapStage.TARGET -> TargetPickerScreen(state, source, machine)
            SwapStage.STUDIO -> CandidateStudioScreen(state, source, machine, onView)
            SwapStage.PERSON, SwapStage.SETUP -> {
                // S1 and S6 open over the photo (or over the studio when versions exist).
                if (state.versions.isNotEmpty()) CandidateStudioScreen(state, source, machine, onView)
                else Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                    FittedImage(source, Modifier.fillMaxSize())
                }
            }
            SwapStage.FINISHED -> Unit
        }
        if (state.stage == SwapStage.PERSON) PersonPickerSheet(state, machine)
        if (state.stage == SwapStage.SETUP) state.setup?.let { SetupSheet(it, machine) }
        when (state.sheet) {
            SwapSheet.CHOOSE_PHOTOS, SwapSheet.WHICH_FACE -> state.pick?.let { ChoosePhotosSheet(state, it, machine) }
            SwapSheet.PHOTOS_USED -> PhotosUsedSheet(state, machine)
            SwapSheet.NONE -> Unit
        }
        if (state.discardDialog) AlertDialog(
            onDismissRequest = machine::keepEditing,
            title = { Text("Discard these versions?") },
            confirmButton = { TextButton(machine::discard, Modifier.testTag("swap-discard")) { Text("Discard") } },
            dismissButton = { TextButton(machine::keepEditing, Modifier.testTag("swap-keep-editing")) { Text("Keep editing") } },
            modifier = Modifier.testTag("swap-discard-dialog")
        )
        PoliteAnnouncement(state.announcement)
    }
}

/** S6: the one-time setup sheet. No percentages: the bar shows progress. */
@Composable
internal fun SetupSheet(setup: SwapSetupUi, machine: SwapPersonMachine) {
    val mb = when (setup) {
        is SwapSetupUi.Offer -> setup.megabytes
        is SwapSetupUi.Running -> setup.megabytes
        is SwapSetupUi.Failed -> setup.megabytes
    }
    SwapBottomSheet(expanded = false, onExpandedChange = {}, onDismiss = machine::notNow, tag = "swap-setup-sheet") {
        Column(Modifier.padding(horizontal = 24.dp).fillMaxWidth()) {
            Text("Get Swap person ready", fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(12.dp))
            Text("A one-time download of about $mb MB. Your photos never leave this phone.",
                fontSize = 16.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            when (setup) {
                is SwapSetupUi.Running -> {
                    LinearProgressIndicator(progress = { setup.progress },
                        modifier = Modifier.fillMaxWidth().testTag("swap-setup-progress"))
                    Spacer(Modifier.height(12.dp))
                    Text("Downloading…", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    TextButton(machine::notNow, Modifier.align(Alignment.End).height(48.dp)) { Text("Not now") }
                }
                else -> {
                    if (setup is SwapSetupUi.Failed) {
                        Text("The download stopped. It will pick up where it left off.", fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("swap-setup-failed"))
                        Spacer(Modifier.height(12.dp))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(machine::notNow, Modifier.height(48.dp).testTag("swap-setup-not-now")) { Text("Not now") }
                        Spacer(Modifier.width(8.dp))
                        Button(machine::download, Modifier.height(48.dp).testTag("swap-setup-download")) {
                            Text(if (setup is SwapSetupUi.Failed) "Try again" else "Download")
                        }
                    }
                }
            }
        }
    }
}

internal fun Dp.orMin(): Dp = if (this < 48.dp) 48.dp else this

@Composable
internal fun Dp.toPxF(): Float = with(LocalDensity.current) { this@toPxF.toPx() }
