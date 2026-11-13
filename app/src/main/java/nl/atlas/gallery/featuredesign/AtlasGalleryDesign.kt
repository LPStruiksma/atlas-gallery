package nl.atlas.gallery.featuredesign

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.background
import android.os.Build
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Search
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Original ATLAS palette: muted chrome, image-first surfaces, restrained teal accent. */
object AtlasColor {
    val Ink = Color(0xFF11171B)
    val InkRaised = Color(0xFF1D272C)
    val Mist = Color(0xFFF5F7F4)
    val Paper = Color(0xFFFFFFFF)
    val Tidal = Color(0xFF9DE1D2)
    val DeepTidal = Color(0xFF176D61)
    val WarmGray = Color(0xFFB5C2C0)
    val QuietGray = Color(0xFF50625F)
}

object AtlasSpace {
    val Gutter = 18.dp
    val Section = 28.dp
    val TileGap = 10.dp
    val MinimumTarget = 48.dp
    val CoverCorner = 20.dp
}

/*
 * #657: every container role is set explicitly. Material's defaults for the
 * roles left out are its purple baseline (dialogs, menus, sheets and text
 * fields read surfaceContainer*, surfaceTint and outline), which is how the
 * "Add name" dialog came out light lavender. Ours stay in the ink/teal family.
 */
internal val darkGalleryColors = darkColorScheme(
    primary = AtlasColor.Tidal,
    onPrimary = AtlasColor.Ink,
    primaryContainer = Color(0xFF1F4F47),
    onPrimaryContainer = Color(0xFFC6F2E7),
    inversePrimary = AtlasColor.DeepTidal,
    secondary = AtlasColor.WarmGray,
    onSecondary = AtlasColor.Ink,
    secondaryContainer = Color(0xFF2E3B3E),
    onSecondaryContainer = Color(0xFFDCE6E3),
    tertiary = AtlasColor.WarmGray,
    onTertiary = AtlasColor.Ink,
    tertiaryContainer = Color(0xFF2E3B3E),
    onTertiaryContainer = Color(0xFFDCE6E3),
    background = AtlasColor.Ink,
    onBackground = Color(0xFFF7FAF8),
    surface = AtlasColor.InkRaised,
    onSurface = Color(0xFFF7FAF8),
    surfaceVariant = Color(0xFF2A3539),
    onSurfaceVariant = Color(0xFFD0DDDA),
    surfaceTint = AtlasColor.InkRaised,
    inverseSurface = Color(0xFFE6ECEA),
    inverseOnSurface = AtlasColor.Ink,
    outline = Color(0xFF7F8F8C),
    outlineVariant = Color(0xFF3A4649),
    surfaceBright = Color(0xFF323D42),
    surfaceDim = AtlasColor.Ink,
    surfaceContainerLowest = Color(0xFF0B0D10),
    surfaceContainerLow = Color(0xFF161D21),
    surfaceContainer = Color(0xFF1D272C),
    surfaceContainerHigh = Color(0xFF243035),
    surfaceContainerHighest = Color(0xFF2A3539)
)

internal val lightGalleryColors = lightColorScheme(
    primary = AtlasColor.DeepTidal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFBDEDE2),
    onPrimaryContainer = Color(0xFF00382F),
    inversePrimary = AtlasColor.Tidal,
    secondary = AtlasColor.DeepTidal,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDE7E4),
    onSecondaryContainer = AtlasColor.Ink,
    tertiary = AtlasColor.QuietGray,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDDE7E4),
    onTertiaryContainer = AtlasColor.Ink,
    background = AtlasColor.Mist,
    onBackground = AtlasColor.Ink,
    surface = AtlasColor.Paper,
    onSurface = AtlasColor.Ink,
    surfaceVariant = Color(0xFFE3EBE8),
    onSurfaceVariant = AtlasColor.QuietGray,
    surfaceTint = AtlasColor.Paper,
    inverseSurface = Color(0xFF2A3236),
    inverseOnSurface = Color(0xFFEFF3F1),
    outline = Color(0xFF70807D),
    outlineVariant = Color(0xFFC9D3D0),
    surfaceBright = AtlasColor.Paper,
    surfaceDim = Color(0xFFD9DFDC),
    surfaceContainerLowest = AtlasColor.Paper,
    surfaceContainerLow = Color(0xFFF8FAF8),
    surfaceContainer = Color(0xFFF1F4F2),
    surfaceContainerHigh = Color(0xFFFBFCFB),
    surfaceContainerHighest = Color(0xFFE3EBE8)
)

