package nl.atlas.gallery.featureaiedit.engine

import nl.atlas.gallery.featureaiedit.network.EditHttpResult
import nl.atlas.gallery.featureaiedit.network.FailureCode
import nl.atlas.gallery.featureaiedit.network.FailurePhase
import nl.atlas.gallery.featureaiedit.provider.Delivery
import nl.atlas.gallery.featureaiedit.provider.EditOutcome
import nl.atlas.gallery.featureaiedit.provider.FailureKind
import nl.atlas.gallery.featureaiedit.provider.hosted.hostedFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiEditIssueTest {
    private fun answered(code: FailureCode, status: Int) =
        hostedFailure(EditHttpResult.Failed(code, false, status, phase = FailurePhase.AFTER_BODY_OR_UNKNOWN))

    @Test fun paymentRequiredIsOutOfCreditButRateLimitIsNot() {
        assertEquals(AiEditIssue.OUT_OF_CREDIT, AiEditIssue.classify(answered(FailureCode.BILLING, 402), false))
        val limited = AiEditIssue.classify(answered(FailureCode.QUOTA, 429), false)
        assertEquals(AiEditIssue.RATE_LIMITED, limited)
        assertFalse(limited!!.mayHaveCharged)
        assertEquals(AiEditIssue.WRONG_KEY, AiEditIssue.classify(answered(FailureCode.AUTH, 401), false))
    }

    @Test fun nothingWasSentOnlyWithTransportProof() {
        val connect = hostedFailure(EditHttpResult.Failed(FailureCode.NETWORK, true, phase = FailurePhase.BEFORE_BODY))
        assertEquals(Delivery.NOT_SENT, connect.delivery)
        assertEquals(AiEditIssue.OFFLINE, AiEditIssue.classify(connect, false))
        val connectTimeout = hostedFailure(EditHttpResult.Failed(FailureCode.TIMEOUT, true, phase = FailurePhase.BEFORE_BODY))
        assertEquals(AiEditIssue.NOT_REACHED, AiEditIssue.classify(connectTimeout, false))
        // Same failure codes after the body (or with an unknown phase): never "Nothing was sent".
        val afterBody = hostedFailure(EditHttpResult.Failed(FailureCode.NETWORK, true))
        assertEquals(AiEditIssue.UNKNOWN_NONRECOVERABLE, AiEditIssue.classify(afterBody, false))
        val readTimeout = hostedFailure(EditHttpResult.Failed(FailureCode.TIMEOUT, true))
        assertEquals(AiEditIssue.UNKNOWN_NONRECOVERABLE, AiEditIssue.classify(readTimeout, false))
        // A 408 or 5xx answer may follow work the service already did: uncertain.
        assertEquals(Delivery.UNCERTAIN, answered(FailureCode.TIMEOUT, 408).delivery)
        assertEquals(Delivery.UNCERTAIN, answered(FailureCode.SERVER, 503).delivery)
    }

    @Test fun uncertainIsRecoverableOnlyWithASavedRemoteId() {
        val lost = EditOutcome.Failed(FailureKind.NETWORK, false)
        assertEquals(Delivery.UNCERTAIN, lost.delivery)
        assertEquals(AiEditIssue.UNKNOWN_RECOVERABLE, AiEditIssue.classify(lost, remoteSaved = true))
        assertEquals(AiEditIssue.UNKNOWN_NONRECOVERABLE, AiEditIssue.classify(lost, remoteSaved = false))
        // Even a credit or key error after acceptance stays "may have charged".
        val afterAccepted = hostedFailure(EditHttpResult.Failed(FailureCode.BILLING, false, 402), afterAccepted = true)
        assertEquals(AiEditIssue.UNKNOWN_RECOVERABLE, AiEditIssue.classify(afterAccepted, remoteSaved = true))
        assertTrue(AiEditIssue.UNKNOWN_NONRECOVERABLE.mayHaveCharged)
    }

    @Test fun cannotCheckIsItsOwnStateNeverWrongKey() {
        val gone = EditOutcome.Failed(FailureKind.CANNOT_CHECK, false, "LOOKUP_REFUSED")
        assertEquals(AiEditIssue.UNKNOWN_CANNOT_CHECK, AiEditIssue.classify(gone, remoteSaved = true))
        assertTrue(AiEditIssue.UNKNOWN_CANNOT_CHECK.mayHaveCharged)
    }

    @Test fun refusalsCancelAndSuccess() {
        assertEquals(AiEditIssue.DECLINED, AiEditIssue.classify(EditOutcome.Refused("c", "m"), false))
        assertEquals(AiEditIssue.CANCELLED, AiEditIssue.classify(EditOutcome.Cancelled, false))
        assertNull(AiEditIssue.classify(EditOutcome.Success(emptyList(), "m", "v"), false))
        assertEquals(AiEditIssue.FAILED,
            AiEditIssue.classify(EditOutcome.Failed(FailureKind.UNKNOWN, true, "NO_IMAGE", Delivery.ANSWERED), false))
    }
}
