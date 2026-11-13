package nl.atlas.gallery.featurealbums

import nl.atlas.gallery.MediaRecord

/**
 * Samsung's hidden albums (parity research `GRID:A7`, #568): a per-album flag kept in
 * ATLAS's own private preferences. A hidden album leaves the Albums grid and
 * its items leave Photos; nothing on the device changes, so unhiding brings
 * everything back. People, Search and the album itself are not filtered.
 */
object HiddenAlbums {
    private const val FIELD = '\u0001'

    fun encode(hidden: Set<AlbumBucketKey>): String =
        hidden.sortedWith(compareBy({ it.volumeName }, { it.relativePath }))
            .joinToString("\n") { it.volumeName + FIELD + it.relativePath }

    /** Malformed lines are dropped, never guessed into a different folder. */
    fun decode(stored: String?): Set<AlbumBucketKey> {
        if (stored.isNullOrEmpty()) return emptySet()
        return stored.split('\n').mapNotNullTo(LinkedHashSet()) { line ->
            val at = line.indexOf(FIELD)
            if (at <= 0 || at == line.lastIndex || line.indexOf(FIELD, at + 1) >= 0) null
            else AlbumBucketKey(line.substring(0, at), line.substring(at + 1))
        }
    }

    fun toggled(hidden: Set<AlbumBucketKey>, album: AlbumBucketKey): Set<AlbumBucketKey> =
        if (album in hidden) hidden - album else hidden + album

    /** Albums grid without hidden albums; the same list when nothing is hidden. */
    fun visibleAlbums(albums: List<AlbumSummary>, hidden: Set<AlbumBucketKey>): List<AlbumSummary> =
        if (hidden.isEmpty()) albums
        else albums.filter { AlbumBucketKey(it.volumeName, it.relativePath) !in hidden }

    /**
     * Photos without items from hidden albums, keeping order. Returns [media]
     * itself when nothing is hidden or nothing matched, so identity-keyed
     * consumers (selection, sort, viewer) don't restart for no change.
     */
    fun filterPhotos(
        media: List<MediaRecord>,
        hidden: Set<AlbumBucketKey>,
        checkpoint: () -> Unit = {}
    ): List<MediaRecord> {
        if (hidden.isEmpty() || media.isEmpty()) return media
        // Volume → folders, so the hot loop never allocates a key per item.
        val byVolume = HashMap<String, HashSet<String>>()
        hidden.forEach { byVolume.getOrPut(it.volumeName) { HashSet() }.add(it.relativePath) }
        var out: ArrayList<MediaRecord>? = null
        media.forEachIndexed { index, record ->
            if ((index and 1023) == 0) checkpoint()
            val drop = byVolume[record.volumeName]?.contains(record.relativePath) == true
            if (drop && out == null) {
                out = ArrayList<MediaRecord>(media.size).also { it.addAll(media.subList(0, index)) }
            } else if (!drop) {
                out?.add(record)
            }
        }
        return out ?: media
    }
}
