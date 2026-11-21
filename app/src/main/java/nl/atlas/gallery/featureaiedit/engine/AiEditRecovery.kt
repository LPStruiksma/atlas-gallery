package nl.atlas.gallery.featureaiedit.engine

import nl.atlas.gallery.featureaiedit.provider.RemoteHandle
import nl.atlas.gallery.featureaiedit.provider.RemoteRecovery

/**
 * The durable record of one paid request (#431 rev 4 row 6). Written before the request starts,
 * updated when a queue provider returns a validated id, and when results are stored. It never
 * holds a key, only a [KeyFingerprint].
 */
data class AiEditRequestRecord(
    val generation: Long,
    val profileId: String,
    val providerId: String,
    val serviceName: String,
    val recovery: RemoteRecovery,
    val stage: Stage,
    val handle: RemoteHandle? = null,
    val prompt: String = ""
) {
    enum class Stage {
        /** Durably marked before the paid request started: it may have been sent. */
        SENT,
        /** A queue provider returned a validated id bound to provider, endpoint and key fingerprint. */
        ACCEPTED,
        /** Results are stored in the session; nothing is in flight. */
        DONE
    }

    /** True when a later Check again may query the saved id (never a resubmit). */
    val recoverable: Boolean
        get() = stage == Stage.ACCEPTED && recovery == RemoteRecovery.QUERY_BY_PERSISTED_ID &&
            handle != null && handle.providerId == providerId && KeyFingerprint.isValid(handle.keyFingerprint)
}

/** What reopening the editor shows for a journalled request (#431 rev 4 "Resumed after process death"). */
sealed interface RestoreDecision {
    /** Nothing in flight: restore the session as it was. */
    data object Normal : RestoreDecision
    /** A queue request with a saved id: show Working and look it up (status/result only, no POST). */
    data class Resume(val record: AiEditRequestRecord) : RestoreDecision
    /** Submit may have been sent and nothing can be looked up: non-recoverable Outcome unknown. */
    data class Unknown(val record: AiEditRequestRecord) : RestoreDecision

    companion object {
        fun of(record: AiEditRequestRecord?): RestoreDecision = when {
            record == null || record.stage == AiEditRequestRecord.Stage.DONE -> Normal
            record.recoverable -> Resume(record)
            else -> Unknown(record)
        }
    }
}
