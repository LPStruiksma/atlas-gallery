package nl.atlas.gallery

/**
 * #33, #41: keeps a MediaStore rescan from holding a second full copy of the
 * library on the Java heap.
 *
 * A scan used to build every record and every string anew while the previous
 * (published or cached) inventory was still on screen, so the heap briefly
 * held the library twice; on a large library that is where the app ran out of
 * memory (OutOfMemoryError in GalleryRepository.readMedia). Most rows do not
 * change between two scans, so a freshly read row that equals the previous
 * record with the same content URI is replaced by that previous object, and
 * the fresh one is garbage at once. The few folder and volume names of a
 * library are shared between rows instead of being stored once per row.
 *
 * Reuse is by full [MediaRecord] equality only: a row whose size, dates,
 * revision or any other field changed is never replaced by its predecessor.
 * Nothing is read from MediaStore here and no access decision is made here;
 * the scan result holds exactly the rows the provider returned, in its order.
 * Used by one scan on one thread.
 */
internal class GalleryScanRecordReuse(previous: List<MediaRecord> = emptyList()) {
    // Keyed by the previous records' own URI strings: no new string per row.
    private val byUri: HashMap<String, MediaRecord>? =
        if (previous.isEmpty()) null
        else HashMap<String, MediaRecord>((previous.size * 4 / 3) + 1).also { map ->
            for (record in previous) map[record.uri] = record
        }
    private val strings = HashMap<String, String>()

    /** Rows of this scan answered with the previous inventory's own object. */
    var reused: Int = 0
        private set

    /** One shared instance per distinct folder or volume name. */
    fun shared(value: String): String = strings.getOrPut(value) { value }

    /** The previous object when [fresh] equals it in every field, else [fresh]. */
    fun canonical(fresh: MediaRecord): MediaRecord {
        val before = byUri?.get(fresh.uri) ?: return fresh
        return if (before == fresh) { reused++; before } else fresh
    }
}

/**
 * The catalogue scan ran out of Java heap. Raised in place of the
 * OutOfMemoryError so the refresh fails like any other unreadable scan (the
 * last published or cached catalogue stays on screen and Refresh retries)
 * instead of ending the process. Carries no provider data.
 */
internal class GalleryScanOutOfMemory : RuntimeException("Gallery scan ran out of memory")

/** Runs one catalogue scan, turning heap exhaustion into [GalleryScanOutOfMemory]. */
internal inline fun <T> failGalleryScanOnHeapExhaustion(scan: () -> T): T =
    try {
        scan()
    } catch (_: OutOfMemoryError) {
        // The partial result list is unreachable from here on and can be collected.
        throw GalleryScanOutOfMemory()
    }
