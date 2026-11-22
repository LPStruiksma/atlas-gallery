package nl.atlas.gallery.featuredesign

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * DESIGN-1: central visual constants. Since 2026-11-18 the values follow the
 * Quiet Frame look (docs/design/QUIET_FRAME.md); role names are unchanged.
 */
object AtlasTokens {
    object Colors {
        val Ink = Color(0xFF111318)
        val InkRaised = Color(0xFF17191F)
        val Mist = Color(0xFFF6F7F9)
        val Paper = Color(0xFFFFFFFF)
        val Tidal = Color(0xFF7FA8FF)
        val DeepTidal = Color(0xFF2A62D4)
        val WarmGray = Color(0xFFB4B8C1)
        val QuietGray = Color(0xFF4A4F59)

        object Dark {
            val PrimaryContainer = Color(0xFF1E2D4A)
            val OnPrimaryContainer = Color(0xFFD6E3FF)
            val SecondaryContainer = Color(0xFF23262E)
            val OnSecondaryContainer = Color(0xFFECEDF0)
            val Foreground = Color(0xFFECEDF0)
            val SurfaceVariant = Color(0xFF23262E)
            val OnSurfaceVariant = Color(0xFFB4B8C1)
            val InverseSurface = Color(0xFFECEDF0)
            val Outline = Color(0xFF6E737D)
            val OutlineVariant = Color(0xFF2E313A)
            val SurfaceBright = Color(0xFF2B2E37)
            val SurfaceLowest = Color(0xFF0B0C0F)
            val SurfaceLow = Color(0xFF131519)
            val SurfaceHigh = Color(0xFF1D2027)
            val Pill = Color(0xFF23262E)
        }
        object Viewer {
            val FilmstripTrack = Color(0xFF2A2A2A)
            val Favourite = Color(0xFFFF4D5E)
            val Backdrop = Color(0xFF171A20)
            val ActionPill = Color(0xD91C1E25)
            val MediaTrack = Color(0xFF292929)
            val Scrim = Color(0xA0000000)
            val SeekInactive = Color(0xFF888888)
            val SelectionAccent = Color(0xFF7FA8FF)
            val ChromeScrimTop = Color(0xB8000000)
            val ChromeScrimBottom = Color(0xCC000000)
        }
        object People {
            val PortraitMuted = Color(0xFF8B95A1)
            val PortraitLight = Color(0xFFADBAC7)
            val PortraitDark = Color(0xFF2A303A)
        }
        object Selection {
            val Accent = Color(0xFF3E91FF)
        }
        object Shell {
            val DarkSurface = Color(0xFF17191F)
            val DarkAccent = Viewer.SelectionAccent
        }
        /**
         * AI Edit editor (#431, Quiet Frame v3): black viewer ground and the lapis action slab.
         * Local here until the Lapis Atlas tokens land (BRAND-L2); then these map onto them.
         */
        object AiEdit {
            val Ground = Color(0xFF000000)
            val Slab = Color(0xFF3448E0)
            val OnBrand = Color(0xFFFFFFFF)
            val OnBrandSecondary = Color(0xFFDCE1FF)
            val Surface = Color(0xFF14152A)
            val Ink = Color(0xFFF2F3F8)
            val InkSecondary = Color(0xFF9AA0B8)
            val BrandText = Color(0xFF8C9BFF)
        }
        object Light {
            val PrimaryContainer = Color(0xFFDCE6FF)
            val OnPrimaryContainer = Color(0xFF0B2A66)
            val SecondaryContainer = Color(0xFFECEEF2)
            val SurfaceVariant = Color(0xFFECEEF2)
            val InverseSurface = Color(0xFF23262E)
            val InverseForeground = Color(0xFFECEDF0)
            val Outline = Color(0xFF737885)
            val OutlineVariant = Color(0xFFD5D8DF)
            val SurfaceDim = Color(0xFFDDE0E6)
            val SurfaceLow = Color(0xFFFAFBFC)
            val SurfaceContainer = Color(0xFFF1F3F6)
            val SurfaceHigh = Color(0xFFFBFBFD)
        }
    }

    object SyntheticArt {
        // Fictional showcase artwork only; no Samsung or user media.
        val AlbumPalettes = listOf(
            listOf(Color(0xFF46686D), Color(0xFFB6C4B4), Color(0xFF40675E)),
            listOf(Color(0xFF99856D), Color(0xFFE2BFA1), Color(0xFF595A4B)),
            listOf(Color(0xFF4E657F), Color(0xFFD5BDB0), Color(0xFF596786)),
            listOf(Color(0xFF6E736B), Color(0xFFC5C2AF), Color(0xFF454E48))
        )
        val Highlight = Color(0xFFFFF0D0)
    }

    object Spacing {
        val Gutter = 16.dp
        val Section = 28.dp
        val TileGap = 10.dp
        val MinimumTarget = 48.dp
        val PillEdge = 12.dp
        val TabHeight = 60.dp
        val HeaderHeight = 56.dp
        val GridGap = 2.dp
    }

    object Radii {
        val Cover = 20.dp
        val EmptyVisual = 28.dp
        /** Quiet Frame bottom sheet, top corners only. */
        val Sheet = 28.dp
        val GridTile = 3.dp
        val PersonCard = 20.dp
        val PersonCover = 18.dp
    }

    object Type {
        val BodyLineHeight = 21.sp
        val NavigationLabel = 11.sp
        val DisplayTracking = (-0.6f).sp
    }
}
