package nl.atlas.gallery.featuresearch

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * FIND-3a (#182): persistent, on-device recent searches.
 *
 * Owner ask (Lukas, 2026-11-15): "a search history ... if possible", with the
 * issue's "Stored locally in app-private storage, keeping the last 50". This
 * file replaces the earlier ephemeral-by-design recents for the Search tab and
 * the scoped search bars. What is stored, where and when it is cleared:
 *
 * - Only submitted query words and opaque person keys, per [SearchHistoryScope].
 *   Never media, thumbnails, face or image data, URIs or album ids.
 * - One app-private file under `filesDir` (manifest `allowBackup="false"`), so
 *   it never leaves the phone. It is never logged, never put in the usage log
 *   (#173), saved-state Bundles or analytics.
 * - Cleared for every scope when photo access is reduced (full to partial, or
 *   any to none), judged by [SearchGrantLevel], not by the epoch string. A
 *   person entry whose key no longer names a person is hidden at display time.
 */
enum class SearchHistoryScope(val key: String) {
    GLOBAL("global"),
    PHOTOS("photos"),
    ALBUMS("albums"),
    ALBUM("album"),
    PEOPLE("people"),
    PERSON("person"),
    STORIES("stories");

    companion object {
        fun fromKey(key: String): SearchHistoryScope? = entries.firstOrNull { it.key == key }
    }
}

/** Photo access, ordered. Only a drop in [rank] clears history. */
enum class SearchGrantLevel(val rank: Int) { NONE(0), PARTIAL(1), FULL(2) }

fun searchGrantLevel(images: Boolean, videos: Boolean, selected: Boolean): SearchGrantLevel = when {
    images && videos -> SearchGrantLevel.FULL
    images || videos || selected -> SearchGrantLevel.PARTIAL
    else -> SearchGrantLevel.NONE
}

/**
 * One recent search: the typed words ([text], may be empty) and the people
 * picked as chips ([personKeys], opaque, in pick order). Display labels are
 * built at display time from the current names, never stored.
 */
data class SearchHistoryEntry(val text: String, val personKeys: List<String> = emptyList()) {
    /** One entry per query: same people (any order) and same words, ignoring case. */
    val identity: String
        get() = personKeys.sorted().joinToString("\u0001") + "\u0000" +
            text.trim().lowercase(Locale.ROOT)

    /** Admitted by the same rule as the search engine; never truncated. */
    val admissible: Boolean
        get() = personKeys.size <= MAX_PERSON_KEYS &&
            personKeys.all { it.isNotBlank() && it.length <= MAX_PERSON_KEY_CHARS } &&
            personKeys.toSet().size == personKeys.size &&
            (text.isNotBlank() || personKeys.isNotEmpty()) &&
            (text.isBlank() || admittedSearchQueryWordsOrNull(text) != null)

    companion object {
        const val MAX_PERSON_KEYS = 8
        const val MAX_PERSON_KEY_CHARS = 256
    }
}

/** Immutable history for every scope. All operations are pure. */
data class SearchHistoryState(
    val enabled: Boolean = true,
    /** Null until the first grant is seen; then the last known level. */
    val grantLevel: SearchGrantLevel? = null,
    val scopes: Map<SearchHistoryScope, List<SearchHistoryEntry>> = emptyMap()
) {
    fun entries(scope: SearchHistoryScope): List<SearchHistoryEntry> = scopes[scope].orEmpty()

    /** What the Recent searches block may show: newest first, at most [SHOWN]. */
    fun shown(scope: SearchHistoryScope): List<SearchHistoryEntry> =
        if (enabled) entries(scope).take(SHOWN) else emptyList()

    val isEmpty: Boolean get() = scopes.values.all { it.isEmpty() }

    fun remember(scope: SearchHistoryScope, entry: SearchHistoryEntry): SearchHistoryState {
        if (!enabled) return this
        val clean = entry.copy(text = entry.text.trim())
        if (!clean.admissible) return this
        val id = clean.identity
        val next = (listOf(clean) + entries(scope).filterNot { it.identity == id }).take(KEPT)
        return copy(scopes = scopes + (scope to next))
    }

    fun remove(scope: SearchHistoryScope, entry: SearchHistoryEntry): SearchHistoryState {
        val id = entry.identity
        val list = entries(scope)
        if (list.none { it.identity == id }) return this
        return withScope(scope, list.filterNot { it.identity == id })
    }

    fun clear(scope: SearchHistoryScope): SearchHistoryState = withScope(scope, emptyList())

    fun clearAll(): SearchHistoryState = copy(scopes = emptyMap())

    /** Off stops saving and clears every scope; on starts saving again. */
    fun withEnabled(on: Boolean): SearchHistoryState =
        if (on) copy(enabled = true) else copy(enabled = false, scopes = emptyMap())

    /** Reduced or revoked photo access clears every scope; more access keeps it. */
    fun onGrant(level: SearchGrantLevel): SearchHistoryState {
        val previous = grantLevel
        val reduced = previous != null && level.rank < previous.rank
        return copy(grantLevel = level, scopes = if (reduced) emptyMap() else scopes)
    }

    /**
     * Undo of remove, clear, clear all or off: brings back the [snapshot]'s
     * switch and entries in their old order, after anything saved since.
     * Never resurrects history across a grant change, and never while off
     * unless the snapshot itself turns history back on.
     */
    fun restore(snapshot: SearchHistoryState): SearchHistoryState {
        if (snapshot.grantLevel != grantLevel) return this
        if (!snapshot.enabled) return copy(enabled = false, scopes = emptyMap())
        val merged = HashMap<SearchHistoryScope, List<SearchHistoryEntry>>()
        for (scope in SearchHistoryScope.entries) {
            val old = snapshot.entries(scope)
            val oldIds = old.mapTo(HashSet()) { it.identity }
            val list = (entries(scope).filterNot { it.identity in oldIds } + old).take(KEPT)
            if (list.isNotEmpty()) merged[scope] = list
        }
        return copy(enabled = true, scopes = merged)
    }

    private fun withScope(scope: SearchHistoryScope, list: List<SearchHistoryEntry>) =
        copy(scopes = if (list.isEmpty()) scopes - scope else scopes + (scope to list))

    companion object {
        /** Samsung shows ten recents (PPL:20, #134). */
        const val SHOWN = 10
        /** Kept per scope (issue #182). */
        const val KEPT = 50
    }
}

private const val HISTORY_HEADER = "ATLAS-SEARCH-HISTORY 1"
private val b64Encoder = Base64.getUrlEncoder().withoutPadding()
private val b64Decoder = Base64.getUrlDecoder()

private fun enc(value: String) = b64Encoder.encodeToString(value.toByteArray(StandardCharsets.UTF_8))
private fun dec(value: String) = String(b64Decoder.decode(value), StandardCharsets.UTF_8)

/** Line format; every free-text field is base64url, so no escaping is needed. */
fun encodeSearchHistory(state: SearchHistoryState): String = buildString {
    append(HISTORY_HEADER).append('\n')
    append("enabled\t").append(if (state.enabled) '1' else '0').append('\n')
    append("grant\t").append(state.grantLevel?.name ?: "-").append('\n')
    for (scope in SearchHistoryScope.entries) {
        for (entry in state.entries(scope)) {
            append("e\t").append(scope.key).append('\t').append(enc(entry.text)).append('\t')
                .append(entry.personKeys.joinToString(",") { enc(it) }).append('\n')
        }
    }
}

/**
 * Null when the file is not a history file at all or is damaged: the caller
 * then starts empty and rewrites it on the next save. Unknown scopes and
 * inadmissible entries are dropped; each scope keeps at most [SearchHistoryState.KEPT].
 */
fun decodeSearchHistory(text: String): SearchHistoryState? = try {
    val lines = text.split('\n').filter { it.isNotEmpty() }
    if (lines.firstOrNull() != HISTORY_HEADER) null else {
        var enabled = true
        var grant: SearchGrantLevel? = null
        val scopes = LinkedHashMap<SearchHistoryScope, MutableList<SearchHistoryEntry>>()
        for (line in lines.drop(1)) {
            val parts = line.split('\t')
            when (parts[0]) {
                "enabled" -> enabled = parts.getOrNull(1) != "0"
                "grant" -> grant = parts.getOrNull(1)?.takeIf { it != "-" }
                    ?.let { name -> SearchGrantLevel.entries.firstOrNull { it.name == name } }
                "e" -> {
                    if (parts.size != 4) continue
                    val scope = SearchHistoryScope.fromKey(parts[1]) ?: continue
                    val keys = if (parts[3].isEmpty()) emptyList() else parts[3].split(',').map(::dec)
                    val entry = SearchHistoryEntry(dec(parts[2]).trim(), keys)
                    val list = scopes.getOrPut(scope) { ArrayList() }
                    if (entry.admissible && list.size < SearchHistoryState.KEPT &&
                        list.none { it.identity == entry.identity }) list += entry
                }
            }
        }
        SearchHistoryState(
            enabled = enabled,
            grantLevel = grant,
            scopes = if (enabled) scopes.filterValues { it.isNotEmpty() } else emptyMap()
        )
    }
} catch (_: IllegalArgumentException) {
    null
}

/** Storage seam: the app uses [SearchHistoryFile.atomic]; tests may fake it. */
interface SearchHistoryFile {
    /** Null when there is no file yet. May throw on I/O errors. */
    fun read(): String?
    fun write(text: String)

    companion object {
        /** Write a sibling temp file, then rename over the old one. [file] is resolved on I/O only. */
        fun atomic(file: () -> File): SearchHistoryFile = object : SearchHistoryFile {
            override fun read(): String? = file().takeIf { it.isFile }?.readText(StandardCharsets.UTF_8)
            override fun write(text: String) {
                val target = file()
                val tmp = File(target.parentFile, target.name + ".tmp")
                tmp.writeText(text, StandardCharsets.UTF_8)
                try {
                    Files.move(tmp.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }
}

/**
 * Process-wide holder. [state] updates at once on the caller's thread; [load]
 * and [flush] do blocking I/O and must run off the main thread. Changes made
 * before [load] finishes are replayed on top of what was read, so an early
 * grant reduction still clears history that was saved in an earlier run.
 * Read or write failures never throw out of here and never log query text.
 */
class SearchHistoryStore(private val file: SearchHistoryFile) {
    private val lock = Any()
    private val writeLock = Any()
    private val _state = MutableStateFlow(SearchHistoryState())
    val state: StateFlow<SearchHistoryState> = _state.asStateFlow()
    private var loaded = false
    private val pending = ArrayList<(SearchHistoryState) -> SearchHistoryState>()
    private var version = 0L
    private var written = 0L

    val isLoaded: Boolean get() = synchronized(lock) { loaded }

    fun load() {
        synchronized(lock) { if (loaded) return }
        val read = try { file.read()?.let(::decodeSearchHistory) } catch (_: Exception) { null }
        synchronized(lock) {
            if (loaded) return
            var next = read ?: SearchHistoryState()
            pending.forEach { next = it(next) }
            if (pending.isNotEmpty()) version++
            pending.clear()
            loaded = true
            _state.value = next
        }
    }

    /** Applies [op] now; returns true when something needs writing. */
    fun update(op: (SearchHistoryState) -> SearchHistoryState): Boolean = synchronized(lock) {
        val before = _state.value
        val next = op(before)
        if (!loaded) pending += op
        if (next == before) return@synchronized false
        _state.value = next
        version++
        loaded
    }

    /** Writes the newest state once, in order. No-op before [load] or when nothing changed. */
    fun flush() {
        synchronized(writeLock) {
            val (text, at) = synchronized(lock) {
                if (!loaded || version == written) return
                encodeSearchHistory(_state.value) to version
            }
            try {
                file.write(text)
                synchronized(lock) { if (at > written) written = at }
            } catch (_: Exception) {
                // Keep `written` behind: the next change retries the whole file.
            }
        }
    }
}
