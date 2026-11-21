package nl.atlas.gallery.featureeditor.render

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The AGSL shader can only run on a device, so this JVM test guards what can drift silently:
 * every uniform the Kotlin side binds is declared, and the colour constants match [ColourOps].
 */
class AgslShaderSourceTest {
    private val source: String = run {
        val f = listOf("src/main/java", "app/src/main/java").map { File(it, "nl/atlas/gallery/featureeditor/render/AgslProgram.kt") }.first { it.exists() }
        val text = f.readText()
        text.substring(text.indexOf("val SHADER: String = \"\"\""), text.lastIndexOf("\"\"\""))
    }

    @Test fun everyBoundUniformIsDeclared() {
        val kotlin = File(listOf("src/main/java", "app/src/main/java").first { File(it).exists() }, "nl/atlas/gallery/featureeditor/render/AgslProgram.kt").readText()
        val bound = Regex("setFloatUniform\\(\"(\\w+)\"").findAll(kotlin).map { it.groupValues[1] }.toSet() +
            Regex("rows\\(\"(\\w+)\"").findAll(kotlin).flatMap { m -> (0..2).map { "${m.groupValues[1]}$it" } } +
            Regex("setInputBuffer\\(\"(\\w+)\"").findAll(kotlin).map { it.groupValues[1] }
        assertTrue(bound.size >= 20)
        for (name in bound) assertTrue("$name is declared", Regex("uniform \\w+ $name;").containsMatchIn(source))
    }

    @Test fun constantsComeFromColourOps() {
        for (c in listOf("EXPOSURE_STOPS", "BRIGHTNESS_GAMMA", "CONTRAST_GAIN", "HIGHLIGHT_STOPS", "SHADOW_STOPS", "WB_STOPS", "HUE_RANGE",
            "DEFINITION_GAIN", "SHARPNESS_GAIN", "VIGNETTE_STOPS")) {
            assertTrue("$c is interpolated", source.contains("\${ColourOps.$c}"))
        }
        val luma = ColourScience.LUMA_P3.joinToString(", ") { it.toString() }
        assertTrue("luma weights match", source.contains("float3($luma)"))
    }
}
