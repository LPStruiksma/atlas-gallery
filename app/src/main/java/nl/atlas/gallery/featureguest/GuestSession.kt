package nl.atlas.gallery.featureguest

/**
 * GUEST-1 (#589): pure guest-view rules, no Android types, so every route and
 * every storage outcome can be enumerated in JVM tests.
 *
 * The guest set is frozen at entry: an ordered, de-duplicated list of the
 * owner's chosen still photos. Nothing in guest view ever reads a live album,
 * timeline, search result or MediaStore query to decide what may be shown.
 */
data class GuestItem(val id: Long, val uri: String)

enum class GuestState { ACTIVE, EXITED }

/** The persisted session record (app-private `AtomicFile`, see [GuestSessionStore]). */
data class GuestRecord(
    val state: GuestState,
    val sessionId: String,
    val items: List<GuestItem>,
    val index: Int,
    /** The auth request the OS confirmation was launched for; "" when none is pending. */
    val authNonce: String = "",
    /** Screen pinning is offered once per session, never again after a decline. */
    val pinOffered: Boolean = false,
    /** Entry from the viewer: the photo to reopen for the owner after a confirmed exit. */
    val returnViewerUri: String = ""
) {
    init {
        require(index >= 0) { "index must not be negative" }
        require(items.isEmpty() || index <= items.lastIndex) { "index outside the frozen set" }
    }

    val active: Boolean get() = state == GuestState.ACTIVE
}

/** One selected library item as the owner's screen knows it, before eligibility. */
data class GuestCandidate(val id: Long, val uri: String, val isVideo: Boolean)

sealed interface GuestEntryPlan {
    /** Start guest view with exactly these items (tap order, de-duplicated), page 0. */
    data class Start(val items: List<GuestItem>) : GuestEntryPlan

    /** Do nothing and show [message] once; null message means no action at all. */
    data class Refuse(val message: String?) : GuestEntryPlan
}

object GuestCopy {
    const val ENTRY = "Guest view"
    const val EXIT = "Exit guest view"
    const val PHOTOS_ONLY = "Select photos only"
    const val NEEDS_LOCK = "Set a screen lock to use guest view"
    const val PHOTO_UNAVAILABLE = "Photo unavailable"
    const val ALL_UNAVAILABLE = "Selected photos unavailable"
}

object GuestEntry {
    /**
     * Brief v2 gap 5: eligible means a MediaStore image row (never a video).
     * Zero items do nothing; any video in the request refuses the WHOLE request
     * rather than silently dropping files. A device without a secure lock never
     * enters, because nothing could protect the exit.
     */
    fun plan(selectedInTapOrder: List<GuestCandidate>, deviceSecure: Boolean): GuestEntryPlan {
        if (selectedInTapOrder.isEmpty()) return GuestEntryPlan.Refuse(null)
        if (selectedInTapOrder.any { it.isVideo || !isImageUri(it.uri) }) {
            return GuestEntryPlan.Refuse(GuestCopy.PHOTOS_ONLY)
        }
        if (!deviceSecure) return GuestEntryPlan.Refuse(GuestCopy.NEEDS_LOCK)
        val seen = HashSet<String>()
        val items = selectedInTapOrder.filter { seen.add(it.uri) }.map { GuestItem(it.id, it.uri) }
        return GuestEntryPlan.Start(items)
    }

    /** Viewer More shows "Guest view" only on a still photo. */
    fun offeredInViewer(candidate: GuestCandidate): Boolean =
        !candidate.isVideo && isImageUri(candidate.uri)

    /** A MediaStore image row URI, e.g. content://media/external/images/media/42. */
    fun isImageUri(uri: String): Boolean =
        uri.startsWith("content://media/") && uri.contains("/images/") && GuestRecordCodec.safeText(uri)
}

/** What the activity may compose at start, decided before ANY route or intent is read. */
sealed interface GuestBoot {
    data object Owner : GuestBoot
    data class Guest(val record: GuestRecord) : GuestBoot

    /** Guard says guest but the set cannot be trusted: black shell, only Exit. */
    data object RecoveryShell : GuestBoot
}

/** The result of reading one durable store. */
sealed interface StoreRead<out T> {
    data object Absent : StoreRead<Nothing>
    data class Value<T>(val value: T) : StoreRead<T>

    /** IO error, parse failure, truncation or an unknown version. */
    data object Unreadable : StoreRead<Nothing>
}

object GuestGuard {
    /**
     * Brief v2 gap 1: only two combinations reach the owner's gallery: a fresh
     * install (neither store ever written) and a coherent exit (sentinel false
     * AND record EXITED). Everything else is guest, and anything incoherent is
     * the recovery shell. There is no third way out.
     */
    fun boot(sentinel: StoreRead<Boolean>, record: StoreRead<GuestRecord>): GuestBoot {
        if (sentinel is StoreRead.Absent && record is StoreRead.Absent) return GuestBoot.Owner
        val exited = sentinel is StoreRead.Value && !sentinel.value &&
            record is StoreRead.Value && record.value.state == GuestState.EXITED
        if (exited) return GuestBoot.Owner
        val activeRecord = (record as? StoreRead.Value)?.value?.takeIf { it.active }
        val sentinelActive = sentinel is StoreRead.Value && sentinel.value
        // A coherent live session (sentinel true) shows its frozen set. A lost
        // sentinel with a live record is still guest (brief v2). Every other
        // mix, e.g. sentinel false beside an ACTIVE record, is a conflict.
        if (activeRecord != null && activeRecord.items.isNotEmpty() &&
            (sentinelActive || sentinel is StoreRead.Absent)) {
            return GuestBoot.Guest(activeRecord)
        }
        return GuestBoot.RecoveryShell
    }

