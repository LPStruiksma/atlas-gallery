package nl.atlas.gallery

import java.text.Collator
import java.util.Locale

/** One sort contract shared by every media overview. */
enum class MediaSortKey { DATE_CREATED, DATE_MODIFIED, FILE_NAME, SIZE, RANDOM }
enum class SortDirection { ASCENDING, DESCENDING }

data class MediaSort(val key: MediaSortKey, val direction: SortDirection) {
    companion object {
        val default = MediaSort(MediaSortKey.DATE_CREATED, SortDirection.DESCENDING)
        val standardOptions: List<MediaSortKey> = MediaSortKey.entries
    }

    val usesDateHeaders: Boolean
        get() = key == MediaSortKey.DATE_CREATED || key == MediaSortKey.DATE_MODIFIED
}

/** Natural numeric-aware locale collation. Per-sort collator is not shared across threads. */
private fun naturalNameCompare(a: String, b: String, collator: Collator): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val aDigit = a[i] in '0'..'9'
        val bDigit = b[j] in '0'..'9'
        if (aDigit && bDigit) {
            val aEnd = a.indexOfFirstDigitEnd(i)
            val bEnd = b.indexOfFirstDigitEnd(j)
            var aSignificant = i
            var bSignificant = j
            while (aSignificant < aEnd && a[aSignificant] == '0') aSignificant++
            while (bSignificant < bEnd && b[bSignificant] == '0') bSignificant++
            val lenA = aEnd - aSignificant
            val lenB = bEnd - bSignificant
            if (lenA != lenB) return lenA.compareTo(lenB)
            for (offset in 0 until lenA) {
                val cmp = a[aSignificant + offset].compareTo(b[bSignificant + offset])
                if (cmp != 0) return cmp
            }
            i = aEnd
            j = bEnd
        } else {
            val aStart = i
            val bStart = j
            while (i < a.length && a[i] !in '0'..'9') i++
            while (j < b.length && b[j] !in '0'..'9') j++
            // A digit vs a text segment is compared by localized collation.
            if (aStart == i || bStart == j) {
                val cmp = collator.compare(a[aStart].toString(), b[bStart].toString())
                if (cmp != 0) return cmp
                i = aStart + 1
                j = bStart + 1
            } else {
                val cmp = collator.compare(a.substring(aStart, i), b.substring(bStart, j))
                if (cmp != 0) return cmp
            }
        }
    }
    return (a.length - i).compareTo(b.length - j)
}

private fun String.indexOfFirstDigitEnd(from: Int): Int {
    var index = from
    while (index < length && this[index] in '0'..'9') index++
    return index
}

/** Stable comparator: direction flips the requested key, never the ID tie-break. */
fun mediaComparator(sort: MediaSort, seed: Long, locale: Locale = Locale.getDefault()): Comparator<MediaRecord> {
    val collator = if (sort.key == MediaSortKey.FILE_NAME)
        Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
    else null
    val direction = if (sort.direction == SortDirection.ASCENDING) 1 else -1
    return Comparator { a, b ->
        val primary = when (sort.key) {
            MediaSortKey.DATE_CREATED -> a.takenAtMillis.compareTo(b.takenAtMillis)
            MediaSortKey.DATE_MODIFIED -> a.modifiedAtSeconds.compareTo(b.modifiedAtSeconds)
            MediaSortKey.FILE_NAME -> naturalNameCompare(a.name, b.name, checkNotNull(collator))
            MediaSortKey.SIZE -> a.sizeBytes.compareTo(b.sizeBytes)
            MediaSortKey.RANDOM -> shuffleSortKey(a.randomShuffleDiscriminator(), seed)
                .compareTo(shuffleSortKey(b.randomShuffleDiscriminator(), seed))
        }
        if (primary != 0) primary.sign * direction
        else compareValuesBy(a, b, { it.id }, { it.uri })
    }
}

private val Int.sign: Int get() = when {
    this < 0 -> -1
    this > 0 -> 1
    else -> 0
}

/** Cancellation is checked outside and during TimSort, including large catalogues. */
fun sortMedia(
    items: List<MediaRecord>,
    sort: MediaSort,
    seed: Long,
    locale: Locale = Locale.getDefault(),
    cancellationCheckpoint: (() -> Unit)? = null
): List<MediaRecord> {
    cancellationCheckpoint?.invoke()
    val comparator = mediaComparator(sort, seed, locale)
    var comparisons = 0
    val guarded = Comparator<MediaRecord> { left, right ->
        if ((++comparisons and 1023) == 0) cancellationCheckpoint?.invoke()
        comparator.compare(left, right)
    }
    return items.sortedWith(guarded).also { cancellationCheckpoint?.invoke() }
}

/** Same avalanche permutation as the established random sort, without allocations. */
private fun shuffleSortKey(id: Long, seed: Long): Long {
    var value = id xor seed
    value = (value xor (value ushr 33)) * -49064778989728563L
    value = (value xor (value ushr 33)) * -4265267296055464877L
    return value xor (value ushr 33)
}
