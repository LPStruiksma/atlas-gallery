package nl.atlas.gallery.featureguest

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.atlas.gallery.featuredesign.AtlasMotion
import nl.atlas.gallery.featuredesign.AtlasTokens

/**
 * GUEST-1 (#589) frame C (and D underneath the system prompt): the only
 * surface while a guest session is live. Theme-independent by design (brief
 * v2): black ground, white 2 dp ring and #8C9BFF sphere on the current
 * filmstrip item, the #3448E0 Exit slab with white text. No back button, no
 * grid, search, details, share, edit, delete or viewer More: those routes are
 * simply not composed. Back is consumed silently.
 */
@Composable
fun GuestScreen(
    boot: GuestBoot,
    onExit: () -> Unit,
    onPageSettled: (Int) -> Unit,
    onFirstFrame: () -> Unit
) {
    BackHandler(enabled = true) { /* consumed: never pops to the gallery */ }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        onFirstFrame()
    }
    Box(Modifier.fillMaxSize().background(GuestColors.Ground).testTag("atlas-guest-root")) {
        val items = (boot as? GuestBoot.Guest)?.record?.items.orEmpty()
        if (items.isEmpty()) {
            Text(GuestCopy.ALL_UNAVAILABLE, color = GuestColors.OnBrand,
                style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(24.dp)
                    .testTag("atlas-guest-all-unavailable"))
            GuestBottom(items = emptyList(), current = 0, onTap = {}, onExit = onExit,
                modifier = Modifier.align(Alignment.BottomCenter))
            return@Box
        }
        val start = GuestPaging.clamp((boot as GuestBoot.Guest).record.index, items.size)
        val pager = rememberPagerState(initialPage = start) { items.size }
        var zoomed by remember { mutableStateOf(false) }
        LaunchedEffect(pager) {
            snapshotFlow { pager.settledPage }.collect { page ->
                zoomed = false
                onPageSettled(GuestPaging.clamp(page, items.size))
            }
        }
        HorizontalPager(
            state = pager,
            userScrollEnabled = !zoomed && items.size > 1,
            beyondViewportPageCount = 0,
            key = { items[it].uri },
            modifier = Modifier.fillMaxSize().testTag("atlas-guest-pager")
        ) { page ->
            GuestPhoto(items[page], page, items.size,
                onZoomChanged = { if (page == pager.currentPage) zoomed = it })
        }
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        GuestBottom(items = items, current = pager.currentPage,
            onTap = { target ->
                val page = GuestPaging.tap(target, items.size) ?: return@GuestBottom
                scope.launch {
                    if (AtlasMotion.animationsDisabled(context)) pager.scrollToPage(page)
                    else pager.animateScrollToPage(page)
                }
            },
            onExit = onExit,
            modifier = Modifier.align(Alignment.BottomCenter))
    }
}

internal object GuestColors {
    val Ground = AtlasTokens.Colors.AiEdit.Ground
    val Slab = AtlasTokens.Colors.AiEdit.Slab
    val OnBrand = AtlasTokens.Colors.AiEdit.OnBrand
    val Sphere = AtlasTokens.Colors.AiEdit.BrandText
    val Placeholder = AtlasTokens.Colors.AiEdit.Surface
}

private sealed interface GuestDecoded {
    data object Loading : GuestDecoded
    data class Ready(val bitmap: Bitmap) : GuestDecoded
    data object Unavailable : GuestDecoded
}

/** Long edge of a decoded guest photo: sharp on a 1080 × 2340 screen with room to zoom. */
private const val GUEST_MAX_EDGE = 2560

