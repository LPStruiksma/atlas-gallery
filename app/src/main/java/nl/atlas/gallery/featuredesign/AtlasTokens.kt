package nl.atlas.gallery.featuredesign

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * DESIGN-1: central, value-preserving visual constants. No visual redesign:
 * existing semantic roles keep the exact ARGB and dp/sp values in use on main.
 */
object AtlasTokens {
    object Colors {
        val Ink = Color(0xFF11171B)
        val InkRaised = Color(0xFF1D272C)
        val Mist = Color(0xFFF5F7F4)
        val Paper = Color(0xFFFFFFFF)
        val Tidal = Color(0xFF9DE1D2)
        val DeepTidal = Color(0xFF176D61)
        val WarmGray = Color(0xFFB5C2C0)
        val QuietGray = Color(0xFF50625F)

        object Dark {
            val PrimaryContainer = Color(0xFF1F4F47)
            val OnPrimaryContainer = Color(0xFFC6F2E7)
            val SecondaryContainer = Color(0xFF2E3B3E)
            val OnSecondaryContainer = Color(0xFFDCE6E3)
            val Foreground = Color(0xFFF7FAF8)
            val SurfaceVariant = Color(0xFF2A3539)
            val OnSurfaceVariant = Color(0xFFD0DDDA)
            val InverseSurface = Color(0xFFE6ECEA)
            val Outline = Color(0xFF7F8F8C)
            val OutlineVariant = Color(0xFF3A4649)
            val SurfaceBright = Color(0xFF323D42)
            val SurfaceLowest = Color(0xFF0B0D10)
            val SurfaceLow = Color(0xFF161D21)
            val SurfaceHigh = Color(0xFF243035)
            val Pill = Color(0xFF262B30)
        }
        object Viewer {
            val FilmstripTrack = Color(0xFF2A2A2A)
            val Favourite = Color(0xFFFF4D5E)
            val Backdrop = Color(0xFF171A20)
            val ActionPill = Color(0xB3202124)
            val MediaTrack = Color(0xFF292929)
            val Scrim = Color(0xA0000000)
            val SeekInactive = Color(0xFF888888)
            val SelectionAccent = Color(0xFFB5E7DA)
        }
        object Shell {
            val DarkSurface = Color(0xFF171B21)
            val DarkAccent = Viewer.SelectionAccent
        }
        object Light {
            val PrimaryContainer = Color(0xFFBDEDE2)
            val OnPrimaryContainer = Color(0xFF00382F)
            val SecondaryContainer = Color(0xFFDDE7E4)
            val SurfaceVariant = Color(0xFFE3EBE8)
            val InverseSurface = Color(0xFF2A3236)
            val InverseForeground = Color(0xFFEFF3F1)
            val Outline = Color(0xFF70807D)
            val OutlineVariant = Color(0xFFC9D3D0)
            val SurfaceDim = Color(0xFFD9DFDC)
            val SurfaceLow = Color(0xFFF8FAF8)
            val SurfaceContainer = Color(0xFFF1F4F2)
            val SurfaceHigh = Color(0xFFFBFCFB)
        }
    }

    object Spacing {
        val Gutter = 18.dp
        val Section = 28.dp
        val TileGap = 10.dp
        val MinimumTarget = 48.dp
        val PillEdge = 12.dp
        val TabHeight = 60.dp
        val HeaderHeight = 56.dp
        val GridGap = 3.dp
    }

    object Radii {
        val Cover = 20.dp
        val EmptyVisual = 28.dp
        val GridTile = 3.dp
        val PersonCard = 24.dp
        val PersonCover = 18.dp
    }

    object Type {
        val BodyLineHeight = 21.sp
        val NavigationLabel = 11.sp
        val DisplayTracking = (-0.6f).sp
    }
}