    /**
     * While a guest session is live, every launch route (launcher, view,
     * review, pick, notification, shortcut, restored navigation, onNewIntent)
     * lands in guest. The caller does not even parse the intent.
     */
    fun routesToGuest(boot: GuestBoot): Boolean = boot !is GuestBoot.Owner

    /**
     * Brief v2 gap 3: only a RESULT_OK from the OS confirmation that THIS
     * process launched for the CURRENT session and nonce may end guest view.
     * [launched] lives in process memory only, so a result redelivered after
     * process death is never accepted ("auth is never restored as successful").
     */
    fun acceptsExit(
        resultOk: Boolean,
        launched: GuestAuthRequest?,
        current: GuestRecord?
    ): Boolean {
        if (!resultOk || launched == null || current == null) return false
        if (!current.active) return false
        if (launched.nonce.isEmpty() || current.authNonce.isEmpty()) return false
        return launched.sessionId == current.sessionId && launched.nonce == current.authNonce
    }
}

/** The in-memory twin of the nonce persisted before launching the OS confirmation. */
data class GuestAuthRequest(val sessionId: String, val nonce: String)

object GuestPaging {
    /** Swipes and filmstrip taps resolve only inside the frozen set; no wrap, no neighbours. */
    fun clamp(index: Int, size: Int): Int = if (size <= 0) 0 else index.coerceIn(0, size - 1)

    fun next(index: Int, size: Int): Int = clamp(index + 1, size)

    fun previous(index: Int, size: Int): Int = clamp(index - 1, size)

    /** A filmstrip tap may only name a page of the frozen set. */
    fun tap(target: Int, size: Int): Int? = target.takeIf { it in 0 until size }
}

/**
 * Versioned line format with a count and an end marker, so a truncated or
 * half-written file is detected rather than read as a shorter set.
 */
object GuestRecordCodec {
    private const val HEADER = "atlas-guest v1"

    fun encode(record: GuestRecord): String = buildString {
        append(HEADER).append('\n')
        append("state=").append(record.state.name).append('\n')
        append("session=").append(record.sessionId).append('\n')
        append("nonce=").append(record.authNonce).append('\n')
        append("index=").append(record.index).append('\n')
        append("pin=").append(if (record.pinOffered) 1 else 0).append('\n')
        append("return=").append(record.returnViewerUri).append('\n')
        append("count=").append(record.items.size).append('\n')
        for (item in record.items) {
            append("item=").append(item.id).append(' ').append(item.uri).append('\n')
        }
        append("end=").append(record.items.size).append('\n')
    }

    /** Null for anything but a complete, well-formed v1 record. */
    fun decode(text: String): GuestRecord? = try {
        decodeOrThrow(text)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun decodeOrThrow(text: String): GuestRecord {
        require(text.endsWith("\n"))
        val lines = text.dropLast(1).split('\n')
        require(lines.size >= 9 && lines[0] == HEADER)
        fun field(at: Int, key: String): String {
            val line = lines[at]
            require(line.startsWith("$key="))
            return line.substring(key.length + 1)
        }
        val state = GuestState.valueOf(field(1, "state"))
        val session = field(2, "session")
        require(session.length in 16..64 && session.all { it.isLetterOrDigit() })
        val nonce = field(3, "nonce")
        require(nonce.isEmpty() || (nonce.length in 16..64 && nonce.all { it.isLetterOrDigit() }))
        val index = field(4, "index").toIntOrNull() ?: throw IllegalArgumentException()
        val pin = when (field(5, "pin")) { "1" -> true; "0" -> false; else -> throw IllegalArgumentException() }
        val returnUri = field(6, "return")
        require(returnUri.isEmpty() || GuestEntry.isImageUri(returnUri))
        val count = field(7, "count").toIntOrNull() ?: throw IllegalArgumentException()
        require(count >= 0 && lines.size == 9 + count)
        val items = (0 until count).map { offset ->
            val value = field(8 + offset, "item")
            val space = value.indexOf(' ')
            require(space > 0)
            val id = value.substring(0, space).toLongOrNull() ?: throw IllegalArgumentException()
            val uri = value.substring(space + 1)
            require(GuestEntry.isImageUri(uri))
            GuestItem(id, uri)
        }
        require(field(8 + count, "end") == count.toString())
        require(items.map { it.uri }.toSet().size == items.size)
        require(state == GuestState.EXITED || items.isNotEmpty() || index == 0)
        require(items.isEmpty() && index == 0 || index in items.indices)
        return GuestRecord(state, session, items, index, nonce, pin, returnUri)
    }

    /** No whitespace or control characters: one record line can never become two. */
    internal fun safeText(value: String): Boolean = value.none { it.isWhitespace() || it.isISOControl() }
}
