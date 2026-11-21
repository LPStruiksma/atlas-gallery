package nl.atlas.gallery

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException

/**
 * A cached gallery inventory is just a presentation optimization. Only a
 * fresh real scan/delta published under the current grant can admit dHashes.
 * Selection-scoped access cannot be proven by permission bits (a re-pick may
 * leave them unchanged), so that scope is permanently memory-only.
 */
internal fun photoDnaPersistentPublicationAllowed(
    grant: GalleryMediaGrant, published: GalleryPublishedInventory
): Boolean = PhotoDnaVisualCacheStore.diskAllowed(grant) &&
    !published.fromCache && published.scanBoundGrant == grant

/**
 * #604: a best-effort, on-device dHash cache, never a source of authorization.
 *
 * Call only from IO while Photo DNA is open, with a newly scanned/published
 * MediaStore inventory. No ordinary app start restores hashes. The file is
 * always under the caller's noBackupFilesDir. No URI, thumbnail, failed decode,
 * pair, or raw pixel is written. Revisions are revalidated against fresh rows.
 *
 * Binary v1: magic/version/grant/count/volume dictionary, then 26 bytes per
 * successful image (dictionary index, id, revision digest, dHash, flat flag).
 * The dictionary also binds MediaStore provider versions, preventing ID reuse
 * after a provider reset from reviving an unrelated hash.
 */
internal class PhotoDnaVisualCacheStore(private val file: File) {
    companion object {
        private const val MAGIC = 0x444E4138 // DNA8
        private const val VERSION = 1
        private const val MAX_ENTRIES = 250_000
        private const val MAX_VOLUMES = 32
        private const val MAX_BYTES = 8L * 1024 * 1024
        private const val MAX_VOLUME_CHARS = 128
        private const val MAX_VERSION_CHARS = 256
        private const val HASH_INFORMATIVE = 1
        private const val HASH_FLAT = 2

        private fun permissionBits(grant: GalleryMediaGrant): Int =
            (if (grant.images) 1 else 0) or
            (if (grant.videos) 2 else 0) or
            (if (grant.selected) 4 else 0)

        internal fun diskAllowed(grant: GalleryMediaGrant): Boolean =
            grant.images && !grant.selectionScoped
    }

