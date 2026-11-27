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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import nl.atlas.gallery.R
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Original ATLAS palette: muted chrome, image-first surfaces, one cool blue accent (Quiet Frame). */
/** Compatibility aliases: source values now live in AtlasTokens. */
object AtlasColor {
    val Ink = AtlasTokens.Colors.Ink
    val InkRaised = AtlasTokens.Colors.InkRaised
    val Mist = AtlasTokens.Colors.Mist
    val Paper = AtlasTokens.Colors.Paper
    val Tidal = AtlasTokens.Colors.Tidal
    val DeepTidal = AtlasTokens.Colors.DeepTidal
    val WarmGray = AtlasTokens.Colors.WarmGray
    val QuietGray = AtlasTokens.Colors.QuietGray
}

object AtlasSpace {
    val Gutter = AtlasTokens.Spacing.Gutter
    val Section = AtlasTokens.Spacing.Section
    val TileGap = AtlasTokens.Spacing.TileGap
    val MinimumTarget = AtlasTokens.Spacing.MinimumTarget
    val CoverCorner = AtlasTokens.Radii.Cover
}

/*
 * #657: every container role is set explicitly. Material's defaults for the
 * roles left out are its purple baseline (dialogs, menus, sheets and text
 * fields read surfaceContainer*, surfaceTint and outline), which is how the
 * "Add name" dialog came out light lavender. Ours stay in the Quiet Frame neutral/blue family.
 */
internal val darkGalleryColors = darkColorScheme(
    // #1011 review: TextButton, links and other text actions draw in `primary`.
    // Dark BrandFill is only 3.15:1 on the black ground, so dark `primary` is
    // BrandText (>= 4.5:1) and filled buttons draw Ground on it. Brand fill stays
    // in primaryContainer and surfaceTint.
    primary = AtlasTokens.Colors.Dark.BrandText,
    onPrimary = AtlasTokens.Colors.Dark.Ground,
    primaryContainer = AtlasTokens.Colors.Dark.BrandFill,
    onPrimaryContainer = AtlasTokens.Colors.Dark.OnBrand,
    inversePrimary = AtlasTokens.Colors.Light.BrandFill,
    secondary = AtlasTokens.Colors.Dark.InkSecondary,
    onSecondary = AtlasTokens.Colors.Dark.Ground,
    secondaryContainer = AtlasTokens.Colors.Dark.Surface,
    onSecondaryContainer = AtlasTokens.Colors.Dark.Ink,
    tertiary = AtlasTokens.Colors.Dark.BrandText,
    onTertiary = AtlasTokens.Colors.Dark.Ground,
    tertiaryContainer = AtlasTokens.Colors.Dark.Surface,
    onTertiaryContainer = AtlasTokens.Colors.Dark.Ink,
    background = AtlasTokens.Colors.Dark.Ground,
    onBackground = AtlasTokens.Colors.Dark.Ink,
    surface = AtlasTokens.Colors.Dark.Surface,
    onSurface = AtlasTokens.Colors.Dark.Ink,
    surfaceVariant = AtlasTokens.Colors.Dark.Surface,
    onSurfaceVariant = AtlasTokens.Colors.Dark.InkSecondary,
    surfaceTint = AtlasTokens.Colors.Dark.BrandFill,
    inverseSurface = AtlasTokens.Colors.Light.Surface,
    inverseOnSurface = AtlasTokens.Colors.Light.Ink,
    outline = AtlasTokens.Colors.Dark.InkSecondary,
    outlineVariant = AtlasTokens.Colors.Dark.Surface,
    surfaceBright = AtlasTokens.Colors.Dark.Surface,
    surfaceDim = AtlasTokens.Colors.Dark.Ground,
    surfaceContainerLowest = AtlasTokens.Colors.Dark.Ground,
    surfaceContainerLow = AtlasTokens.Colors.Dark.Surface,
    surfaceContainer = AtlasTokens.Colors.Dark.Surface,
    surfaceContainerHigh = AtlasTokens.Colors.Dark.Surface,
    surfaceContainerHighest = AtlasTokens.Colors.Dark.Surface
)

