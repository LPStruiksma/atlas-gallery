package nl.atlas.gallery

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * #505: the last published MediaStore catalogue, kept in app-private
 * no-backup storage so a cold start opens straight into the grid while the
 * background scan refreshes it. Never leaves the device (no-backup dir, not
 * in Android backup) and is deleted when media access is fully revoked.
 *
 * Compact binary: folder, volume, provider-version and URI-prefix strings are
 * interned once, so a 250k-item library is a few MB, not tens of MB.
 */
internal object GalleryInventoryCacheCodec {
    private const val MAGIC = 0x41544c43 // "ATLC"
    private const val VERSION = 2
    // Upper bound well above any phone library; rejects a corrupt count early.
    private const val MAX_ITEMS = 5_000_000

    data class Snapshot(val grant: GalleryMediaGrant, val items: List<MediaRecord>)

    fun encode(out: OutputStream, grant: GalleryMediaGrant, items: List<MediaRecord>,
               checkActive: () -> Unit = {}) {
        val data = DataOutputStream(BufferedOutputStream(out, 1 shl 16))
        val table = LinkedHashMap<String, Int>()
        fun intern(value: String): Int = table.getOrPut(value) { table.size }
        fun uriPrefixOf(r: MediaRecord): String? {
            val idSuffix = "/" + r.id
            return if (r.uri.endsWith(idSuffix)) r.uri.dropLast(idSuffix.length) else null
        }
        // Pass 1 builds the shared string table so pass 2 can stream rows.
        for ((i, r) in items.withIndex()) {
            if ((i and 4095) == 0) checkActive()
            uriPrefixOf(r)?.let { intern(it) }
            intern(r.relativePath)
            intern(r.volumeName)
            r.providerVersion?.let { intern(it) }
        }
        data.writeInt(MAGIC)
        data.writeInt(VERSION)
        data.writeByte((if (grant.images) 1 else 0) or
            (if (grant.videos) 2 else 0) or (if (grant.selected) 4 else 0))
        data.writeInt(table.size)
        for (key in table.keys) data.writeUTF(key)
        data.writeInt(items.size)
        for ((i, r) in items.withIndex()) {
            if ((i and 4095) == 0) checkActive()
            val uriPrefix = uriPrefixOf(r)
            data.writeLong(r.id)
            if (uriPrefix != null) {
                data.writeInt(table.getValue(uriPrefix))
            } else {
                data.writeInt(-1)
                data.writeUTF(r.uri)
            }
            data.writeUTF(r.name)
            data.writeInt(table.getValue(r.relativePath))
            data.writeLong(r.sizeBytes)
            data.writeLong(r.takenAtMillis)
            data.writeLong(r.durationMillis)
            data.writeLong(r.modifiedAtSeconds)
            data.writeInt(table.getValue(r.volumeName))
            data.writeInt(r.providerVersion?.let { table.getValue(it) } ?: -1)
            data.writeLong(r.generationModified)
            data.writeInt(r.widthPixels)
            data.writeInt(r.heightPixels)
            data.writeInt(r.orientationDegrees)
            data.writeByte((if (r.isVideo) 1 else 0) or
                (if (r.isFavorite) 2 else 0) or (if (r.favoriteKnown) 4 else 0))
            data.writeByte(r.dateSource.ordinal)
        }
        data.flush()
    }

