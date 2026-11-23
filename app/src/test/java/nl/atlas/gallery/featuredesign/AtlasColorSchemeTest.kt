package nl.atlas.gallery.featuredesign

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #657: the roles dialogs, menus, sheets and text fields read must be ATLAS's
 * own, never Material's purple baseline (the light-lavender "Add name").
 */
class AtlasColorSchemeTest {
    private fun containerRoles(s: ColorScheme) = mapOf(
        "surfaceContainerLowest" to s.surfaceContainerLowest,
        "surfaceContainerLow" to s.surfaceContainerLow,
        "surfaceContainer" to s.surfaceContainer,
        "surfaceContainerHigh" to s.surfaceContainerHigh,
        "surfaceContainerHighest" to s.surfaceContainerHighest,
        "surfaceTint" to s.surfaceTint,
        "surfaceBright" to s.surfaceBright,
        "surfaceDim" to s.surfaceDim,
        "outline" to s.outline,
        "outlineVariant" to s.outlineVariant,
        "primaryContainer" to s.primaryContainer,
        "secondaryContainer" to s.secondaryContainer,
        "tertiary" to s.tertiary,
        "inverseSurface" to s.inverseSurface,
        "inversePrimary" to s.inversePrimary
    )

    @Test fun noContainerRoleFallsBackToMaterialBaseline() {
        listOf(darkGalleryColors to darkColorScheme(), lightGalleryColors to lightColorScheme())
            .forEach { (ours, baseline) ->
                val base = containerRoles(baseline)
                containerRoles(ours).forEach { (role, colour) ->
                    // Plain white is shared with the baseline and is not tinted.
                    if (colour == Color.White) return@forEach
                    assertNotEquals("$role is Material's baseline", base.getValue(role), colour)
                }
            }
    }

    @Test fun dialogContainerIsDarkInDarkAndLightInLight() {
        assertTrue(darkGalleryColors.surfaceContainerHigh.luminance() < 0.05f)
        assertTrue(lightGalleryColors.surfaceContainerHigh.luminance() > 0.85f)
        // The dialog lifts off its page in both themes.
        assertNotEquals(darkGalleryColors.background, darkGalleryColors.surfaceContainerHigh)
    }

    @Test fun noContainerIsPurple() {
        listOf(darkGalleryColors, lightGalleryColors).forEach { s ->
            containerRoles(s).forEach { (role, c) ->
                // Material's baseline containers lean blue+red over green (lavender).
                val purple = c.blue > c.green + 0.02f && c.red > c.green + 0.02f
                assertTrue("$role is purple: $c", !purple)
            }
        }
    }

    private fun contrast(a: Color, b: Color): Double {
        val x = a.luminance().toDouble()
        val y = b.luminance().toDouble()
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }

    /** #1011: every role Material draws text in must reach 4.5:1 on the backgrounds it sits on. */
    @Test fun textRolesMeetBodyContrastOnGroundSurfaceAndDialogs() {
        listOf("dark" to darkGalleryColors, "light" to lightGalleryColors).forEach { (theme, s) ->
            val backgrounds = mapOf("background" to s.background, "surface" to s.surface,
                "surfaceContainerHigh" to s.surfaceContainerHigh)
            val textRoles = mapOf("primary" to s.primary, "secondary" to s.secondary,
                "tertiary" to s.tertiary, "onBackground" to s.onBackground,
                "onSurface" to s.onSurface, "onSurfaceVariant" to s.onSurfaceVariant)
            textRoles.forEach { (role, text) ->
                backgrounds.forEach { (bg, colour) ->
                    val c = contrast(text, colour)
                    assertTrue("$theme $role on $bg: $c", c >= 4.5)
                }
            }
            listOf(Triple("onPrimary", s.onPrimary, s.primary),
                Triple("onPrimaryContainer", s.onPrimaryContainer, s.primaryContainer),
                Triple("onSecondary", s.onSecondary, s.secondary)).forEach { (role, fg, bg) ->
                assertTrue("$theme $role: ${contrast(fg, bg)}", contrast(fg, bg) >= 4.5)
            }
        }
    }

    @Test fun lapisPaletteMatchesApprovedQuietFrameV3() {
        val light = AtlasTokens.Colors.Light
        val dark = AtlasTokens.Colors.Dark
        val expectedLight = listOf(
            light.Ground to Color(0xFFFFFFFF), light.Surface to Color(0xFFF2F3F8),
            light.Ink to Color(0xFF0E1030), light.InkSecondary to Color(0xFF5A5F7A),
            light.BrandFill to Color(0xFF2639C9), light.BrandText to Color(0xFF2639C9),
            light.OnBrand to Color(0xFFFFFFFF), light.OnBrandSecondary to Color(0xFFDCE1FF),
            light.ViewerGround to Color(0xFF000000)
        )
        val expectedDark = listOf(
            dark.Ground to Color(0xFF000000), dark.Surface to Color(0xFF14152A),
            dark.Ink to Color(0xFFF2F3F8), dark.InkSecondary to Color(0xFF9AA0B8),
            dark.BrandFill to Color(0xFF3448E0), dark.BrandText to Color(0xFF8C9BFF),
            dark.OnBrand to Color(0xFFFFFFFF), dark.OnBrandSecondary to Color(0xFFDCE1FF),
            dark.ViewerGround to Color(0xFF000000)
        )
        (expectedLight + expectedDark).forEachIndexed { index, (actual, expected) ->
            assertEquals("Lapis palette entry $index", expected, actual)
        }
    }

    @Test fun lapisTextAndFillMeetBothThemeContrastThresholds() {
        val palettes = listOf(
            Triple("light", AtlasTokens.Colors.Light.Ground, AtlasTokens.Colors.Light.Surface) to
                listOf(AtlasTokens.Colors.Light.Ink, AtlasTokens.Colors.Light.InkSecondary,
                    AtlasTokens.Colors.Light.BrandText, AtlasTokens.Colors.Light.BrandFill,
                    AtlasTokens.Colors.Light.OnBrand, AtlasTokens.Colors.Light.OnBrandSecondary),
            Triple("dark", AtlasTokens.Colors.Dark.Ground, AtlasTokens.Colors.Dark.Surface) to
                listOf(AtlasTokens.Colors.Dark.Ink, AtlasTokens.Colors.Dark.InkSecondary,
                    AtlasTokens.Colors.Dark.BrandText, AtlasTokens.Colors.Dark.BrandFill,
                    AtlasTokens.Colors.Dark.OnBrand, AtlasTokens.Colors.Dark.OnBrandSecondary)
        )
        palettes.forEach { (bases, roles) ->
            val (theme, ground, surface) = bases
            roles.take(3).forEachIndexed { index, text ->
                for ((label, background) in listOf("ground" to ground, "surface" to surface)) {
                    assertTrue("$theme text $index on $label: ${contrast(text, background)}",
                        contrast(text, background) >= 4.5)
                }
            }
            val fill = roles[3]
            assertTrue("$theme brand-fill on ground", contrast(fill, ground) >= 3.0)
            assertTrue("$theme on-brand", contrast(roles[4], fill) >= 4.5)
            assertTrue("$theme on-brand-secondary", contrast(roles[5], fill) >= 4.5)
        }
        assertEquals(AtlasTokens.Colors.Dark.Ground, darkGalleryColors.background)
        assertEquals(AtlasTokens.Colors.Light.Ground, lightGalleryColors.background)
        assertEquals(AtlasTokens.Colors.Dark.Surface, darkGalleryColors.surface)
        assertEquals(AtlasTokens.Colors.Light.Surface, lightGalleryColors.surface)
    }
}
