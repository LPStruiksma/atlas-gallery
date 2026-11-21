package nl.atlas.gallery.featureaiedit.ui

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64

internal const val AI_EDIT_PROMPT_HISTORY_FILE = "recent-prompts-v1"

/**
 * Only explicitly successful user-submitted prompts belong here. Caller supplies a file beneath
 * Context.noBackupFilesDir: prompts must never enter Android Auto Backup, preferences, telemetry,
 * provider configuration or the gallery catalog. No URIs, images, credentials or provider metadata.
 */
internal class AiEditPromptHistory(private val file: File) {
    private companion object {
        const val MAX_ENTRIES = 20
        const val MAX_PROMPT_CHARS = 16_384
        const val MAX_FILE_BYTES = 2_000_000L
    }

    fun entries(): List<String> = runCatching {
        if (!file.isFile || file.length() > MAX_FILE_BYTES) emptyList()
        else file.readLines(Charsets.UTF_8).take(MAX_ENTRIES).mapNotNull { line ->
            runCatching {
                String(Base64.getUrlDecoder().decode(line), Charsets.UTF_8)
                    .takeIf { it.isNotBlank() && it.length <= MAX_PROMPT_CHARS }
            }.getOrNull()
        }.distinct()
    }.getOrDefault(emptyList())

    fun record(prompt: String) {
        val value = prompt.trim().take(MAX_PROMPT_CHARS)
        if (value.isBlank()) return
        val next = (listOf(value) + entries().filterNot { it == value }).take(MAX_ENTRIES)
        val parent = file.parentFile ?: return
        if (!parent.isDirectory && !parent.mkdirs()) return
        val temporary = File(parent, file.name + ".tmp")
        try {
            temporary.writeText(next.joinToString("\n") {
                Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(Charsets.UTF_8))
            } + "\n", Charsets.UTF_8)
            try {
                Files.move(temporary.toPath(), file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    fun clear(): Boolean {
        File(file.parentFile, file.name + ".tmp").delete()
        return !file.exists() || file.delete()
    }
}
