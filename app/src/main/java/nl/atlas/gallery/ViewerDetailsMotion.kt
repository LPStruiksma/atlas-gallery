package nl.atlas.gallery

import android.content.Context
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.staticCompositionLocalOf
import kotlin.math.min

/*
 * Swipe-up Details motion: the pure geometry and release rules behind the one
 * gesture-driven details surface in GalleryViewer. JVM-tested in
 * ViewerDetailsMotionTest; no Compose state lives here.
 *
 * Samsung reference (atlas-native samsung-rea VIEW:V27): the details are one
 * bottom surface driven by the slide offset; its rows fade in over the second
 * half of the slide and the photo moves up above it. Atlas keeps that
 * behaviour with its own look (Quiet Frame: black viewer ground).
 */

/** Share of the viewer height the open details surface covers. */
internal const val VIEWER_DETAILS_PANEL_FRACTION = 0.42f

/** A release at least this fast (dp/s) settles in its direction regardless of position. */
internal const val VIEWER_DETAILS_FLING_DP = 1_100

/** How far ahead (s) a release's velocity is projected when picking the settle side. */
internal const val VIEWER_DETAILS_PROJECTION_S = 0.15f

/**
 * Critically damped (no bounce) and soft enough that a release carries the
 * finger's speed into the settle instead of starting a fixed-length tween.
 */
internal val VIEWER_DETAILS_SPRING: AnimationSpec<Float> =
    spring(dampingRatio = 1f, stiffness = 500f)

internal fun viewerDetailsPanelPx(viewerHeightPx: Float): Float =
    if (viewerHeightPx.isFinite() && viewerHeightPx > 0f)
        viewerHeightPx * VIEWER_DETAILS_PANEL_FRACTION else 0f

/**
 * Progress after the finger moved [travelY] px (down positive) from where a
 * gesture grabbed the surface at [base]. The surface's top edge stays under
 * the finger: one pixel of travel is one pixel of surface.
 */
internal fun viewerDetailsDragProgress(base: Float, travelY: Float, panelPx: Float): Float {
    if (!base.isFinite() || !travelY.isFinite() || !panelPx.isFinite() || panelPx <= 0f)
        return base.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    return (base - travelY / panelPx).coerceIn(0f, 1f)
}

/**
 * Whether a release at [progress] with [velocityUpPx] (px/s, up positive)
 * settles open. A fast flick wins outright; otherwise the position projected
 * [VIEWER_DETAILS_PROJECTION_S] ahead decides, so a short quick swipe opens
 * and a slow half-hearted one falls back.
 */
internal fun viewerDetailsSettleOpen(
    progress: Float, velocityUpPx: Float, panelPx: Float, flingPx: Float
): Boolean {
    if (!progress.isFinite()) return false
    if (!velocityUpPx.isFinite() || !panelPx.isFinite() || panelPx <= 0f) return progress >= .5f
    if (velocityUpPx >= flingPx) return true
    if (velocityUpPx <= -flingPx) return false
    return progress + velocityUpPx / panelPx * VIEWER_DETAILS_PROJECTION_S >= .5f
}

internal data class ViewerDetailsPhotoTransform(val scale: Float, val translationY: Float)

/**
 * Where the photo sits at [progress]: centred in the space above the surface,
 * shrunk only when its fitted height does not fit there. [aspect] is
 * height / width of the media (0 when unknown: treated as full height). With
 * the default centre pivot, a full-height photo's bottom edge lands exactly
 * on the surface's top edge at every progress.
 */
internal fun viewerDetailsPhotoTransform(
    progress: Float, width: Float, height: Float, panelPx: Float, aspect: Float
): ViewerDetailsPhotoTransform {
    val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
    if (!(width > 0f) || !(height > 0f) || !(panelPx > 0f) || !width.isFinite() ||
        !height.isFinite() || !panelPx.isFinite()) return ViewerDetailsPhotoTransform(1f, 0f)
    val available = (height - panelPx).coerceAtLeast(1f)
    val fitted = if (aspect.isFinite() && aspect > 0f) min(height, width * aspect) else height
    val targetScale = min(1f, available / fitted)
    return ViewerDetailsPhotoTransform(1f + (targetScale - 1f) * p, -panelPx / 2f * p)
}

/** Rows fade in while the surface rises (Samsung: over the second half). */
internal fun viewerDetailsContentAlpha(progress: Float): Float =
    if (!progress.isFinite()) 0f else ((progress - .2f) / .55f).coerceIn(0f, 1f)

/**
 * Whether the system "remove animations" setting is on, in which case the
 * details surface cuts to its end state. Tests that measure the motion on
 * the animation-free CI emulator provide `{ false }` instead of changing
 * device-wide settings.
 */
internal val LocalViewerAnimationsDisabled =
    staticCompositionLocalOf<(Context) -> Boolean> { ::sharedPhotoAnimationDisabled }