@Composable
private fun GuestPhoto(item: GuestItem, page: Int, count: Int, onZoomChanged: (Boolean) -> Unit) {
    val context = LocalContext.current
    // Only this item's own URI is opened: no neighbour, album or cache lookup.
    // ImageDecoder returns the first frame of an animated image, so a GIF,
    // animated WebP or motion photo shows a still, never motion or sound.
    val decoded by produceState<GuestDecoded>(GuestDecoded.Loading, item.uri) {
        value = withContext(Dispatchers.IO) {
            try {
                val source = ImageDecoder.createSource(context.contentResolver, Uri.parse(item.uri))
                GuestDecoded.Ready(ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val edge = maxOf(info.size.width, info.size.height)
                    var sample = 1
                    while (edge / (sample * 2) >= GUEST_MAX_EDGE) sample *= 2
                    decoder.setTargetSampleSize(sample)
                })
            } catch (stopped: kotlinx.coroutines.CancellationException) {
                throw stopped
            } catch (_: Exception) {
                GuestDecoded.Unavailable
            }
        }
    }
    var scale by remember(item.uri) { mutableFloatStateOf(1f) }
    var offset by remember(item.uri) { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        val next = (scale * zoom).coerceIn(1f, 5f)
        scale = next
        offset = if (next > 1f) offset + pan else Offset.Zero
        onZoomChanged(next > 1f)
    }
    Box(
        Modifier.fillMaxSize()
            .semantics { contentDescription = "Photo ${page + 1} of $count" }
            .testTag("atlas-guest-page-$page"),
        contentAlignment = Alignment.Center
    ) {
        when (val state = decoded) {
            is GuestDecoded.Ready -> Image(
                bitmap = state.bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
                    .transformable(transform, canPan = { scale > 1f })
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
            )
            GuestDecoded.Unavailable -> Text(GuestCopy.PHOTO_UNAVAILABLE, color = GuestColors.OnBrand,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(24.dp).testTag("atlas-guest-photo-unavailable"))
            GuestDecoded.Loading -> Box(Modifier.size(12.dp).background(GuestColors.Sphere, CircleShape))
        }
    }
}

@Composable
private fun GuestBottom(
    items: List<GuestItem>,
    current: Int,
    onTap: (Int) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(
                WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (items.size > 1) {
            GuestFilmstrip(items, current, onTap)
            Spacer(Modifier.height(8.dp))
        }
        Box(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(GuestColors.Slab)
                .clickable(role = Role.Button, onClick = onExit)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag("atlas-guest-exit"),
            contentAlignment = Alignment.Center
        ) {
            Text(GuestCopy.EXIT, color = GuestColors.OnBrand,
                style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun GuestFilmstrip(items: List<GuestItem>, current: Int, onTap: (Int) -> Unit) {
    val strip = rememberLazyListState()
    LaunchedEffect(current) { strip.animateScrollToItem(current.coerceAtLeast(0)) }
    // Virtualised over the frozen set only: a huge selection never pulls the gallery.
    LazyRow(
        state = strip,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth().testTag("atlas-guest-filmstrip")
    ) {
        itemsIndexed(items, key = { _, item -> item.uri }) { index, item ->
            val isCurrent = index == current
            Column(
                Modifier.size(width = 52.dp, height = 64.dp)
                    .clickable(role = Role.Button) { onTap(index) }
                    .semantics {
                        contentDescription = "Photo ${index + 1} of ${items.size}"
                        selected = isCurrent
                    }
                    .testTag("atlas-guest-thumb-$index"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                GuestThumb(item, Modifier.size(44.dp).clip(RoundedCornerShape(8.dp))
                    .then(if (isCurrent) Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp))
                    else Modifier))
                Spacer(Modifier.height(4.dp))
                Box(Modifier.size(6.dp).background(
                    if (isCurrent) GuestColors.Sphere else Color.Transparent, CircleShape))
            }
        }
    }
}

@Composable
private fun GuestThumb(item: GuestItem, modifier: Modifier) {
    val context = LocalContext.current
    val thumb by produceState<Bitmap?>(null, item.uri) {
        value = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.loadThumbnail(Uri.parse(item.uri), Size(176, 176), null)
            } catch (stopped: kotlinx.coroutines.CancellationException) {
                throw stopped
            } catch (_: Exception) {
                null
            }
        }
    }
    Box(modifier.background(GuestColors.Placeholder)) {
        thumb?.let {
            Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize())
        }
    }
}