internal val lightGalleryColors = lightColorScheme(
    primary = AtlasTokens.Colors.Light.BrandFill,
    onPrimary = AtlasTokens.Colors.Light.OnBrand,
    primaryContainer = AtlasTokens.Colors.Light.BrandFill,
    onPrimaryContainer = AtlasTokens.Colors.Light.OnBrand,
    inversePrimary = AtlasTokens.Colors.Dark.BrandText,
    secondary = AtlasTokens.Colors.Light.BrandFill,
    onSecondary = AtlasTokens.Colors.Light.OnBrand,
    secondaryContainer = AtlasTokens.Colors.Light.Surface,
    onSecondaryContainer = AtlasTokens.Colors.Light.Ink,
    tertiary = AtlasTokens.Colors.Light.InkSecondary,
    onTertiary = AtlasTokens.Colors.Light.OnBrand,
    tertiaryContainer = AtlasTokens.Colors.Light.Surface,
    onTertiaryContainer = AtlasTokens.Colors.Light.Ink,
    background = AtlasTokens.Colors.Light.Ground,
    onBackground = AtlasTokens.Colors.Light.Ink,
    surface = AtlasTokens.Colors.Light.Surface,
    onSurface = AtlasTokens.Colors.Light.Ink,
    surfaceVariant = AtlasTokens.Colors.Light.Surface,
    onSurfaceVariant = AtlasTokens.Colors.Light.InkSecondary,
    surfaceTint = AtlasTokens.Colors.Light.BrandFill,
    inverseSurface = AtlasTokens.Colors.Dark.Surface,
    inverseOnSurface = AtlasTokens.Colors.Dark.Ink,
    outline = AtlasTokens.Colors.Light.InkSecondary,
    outlineVariant = AtlasTokens.Colors.Light.Surface,
    surfaceBright = AtlasTokens.Colors.Light.Ground,
    surfaceDim = AtlasTokens.Colors.Light.Surface,
    surfaceContainerLowest = AtlasTokens.Colors.Light.Ground,
    surfaceContainerLow = AtlasTokens.Colors.Light.Surface,
    surfaceContainer = AtlasTokens.Colors.Light.Surface,
    surfaceContainerHigh = AtlasTokens.Colors.Light.Surface,
    surfaceContainerHighest = AtlasTokens.Colors.Light.Surface
)

// The two bundled variable TTF files provide the requested 700/800 and 400/500/600
// optical weights. Android's font resolver selects their wght axis per FontWeight.
private val funnelDisplay = FontFamily(
    Font(R.font.funnel_display, weight = FontWeight.Bold),
    Font(R.font.funnel_display, weight = FontWeight.ExtraBold)
)
private val funnelSans = FontFamily(
    Font(R.font.funnel_sans, weight = FontWeight.Normal),
    Font(R.font.funnel_sans, weight = FontWeight.Medium),
    Font(R.font.funnel_sans, weight = FontWeight.SemiBold)
)

