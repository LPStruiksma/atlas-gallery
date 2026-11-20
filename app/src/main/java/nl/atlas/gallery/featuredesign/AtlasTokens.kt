package nl.atlas.gallery.featuredesign

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** BRAND-L2 #655: Quiet Frame v3 Lapis Atlas. Opaque semantic roles are the source of truth. */
object AtlasTokens {
    object Colors {
        object Dark {
            val Ground = Color(0xFF000000)
            val Surface = Color(0xFF14152A)
            val Ink = Color(0xFFF2F3F8)
            val InkSecondary = Color(0xFF9AA0B8)
            val BrandFill = Color(0xFF3448E0)
            val BrandText = Color(0xFF8C9BFF)
            val OnBrand = Color(0xFFFFFFFF)
            val OnBrandSecondary = Color(0xFFDCE1FF)
            val ViewerGround = Color(0xFF000000)

            // Material 3 container roles must be complete; never use its purple defaults.
            val PrimaryContainer = BrandFill
            val OnPrimaryContainer = OnBrand
            val SecondaryContainer = Surface
            val OnSecondaryContainer = Ink
            val Foreground = Ink
            val SurfaceVariant = Surface
            val OnSurfaceVariant = InkSecondary
            val InverseSurface = Light.Surface
            val Outline = InkSecondary
            val OutlineVariant = Surface
            val SurfaceBright = Surface
            val SurfaceLowest = Ground
            val SurfaceLow = Surface
            val SurfaceHigh = Surface
            val Pill = Surface
        }
        object Light {
            val Ground = Color(0xFFFFFFFF)
            val Surface = Color(0xFFF2F3F8)
            val Ink = Color(0xFF0E1030)
            val InkSecondary = Color(0xFF5A5F7A)
            val BrandFill = Color(0xFF2639C9)
            val BrandText = Color(0xFF2639C9)
            val OnBrand = Color(0xFFFFFFFF)
            val OnBrandSecondary = Color(0xFFDCE1FF)
            val ViewerGround = Color(0xFF000000)

            val PrimaryContainer = BrandFill
            val OnPrimaryContainer = OnBrand
            val SecondaryContainer = Surface
            val SurfaceVariant = Surface
            val InverseSurface = Dark.Surface
            val InverseForeground = Dark.Ink
            val Outline = InkSecondary
            val OutlineVariant = Surface
            val SurfaceDim = Surface
            val SurfaceLow = Surface
            val SurfaceContainer = Surface
            val SurfaceHigh = Surface
        }

        // Compatibility names kept so feature call sites inherit the Lapis palette
        // without a navigation/component rewrite (those changes belong to BRAND-L4+).
        val Ink = Light.Ink
        val InkRaised = Dark.Surface
        val Mist = Light.Surface
        val Paper = Light.Ground
        val Tidal = Dark.BrandText
        val DeepTidal = Light.BrandFill
        val WarmGray = Dark.InkSecondary
        val QuietGray = Light.InkSecondary

        object Viewer {
            val FilmstripTrack = Dark.Surface
            val Favourite = Dark.BrandText
            val Backdrop = Dark.ViewerGround
            val ActionPill = Dark.BrandFill.copy(alpha = 0.85f)
            val MediaTrack = Dark.Surface
            // Necessary transparent black overlays preserve media visibility.
            val Scrim = Color(0xA0000000)
            val SeekInactive = Dark.InkSecondary
            val SelectionAccent = Dark.BrandText
            val ChromeScrimTop = Color(0xB8000000)
            val ChromeScrimBottom = Color(0xCC000000)
        }
        object People {
            // Image placeholders, not UI chrome: synthetic portrait variation.
            val PortraitMuted = Color(0xFF8B95A1)
            val PortraitLight = Color(0xFFADBAC7)
            val PortraitDark = Color(0xFF2A303A)
        }
        object Selection {
            val Accent = Light.BrandFill
        }
        object Shell {
            val DarkSurface = Dark.Surface
            val DarkAccent = Dark.BrandText
        }
    }

    object SyntheticArt {
        // Fictional showcase artwork only, exempt from UI chrome uniformity.
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
        val Sheet = 28.dp
        val GridTile = 3.dp
        val PersonCard = 20.dp
        val PersonCover = 18.dp
    }

    object Type {
        val BodyLineHeight = 22.sp
        // Existing navigation caps at 200% are preserved pending BRAND-L4.
        val NavigationLabel = 11.sp
        val DisplayTracking = (-0.03f).em
    }
}
