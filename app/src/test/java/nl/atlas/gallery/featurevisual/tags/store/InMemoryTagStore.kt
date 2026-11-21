package nl.atlas.gallery.featurevisual.tags.store

import java.util.TreeMap

/** JVM fake of [VisualTagStore] with the same contract (ASCII keys, so String order = SQLite BINARY). */
class InMemoryTagStore : VisualTagStorePort {
    private val order = compareBy<TagRowKey> { it.mediaKey }.thenBy { it.frameMs }
    val rows = TreeMap<TagRowKey, TaggedRow>(order)
    var fence: String? = null
    var state = TagSweepState(null, null)
    var wipes = 0

    private fun inRange(k: TagRowKey, after: TagRowKey?, last: TagRowKey?) =
        (after == null || order.compare(k, after) > 0) && (last == null || order.compare(k, last) <= 0)

    override fun useFence(fence: String): Boolean {
        if (this.fence == fence) return false
        val had = this.fence != null
        rows.clear(); state = TagSweepState(null, null); this.fence = fence
        if (had) wipes++
        return had
    }

    override fun sweepState() = state

    override fun revisionsIn(after: TagRowKey?, last: TagRowKey) =
        rows.filterKeys { inRange(it, after, last) }.mapValues { it.value.revision }

    override fun applySweepPage(after: TagRowKey?, last: TagRowKey, present: Set<TagRowKey>, rows: List<TaggedRow>) {
        this.rows.keys.filter { inRange(it, after, last) && it !in present }.forEach { this.rows.remove(it) }
        rows.forEach { this.rows[it.key] = it }
        state = TagSweepState(last, null)
    }

    override fun rowsAfter(after: TagRowKey?) = rows.keys.count { inRange(it, after, null) }

    override fun finishSweep(after: TagRowKey?, nowMs: Long) {
        rows.keys.filter { inRange(it, after, null) }.forEach { rows.remove(it) }
        state = TagSweepState(null, nowMs)
    }

    override fun restartSweep() { state = TagSweepState(null, null) }

    override fun putRows(rows: List<TaggedRow>, replaceMedia: Set<String>) {
        this.rows.keys.filter { it.mediaKey in replaceMedia }.forEach { this.rows.remove(it) }
        rows.forEach { this.rows[it.key] = it }
    }

    override fun tagsFor(mediaKey: String, revision: String?): List<StoredTag> =
        rows.values.filter { it.key.mediaKey == mediaKey && (revision == null || it.revision == revision) }
            .flatMap { it.hits }.groupBy { it.id }
            .map { (id, hits) -> StoredTag(id, hits.maxOf { it.margin }) }
            .sortedWith(compareByDescending<StoredTag> { it.margin }.thenBy { it.id })

    override fun mediaForTag(tagId: String, scope: TagScope, limit: Int): List<String> =
        rows.values.flatMap { r -> r.hits.filter { it.id == tagId }.map { r.key.mediaKey to it.margin } }
            .groupBy({ it.first }, { it.second }).mapValues { it.value.max() }.entries
            .sortedWith(compareByDescending<Map.Entry<String, Float>> { it.value }.thenBy { it.key })
            .map { it.key }
            .filter { scope !is TagScope.Media || it in scope.keys }
            .take(limit)

    override fun counts() = TagStoreCounts(rows.size, rows.values.sumOf { it.hits.size },
        rows.values.filter { it.hits.isNotEmpty() }.map { it.key.mediaKey }.toSet().size)

    override fun deleteAll() {
        rows.clear(); state = TagSweepState(null, null); fence = null
    }
}