/**
 * #662: true under a dark ATLAS theme. MainActivity follows the phone's
 * light/dark setting; full-screen media routes ([AtlasDarkRoute]) stay dark.
 */
val LocalAtlasDark = staticCompositionLocalOf { true }

/** The page behind every gallery screen: near-black in dark, white in light. */
val AtlasPageColor: Color
    @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainerLowest

/** Caller-controlled root theme; does not change Activity or system bars. */
@Composable
fun AtlasGalleryTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
  CompositionLocalProvider(LocalAtlasDark provides dark) {
    MaterialTheme(
        colorScheme = if (dark) darkGalleryColors else lightGalleryColors,
        typography = Typography(
            headlineLarge = MaterialTheme.typography.headlineLarge.copy(
                fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6f).sp),
            headlineMedium = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.SemiBold),
            titleLarge = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.SemiBold),
            titleMedium = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Medium),
            bodyMedium = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp)
        ),
        content = content
    )
  }
}

/**
 * #662: system bar icons that read on the current page, light icons on dark
 * and dark icons on light. Call once at the root with the theme's darkness.
 */
@Composable
fun AtlasSystemBarIcons(dark: Boolean) {
    val view = LocalView.current
    LaunchedEffect(view, dark) { setSystemBarIcons(view, dark) }
}

/**
 * #662: Samsung keeps its viewer and other full-screen media dark whatever the
 * phone's setting. [content] gets the dark theme and light bar icons while it
 * is shown; leaving it restores the icons for the surrounding theme.
 */
@Composable
fun AtlasDarkRoute(content: @Composable () -> Unit) {
    val outerDark = LocalAtlasDark.current
    val view = LocalView.current
    DisposableEffect(view, outerDark) {
        setSystemBarIcons(view, dark = true)
        onDispose { setSystemBarIcons(view, outerDark) }
    }
    AtlasGalleryTheme(dark = true) {
        // Its own full-screen dark page. The app draws edge to edge, so the
        // system bars show whatever is behind them: without this the light
        // theme's white root page showed through a route that pads itself
        // above the navigation bar (white bar, light glyphs: #670 review).
        // The route's own text colour too: screens drawn on a fixed dark
        // backdrop (Declutter) set none, and would otherwise inherit the
        // surrounding page's, which is near-black under the light theme.
        Surface(
            Modifier.fillMaxSize(),
            color = AtlasPageColor,
            contentColor = MaterialTheme.colorScheme.onBackground,
            content = content
        )
    }
}

@Suppress("DEPRECATION") // Bar colours only matter below Android 15 (edge-to-edge).
private fun setSystemBarIcons(view: android.view.View, dark: Boolean) {
    if (view.isInEditMode) return
    val window = (view.context.findActivity() ?: return).window
    val page = if (dark) android.graphics.Color.rgb(11, 13, 16) else android.graphics.Color.WHITE
    window.statusBarColor = page
    window.navigationBarColor = page
    WindowCompat.getInsetsController(window, view).apply {
        isAppearanceLightStatusBars = !dark
        isAppearanceLightNavigationBars = !dark
    }
}

private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

