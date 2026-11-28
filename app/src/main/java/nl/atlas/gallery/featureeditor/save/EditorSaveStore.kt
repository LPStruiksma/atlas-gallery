package nl.atlas.gallery.featureeditor.save

import nl.atlas.gallery.featureeditor.engine.EditRecipe
import nl.atlas.gallery.featureeditor.engine.RecipeJson
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties

/**
 * EDIT-3 (#633): app-private, no-backup record of Atlas edits. Pure `java.io`, no Android types,
 * so the journal rules are unit-tested (`EditorSaveStoreTest`).
 *
 * One directory per edited MediaStore URI (named by the SHA-256 of the URI string):
 * - `meta.properties`: kind, URI, SHA-256 of the original and of the last saved bytes, state.
 * - `original.bin` (Save only): the photo's bytes from before the first Atlas Save. Revert writes
 *   exactly these bytes back. A later Save of an unchanged Atlas edit keeps this file, so Revert
 *   always returns to the true original, never to an earlier edit.
 * - `recipe.json`: the recipe of the last Save, so reopening restores non-destructive editing.
 * - `pending.bin` / `pending-recipe.json`: the fully encoded next version, written **before**
 *   MediaStore is touched. While `state=writing`, recovery can always finish the write.
 *
 * Save as copy records only the recipe, the source URI and both hashes: the source photo stays
 * in MediaStore and is the copy's base.
 */
internal class EditorSaveStore(private val root: File) {
    enum class Kind { IN_PLACE, COPY }
    enum class State { COMMITTED, WRITING, REVERTING }

    data class Entry(
        val kind: Kind,
        val uri: String,
        val state: State,
        /** SHA-256 of `original.bin` (Save) or of the source photo when the copy was made. */
        val originalSha: String,
        /** SHA-256 of what Atlas last wrote to [uri]; empty before the first commit. */
        val savedSha: String,
        /** SHA-256 of `pending.bin` while [state] is [State.WRITING]. */
        val pendingSha: String = "",
        /** Copy only: the photo the copy was made from. */
        val sourceUri: String? = null,
    )

    fun dir(uri: String): File = File(root, sha256(uri.toByteArray(Charsets.UTF_8)))
    fun originalFile(uri: String): File = File(dir(uri), ORIGINAL)
    fun pendingFile(uri: String): File = File(dir(uri), PENDING)

    fun entry(uri: String): Entry? {
        val meta = File(dir(uri), META)
        if (!meta.isFile) return null
        val p = Properties().apply { meta.inputStream().use { load(it) } }
        val stored = p.getProperty("uri") ?: return null
        if (stored != uri) return null // hash collision guard
        return runCatching {
            Entry(
                kind = Kind.valueOf(p.getProperty("kind")),
                uri = stored,
                state = State.valueOf(p.getProperty("state")),
                originalSha = p.getProperty("originalSha"),
                savedSha = p.getProperty("savedSha", ""),
                pendingSha = p.getProperty("pendingSha", ""),
                sourceUri = p.getProperty("sourceUri"),
            )
        }.getOrNull()
    }

    fun entries(): List<Entry> = root.listFiles().orEmpty().mapNotNull { d ->
        val meta = File(d, META)
        if (!meta.isFile) return@mapNotNull null
        val uri = Properties().apply { meta.inputStream().use { load(it) } }.getProperty("uri")
        uri?.let(::entry)
    }

    /** The committed recipe for [uri], or null. */
    fun recipe(uri: String): EditRecipe? {
        val f = File(dir(uri), RECIPE)
        if (!f.isFile) return null
        return runCatching { RecipeJson.decode(f.readText()) }.getOrNull()
    }

