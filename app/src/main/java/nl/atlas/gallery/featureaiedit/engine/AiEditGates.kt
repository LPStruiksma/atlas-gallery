package nl.atlas.gallery.featureaiedit.engine

import nl.atlas.gallery.featureaiedit.provider.CostDisclosure

/** One confirmation that must be answered before a paid request starts (#431 rev 4 F and cost rules). */
enum class AiEditGate {
    /** The general "Before your first AI Edit" disclosure, once per install. */
    FIRST_USE,
    /** Per service, when ATLAS doesn't know the total price (unknown or output-only/partial). */
    PRICE_UNKNOWN,
    /** After an edit that may have been charged: "{Service} may charge again." */
    CHARGE_AGAIN
}

/**
 * Which confirmation, if any, stands between Generate and the request. Pure. Order: first use,
 * then the per-service price consent, then the charge-again confirmation for this one attempt.
 * Nothing is sent while a gate is open; answering "Not now", Cancel or "Open {Service}" sends nothing.
 */
object AiEditGates {
    fun next(
        firstUseAcknowledged: Boolean,
        disclosure: CostDisclosure,
        priceConsented: Boolean,
        lastIssue: AiEditIssue?,
        chargeAgainConfirmed: Boolean
    ): AiEditGate? = when {
        !firstUseAcknowledged -> AiEditGate.FIRST_USE
        disclosure !is CostDisclosure.Total && !priceConsented -> AiEditGate.PRICE_UNKNOWN
        lastIssue?.mayHaveCharged == true && !chargeAgainConfirmed -> AiEditGate.CHARGE_AGAIN
        else -> null
    }
}