enum class AtlasDestination(
    val title: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector
) {
    // #685: photos = a single picture, albums = a four-tile grid.
    // #652: inactive glyphs are outlined; active glyphs are filled (#509 §0).
    PHOTOS("Photos", Icons.Outlined.Image, Icons.Filled.Image),
    ALBUMS("Albums", Icons.Outlined.GridView, Icons.Filled.GridView),
    PEOPLE("People", Icons.Outlined.People, Icons.Filled.People),
    SEARCH("Search", Icons.Outlined.Search, Icons.Filled.Search)
}

/**
 * #22: the Scaffold's main content records its actual drawing commands in a
 * reusable layer. The navigation pill samples that layer independently; simply
 * adding Modifier.blur to a Surface would blur the glyphs, not its backdrop.
 * No bitmap readback, image copies, photo access or per-frame PixelCopy.
 */
internal class AtlasTabBackdropState(val layer: GraphicsLayer) {
    var originInRoot by mutableStateOf(Offset.Unspecified)
}

internal val LocalAtlasTabBackdrop = staticCompositionLocalOf<AtlasTabBackdropState?> { null }

@Composable
internal fun rememberAtlasTabBackdropState(): AtlasTabBackdropState {
    val layer = rememberGraphicsLayer()
    return remember(layer) { AtlasTabBackdropState(layer) }
}

internal fun Modifier.atlasCaptureTabBackdrop(backdrop: AtlasTabBackdropState): Modifier =
    this.onGloballyPositioned { backdrop.originInRoot = it.positionInRoot() }
        .drawWithContent {
            backdrop.layer.record {
                this@drawWithContent.drawContent()
            }
            drawLayer(backdrop.layer)
        }

/**
 * #640 (#509 §2): Samsung's compact floating tab bar. One fully rounded dark
 * pill, 12 dp from the screen edges, outlined-weight 24 dp icons with small
 * labels; the active tab uses the filled glyph, tint and label weight,
 * never a Material selection blob. Activity is a secondary settings surface, never a primary
 * gallery destination.
 */
