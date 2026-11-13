package nl.atlas.gallery.featureorganization

import nl.atlas.gallery.MediaRecord

/** Android's share sheet and binder transactions stay bounded; larger shares are refused. */
const val MAX_BATCH_SHARE_ITEMS = 500

/** Matches the fail-closed MediaStore preflight bound used before every Trash request. */
const val MAX_BATCH_TRASH_ITEMS = 200

/**
 * Host-owned multi-selection, bound to ONE immutable gallery list reference and
 * ONE permission publication epoch. It lives only in memory (never saved state,
 * never a file) and holds only the selected media keys. A new list, grant or
 * epoch makes it stale, and the bar then fails closed ("select again").
 */
class GalleryBatchSelection private constructor(
    val source: List<MediaRecord>,
    val epoch: Long,
    val uris: Set<String>
) {
    val count: Int get() = uris.size

    fun contains(media: MediaRecord): Boolean = media.uri in uris

    /** O(1): reference identity and epoch, never a 150k structural equals. */
    fun isCurrentFor(current: List<MediaRecord>, currentEpoch: Long, accessOk: Boolean): Boolean =
        accessOk && source === current && epoch == currentEpoch

    /** Removing the last item ends selection mode (null). */
    fun toggled(media: MediaRecord): GalleryBatchSelection? {
        if (media.uri.isBlank()) return this
        val next = if (media.uri in uris) uris - media.uri else uris + media.uri
        return if (next.isEmpty()) null else GalleryBatchSelection(source, epoch, next)
    }

    fun uiState(
        current: List<MediaRecord>,
        currentEpoch: Long,
        accessOk: Boolean,
        operating: Boolean
    ): BatchSelectionUiState = BatchSelectionUiState(
        selectedCount = uris.size,
        eligibleCount = current.size,
        sourceCurrent = isCurrentFor(current, currentEpoch, accessOk),
        isOperating = operating,
        mode = BatchSelectionMode.LIBRARY
    )

    /**
     * Samsung Photos selection (#556): a drag, a date header or "All" replaces
     * the whole set. Unlike [toggled], an empty set keeps selection mode open
     * ("Select items"); only Back or the scope changing ends it.
     */
    fun replaced(next: Set<String>): GalleryBatchSelection =
        if (next == uris) this else GalleryBatchSelection(source, epoch, next.filterTo(HashSet()) { it.isNotBlank() })

    /**
     * Samsung GRID:S4: after a library reload of the same view, keep only the
     * URIs still present in the newly published [current] list (one pass,
     * worker side). An empty result keeps selection mode open.
     */
    fun rebasedOnto(
        current: List<MediaRecord>,
        currentEpoch: Long,
        cancellationCheckpoint: () -> Unit = {}
    ): GalleryBatchSelection {
        val kept = HashSet<String>(uris.size * 2)
        current.forEachIndexed { index, media ->
            if ((index and 511) == 0) cancellationCheckpoint()
            if (media.uri in uris) kept.add(media.uri)
        }
        return GalleryBatchSelection(current, currentEpoch, kept)
    }

    /** Tap in Photos selection mode: toggles one item and never leaves the mode. */
    fun toggledKeepingMode(media: MediaRecord): GalleryBatchSelection {
        if (media.uri.isBlank()) return this
        return GalleryBatchSelection(source, epoch,
            if (media.uri in uris) uris - media.uri else uris + media.uri)
    }

    companion object {
        /** Starts from a full set (a long-press drag). Empty or blank-only sets do not start. */
        fun of(source: List<MediaRecord>, epoch: Long, selected: Set<String>): GalleryBatchSelection? {
            val clean = selected.filterTo(HashSet()) { it.isNotBlank() }
            return if (source.isEmpty() || clean.isEmpty() || epoch < 0L) null
            else GalleryBatchSelection(source, epoch, clean)
        }

        /** A long press starts selection with exactly that item. */
        fun start(source: List<MediaRecord>, epoch: Long, first: MediaRecord): GalleryBatchSelection? =
            if (source.isEmpty() || first.uri.isBlank() || epoch < 0L) null
            else GalleryBatchSelection(source, epoch, setOf(first.uri))
    }
}

/**
 * Worker-side resolution right before an operation: one pass over the current
 * list, records returned in gallery order. Fails closed (null) when the list is
 * not the selection's own source or any selected item is no longer in it.
 */
fun resolveBatchSelection(
    selection: GalleryBatchSelection,
    current: List<MediaRecord>,
    cancellationCheckpoint: () -> Unit = {}
): List<MediaRecord>? {
    if (selection.source !== current || selection.uris.isEmpty()) return null
    val found = HashSet<String>(selection.uris.size * 2)
    val out = ArrayList<MediaRecord>(selection.uris.size)
    current.forEachIndexed { index, media ->
        if ((index and 511) == 0) cancellationCheckpoint()
        if (media.uri in selection.uris && found.add(media.uri)) out.add(media)
    }
    return if (found.size == selection.uris.size) out else null
}