    /** Called on IO when the current grant is observed, without loading hashes. */
    @Synchronized fun purgeUnlessGrant(grant: GalleryMediaGrant) {
        if (!file.exists()) return
        if (!diskAllowed(grant) || file.length() > MAX_BYTES || file.length() < 16L) {
            delete()
            return
        }
        try {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION ||
                    input.readInt() != permissionBits(grant)) delete()
            }
        } catch (_: IOException) {
            delete()
        }
    }

    @Synchronized fun delete() {
        file.delete()
        File(file.parentFile, file.name + ".tmp").delete()
    }

    /** A missing or unversioned provider must not authorize a persistent result. */
    private fun versions(source: List<MediaRecord>): LinkedHashMap<String, String>? {
        val found = LinkedHashMap<String, String>()
        for (record in source) {
            if (record.isVideo) continue
            val volume = record.volumeName
            val version = record.providerVersion ?: return null
            if (volume.isBlank() || volume.length > MAX_VOLUME_CHARS ||
                version.isBlank() || version.length > MAX_VERSION_CHARS) return null
            val old = found.putIfAbsent(volume, version)
            if (old != null && old != version) return null
            if (found.size > MAX_VOLUMES) return null
        }
        return found
    }

    /**
     * Two-phase admission: parse bounded bytes into a temporary primitive
     * cache, then copy only entries matching the fresh source revision. The
     * live cache never sees a corrupt, partial or incompatible file.
     */
    @Synchronized fun restoreInto(
        cache: PhotoDnaVisualCache,
        grant: GalleryMediaGrant,
        source: List<MediaRecord>,
        authorized: () -> Boolean,
        checkpoint: () -> Unit = {}
    ): Int {
        if (!diskAllowed(grant) || !authorized()) return 0
        val liveVersions = versions(source) ?: return 0
        if (!file.isFile) return 0
        if (file.length() !in 16L..MAX_BYTES) {
            delete()
            return 0
        }
        val restored = PhotoDnaVisualCache()
        try {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION ||
                    input.readInt() != permissionBits(grant)) throw IOException("scope")
                val count = input.readInt()
                if (count !in 0..MAX_ENTRIES) throw IOException("count")
                val nVolumes = input.readUnsignedByte()
                if (nVolumes > MAX_VOLUMES) throw IOException("volumes")
                val dictionary = ArrayList<String>(nVolumes)
                val versionsRead = LinkedHashMap<String, String>()
                repeat(nVolumes) {
                    val volume = input.readUTF()
                    val version = input.readUTF()
                    if (volume.isBlank() || volume.length > MAX_VOLUME_CHARS ||
                        version.isBlank() || version.length > MAX_VERSION_CHARS ||
                        versionsRead.putIfAbsent(volume, version) != null) {
                        throw IOException("dictionary")
                    }
                    dictionary.add(volume)
                }
                if (versionsRead != liveVersions) throw IOException("provider")
                repeat(count) { index ->
                    if ((index and 1023) == 0) {
                        checkpoint()
                        if (!authorized()) return 0
                    }
                    val volumeIndex = input.readUnsignedByte()
                    val id = input.readLong()
                    val revision = input.readLong()
                    val hash = input.readLong()
                    val state = input.readUnsignedByte()
                    if (volumeIndex >= dictionary.size || id <= 0L ||
                        state != HASH_INFORMATIVE && state != HASH_FLAT) {
                        throw IOException("entry")
                    }
                    restored.putPersisted(dictionary[volumeIndex], id, revision, hash,
                        state == HASH_INFORMATIVE)
                }
                if (restored.size != count || input.read() != -1)
                    throw IOException("duplicate/trailing")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            delete()
            return 0
        }
        if (!authorized()) return 0
        var hits = 0
        for ((index, record) in source.withIndex()) {
            if ((index and 1023) == 0) {
                checkpoint()
                if (!authorized()) {
                    cache.clear()
                    return 0
                }
            }
            if (record.isVideo) continue
            restored.get(record)?.let {
                cache.put(record, it)
                hits++
            }
        }
        if (!authorized()) {
            cache.clear()
            return 0
        }
        return hits
    }

    /**
     * Count and stream the successful, current image hashes only. A same-dir
     * atomic replacement preserves the previous valid cache on cancellation
     * or IO failure. No failed thumbnails or non-current entries are saved.
     */
    @Synchronized fun save(
        cache: PhotoDnaVisualCache,
        grant: GalleryMediaGrant,
        source: List<MediaRecord>,
        authorized: () -> Boolean,
        checkpoint: () -> Unit = {}
    ): Int {
        if (!diskAllowed(grant) || !authorized()) return 0
        val currentVersions = versions(source) ?: return 0
        val indexByVolume = currentVersions.keys.withIndex().associate { it.value to it.index }
        var count = 0
        for ((index, record) in source.withIndex()) {
            if ((index and 1023) == 0) {
                checkpoint()
                if (!authorized()) return 0
            }
            if (!record.isVideo && cache.get(record) != null) count++
        }
        if (count == 0 || count > MAX_ENTRIES) return 0
        val temp = File(file.parentFile, file.name + ".tmp")
        try {
            file.parentFile?.mkdirs()
            FileOutputStream(temp).use { raw ->
                DataOutputStream(BufferedOutputStream(raw)).use { output ->
                    output.writeInt(MAGIC)
                    output.writeInt(VERSION)
                    output.writeInt(permissionBits(grant))
                    output.writeInt(count)
                    output.writeByte(currentVersions.size)
                    currentVersions.forEach { (volume, version) ->
                        output.writeUTF(volume)
                        output.writeUTF(version)
                    }
                    for ((index, record) in source.withIndex()) {
                        if ((index and 1023) == 0) {
                            checkpoint()
                            if (!authorized()) return 0
                        }
                        if (record.isVideo) continue
                        val hash = cache.get(record) ?: continue
                        output.writeByte(indexByVolume.getValue(record.volumeName))
                        output.writeLong(record.id)
                        output.writeLong(cache.revisionDigest(record))
                        output.writeLong(hash.value)
                        output.writeByte(if (hash.informative) HASH_INFORMATIVE else HASH_FLAT)
                    }
                    output.flush()
                    raw.fd.sync()
                }
            }
            if (!authorized() || temp.length() > MAX_BYTES) return 0
            // Do not replace with an unverified (possibly truncated) stream.
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
            return count
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return 0
        } finally {
            temp.delete()
        }
    }

    internal fun diskBytes(): Long = if (file.isFile) file.length() else 0L
}
