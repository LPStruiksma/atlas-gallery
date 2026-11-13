package nl.atlas.gallery.featuredesign

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import nl.atlas.gallery.featurealbums.AlbumSummary

/**
 * Clearly labelled fictional illustration for design previews only.
 * No image bytes, network access, biometric identity or owner media.
 * Never use this as a fallback cover for a real production album.
 */
@Composable
fun AtlasSyntheticAlbumArtwork(album: AlbumSummary) {
    val palettes = listOf(
        listOf(Color(0xFF46686D), Color(0xFFB6C4B4), Color(0xFF40675E)),
        listOf(Color(0xFF99856D), Color(0xFFE2BFA1), Color(0xFF595A4B)),
        listOf(Color(0xFF4E657F), Color(0xFFD5BDB0), Color(0xFF596786)),
        listOf(Color(0xFF6E736B), Color(0xFFC5C2AF), Color(0xFF454E48))
    )
    // Title-length choice is stable and completely fictional.
    val palette = palettes[album.title.length % palettes.size]
    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(palette[0], palette[1], palette[2]),
                    endY = h
                )
            )
            drawCircle(
                color = Color(0xFFFFF0D0).copy(alpha = 0.78f),
                radius = w * 0.105f,
                center = Offset(w * 0.73f, h * 0.23f)
            )
            val distant = Path().apply {
                moveTo(0f, h * 0.67f)
                cubicTo(w * 0.22f, h * 0.48f, w * 0.31f, h * 0.75f,
                    w * 0.53f, h * 0.62f)
                cubicTo(w * 0.72f, h * 0.51f, w * 0.83f, h * 0.58f,
                    w, h * 0.49f)
                lineTo(w, h)
                lineTo(0f, h)
                close()
            }
            drawPath(distant, color = palette[2].copy(alpha = 0.62f))
            val foreground = Path().apply {
                moveTo(0f, h * 0.83f)
                cubicTo(w * 0.31f, h * 0.65f, w * 0.55f, h * 0.95f,
                    w, h * 0.72f)
                lineTo(w, h)
                lineTo(0f, h)
                close()
            }
            drawPath(foreground, color = palette[0].copy(alpha = 0.86f))
        }
        Text(
            text = "FICTIONAL",
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
                .background(Color.Black.copy(alpha = 0.60f), RoundedCornerShape(7.dp))
                .padding(horizontal = 7.dp, vertical = 4.dp)
                .testTag("atlas-fictional-artwork")
        )
    }
}
