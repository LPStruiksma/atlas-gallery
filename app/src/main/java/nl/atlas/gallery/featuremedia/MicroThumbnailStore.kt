package nl.atlas.gallery.featuremedia

import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/**
 * #555: ATLAS's own disk cache of micro thumbnails (Samsung T2, parity research option a).
 *
 * - One small JPEG per photo revision. The file name is a hash of the
 *   revision key ([microThumbnailDiskKey]), so an edited, rotated or
 *   re-indexed photo gets a new file automatically; there is no index (the
 *   file existing is the hit), like Samsung's own cache.
 * - 256 subfolders keep directories small for a 250k-photo library.
 * - Writes go to a temp file and are renamed, so a reader never sees half a
 *   thumbnail.
 * - A size budget: [bytesUsed] tracks the folder size once [scan] has run.
 *   What to delete at the budget is the warmer's call, by capture age
 *   ([fillMicroStore]); [retainOnly] clears copies of photos that are gone.
 *
 * The folder is app-private (no other app can read it). Keys are not
 * permission: callers only ask for photos in the current authorized
 * inventory, and the whole folder is deleted when full access ends
 * ([MicroThumbnails.applyGrant]).
 */
internal class MicroThumbnailStore(val root: File, val budgetBytes: Long) {
    private val used = AtomicLong(-1L)

    /** Folder size in bytes, or -1 until [scan] has run. */
    val bytesUsed: Long get() = used.get()

    fun fileFor(diskKey: String): File {
        val hash = sha1Hex(diskKey)
        return File(File(root, hash.substring(0, 2)), hash.substring(2) + ".jpg")
    }

    fun contains(diskKey: String): Boolean = fileFor(diskKey).isFile

    /** The stored bytes, or null. */
    fun read(diskKey: String): ByteArray? = try {
        fileFor(diskKey).readBytes()
    } catch (e: FileNotFoundException) {
        null
    }

    fun write(diskKey: String, bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val target = fileFor(diskKey)
        val folder = target.parentFile ?: return false
        if (!folder.isDirectory && !folder.mkdirs() && !folder.isDirectory) return false
        val temp = File(folder, "${target.name}.${Thread.currentThread().id}.tmp")
        return try {
            temp.writeBytes(bytes)
            val previous = if (target.isFile) target.length() else 0L
            if (!temp.renameTo(target)) {
                temp.delete()
                false
            } else {
                used.getAndUpdate { if (it < 0L) it else it + bytes.size - previous }
                true
            }
        } catch (e: java.io.IOException) {
            temp.delete()
            false
        }
    }

    /** Drops one entry, e.g. a file that no longer decodes. */
    fun remove(diskKey: String) {
        val file = fileFor(diskKey)
        val length = if (file.isFile) file.length() else 0L
        if (file.delete()) used.getAndUpdate { if (it < 0L) it else (it - length).coerceAtLeast(0L) }
    }

    /** True once the folder is known to be at or over the budget. */
    fun full(): Boolean {
        val bytes = used.get()
        return bytes >= 0L && bytes >= budgetBytes
    }

    /** Walks the folder once to learn its size (background thread). */
    fun scan(): Long {
        var total = 0L
        root.listFiles()?.forEach { folder ->
            folder.listFiles()?.forEach { if (it.isFile && it.name.endsWith(".jpg")) total += it.length() }
        }
        used.set(total)
        return total
    }

    /**
     * Deletes every stored file that belongs to none of [diskKeys], e.g. the
     * copies of deleted photos or of old revisions. Returns the number deleted.
     */
    fun retainOnly(diskKeys: Iterable<String>): Int {
        val keep = diskKeys.mapTo(HashSet()) { fileFor(it).path }
        var total = 0L
        var deleted = 0
        root.listFiles()?.forEach { folder ->
            folder.listFiles()?.forEach {
                if (!it.isFile) return@forEach
                val length = it.length()
                if (it.path !in keep && it.delete()) deleted++ else total += length
            }
        }
        used.set(total)
        return deleted
    }

    fun clear() {
        root.deleteRecursively()
        used.set(0L)
    }

    private companion object {
        fun sha1Hex(text: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
            val out = CharArray(digest.size * 2)
            val hex = "0123456789abcdef"
            digest.forEachIndexed { i, b ->
                out[i * 2] = hex[(b.toInt() ushr 4) and 0xF]
                out[i * 2 + 1] = hex[b.toInt() and 0xF]
            }
            return String(out)
        }
    }
}