    /**
     * Step 1 of Save: keep the original and journal the next version. [current] is what the
     * MediaStore item holds now. When it is exactly what Atlas last saved, the stored original is
     * kept. Otherwise (first Save, or the photo changed outside Atlas since) [current] becomes the
     * original and any older record is discarded, because its recipe no longer describes it.
     */
    fun prepareInPlace(uri: String, current: ByteArray, pending: ByteArray, recipe: EditRecipe): Entry {
        val currentSha = sha256(current)
        val existing = entry(uri)
        val keepOriginal = existing != null && existing.kind == Kind.IN_PLACE &&
            existing.state == State.COMMITTED && existing.savedSha == currentSha &&
            originalFile(uri).isFile && sha256(originalFile(uri).readBytes()) == existing.originalSha
        if (!keepOriginal) {
            clear(uri)
            dir(uri).mkdirs()
            atomicWrite(originalFile(uri), current)
        }
        val base = if (keepOriginal) existing!! else
            Entry(Kind.IN_PLACE, uri, State.COMMITTED, originalSha = currentSha, savedSha = "")
        atomicWrite(pendingFile(uri), pending)
        atomicWrite(File(dir(uri), PENDING_RECIPE), RecipeJson.encode(recipe).toByteArray(Charsets.UTF_8))
        val writing = base.copy(state = State.WRITING, pendingSha = sha256(pending))
        writeMeta(writing)
        return writing
    }

    /** Step 3 of Save, after MediaStore holds exactly `pending.bin`. */
    fun commitInPlace(uri: String) {
        val e = checkNotNull(entry(uri)) { "No save in progress" }
        check(e.state == State.WRITING)
        val d = dir(uri)
        Files.move(File(d, PENDING_RECIPE).toPath(), File(d, RECIPE).toPath(),
            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        writeMeta(e.copy(state = State.COMMITTED, savedSha = e.pendingSha, pendingSha = ""))
        pendingFile(uri).delete()
    }

    /** The Save never reached MediaStore: return to the previous committed state (or nothing). */
    fun abortPending(uri: String) {
        val e = entry(uri) ?: return
        if (e.state != State.WRITING) return
        if (e.savedSha.isEmpty()) { clear(uri); return }
        writeMeta(e.copy(state = State.COMMITTED, pendingSha = ""))
        pendingFile(uri).delete()
        File(dir(uri), PENDING_RECIPE).delete()
    }

    fun markReverting(uri: String) {
        val e = checkNotNull(entry(uri))
        check(e.kind == Kind.IN_PLACE)
        writeMeta(e.copy(state = State.REVERTING))
    }

    fun recordCopy(uri: String, sourceUri: String, sourceSha: String, savedSha: String, recipe: EditRecipe) {
        clear(uri)
        dir(uri).mkdirs()
        atomicWrite(File(dir(uri), RECIPE), RecipeJson.encode(recipe).toByteArray(Charsets.UTF_8))
        writeMeta(Entry(Kind.COPY, uri, State.COMMITTED, originalSha = sourceSha, savedSha = savedSha,
            sourceUri = sourceUri))
    }

    fun clear(uri: String) {
        dir(uri).deleteRecursively()
    }

    private fun writeMeta(e: Entry) {
        val p = Properties()
        p.setProperty("kind", e.kind.name)
        p.setProperty("uri", e.uri)
        p.setProperty("state", e.state.name)
        p.setProperty("originalSha", e.originalSha)
        p.setProperty("savedSha", e.savedSha)
        p.setProperty("pendingSha", e.pendingSha)
        e.sourceUri?.let { p.setProperty("sourceUri", it) }
        val out = java.io.ByteArrayOutputStream()
        p.store(out, null)
        atomicWrite(File(dir(e.uri), META), out.toByteArray())
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.outputStream().use { out -> out.write(bytes); out.fd.sync() }
        Files.move(tmp.toPath(), target.toPath(),
            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        private const val META = "meta.properties"
        private const val ORIGINAL = "original.bin"
        private const val PENDING = "pending.bin"
        private const val RECIPE = "recipe.json"
        private const val PENDING_RECIPE = "pending-recipe.json"

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