private fun lapisTypography(dark: Boolean): Typography {
    val base = Typography()
    val brand = if (dark) AtlasTokens.Colors.Dark.BrandText else AtlasTokens.Colors.Light.BrandText
    return Typography(
        displayLarge = base.displayLarge.copy(fontFamily = funnelDisplay, fontWeight = FontWeight.ExtraBold,
            letterSpacing = AtlasTokens.Type.DisplayTracking, color = brand),
        displayMedium = base.displayMedium.copy(fontFamily = funnelDisplay, fontWeight = FontWeight.Bold),
        displaySmall = base.displaySmall.copy(fontFamily = funnelDisplay, fontWeight = FontWeight.Bold),
        headlineLarge = base.headlineLarge.copy(fontFamily = funnelDisplay, fontWeight = FontWeight.Bold,
            fontSize = 38.sp, lineHeight = 44.sp, letterSpacing = AtlasTokens.Type.DisplayTracking, color = brand),
        headlineMedium = base.headlineMedium.copy(fontFamily = funnelDisplay, fontWeight = FontWeight.Bold),
        headlineSmall = base.headlineSmall.copy(fontFamily = funnelDisplay, fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontFamily = funnelSans, fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp, lineHeight = 24.sp),
        titleMedium = base.titleMedium.copy(fontFamily = funnelSans, fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp, lineHeight = 24.sp),
        titleSmall = base.titleSmall.copy(fontFamily = funnelSans, fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp, lineHeight = 22.sp),
        bodyLarge = base.bodyLarge.copy(fontFamily = funnelSans, fontWeight = FontWeight.Normal,
            fontSize = 15.sp, lineHeight = 22.sp),
        bodyMedium = base.bodyMedium.copy(fontFamily = funnelSans, fontWeight = FontWeight.Normal,
            fontSize = 15.sp, lineHeight = 22.sp),
        bodySmall = base.bodySmall.copy(fontFamily = funnelSans, fontWeight = FontWeight.Normal,
            fontSize = 14.sp, lineHeight = 20.sp),
        labelLarge = base.labelLarge.copy(fontFamily = funnelSans, fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp, lineHeight = 20.sp),
        labelMedium = base.labelMedium.copy(fontFamily = funnelSans, fontWeight = FontWeight.Normal,
            fontSize = 14.sp, lineHeight = 20.sp),
        labelSmall = base.labelSmall.copy(fontFamily = funnelSans, fontWeight = FontWeight.Normal,
            fontSize = 14.sp, lineHeight = 20.sp)
    )
}

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
        typography = lapisTypography(dark),
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
    // BRAND-L4 (#657, Quiet Frame v3 Structure 1): two places and search.
    // PHOTOS is "Pictures"; ALBUMS is "Collections", which also hosts People
    // and Stories until BRAND-L5. PEOPLE stays a route, never a primary place.
    PHOTOS("Pictures", Icons.Outlined.Image, Icons.Filled.Image),
    ALBUMS("Collections", Icons.Outlined.GridView, Icons.Filled.GridView),
    PEOPLE("People", Icons.Outlined.People, Icons.Filled.People),
    SEARCH("Search", Icons.Outlined.Search, Icons.Filled.Search)
}

/** The slab's places, in order. The search sphere follows them. */
val AtlasSlabPlaces: List<AtlasDestination> = listOf(AtlasDestination.PHOTOS, AtlasDestination.ALBUMS)

/** BRAND-L4: which slab target shows as current; People lives under Collections. */
fun navigationSelection(destination: AtlasDestination): AtlasDestination =
    if (destination == AtlasDestination.PEOPLE) AtlasDestination.ALBUMS else destination

/**
 * BRAND-L4 (#657; Quiet Frame v3 Structure 1, Components "Navigation slab"):
 * a short, centred, lapis-filled slab with a 24 dp radius. It holds the two
 * places, Pictures and Collections, and the search sphere. The current place
 * is a white pill with lapis text; the search sphere is the white held sphere
 * with a lapis glyph. It is opaque, so nothing is sampled or blurred under it.
 *
 * Every target is at least 48 dp. Labels grow with the system font (a 200 %
 * cap); if a very long label ever runs out of width the sphere moves to a
 * second row inside the same slab, never detached (#816 geometry audit).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AtlasGalleryNavigation(
    selected: AtlasDestination,
    onSelect: (AtlasDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    val current = navigationSelection(selected)
    Box(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = AtlasPillEdge, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(AtlasTokens.Radii.Slab),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            // A soft lift keeps the slab off a white page; on black it adds nothing.
            shadowElevation = if (LocalAtlasDark.current) 0.dp else 4.dp,
            modifier = Modifier.testTag("atlas-nav-pill")
        ) {
            FlowRow(
                Modifier.padding(AtlasTokens.Spacing.SlabPadding),
                horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                itemVerticalAlignment = Alignment.CenterVertically
            ) {
                AtlasSlabPlaces.forEach { destination ->
                    AtlasSlabPlace(
                        destination = destination,
                        selected = current == destination,
                        onClick = { onSelect(destination) }
                    )
                }
                AtlasSearchSphere(
                    selected = current == AtlasDestination.SEARCH,
                    onClick = { onSelect(AtlasDestination.SEARCH) }
                )
            }
        }
    }
}

/** BRAND-L4: the screens Collections hosts until BRAND-L5's rows land. */
enum class AtlasCollectionsSection(val title: String) {
    ALBUMS("Albums"), PEOPLE("People"), STORIES("Stories")
}

