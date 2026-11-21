package nl.atlas.gallery.featureguest

/**
 * GUEST-1 (#589) brief v2 gap 1: the durable guard. Two app-private stores,
 * both framework facilities with no library:
 *
 *  - a sentinel flag written with `SharedPreferences.commit()` (must return true);
 *  - the session record written with `android.util.AtomicFile` (fsync + rename).
 *
 * The write order makes every crash point fail closed:
 *
 *  entry: sentinel=true → record ACTIVE → read back and verify → first guest frame
 *  exit:  (OS RESULT_OK for this session+nonce) → record EXITED → sentinel=false → owner
 *
 * A kill after the sentinel but before the record boots to the recovery shell;
 * a kill between the exit's two writes boots to the recovery shell (sentinel
 * true beside EXITED). Nothing in this class ever answers "owner" on a failure.
 * The backends are interfaces so JVM tests can inject a failure or a crash at
 * every boundary.
 */
interface GuestSentinelBackend {
    /** Absent when never written; throws on an IO/parse problem. */
    fun read(): Boolean?

    /** True only when the value is durably on disk (`commit()`). */
    fun commit(active: Boolean): Boolean
}

interface GuestRecordBackend {
    /** Null when the file was never written; throws on an IO problem. */
    fun read(): String?

    /** True only after a complete atomic replace (`AtomicFile.finishWrite`). */
    fun write(text: String): Boolean
}

sealed interface GuestStartResult {
    data class Started(val record: GuestRecord) : GuestStartResult

    /** Something may have been activated: show the recovery shell, never the gallery. */
    data object Shell : GuestStartResult
}

class GuestSessionStore(
    private val sentinel: GuestSentinelBackend,
    private val records: GuestRecordBackend,
    private val newToken: () -> String
) {
    fun readSentinel(): StoreRead<Boolean> = try {
        sentinel.read()?.let { StoreRead.Value(it) } ?: StoreRead.Absent
    } catch (_: Exception) {
        StoreRead.Unreadable
    }

    fun readRecord(): StoreRead<GuestRecord> = try {
        val text = records.read()
        if (text == null) StoreRead.Absent
        else GuestRecordCodec.decode(text)?.let { StoreRead.Value(it) } ?: StoreRead.Unreadable
    } catch (_: Exception) {
        StoreRead.Unreadable
    }

    fun boot(): GuestBoot = GuestGuard.boot(readSentinel(), readRecord())

    /** The live record when it is readable and ACTIVE; null otherwise. */
    fun activeRecord(): GuestRecord? = (readRecord() as? StoreRead.Value)?.value?.takeIf { it.active }

    /**
     * Commits the frozen set before anything of it is shown. Returns
     * [GuestStartResult.Started] only when both writes landed and the record
     * reads back exactly as written.
     */
    fun start(items: List<GuestItem>, returnViewerUri: String): GuestStartResult {
        require(items.isNotEmpty())
        if (!safeCommit(true)) return GuestStartResult.Shell
        val record = GuestRecord(GuestState.ACTIVE, newToken(), items, 0,
            returnViewerUri = returnViewerUri.takeIf { GuestEntry.isImageUri(it) }.orEmpty())
        return if (safeWrite(record)) GuestStartResult.Started(record) else GuestStartResult.Shell
    }

    /**
     * Before launching the OS confirmation: persist a fresh nonce for the live
     * session. A recovery shell (no readable ACTIVE record) first re-establishes
     * an empty ACTIVE session so its exit is bound to a nonce too. Null when
     * nothing could be persisted, in which case no confirmation is launched.
     */
    fun prepareAuth(): GuestAuthRequest? {
        val base = activeRecord()
            ?: GuestRecord(GuestState.ACTIVE, newToken(), emptyList(), 0)
        if (!safeCommit(true)) return null
        val nonce = newToken()
        val next = base.copy(authNonce = nonce)
        return if (safeWrite(next)) GuestAuthRequest(next.sessionId, nonce) else null
    }

    /**
     * Runs the exit writes only when [GuestGuard.acceptsExit] holds against the
     * record as it is ON DISK now. Returns true when the owner may be shown.
     * Any failed write leaves (or puts) the device in guest.
     */
    fun exit(resultOk: Boolean, launched: GuestAuthRequest?): Boolean {
        val current = activeRecord()
        if (!GuestGuard.acceptsExit(resultOk, launched, current)) return false
        val exited = requireNotNull(current).copy(state = GuestState.EXITED, authNonce = "")
        if (!safeWrite(exited)) return false
        return safeCommit(false)
    }

    /** Index and pin bookkeeping for a live session; failures are harmless (the set never changes). */
    fun update(transform: (GuestRecord) -> GuestRecord): GuestRecord? {
        val current = activeRecord() ?: return null
        val next = transform(current)
        require(next.sessionId == current.sessionId && next.items == current.items && next.active)
        return if (safeWrite(next)) next else null
    }

    private fun safeCommit(active: Boolean): Boolean = try {
        sentinel.commit(active)
    } catch (_: Exception) {
        false
    }

    /**
     * Writes and reads back: `AtomicFile.finishWrite` logs rather than throws
     * on some IO failures, so success is what the disk returns, not what the
     * write call said.
     */
    private fun safeWrite(record: GuestRecord): Boolean {
        val wrote = try {
            records.write(GuestRecordCodec.encode(record))
        } catch (_: Exception) {
            false
        }
        if (!wrote) return false
        val back = readRecord()
        return back is StoreRead.Value && back.value == record
    }
}
