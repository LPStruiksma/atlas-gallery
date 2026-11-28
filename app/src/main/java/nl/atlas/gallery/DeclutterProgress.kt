package nl.atlas.gallery

import android.content.SharedPreferences
import java.security.MessageDigest

/**
 * TIDY-1: durable, non-destructive review progress for a Declutter source.
 *
 * Only positive "Keep" decisions persist, per source (and month). A staged
 * Trash choice, a Skip, an OS request or a confirmation flag NEVER enters this
 * store, so nothing here can lead to a deletion.
 */

/** Canonical identity of a review source. Versioned so the layout can change. */
fun declutterScopeKey(
    kind: String,
    id: String,
    filter: String = "",
    sort: String = "",
    month: String = "",
    snapshot: String = ""
): String {
    require(kind.isNotBlank() && id.isNotBlank()) { "A review source needs a kind and an id" }
    fun esc(part: String) = part.replace("\\", "\\\\").replace("|", "\\|")
    val fields = if (snapshot.isBlank()) listOf("v1", kind, id, filter, sort, month)
        else listOf("v2", kind, id, filter, sort, month, snapshot)
    return fields.joinToString("|") { esc(it) }
}

/** Fingerprint the exact frozen source membership, order and media revisions. */
fun declutterSourceFingerprint(items: List<MediaRecord>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    items.forEach { item ->
        digest.update(
            ("\u0000" + item.uri + "\u0000" + item.sizeBytes + "\u0000" +
                item.modifiedAtSeconds + "\n").toByteArray(Charsets.UTF_8)
        )
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/**
 * A Keep stays valid only while the file looks the same: a replaced or edited
 * file (other size or modification time) reappears for review.
 */
fun declutterKeepIdentity(media: MediaRecord): String =
    "${media.uri}#${media.sizeBytes}#${media.modifiedAtSeconds}"

interface DeclutterKeepStore {
    fun load(scopeKey: String): Set<String>
    fun add(scopeKey: String, identity: String)
    fun remove(scopeKey: String, identity: String)
    fun clear(scopeKey: String)
}

class InMemoryDeclutterKeepStore : DeclutterKeepStore {
    private val data = HashMap<String, MutableSet<String>>()
    override fun load(scopeKey: String): Set<String> = data[scopeKey]?.toSet() ?: emptySet()
    override fun add(scopeKey: String, identity: String) {
        data.getOrPut(scopeKey) { HashSet() }.add(identity)
    }
    override fun remove(scopeKey: String, identity: String) { data[scopeKey]?.remove(identity) }
    override fun clear(scopeKey: String) { data.remove(scopeKey) }
}

/** Private on-device store; one string set per hashed source key. */
class SharedPreferencesDeclutterKeepStore(
    private val prefs: SharedPreferences
) : DeclutterKeepStore {
    private fun prefKey(scopeKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(scopeKey.toByteArray())
        return "keeps.v1." + digest.take(12).joinToString("") { "%02x".format(it) }
    }
    override fun load(scopeKey: String): Set<String> =
        prefs.getStringSet(prefKey(scopeKey), null)?.toSet() ?: emptySet()
    override fun add(scopeKey: String, identity: String) = update(scopeKey) { it.add(identity) }
    override fun remove(scopeKey: String, identity: String) = update(scopeKey) { it.remove(identity) }
    override fun clear(scopeKey: String) { prefs.edit().remove(prefKey(scopeKey)).apply() }
    private fun update(scopeKey: String, change: (MutableSet<String>) -> Unit) {
        val key = prefKey(scopeKey)
        // getStringSet's result must not be modified in place.
        val next = HashSet(prefs.getStringSet(key, null) ?: emptySet())
        change(next)
        prefs.edit().apply { if (next.isEmpty()) remove(key) else putStringSet(key, next) }.apply()
    }
}

/** Progress of one frozen source against its saved Keeps. */
class DeclutterProgress internal constructor(
    val total: Int,
    /** Saved Keeps that still match an item in the current source. */
    val reviewed: Int,
    /** Source items not yet kept, in source order. */
    val unreviewed: List<MediaRecord>
) {
    val complete: Boolean get() = total > 0 && unreviewed.isEmpty()

    companion object {
        /**
         * Rebases saved Keeps onto the current source: Keeps for files that left
         * the source or changed (identity mismatch) simply do not count.
         */
        fun of(source: List<MediaRecord>, kept: Set<String>): DeclutterProgress {
            val unreviewed = ArrayList<MediaRecord>()
            var reviewed = 0
            for (media in source) {
                if (declutterKeepIdentity(media) in kept) reviewed++ else unreviewed.add(media)
            }
            return DeclutterProgress(source.size, reviewed, unreviewed)
        }
    }
}
