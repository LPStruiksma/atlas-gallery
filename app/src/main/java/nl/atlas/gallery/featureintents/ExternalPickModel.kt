package nl.atlas.gallery.featureintents

/**
 * BENCH-2 (#573) A2b: the picker's choice rules and copy, pure Kotlin so every
 * rule is a JVM test. The grid and the Activity result live elsewhere.
 */
internal object PickSelection {
    /**
     * A tap on [uri]. Single pick: the tapped item becomes the one choice, or
     * the choice clears when it was already chosen. Multiple: the item is added
     * at the end or removed, keeping pick order (it becomes the result order).
     */
    fun toggle(chosen: List<String>, uri: String, allowMultiple: Boolean): List<String> = when {
        uri in chosen -> chosen - uri
        allowMultiple -> chosen + uri
        else -> listOf(uri)
    }

    /**
     * A long-press drag reports the grid's whole new set. Items already chosen
     * keep their order; newly added ones follow in [offered] (grid) order.
     * Single pick ignores drags: one tap is the whole choice there.
     */
    fun replaced(chosen: List<String>, newSet: Set<String>, offered: List<String>, allowMultiple: Boolean): List<String> {
        if (!allowMultiple) return chosen
        val kept = chosen.filter { it in newSet }
        val keptSet = kept.toHashSet()
        return kept + offered.filter { it in newSet && it !in keptSet }
    }

    /** Drops items the catalogue no longer offers (deleted, grant narrowed, filter). */
    fun stillOffered(chosen: List<String>, offered: Set<String>): List<String> = chosen.filter { it in offered }
}

internal object PickCopy {
    private enum class Noun(val one: String, val many: String) {
        PHOTO("a photo", "photos"), VIDEO("a video", "videos"), ITEM("an item", "items")
    }

    private fun noun(filter: PickMediaFilter): Noun = when {
        filter.allowsImages && !filter.allowsVideos -> Noun.PHOTO
        filter.allowsVideos && !filter.allowsImages -> Noun.VIDEO
        else -> Noun.ITEM
    }

    /** "Choose a photo", "Choose videos", "Choose items", … */
    fun title(request: ExternalEntry.Pick): String =
        noun(request.filter).let { "Choose ${if (request.allowMultiple) it.many else it.one}" }

    /** The empty grid: "No matching photos" / "No matching videos" / "No matching items". */
    fun empty(filter: PickMediaFilter): String = "No matching ${noun(filter).many}"

    /** "Done", then "Done (2)" once something is chosen in a multiple pick. */
    fun done(request: ExternalEntry.Pick, count: Int): String =
        if (request.allowMultiple && count > 0) "Done ($count)" else "Done"

    /** Announced and shown when a chosen item fails the final provider check. */
    const val SOME_UNAVAILABLE = "Some items can't be used. Choose again."
}
