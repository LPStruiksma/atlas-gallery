package nl.atlas.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** #34: aggregate-only fixture values, no actual photos or identifying metadata. */
class PhotoDetailsPresentationTest {
    @Test fun plainLabelsJoinEqualDimensionsAndFormatLargeCounts() {
        val output = photoDetailsContent(
            checked = 248_812, partial = 219_098, skipped = 0,
            missing = mapOf(
                "heightPixels" to 106, "orientationDegrees" to 219_098,
                "sizeBytes" to 76, "widthPixels" to 106
            ), locale = Locale.US
        )
        assertEquals("Photos checked: 248,812 · missing some details: 219,098 · skipped: 0",
            output.summary)
        assertEquals(listOf("Rotation not recorded", "File size unknown",
            "Width/height unknown"), output.rows.map { it.label })
        assertEquals(listOf("219,098 (88.06%)", "76 (0.03%)", "106 (0.04%)"),
            output.rows.map { it.countText })
        assertTrue(output.rows[0].note.contains("decode", ignoreCase = true))
        assertTrue(output.rows[1].note.contains("sort", ignoreCase = true))
        assertTrue(output.rows[2].note.contains("proportions", ignoreCase = true))
        assertFalse(output.toString().contains("orientationDegrees"))
        assertFalse(output.toString().contains("heightPixels"))
        assertFalse(output.toString().contains("sizeBytes"))
    }

    @Test fun unequalDimensionsStaySeparateAndUnknownKeysNeverLeak() {
        val output = photoDetailsContent(
            checked = 10, partial = 6, skipped = 2,
            missing = mapOf(
                "widthPixels" to 6, "heightPixels" to 2,
                "generationModified" to 3, "providerVersion" to 1,
                "modifiedAtSeconds" to 1, "editorRevision" to 1,
                "vendorUnsafeFieldPath" to 4
            ), locale = Locale.US
        )
        assertEquals(7, output.rows.size)
        assertEquals(1, output.rows.count { it.label == "Width unknown" })
        assertEquals(1, output.rows.count { it.label == "Height unknown" })
        assertTrue(output.rows.any { it.label == "Other detail unavailable" })
        assertFalse(output.toString().contains("vendorUnsafeFieldPath"))
        assertFalse(output.toString().contains("generationModified"))
        assertTrue(output.summary.endsWith("skipped: 2"))
    }

    @Test fun zeroAndLocaleVariationDoNotDivideByZeroOrExposeRawNames() {
        val output = photoDetailsContent(
            checked = 0, partial = 0, skipped = 0,
            missing = mapOf("sizeBytes" to 1, "widthPixels" to 0),
            locale = Locale.GERMANY
        )
        assertEquals("1 (0,00%)", output.rows.single().countText)
        assertEquals("Photos checked: 0 · missing some details: 0 · skipped: 0",
            output.summary)
        val german = photoDetailsContent(12_345, 4_000, 0,
            mapOf("orientationDegrees" to 2_000), Locale.GERMANY)
        assertTrue(german.summary.contains("12.345"))
        assertEquals("2.000 (16,20%)", german.rows.single().countText)
    }
}
