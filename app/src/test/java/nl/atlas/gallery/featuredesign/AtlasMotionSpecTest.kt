package nl.atlas.gallery.featuredesign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** DESIGN-7: every named motion has a deterministic zero-duration alternative. */
class AtlasMotionSpecTest {
    @Test fun motionPresetsKeepReducedMotionInstant() {
        AtlasMotionKind.entries.forEach { kind ->
            val ordinary = atlasMotionSpec(kind, reduceMotion = false)
            val reduced = atlasMotionSpec(kind, reduceMotion = true)
            assertTrue("Normal ${kind.name} should animate", ordinary.durationMillis > 0)
            assertEquals("Reduced ${kind.name} must be instantaneous", 0, reduced.durationMillis)
            assertEquals(0, reduced.delayMillis)
        }
    }

    @Test fun namedPresetsRemainCoherent() {
        assertEquals(180, atlasMotionSpec(AtlasMotionKind.TAB, false).durationMillis)
        assertEquals(240, atlasMotionSpec(AtlasMotionKind.SHEET, false).durationMillis)
        assertEquals(200, atlasMotionSpec(AtlasMotionKind.SELECTION, false).durationMillis)
        assertEquals(120, atlasMotionSpec(AtlasMotionKind.PRESS, false).durationMillis)
        assertEquals(200, atlasMotionSpec(AtlasMotionKind.PILL, false).durationMillis)
        assertEquals(200, atlasMotionSpec(AtlasMotionKind.HEADER, false).durationMillis)
    }
}
