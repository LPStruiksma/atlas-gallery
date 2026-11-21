package nl.atlas.gallery.featureaiedit.engine

import nl.atlas.gallery.featureaiedit.provider.Delivery
import nl.atlas.gallery.featureaiedit.provider.EditOutcome
import nl.atlas.gallery.featureaiedit.provider.FailureKind

/**
 * The editor states an outcome can lead to (#431 rev 4 D plus addendum A2 to A4). Pure: no Android,
 * no provider name. The UI shows one card per value and never decides from a display name or
 * from local async support.
 */
enum class AiEditIssue {
    /** The service declined (policy, safety). */
    DECLINED,
    /** The service answered that the key is wrong, or there is no key. Nothing ran. */
    WRONG_KEY,
    /** HTTP 402 or a documented prepaid-exhausted code. Offers "Open {Service}". */
    OUT_OF_CREDIT,
    /** HTTP 429 / RESOURCE_EXHAUSTED: a rate or quota limit, never "out of credit". Manual retry only. */
    RATE_LIMITED,
    /** Proven not sent, connection failure: "No connection" · "Nothing was sent." */
    OFFLINE,
    /** Proven not sent, timeout or other failure before the body: "Couldn't reach {Service}". */
    NOT_REACHED,
    /** The service answered but made no edit, or the request was refused locally. */
    FAILED,
    /** May have been charged; a validated remote id is saved, so "Check again" can look it up. */
    UNKNOWN_RECOVERABLE,
    /** May have been charged; a saved request can no longer be checked (key changed, lookup refused). */
    UNKNOWN_CANNOT_CHECK,
    /** May have been charged and nothing can be looked up. Try again needs a "may charge again" confirm. */
    UNKNOWN_NONRECOVERABLE,
    CANCELLED;

    /** States where the paid request may have reached the service, so a new submit could charge again. */
    val mayHaveCharged: Boolean
        get() = this == UNKNOWN_RECOVERABLE || this == UNKNOWN_CANNOT_CHECK || this == UNKNOWN_NONRECOVERABLE

    companion object {
        /**
         * [remoteSaved] is true only when a validated remote id is durably journalled for this
         * request and bound to its provider, endpoint and key fingerprint.
         */
        fun classify(outcome: EditOutcome, remoteSaved: Boolean): AiEditIssue? = when (outcome) {
            is EditOutcome.Success -> null
            EditOutcome.Cancelled -> CANCELLED
            is EditOutcome.Refused -> DECLINED
            is EditOutcome.Failed -> classifyFailure(outcome, remoteSaved)
        }

        private fun classifyFailure(failed: EditOutcome.Failed, remoteSaved: Boolean): AiEditIssue {
            if (failed.kind == FailureKind.CANNOT_CHECK) return UNKNOWN_CANNOT_CHECK
            return when (failed.delivery) {
                Delivery.UNCERTAIN -> if (remoteSaved) UNKNOWN_RECOVERABLE else UNKNOWN_NONRECOVERABLE
                Delivery.NOT_SENT -> when (failed.kind) {
                    FailureKind.AUTH -> WRONG_KEY
                    FailureKind.NETWORK -> OFFLINE
                    FailureKind.TIMEOUT, FailureKind.SERVER -> NOT_REACHED
                    else -> FAILED
                }
                Delivery.ANSWERED -> when (failed.kind) {
                    FailureKind.AUTH -> WRONG_KEY
                    FailureKind.BILLING -> OUT_OF_CREDIT
                    FailureKind.QUOTA -> RATE_LIMITED
                    else -> FAILED
                }
            }
        }
    }
}
