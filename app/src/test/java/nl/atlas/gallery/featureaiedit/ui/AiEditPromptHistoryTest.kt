package nl.atlas.gallery.featureaiedit.ui

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AiEditPromptHistoryTest {
    private fun inTempDir(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("atlas-aie21-history").toFile()
        try { block(File(directory, "history")) } finally { directory.deleteRecursively() }
    }

    @Test fun storesOnlyPromptsAndReusesNewestFirstWithoutDuplicates() = inTempDir { file ->
        val history = AiEditPromptHistory(file)
        history.record("  Clean background  ")
        history.record("Fix lighting")
        history.record("Clean background")
        assertEquals(listOf("Clean background", "Fix lighting"), history.entries())
        assertFalse(file.readText().contains("Clean background")) // encoded, not plain text
        assertFalse(file.readText().contains("Fix lighting"))
    }

    @Test fun keepsAtMostTwentyAndSupportsExplicitClear() = inTempDir { file ->
        val history = AiEditPromptHistory(file)
        (1..25).forEach { history.record("Edit $it") }
        assertEquals(20, history.entries().size)
        assertEquals("Edit 25", history.entries().first())
        assertEquals("Edit 6", history.entries().last())
        assertTrue(history.clear())
        assertEquals(emptyList<String>(), history.entries())
    }

    @Test fun ignoresEmptyOversizedAndCorruptLines() = inTempDir { file ->
        val history = AiEditPromptHistory(file)
        history.record("  ")
        assertFalse(file.exists())
        history.record("x".repeat(20_000))
        assertEquals(16_384, history.entries().single().length)
        file.writeText("not a valid base64!\n")
        assertTrue(history.entries().isEmpty())
    }
}