@Composable
fun AtlasGalleryNavigation(
    selected: AtlasDestination,
    onSelect: (AtlasDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = AtlasPillEdge, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        val backdrop = LocalAtlasTabBackdrop.current
        val blurredLayer = rememberGraphicsLayer()
        val blurRadiusPx = with(LocalDensity.current) { 14.dp.toPx() }
        val blurEffect = remember(blurRadiusPx) {
            BlurEffect(blurRadiusPx, blurRadiusPx, TileMode.Clamp)
        }
        var pillOrigin by remember { mutableStateOf(Offset.Unspecified) }
        Box(
            Modifier.fillMaxWidth().height(AtlasTabPillHeight)
                .onGloballyPositioned { pillOrigin = it.positionInRoot() }
        ) {
            // API31+: blur only the live page underneath the pill, clipped to
            // its outline. The translucent Surface and all four tab items are
            // drawn *after* the effect, so text and glyphs remain pixel-sharp.
            // API29-30: no RenderEffect; keep the existing translucent Surface.
            if (Build.VERSION.SDK_INT >= 31 && backdrop != null) {
                Box(Modifier.matchParentSize().clip(CircleShape).drawWithContent {
                    if (pillOrigin.isSpecified && backdrop.originInRoot.isSpecified) {
                        val shift = backdrop.originInRoot - pillOrigin
                        blurredLayer.record(
                            size = IntSize(size.width.roundToInt(), size.height.roundToInt())
                        ) {
                            translate(left = shift.x, top = shift.y) {
                                drawLayer(backdrop.layer)
                            }
                        }
                        blurredLayer.renderEffect = blurEffect
                        drawLayer(blurredLayer)
                    }
                })
            }
            Surface(
                shape = CircleShape,
                color = AtlasPillColor,
                contentColor = MaterialTheme.colorScheme.onSurface,
                // A white pill needs a soft shadow to lift off a white page.
                shadowElevation = if (LocalAtlasDark.current) 0.dp else 4.dp,
                modifier = Modifier.matchParentSize().testTag("atlas-nav-pill")
            ) {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AtlasDestination.entries.forEach { destination ->
                        AtlasTabItem(
                            destination = destination,
                            selected = selected == destination,
                            onClick = { onSelect(destination) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * #652: height of the floating tab bar (pill, its margins and the system
 * navigation bar) that a screen lets its scrolling content draw under. Lazy
 * grids add it as bottom content padding so the last row stays reachable;
 * 0 when the screen is padded above the bar instead.
 */
val LocalAtlasBottomChrome = compositionLocalOf { 0.dp }

/**
 * Floating chrome (#509 §0): translucent pill, 12 dp from the screen edges;
 * dark on the dark theme (#652 alpha), white with a soft lift on light (#662).
 */
val AtlasPillColor: Color
    @Composable @ReadOnlyComposable get() =
        if (LocalAtlasDark.current) Color(0xFF262B30).copy(alpha = 0.80f)
        else Color(0xFFFFFFFF).copy(alpha = 0.90f)
val AtlasPillEdge = 12.dp
val AtlasTabPillHeight = 60.dp

@Composable
private fun AtlasTabItem(
    destination: AtlasDestination,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ink = MaterialTheme.colorScheme.onSurface
    // 11sp labels still meet text contrast over a white tile behind the dark pill.
    val tint = if (selected) ink else ink.copy(alpha = 0.78f)
    Column(
        modifier
            .fillMaxHeight()
            .clip(CircleShape)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .testTag("atlas-nav-" + destination.name.lowercase()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(if (selected) destination.selectedIcon else destination.icon,
            contentDescription = null, tint = tint,
            modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(2.dp))
        Text(
            destination.title,
            color = tint,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1
        )
    }
}

/**
 * #641: true while the grid under an [AtlasGalleryHeader] is scrolled away
 * from its top. The header then folds its second line (the count) away and
 * keeps only the slim bar with title, sort and ⋮ (#509 §2: "no permanent
 * second line", "a compact bar (search, ⋮) that stays").
 */
val LocalAtlasHeaderCollapsed = compositionLocalOf { false }

/** True when [state] is scrolled away from its first item's top edge. */
fun LazyGridState.scrolledFromTop(): Boolean =
    firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > 0

/**
 * Samsung shell (#506, #641): [header] is drawn over the top of a lazy grid
 * and stays pinned as a slim bar while the grid scrolls under it; once the
 * grid leaves its top, the header's count line folds away
 * ([LocalAtlasHeaderCollapsed]) and comes back at the top. [content] receives
 * the header's height to use as top content padding, so the first row starts
 * below the header and the grid keeps its full size. [pinned] is kept for
 * callers (e.g. a selection top bar); the header is always on screen.
 */
@Composable
fun AtlasScrollAwayHeader(
    gridState: LazyGridState,
    modifier: Modifier = Modifier,
    pinned: Boolean = false,
    header: @Composable () -> Unit,
    content: @Composable (headerHeight: Dp) -> Unit
) {
    var headerPx by remember { mutableIntStateOf(0) }
    // Flips only when the grid crosses its top, not on every scroll frame.
    val collapsed by remember(gridState) { derivedStateOf { gridState.scrolledFromTop() } }
    Box(modifier.clipToBounds()) {
        content(with(LocalDensity.current) { headerPx.toDp() })
        Box(Modifier.fillMaxWidth()
            .onSizeChanged { headerPx = it.height }
            .testTag("atlas-scrolling-header")) {
            CompositionLocalProvider(LocalAtlasHeaderCollapsed provides (collapsed && !pinned)) {
                header()
            }
        }
    }
}

/** Samsung Gallery-style slim bar: everything sits in one row of at most this height. */
val AtlasHeaderHeight = 56.dp

/**
 * One overflow (⋮) entry. [checked] non-null renders a checkmark state, so a
 * view toggle reads as a setting rather than as a command.
 */
data class AtlasHeaderMenuItem(
    val label: String,
    val testTag: String,
    val checked: Boolean? = null,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

/**
 * Compact, image-first top bar shared by every top-level gallery surface
 * (#492): an optional Back arrow, a single-line title with an optional
 * one-line status, caller icon [actions], and an overflow menu holding
 * secondary actions and the Settings destination. Default height is
 * [AtlasHeaderHeight] so photos start near the top of the screen.
 * No inert controls: Back, actions and menu entries appear only when real.
 */
@Composable
fun AtlasGalleryHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    backDescription: String = "Back",
    onOpenLibraryStatus: (() -> Unit)? = null,
    menuItems: List<AtlasHeaderMenuItem> = emptyList(),
    actions: @Composable RowScope.() -> Unit = {}
) {
    val overflow = buildList {
        addAll(menuItems)
        if (onOpenLibraryStatus != null) add(AtlasHeaderMenuItem(
            "Settings", "atlas-library-status",
            onClick = onOpenLibraryStatus))
    }
    Row(
        modifier.fillMaxWidth().heightIn(min = AtlasHeaderHeight)
            .padding(start = if (onBack != null) 4.dp else AtlasSpace.Gutter, end = 4.dp)
            .testTag("atlas-header"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(AtlasSpace.MinimumTarget).testTag("atlas-header-back")
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = backDescription)
            }
            Spacer(Modifier.width(4.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                modifier = Modifier.semantics { heading() }.testTag("atlas-header-title"),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // #641: the count line folds away while the grid is scrolled;
            // the row keeps its 56 dp, so nothing below it moves.
            AnimatedVisibility(
                visible = !subtitle.isNullOrBlank() && !LocalAtlasHeaderCollapsed.current,
                enter = fadeIn(tween(200)) + expandVertically(tween(200)),
                exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
            ) {
                Text(
                    subtitle.orEmpty(),
                    modifier = Modifier.testTag("atlas-header-subtitle"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        actions()
        if (overflow.isNotEmpty()) AtlasHeaderOverflow(overflow)
    }
}

@Composable
private fun AtlasHeaderOverflow(items: List<AtlasHeaderMenuItem>) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.size(AtlasSpace.MinimumTarget).testTag("atlas-header-more")
        ) {
            Icon(Icons.Filled.MoreVert, contentDescription = "More options")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.label) },
                    enabled = item.enabled,
                    onClick = { expanded = false; item.onClick() },
                    trailingIcon = item.checked?.let { checked ->
                        {
                            Icon(
                                if (checked) Icons.Filled.CheckBox
                                else Icons.Filled.CheckBoxOutlineBlank,
                                contentDescription = null
                            )
                        }
                    },
                    modifier = Modifier.testTag(item.testTag).semantics {
                        item.checked?.let { selected = it }
                    }
                )
            }
        }
    }
}

/**
 * Truthful empty/permission/error surface. Never simulates a scanner,
 * imported faces, cached photos or destructive actions. No fake button.
 */
@Composable
fun AtlasEmptyState(
    title: String,
    explanation: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Filled.PhotoLibrary,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    visual: (@Composable () -> Unit)? = null
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 44.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (visual != null) {
            Box(Modifier.size(148.dp), contentAlignment = Alignment.Center) { visual() }
        } else {
            Box(
                Modifier.size(98.dp).background(
                    MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(28.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            title, modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            explanation, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(22.dp))
            Button(
                onClick = onAction,
                modifier = Modifier.heightIn(min = AtlasSpace.MinimumTarget)
                    .testTag("atlas-empty-action")
            ) { Text(actionLabel) }
        }
    }
}

@Composable
fun AtlasGalleryLoading(
    message: String = "Finding your photos on this device…",
    modifier: Modifier = Modifier
) {
    Row(
        modifier.fillMaxWidth().padding(AtlasSpace.Section),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.dp)
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}
