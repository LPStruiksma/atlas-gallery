package nl.atlas.gallery.featuredesign

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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
}
