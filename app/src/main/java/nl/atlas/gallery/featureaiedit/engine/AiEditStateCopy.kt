package nl.atlas.gallery.featureaiedit.engine

import nl.atlas.gallery.featureaiedit.provider.CostDisclosure
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A state card: title, one line of detail, and its actions in order (primary last). */
data class IssueCard(val title: String, val detail: String, val actions: List<IssueAction>)

enum class IssueAction {
    CHANGE_PROMPT, TRY_ANOTHER_SERVICE, OPEN_SETTINGS, OPEN_SERVICE, TRY_AGAIN,
    CHECK_AGAIN, DISCARD, TRY_AGAIN_CHARGED
}

/**
 * Copy for every editor state (#431 rev 4 D and E, addendum A2 and A4). Sentence case, no middle
 * dots, the service's own name, never "provider" or "endpoint". Pure, so JVM tests pin it.
 */
object AiEditStateCopy {
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

    fun card(issue: AiEditIssue, service: String, reason: String? = null): IssueCard? = when (issue) {
        AiEditIssue.DECLINED -> IssueCard("$service declined this edit",
            reason?.takeIf { it.isNotBlank() }?.take(160) ?: "It didn't give a reason.",
            listOf(IssueAction.TRY_ANOTHER_SERVICE, IssueAction.CHANGE_PROMPT))
        AiEditIssue.DECLINED_MAY_HAVE_CHARGED -> IssueCard("$service declined this edit",
            (reason?.takeIf { it.isNotBlank() }?.take(160) ?: "It didn't give a reason.") +
                " $service may have charged for it.",
            listOf(IssueAction.TRY_ANOTHER_SERVICE, IssueAction.CHANGE_PROMPT))
        AiEditIssue.WRONG_KEY -> IssueCard("$service didn't accept your key",
            "Check the key in Settings, then try again.", listOf(IssueAction.OPEN_SETTINGS))
        AiEditIssue.OUT_OF_CREDIT -> IssueCard("$service says you're out of credit",
            "Add credit with $service, or use another service.",
            listOf(IssueAction.TRY_ANOTHER_SERVICE, IssueAction.OPEN_SERVICE))
        AiEditIssue.RATE_LIMITED -> IssueCard("$service is limiting requests right now",
            "It didn't process this edit. Try again in a little while.",
            listOf(IssueAction.TRY_ANOTHER_SERVICE, IssueAction.TRY_AGAIN))
        AiEditIssue.OFFLINE -> IssueCard("No connection", "Nothing was sent.", listOf(IssueAction.TRY_AGAIN))
        AiEditIssue.NOT_REACHED -> IssueCard("Couldn't reach $service", "Nothing was sent.", listOf(IssueAction.TRY_AGAIN))
        AiEditIssue.FAILED -> IssueCard("$service couldn't make this edit",
            "Try again, or change what you asked for.", listOf(IssueAction.CHANGE_PROMPT, IssueAction.TRY_AGAIN))
        AiEditIssue.UNKNOWN_RECOVERABLE -> IssueCard("We couldn't confirm this edit",
            "$service may have charged for it.", listOf(IssueAction.DISCARD, IssueAction.CHECK_AGAIN))
        AiEditIssue.UNKNOWN_CANNOT_CHECK -> IssueCard("We couldn't confirm this edit",
            "$service may have charged for it. ATLAS can no longer check this edit.",
            listOf(IssueAction.TRY_AGAIN_CHARGED, IssueAction.DISCARD))
        AiEditIssue.UNKNOWN_NONRECOVERABLE -> IssueCard("We couldn't confirm this edit",
            "$service may have charged for it. The result can't be recovered.",
            listOf(IssueAction.TRY_AGAIN_CHARGED, IssueAction.DISCARD))
        AiEditIssue.CANCELLED -> null
    }

    fun actionLabel(action: IssueAction, service: String): String = when (action) {
        IssueAction.CHANGE_PROMPT -> "Change prompt"
        IssueAction.TRY_ANOTHER_SERVICE -> "Try another service"
        IssueAction.OPEN_SETTINGS -> "Open settings"
        IssueAction.OPEN_SERVICE -> "Open $service"
        IssueAction.TRY_AGAIN, IssueAction.TRY_AGAIN_CHARGED -> "Try again"
        IssueAction.CHECK_AGAIN -> "Check again"
        IssueAction.DISCARD -> "Discard"
    }

    /** "{Service} may charge again." confirmation before any new POST after an uncertain charge. */
    fun chargeAgainTitle(service: String) = "$service may charge again."
    const val CHARGE_AGAIN_DETAIL = "The last edit may already have been charged. Trying again sends a new request."

    fun cancelledToast(service: String) = "Cancelled. $service may still charge for work already started."

    /** The one line under the photo before Generate (#431 rev 4 cost rules, addendum A1). */
    fun costLine(disclosure: CostDisclosure, service: String, model: String?): String = when (disclosure) {
        is CostDisclosure.Total -> "About ${disclosure.amount} per edit${model?.let { " with $it" } ?: ""}. " +
            "${disclosure.source}, checked ${DATE.format(disclosure.observedOn)}."
        is CostDisclosure.Partial -> "Image output about ${disclosure.amount}; input extra; total unknown. " +
            "${disclosure.source}, checked ${DATE.format(disclosure.observedOn)}."
        CostDisclosure.Unknown -> "Sent to $service. $service sets the price."
    }

    fun priceConsentTitle(service: String) = "$service charges for each edit"
    const val PRICE_CONSENT_DETAIL = "ATLAS doesn't know the total price. Check the service's pricing before continuing."

    /** Shown when the source is larger than the service accepts (#431 rev 2 table). */
    fun sentAtSize(width: Int, height: Int) = "This photo will be sent at $width×$height."

    /** AIE-21 session Undo outcomes (rev 5 E). */
    const val UNDO_REMOVED = "Copy removed"
    const val UNDO_FAILED = "Couldn't undo. Copy still saved."

    const val NO_SERVICE_TITLE = "Connect a service to start"
    const val NO_SERVICE_DETAIL = "AI Edit sends the photo you choose and your instruction to a service that makes the edit."

    /** Existing "Before your first AI Edit" wording (AiEditSettingsPanel), shown once. */
    const val FIRST_USE_TITLE = "Before your first AI Edit"
    const val FIRST_USE_TEXT = "Only the image you explicitly select, your edit instruction and any " +
        "optional mask or reference images are sent to the provider you choose. " +
        "Location metadata is removed by default. Your gallery is never scanned " +
        "or uploaded automatically. The remote provider may retain data or charge for a request. " +
        "You can cancel a job; that may not refund remote processing."

    fun working(sending: Boolean, service: String) = if (sending) "Sending photo…" else "Editing with $service"

}
