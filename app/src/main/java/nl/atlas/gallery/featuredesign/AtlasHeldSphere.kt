package nl.atlas.gallery.featuredesign

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Calendar
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * BRAND-L4 (#657; Quiet Frame v3 "Signature: the held sphere"): one lapis
 * sphere marks what is current, chosen or loading on every surface. This file
 * holds the shared drawing so every surface uses the same vocabulary.
 */
object AtlasHeldSphere {
    /** The solid lapis of the sphere in the current theme. */
    val fill: Color
        @Composable get() = MaterialTheme.colorScheme.primaryContainer

    /** White ink on the sphere. */
    val onFill: Color
        @Composable get() = MaterialTheme.colorScheme.onPrimaryContainer
}

/**
 * Selection: a lapis sphere on the tile corner with a 2 dp white halo; the
 * pick number when the selection is ordered, otherwise a tick.
 */
@Composable
fun AtlasSelectionSphere(
    modifier: Modifier = Modifier,
    size: Dp = AtlasTokens.Sphere.Selection,
    order: Int? = null
) {
    Box(
        modifier.size(size)
            .clip(CircleShape)
            .background(AtlasHeldSphere.fill)
            .border(AtlasTokens.Sphere.Halo, Color.White, CircleShape)
            .testTag("atlas-selection-sphere"),
        contentAlignment = Alignment.Center
    ) {
        if (order != null) {
            Text(order.toString(), color = Color.White, fontWeight = FontWeight.SemiBold,
                // The number keeps its size inside the fixed sphere at any font scale.
                fontSize = (size.value * 0.5f / currentFontScale()).sp, maxLines = 1)
        } else {
            Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White,
                modifier = Modifier.size(size * 0.62f))
        }
    }
}

@Composable
private fun currentFontScale(): Float =
    androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(0.01f)

/**
 * Loading: the sphere climbs an arc beside a plain count. With a known
 * [progress] it sits at that point of the arc; without one it climbs slowly
 * and starts again. When the system "remove animations" setting is on it
 * rests at the arc's midpoint instead (nothing moves on its own).
 */
@Composable
fun AtlasLoadingArc(
    progress: Float?,
    modifier: Modifier = Modifier,
    size: Dp = AtlasTokens.Sphere.LoadingArc
) {
    val context = LocalContext.current
    val still = remember(context) { AtlasMotion.animationsDisabled(context) }
    val climb = if (progress != null || still) null else {
        val transition = rememberInfiniteTransition(label = "atlas-loading-arc")
        transition.animateFloat(0f, 1f,
            infiniteRepeatable(tween(1_600, easing = LinearEasing), RepeatMode.Restart),
            label = "atlas-loading-climb")
    }
    val fraction = (progress ?: climb?.value ?: 0.5f).coerceIn(0f, 1f)
    val track = MaterialTheme.colorScheme.surfaceVariant
    val sphere = AtlasHeldSphere.fill
    Canvas(modifier.size(size).testTag("atlas-loading-arc")) {
        val sphereRadius = AtlasTokens.Sphere.Loading.toPx() / 2f
        val stroke = 2.dp.toPx()
        val radius = (minOf(this.size.width, this.size.height) / 2f) - sphereRadius
        val centre = Offset(this.size.width / 2f, this.size.height / 2f + radius / 2f)
        // A half-circle arc from the lower left, over the top, to the lower right.
        drawArc(track, startAngle = 180f, sweepAngle = 180f, useCenter = false,
            topLeft = Offset(centre.x - radius, centre.y - radius),
            size = androidx.compose.ui.geometry.Size(radius * 2f, radius * 2f),
            style = Stroke(stroke, cap = StrokeCap.Round))
        val angle = Math.toRadians((180.0 + 180.0 * fraction))
        val at = Offset(centre.x + radius * cos(angle).toFloat(), centre.y + radius * sin(angle).toFloat())
        drawCircle(sphere, sphereRadius, at)
    }
}

/**
 * Fast-scroll sphere text: the short month of the photo under the thumb for
 * date sorts ("Nov"), otherwise null (the bubble beside it carries the size
 * bucket, letter or position).
 */
fun fastScrollSphereLabel(
    usesDates: Boolean,
    dateMillis: Long,
    locale: Locale = Locale.getDefault()
): String? {
    if (!usesDates || dateMillis <= 0L) return null
    val calendar = Calendar.getInstance().apply { timeInMillis = dateMillis }
    return calendar.getDisplayName(Calendar.MONTH, Calendar.SHORT_STANDALONE, locale)
        ?.trimEnd('.')
        ?.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
}