/**
 * BRAND-L4 (#657, unit item 1): Collections hosts today's Albums, People and
 * Stories screens as sections, so nothing loses its home when the four-tab
 * bar goes. The current section is the solid lapis pill; each pill is a
 * 48 dp target. BRAND-L5 replaces this with collection rows.
 */
@Composable
fun AtlasCollectionsSections(
    current: AtlasCollectionsSection,
    onSelect: (AtlasCollectionsSection) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(horizontal = AtlasTokens.Spacing.Gutter, vertical = 4.dp)
            .testTag("atlas-collections-sections"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AtlasCollectionsSection.entries.forEach { section ->
            val selected = section == current
            Box(
                Modifier
                    .heightIn(min = AtlasTokens.Spacing.MinimumTarget)
                    .widthIn(min = AtlasTokens.Spacing.MinimumTarget)
                    .clip(CircleShape)
                    .background(if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant)
                    .selectable(selected = selected, role = Role.Tab) { onSelect(section) }
                    .padding(horizontal = 18.dp, vertical = 6.dp)
                    .testTag("atlas-collections-" + section.name.lowercase()),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    section.title,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

/**
 * #652: height of the floating tab bar (pill, its margins and the system
 * navigation bar) that a screen lets its scrolling content draw under. Lazy
 * grids add it as bottom content padding so their last row stays reachable;
 * 0 when the screen is padded above the bar instead.
 */
val LocalAtlasBottomChrome = compositionLocalOf { 0.dp }

/**
 * Floating chrome (#509 §0) for the selection action bar: translucent pill,
 * 12 dp from the screen edges; dark on the dark theme (#652 alpha), white with
 * a soft lift on light (#662). The navigation slab itself is opaque lapis.
 */
val AtlasPillColor: Color
    @Composable @ReadOnlyComposable get() =
        if (LocalAtlasDark.current) AtlasTokens.Colors.Dark.Pill.copy(alpha = 0.80f)
        else AtlasTokens.Colors.Paper.copy(alpha = 0.90f)
val AtlasPillEdge = AtlasTokens.Spacing.PillEdge
val AtlasTabPillHeight = AtlasTokens.Spacing.TabHeight

/**
 * BRAND-L4: the effective font-scale factor for slab labels. Labels follow the
 * system font up to 200 % (the approved slab geometry, #816); beyond that the
 * size is capped so the slab stays one control.
 */
internal fun navigationLabelCap(fontScale: Float) =
    fontScale.coerceAtLeast(1f).coerceAtMost(2f) / fontScale.coerceAtLeast(0.01f)

/** Slab label size in sp, with [navigationLabelCap] applied. */
internal fun navigationLabelSize(fontScale: Float) =
    AtlasTokens.Type.NavigationLabel * navigationLabelCap(fontScale)

@Composable
private fun AtlasSlabPlace(
    destination: AtlasDestination,
    selected: Boolean,
    onClick: () -> Unit
) {
    val fontScale = LocalDensity.current.fontScale
    val labelSize = navigationLabelSize(fontScale)
    // #413: an sp line height must take the same cap as the size.
    val lineHeight = AtlasTokens.Type.NavigationLineHeight * navigationLabelCap(fontScale)
    // Current place: white pill, lapis text. Others: white text on the lapis slab.
    val ink = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.onPrimaryContainer
    Box(
        Modifier
            .heightIn(min = AtlasTokens.Spacing.MinimumTarget)
            .widthIn(min = AtlasTokens.Spacing.MinimumTarget)
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.onPrimaryContainer else Color.Transparent)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = AtlasTokens.Spacing.SlabPlacePadding, vertical = 6.dp)
            .testTag("atlas-nav-" + destination.name.lowercase()),
        contentAlignment = Alignment.Center
    ) {
        Text(
            destination.title,
            color = ink,
            fontSize = labelSize,
            lineHeight = lineHeight,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.testTag("atlas-nav-label-" + destination.name.lowercase())
        )
    }
}

/** The search sphere: the held sphere in white with a lapis glyph, 48 dp. */
@Composable
private fun AtlasSearchSphere(selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(AtlasTokens.Spacing.MinimumTarget)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onPrimaryContainer)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .testTag("atlas-nav-search"),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Filled.Search, contentDescription = AtlasDestination.SEARCH.title,
            tint = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(24.dp))
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
val AtlasHeaderHeight = AtlasTokens.Spacing.HeaderHeight

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
        // BRAND-L2: Funnel Display's 38/44 title plus the 14/20 subtitle can
        // measure about 68dp on Android. Share 5dp of line-box leading to
        // preserve both approved type roles and the #492 64dp compact header,
        // without shrinking the neighbouring 48dp action targets.
        Column(Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy((-5).dp)) {
            Text(
                title,
                modifier = Modifier.semantics { heading() }.testTag("atlas-header-title"),
                style = MaterialTheme.typography.headlineLarge,
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
 * DESIGN-6 #136, Quiet Frame: the one shared quiet state for empty,
 * permission and error surfaces. Centred in the content area (above the
 * floating nav pill, so the bars never move), an optional 22 dp ink-muted
 * outline icon, a short title, an optional one-line body and at most one
 * accent text action that does the real recovery. No illustration, banner or
 * filled button. Never simulates a scanner, imported faces, cached photos or
 * destructive actions.
 */
@Composable
fun AtlasEmptyState(
    title: String,
    explanation: String = "",
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    /** The floating nav pill's height where content draws under it; 0 on overlays. */
    bottomInset: Dp = LocalAtlasBottomChrome.current,
    /** Screen-specific test tags on the title and body text nodes. */
    titleTag: String = "atlas-quiet-title",
    bodyTag: String = "atlas-quiet-body"
) {
    Column(
        modifier.fillMaxSize()
            .padding(bottom = bottomInset)
            .padding(horizontal = AtlasQuietState.SideMargin),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Icon(
                icon, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(AtlasQuietState.IconSize).testTag("atlas-quiet-icon")
            )
            Spacer(Modifier.height(12.dp))
        }
        Text(
            title,
            modifier = Modifier.semantics { heading() }.testTag(titleTag),
            style = AtlasQuietState.titleStyle(),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        if (explanation.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                explanation, color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                modifier = Modifier.testTag(bodyTag)
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = onAction,
                modifier = Modifier.heightIn(min = AtlasSpace.MinimumTarget)
                    .testTag("atlas-empty-action"),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary)
            ) { Text(actionLabel, style = MaterialTheme.typography.labelLarge) }
        }
    }
}

/** Quiet Frame measures for [AtlasEmptyState] and [AtlasGalleryLoading]. */
object AtlasQuietState {
    /** QUIET_FRAME.md icons: 22 dp outline. The brief's 48 dp is only a ceiling. */
    val IconSize = 22.dp
    val SideMargin = 32.dp
    /** Brief state 7: show nothing at all for loads that finish sooner. */
    const val LoadingQuietMillis = 300L

    /** QUIET_FRAME.md section title: 20 / 26 sp, weight 500. */
    @Composable
    fun titleStyle(): TextStyle = MaterialTheme.typography.titleLarge.copy(
        fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium)
}

/**
 * DESIGN-6 #136 state 7: a load shows nothing for its first 300 ms, then one
 * small indicator and a short line at the same centre as [AtlasEmptyState],
 * so neither a fast load nor the empty result that follows makes it jump.
 * Never a full-screen spinner wall.
 */
@Composable
fun AtlasGalleryLoading(
    message: String = "Loading photos…",
    modifier: Modifier = Modifier,
    bottomInset: Dp = LocalAtlasBottomChrome.current
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(AtlasQuietState.LoadingQuietMillis)
        visible = true
    }
    Column(
        modifier.fillMaxSize()
            .padding(bottom = bottomInset)
            .padding(horizontal = AtlasQuietState.SideMargin)
            .semantics { stateDescription = message }
            .testTag("atlas-quiet-loading"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (visible) {
            // BRAND-L4 (#657): the held sphere climbs an arc beside the line.
            AtlasLoadingArc(progress = null)
            Spacer(Modifier.height(12.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("atlas-quiet-loading-line"))
        }
    }
}
