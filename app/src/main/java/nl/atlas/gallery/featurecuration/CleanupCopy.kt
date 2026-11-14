package nl.atlas.gallery.featurecuration

import java.util.Locale
import kotlin.math.ceil

/**
 * #663: plain words for the clean-up screens (Photo DNA and Declutter), in
 * the tone of Samsung Gallery's own clean-up ("2 duplicates", "1 KB"). Pure
 * strings only: no matching, keeper or Trash rule lives here.
 */

/** "1 file", "2 files", "0 files". */
fun countOf(count: Int, one: String, many: String): String =
    "$count ${if (count == 1) one else many}"

private val SIZE_UNITS = arrayOf("KB", "MB", "GB", "TB")

/**
 * A file size the way a phone shows it: decimal units, whole KB (anything
 * under 1 KB shows as "1 KB", never "0 KB" or bytes), one decimal below
 * 100 MB/GB/TB and whole numbers above. Rounding never shows "1000 KB".
 */
fun cleanupSize(bytes: Long, locale: Locale = Locale.getDefault()): String {
    if (bytes <= 0L) return "0 KB"
    val kb = ceil(bytes / 1000.0).toLong()
    if (kb < 1000L) return "$kb KB"
    var value = bytes / 1_000_000.0
    var unit = 1
    while (true) {
        val decimals = if (value < 100.0) 1 else 0
        val text = String.format(locale, "%.${decimals}f", value)
        val rounded = if (decimals == 1) Math.round(value * 10) / 10.0 else Math.round(value).toDouble()
        if (rounded < 1000.0 || unit == SIZE_UNITS.lastIndex) return "$text ${SIZE_UNITS[unit]}"
        value /= 1000.0
        unit++
    }
}

/** Every Photo DNA string a person reads, so tests and screens share them. */
object PhotoDnaCopy {
    const val TITLE = "Photo DNA"
    const val ENTRY_SUBTITLE = "Find duplicate photos. You choose what goes."

    const val NO_ACCESS_STATUS = "No access to your photos"
    const val NO_ACCESS_BODY =
        "Photo DNA needs access to your photos to find duplicates. Nothing was changed."
    const val SEARCHING = "Looking for duplicates…"
    const val RECHECKING = "Checking the selected files again…"
    /** #42: planning stopped on an error; nothing was changed. */
    const val FAILED_STATUS = "Couldn't finish checking duplicates"
    const val FAILED_BODY =
        "Couldn't finish checking duplicates. Nothing was changed. Try again, or close Photo DNA."
    const val RETRY = "Retry"

    /** "Checked 2 files", plus "· 3 more next time" when a pass was cut short. */
    fun checked(files: Int, deferred: Int): String =
        "Checked ${countOf(files, "file", "files")}" +
            if (deferred > 0) " · $deferred more next time" else ""

    /** Section header for exact copies: "Duplicates · 1 group". */
    fun duplicatesHeader(groups: Int): String =
        "Duplicates · " + countOf(groups, "group", "groups")

    /** Group card header: "2 copies · up to 1 KB to free". */
    fun groupHeader(copies: Int, freeableBytes: Long, locale: Locale = Locale.getDefault()): String =
        countOf(copies, "copy", "copies") + " · up to " + cleanupSize(freeableBytes, locale) + " to free"

    const val KEEP = "Keep"
    const val PROTECTED = "Protected"

    /** Why this copy is kept ([CurationExactGroup.keeperReason]). */
    const val KEEPER_USER = "Keeping the copy you chose"
    const val KEEPER_PEOPLE = "Keeping the copy with people you named"
    const val KEEPER_FAVOURITE = "Keeping your favourite"
    const val KEEPER_PROTECTED_FOLDER = "Keeping the copy in a protected folder"
    const val KEEPER_DEFAULT = "Keep"

    const val REVIEW_SELECTED = "Review selected…"

    /** Section header for look-alikes: "Similar photos · 1 pair". */
    fun similarHeader(pairs: Int): String = "Similar photos · " + countOf(pairs, "pair", "pairs")
    const val PAIR_RELATED = "Looks related"
    const val PAIR_REVIEW = "Worth a closer look"
    const val PAIR_UNKNOWN = "Might be related"
    const val COMPARE = "Compare photos"
}