    /** Returns null for any unknown, truncated or corrupt file. */
    fun decode(input: InputStream, checkActive: () -> Unit = {}): Snapshot? = try {
        val data = DataInputStream(BufferedInputStream(input, 1 shl 16))
        if (data.readInt() != MAGIC || data.readInt() != VERSION) null else {
            val bits = data.readUnsignedByte()
            val grant = GalleryMediaGrant(bits and 1 != 0, bits and 2 != 0, bits and 4 != 0)
            val tableSize = data.readInt()
            require(tableSize in 0..MAX_ITEMS * 4)
            val table = Array(tableSize) { data.readUTF() }
            val count = data.readInt()
            require(count in 0..MAX_ITEMS)
            val sources = MediaDateSource.values()
            val items = ArrayList<MediaRecord>(count)
            for (i in 0 until count) {
                if ((i and 4095) == 0) checkActive()
                val id = data.readLong()
                val prefix = data.readInt()
                val uri = if (prefix >= 0) table[prefix] + "/" + id else data.readUTF()
                val name = data.readUTF()
                val folder = table[data.readInt()]
                val size = data.readLong()
                val taken = data.readLong()
                val duration = data.readLong()
                val modified = data.readLong()
                val volume = table[data.readInt()]
                val version = data.readInt().let { if (it >= 0) table[it] else null }
                val generation = data.readLong()
                val width = data.readInt()
                val height = data.readInt()
                val orientation = data.readInt()
                val flags = data.readUnsignedByte()
                val source = sources.getOrElse(data.readUnsignedByte()) { MediaDateSource.UNKNOWN }
                items.add(MediaRecord(
                    id = id, uri = uri, name = name, relativePath = folder,
                    sizeBytes = size, takenAtMillis = taken,
                    isVideo = flags and 1 != 0, durationMillis = duration,
                    modifiedAtSeconds = modified, volumeName = volume,
                    providerVersion = version, generationModified = generation,
                    widthPixels = width, heightPixels = height,
                    orientationDegrees = orientation,
                    isFavorite = flags and 2 != 0, favoriteKnown = flags and 4 != 0,
                    dateSource = source))
            }
            if (data.read() != -1) null else Snapshot(grant, items)
        }
    } catch (cancelled: java.util.concurrent.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

/**
 * File wrapper: atomic replacement and serialized read/write/revoke IO.
 *
 * A revoked flag is set before acquiring the file lock, so an in-flight
 * encoder cannot publish after access loss. The lock protects both the
 * final rename and deletion (including the temporary file).
 */
internal class GalleryInventoryCacheFile(private val file: File) {
    private val fileLock = Any()
    private val revoked = AtomicBoolean(false)

    fun read(checkActive: () -> Unit = {}): GalleryInventoryCacheCodec.Snapshot? =
        synchronized(fileLock) {
            if (revoked.get() || !file.isFile) null
            else try {
                file.inputStream().use { GalleryInventoryCacheCodec.decode(it, checkActive) }
            } catch (cancelled: java.util.concurrent.CancellationException) {
                throw cancelled
            } catch (_: Exception) { null }
        }

    fun write(grant: GalleryMediaGrant, items: List<MediaRecord>, checkActive: () -> Unit = {}) {
        synchronized(fileLock) {
            if (revoked.get()) return
            val tmp = File(file.parentFile, file.name + ".tmp")
            try {
                tmp.outputStream().use {
                    GalleryInventoryCacheCodec.encode(it, grant, items, checkActive)
                }
                // Revocation can arrive during encode, even when cancellation
                // hasn't reached the writer. Never publish that late snapshot.
                checkActive()
                if (revoked.get()) {
                    tmp.delete()
                    return
                }
                if (!tmp.renameTo(file)) tmp.delete()
            } catch (e: Exception) {
                tmp.delete()
                if (e is java.util.concurrent.CancellationException) throw e
            }
        }
    }

    /**
     * Invalidate synchronously, before the caller queues deletion on IO.
     * Once this returns, any writer still encoding will skip the rename.
     */
    fun invalidate() {
        revoked.set(true)
    }

    internal fun isInvalidatedForTest(): Boolean = revoked.get()

    fun revoke() {
        invalidate()
        delete()
    }

    /** Regranted access may write fresh cache bytes after the old generation drains. */
    fun restore() {
        synchronized(fileLock) {
            // Clear any surviving old-grant bytes before allowing new writes.
            file.delete()
            File(file.parentFile, file.name + ".tmp").delete()
            revoked.set(false)
        }
    }

    /**
     * Deferred cleanup from a revoke must not erase a newer grant's cache.
     * The check and deletion share the write/restore monitor.
     */
    fun deleteIfRevoked() {
        synchronized(fileLock) {
            if (revoked.get()) delete()
        }
    }

    fun delete() {
        synchronized(fileLock) {
            file.delete()
            File(file.parentFile, file.name + ".tmp").delete()
        }
    }
}

/**
 * The cached catalogue may only fill the very first, still-empty publication:
 * never over a real scan result, never after revocation, never without a live
 * grant, and only when it was written under the same kind of access (a
 * selected-photos cache is not shown after the user switched to full access,
 * or the other way round; the scan fills that case in seconds).
 */
internal fun mayShowCachedGalleryInventory(
    current: GalleryPublishedInventory,
    liveGrant: GalleryMediaGrant,
    accessRevoked: Boolean,
    cachedGrant: GalleryMediaGrant
): Boolean = !accessRevoked && liveGrant.hasAny && current.observedGrant == null &&
    current.items.isEmpty() && cachedGrant.hasAny &&
    cachedGrant.selectionScoped == liveGrant.selectionScoped
