package nl.atlas.gallery.featureaiedit.provider

import java.time.LocalDate
import java.util.Currency
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Transport-independent edit contract. Images are caller-selected handles; the optional pixel
 * array is ONLY for synthetic JVM fixtures. Adapters own decoding, image IO and credential policy.
 * No source image, prompt, or credentials are logged by this package.
 */
enum class ImageFormat { PNG, JPEG, WEBP }

data class EditImage(
    val handle: String,
    val width: Int,
    val height: Int,
    val format: ImageFormat,
    val pixels: List<Int> = emptyList()
)

data class OutputSpec(
    val width: Int,
    val height: Int,
    val format: ImageFormat = ImageFormat.PNG,
    val candidates: Int = 1
)

data class CapabilityKey(val name: String)

sealed interface OptionValue {
    data class IntegerValue(val value: Long) : OptionValue
    data class DecimalValue(val value: Double) : OptionValue
    data class BooleanValue(val value: Boolean) : OptionValue
    data class TextValue(val value: String) : OptionValue
}

/** Schema is explicit; a provider cannot expose an untyped arbitrary parameter. */
sealed interface OptionSchema {
    data class IntegerRange(val min: Long, val max: Long) : OptionSchema
    data class DecimalRange(val min: Double, val max: Double) : OptionSchema
    data object BooleanFlag : OptionSchema
    data class Choices(val values: Set<String>) : OptionSchema
    data class Text(val maxLength: Int) : OptionSchema

    fun accepts(value: OptionValue): Boolean = when (this) {
        is IntegerRange -> value is OptionValue.IntegerValue && value.value in min..max
        is DecimalRange -> value is OptionValue.DecimalValue &&
            value.value.isFinite() && value.value >= min && value.value <= max
        BooleanFlag -> value is OptionValue.BooleanValue
        is Choices -> value is OptionValue.TextValue && value.value in values
        is Text -> value is OptionValue.TextValue && value.value.length <= maxLength
    }
}

data class AspectRange(val min: Double, val max: Double) {
    init { require(min.isFinite() && max.isFinite() && min > 0 && min <= max) }
    fun supports(width: Int, height: Int): Boolean {
        val ratio = width.toDouble() / height
        return ratio >= min && ratio <= max
    }
}

/**
 * What a provider's price covers (#431 addendum A1). [TOTAL] means every applicable charge at the
 * chosen size, model and request shape is included; [OUTPUT_ONLY] prices just the generated image
 * (input is charged on top); [PARTIAL] covers some charges but not all of them.
 */
enum class CostScope { TOTAL, OUTPUT_ONLY, PARTIAL }

/**
 * A provider's price for one request, in the provider's own currency ([currencyCode], ISO 4217,
 * never converted). [scope], [source] and [observedOn] are typed so the editor never parses
 * [note]: a missing scope, source or date makes the estimate unusable ([CostDisclosure.of]).
 */
data class CostEstimate(
    val currencyCode: String,
    val minorUnits: Long,
    val note: String = "",
    val scope: CostScope? = null,
    val source: String? = null,
    val observedOn: LocalDate? = null
)

/**
 * What the editor may say about cost before Generate. It branches only on the typed scope:
 * [Total] → "About {amount}", [Partial] (OUTPUT_ONLY or PARTIAL) → "{amount}; input extra; total
 * unknown", [Unknown] → "{Service} sets the price." Anything incomplete fails closed to [Unknown].
 */
sealed interface CostDisclosure {
    data class Total(val amount: String, val source: String, val observedOn: LocalDate) : CostDisclosure
    data class Partial(val amount: String, val scope: CostScope, val source: String, val observedOn: LocalDate) : CostDisclosure
    data object Unknown : CostDisclosure

    companion object {
        private val CODE = Regex("[A-Z]{3}")

        fun of(estimate: CostEstimate?): CostDisclosure {
            if (estimate == null) return Unknown
            val scope = estimate.scope ?: return Unknown
            val source = estimate.source?.trim()?.takeIf { it.isNotEmpty() && it.length <= 120 } ?: return Unknown
            val date = estimate.observedOn ?: return Unknown
            if (estimate.minorUnits < 0) return Unknown
            val amount = formatAmount(estimate.currencyCode, estimate.minorUnits) ?: return Unknown
            return when (scope) {
                CostScope.TOTAL -> Total(amount, source, date)
                CostScope.OUTPUT_ONLY, CostScope.PARTIAL -> Partial(amount, scope, source, date)
            }
        }

        /**
         * Formats in the estimate's own currency with its own number of minor digits. Never a
         * conversion; an unknown code is refused rather than guessed.
         */
        fun formatAmount(currencyCode: String, minorUnits: Long): String? {
            if (!CODE.matches(currencyCode) || minorUnits < 0) return null
            val currency = try { Currency.getInstance(currencyCode) } catch (_: IllegalArgumentException) { return null }
            val digits = currency.defaultFractionDigits.coerceIn(0, 4)
            var divisor = 1L
            repeat(digits) { divisor *= 10 }
            val whole = minorUnits / divisor
            val fraction = minorUnits % divisor
            val number = if (digits == 0) whole.toString()
                else whole.toString() + "." + fraction.toString().padStart(digits, '0')
            val symbol = when (currencyCode) {
                "USD" -> "US$"
                "EUR" -> "€"
                "GBP" -> "£"
                else -> "$currencyCode "
            }
            return symbol + number
        }
    }
}

/**
 * Whether a request that may have reached the service can be looked up again after the app was
 * stopped (#431 rev 4 row 6). Independent of [ProviderCapabilities.supportsAsync], which only
 * says that `poll()` never blocks: Gemini is async locally but has no remote job to query.
 */
enum class RemoteRecovery { NONE, QUERY_BY_PERSISTED_ID }

data class ProviderCapabilities(
    val supportsInstructionEdit: Boolean,
    val supportsMaskEdit: Boolean,
    val maxReferences: Int,
    val maxCandidates: Int,
    val supportsMultiTurn: Boolean,
    val supportsSeed: Boolean,
    val maxInputPixels: Long,
    val maxOutputPixels: Long,
    val aspectRange: AspectRange?,
    val inputFormats: Set<ImageFormat>,
    val outputFormats: Set<ImageFormat>,
    val supportsAsync: Boolean,
    val supportsStreaming: Boolean,
    val estimatedCostPerRequest: CostEstimate? = null,
    val advancedParameters: Map<CapabilityKey, OptionSchema> = emptyMap(),
    val remoteRecovery: RemoteRecovery = RemoteRecovery.NONE
)

enum class OversizePolicy { REJECT, DOWNSCALE }

data class ImageEditRequest(
    val source: EditImage,
    val instruction: String,
    val mask: EditImage? = null,
    val references: List<EditImage> = emptyList(),
    val output: OutputSpec,
    val options: Map<CapabilityKey, OptionValue> = emptyMap(),
    val parentEditId: String? = null,
    val oversizePolicy: OversizePolicy = OversizePolicy.REJECT
)

sealed interface ResizePlan {
    data object Keep : ResizePlan
    data class Downscale(val width: Int, val height: Int) : ResizePlan
}

data class EditExecutionPlan(val source: ResizePlan, val references: List<ResizePlan>)

enum class RejectionReason {
    INVALID_CAPABILITIES, INVALID_DIMENSIONS, EMPTY_SOURCE, EMPTY_INSTRUCTION,
    INSTRUCTION_UNSUPPORTED, MASK_UNSUPPORTED, MASK_MISMATCH,
    REFERENCES_UNSUPPORTED, CANDIDATES_UNSUPPORTED, MULTI_TURN_UNSUPPORTED,
    SEED_UNSUPPORTED, INPUT_FORMAT, OUTPUT_FORMAT, INPUT_PIXELS,
    OUTPUT_PIXELS, OUTPUT_ASPECT, UNKNOWN_OPTION, INVALID_OPTION, UNSAFE_MASK_RESIZE
}

sealed interface Admission {
    data class Accepted(val plan: EditExecutionPlan) : Admission
    data class Rejected(val reason: RejectionReason, val explanation: String) : Admission
}

/**
 * Honest, overflow-safe admission. Downscale is an explicit *plan*, not an implicit mutation:
 * adapters must perform the planned scaling (and may reject if their pipeline cannot).
 * No automatic resizing of masks or output, and no hidden tiling/network fallback.
 */
object ImageEditRequestValidator {
    fun validate(request: ImageEditRequest, caps: ProviderCapabilities): Admission {
        fun reject(reason: RejectionReason, message: String) = Admission.Rejected(reason, message)
        if (caps.maxInputPixels < 1 || caps.maxOutputPixels < 1 || caps.maxCandidates < 1 ||
            caps.maxReferences < 0 || caps.inputFormats.isEmpty() || caps.outputFormats.isEmpty() ||
            (caps.supportsStreaming && !caps.supportsAsync)
        ) return reject(RejectionReason.INVALID_CAPABILITIES, "Provider capability limits are invalid")
        fun good(image: EditImage) = image.handle.isNotBlank() && image.width > 0 && image.height > 0 &&
            (image.pixels.isEmpty() || image.pixels.size.toLong() == image.width.toLong() * image.height)
        if (request.source.handle.isBlank()) return reject(RejectionReason.EMPTY_SOURCE, "Source handle is blank")
        if (request.source.width <= 0 || request.source.height <= 0 ||
            request.output.width <= 0 || request.output.height <= 0
        ) return reject(RejectionReason.INVALID_DIMENSIONS, "Dimensions must be positive")
        if (!good(request.source) || !request.references.all(::good) ||
            (request.mask != null && !good(request.mask))
        ) return reject(RejectionReason.INVALID_DIMENSIONS, "Image dimensions or synthetic pixels are invalid")
        if (request.instruction.isBlank()) return reject(RejectionReason.EMPTY_INSTRUCTION, "Instruction is blank")
        if (!caps.supportsInstructionEdit) return reject(RejectionReason.INSTRUCTION_UNSUPPORTED, "Instruction editing unsupported")
        if (request.mask != null && !caps.supportsMaskEdit)
            return reject(RejectionReason.MASK_UNSUPPORTED, "Masks unsupported")
        if (request.mask != null && (request.mask.width != request.source.width ||
            request.mask.height != request.source.height))
            return reject(RejectionReason.MASK_MISMATCH, "Mask does not align with source")
        if (request.references.size > caps.maxReferences)
            return reject(RejectionReason.REFERENCES_UNSUPPORTED, "Too many reference images")
        if (request.output.candidates !in 1..caps.maxCandidates)
            return reject(RejectionReason.CANDIDATES_UNSUPPORTED, "Candidate count exceeds limit")
        if (request.parentEditId != null && !caps.supportsMultiTurn)
            return reject(RejectionReason.MULTI_TURN_UNSUPPORTED, "Multi-turn unsupported")
        if (request.source.format !in caps.inputFormats ||
            request.references.any { it.format !in caps.inputFormats } ||
            (request.mask != null && request.mask.format != ImageFormat.PNG))
            return reject(RejectionReason.INPUT_FORMAT, "Unsupported image or mask format")
        if (request.output.format !in caps.outputFormats)
            return reject(RejectionReason.OUTPUT_FORMAT, "Unsupported output format")
        if (request.output.width.toLong() * request.output.height > caps.maxOutputPixels)
            return reject(RejectionReason.OUTPUT_PIXELS, "Output resolution exceeds limit")
        if (caps.aspectRange != null &&
            !caps.aspectRange.supports(request.output.width, request.output.height))
            return reject(RejectionReason.OUTPUT_ASPECT, "Output aspect unsupported")

        for ((key, value) in request.options) {
            if (key.name == "seed") {
                if (!caps.supportsSeed) return reject(RejectionReason.SEED_UNSUPPORTED, "Seed unsupported")
                if (value !is OptionValue.IntegerValue)
                    return reject(RejectionReason.INVALID_OPTION, "Seed must be integer")
            } else {
                val schema = caps.advancedParameters[key]
                    ?: return reject(RejectionReason.UNKNOWN_OPTION, "Unknown option")
                if (!schema.accepts(value))
                    return reject(RejectionReason.INVALID_OPTION, "Option outside declared schema")
            }
        }
        fun planFor(image: EditImage): ResizePlan? {
            if (image.width.toLong() * image.height <= caps.maxInputPixels) return ResizePlan.Keep
            if (request.oversizePolicy != OversizePolicy.DOWNSCALE) return null
            val factor = sqrt(caps.maxInputPixels.toDouble() /
                (image.width.toDouble() * image.height.toDouble()))
            var w = floor(image.width * factor).toInt().coerceAtLeast(1)
            var h = floor(image.height * factor).toInt().coerceAtLeast(1)
            while (w.toLong() * h > caps.maxInputPixels) {
                if (w >= h && w > 1) w-- else if (h > 1) h-- else return null
            }
            return ResizePlan.Downscale(w, h)
        }
        val sourcePlan = planFor(request.source)
            ?: return reject(RejectionReason.INPUT_PIXELS, "Source exceeds input limit")
        if (request.mask != null && sourcePlan !is ResizePlan.Keep)
            return reject(RejectionReason.UNSAFE_MASK_RESIZE, "Mask needs aligned resize, unavailable")
        val referencePlans = request.references.map { planFor(it) }
        if (referencePlans.any { it == null })
            return reject(RejectionReason.INPUT_PIXELS, "Reference exceeds input limit")
        return Admission.Accepted(EditExecutionPlan(sourcePlan, referencePlans.filterNotNull()))
    }
}

/**
 * [BILLING] is an empty balance or a payment problem (HTTP 402 or a documented prepaid-exhausted
 * code); [QUOTA] is a rate or quota limit (HTTP 429, Gemini `RESOURCE_EXHAUSTED`) and never means
 * "out of credit" (#431 addendum A2). [CANNOT_CHECK] is a recovery lookup that can no longer be
 * answered for this request (key changed or removed, or the service refused the lookup).
 */
enum class FailureKind { NETWORK, TIMEOUT, AUTH, BILLING, QUOTA, INVALID_REQUEST, SERVER, UNKNOWN, CANNOT_CHECK }

/**
 * Whether the request that could cost money may have reached the service (#431 addendum A3).
 * - [NOT_SENT]: proven never sent, for example a failure before the request body was written
 *   (DNS, connect, TLS) or before submit started at all. Only this allows "Nothing was sent".
 * - [ANSWERED]: the service answered with a definite refusal (an HTTP 4xx other than 408), so it
 *   did not process this edit.
 * - [UNCERTAIN]: anything else after submit may have started. The default, so an adapter that
 *   forgets to say fails closed.
 */
enum class Delivery { NOT_SENT, ANSWERED, UNCERTAIN }

sealed interface EditOutcome {
    data class Success(
        val images: List<EditImage>,
        val providerModel: String,
        val providerVersion: String,
        val metadata: Map<String, String> = emptyMap()
    ) : EditOutcome
    data class Refused(val providerCode: String, val providerMessage: String) : EditOutcome
    data class Failed(
        val kind: FailureKind,
        val retryable: Boolean,
        val message: String = "",
        val delivery: Delivery = Delivery.UNCERTAIN
    ) : EditOutcome
    data object Cancelled : EditOutcome
}

sealed interface JobSnapshot {
    data class Pending(val progress: Double? = null) : JobSnapshot
    data class Completed(val outcome: EditOutcome) : JobSnapshot
}

/** Polling and stream callbacks share a bounded, cancellation-aware contract. */
interface EditJob {
    val id: String
    fun poll(): JobSnapshot
    fun cancel()

    fun await(maxPolls: Int = 100): EditOutcome {
        require(maxPolls > 0)
        repeat(maxPolls) {
            when (val snapshot = poll()) {
                is JobSnapshot.Completed -> return snapshot.outcome
                is JobSnapshot.Pending -> Unit
            }
        }
        cancel()
        return EditOutcome.Failed(FailureKind.TIMEOUT, retryable = true)
    }

    fun stream(maxPolls: Int = 100, onEvent: (JobSnapshot) -> Unit): EditOutcome {
        require(maxPolls > 0)
        repeat(maxPolls) {
            val snapshot = poll()
            onEvent(snapshot)
            if (snapshot is JobSnapshot.Completed) return snapshot.outcome
        }
        cancel()
        val outcome = EditOutcome.Failed(FailureKind.TIMEOUT, retryable = true)
        onEvent(JobSnapshot.Completed(outcome))
        return outcome
    }
}

/**
 * Durable hand-off between a hosted adapter and the caller's journal (#431 rev 4 row 6).
 * - [beforeSend] runs on the worker immediately before the request that may cost money starts.
 *   It must durably record "submit may have been sent" and return true; false aborts with
 *   nothing sent (for example the user cancelled meanwhile).
 * - [remoteAccepted] runs once a queue provider returned a validated remote request id, before
 *   the adapter polls it. It must durably store the id bound to [RemoteHandle.providerId] and
 *   [RemoteHandle.endpoint]; only then may the editor offer "Check again".
 * Neither callback may receive or store a credential.
 */
interface SubmitJournal {
    fun beforeSend(): Boolean
    fun remoteAccepted(handle: RemoteHandle)

    companion object {
        val NONE: SubmitJournal = object : SubmitJournal {
            override fun beforeSend(): Boolean = true
            override fun remoteAccepted(handle: RemoteHandle) = Unit
        }
    }
}

/**
 * A provider-side request that can be queried again: which service and endpoint accepted it, its
 * remote id, and how many images were asked for. [keyFingerprint] is a non-secret fingerprint of
 * the key that submitted it (see `KeyFingerprint`), filled in by the caller, never the key.
 */
data class RemoteHandle(
    val providerId: String,
    val endpoint: String,
    val remoteId: String,
    val candidates: Int = 1,
    val keyFingerprint: String? = null
) {
    override fun toString(): String = "RemoteHandle($providerId, $endpoint)"
}

interface ImageEditProvider {
    val id: String
    val displayName: String
    fun capabilities(): ProviderCapabilities
    /** Invalid requests complete with Failed(INVALID_REQUEST); only the selected image may be supplied. */
    fun submit(request: ImageEditRequest): EditJob

    /** As [submit], reporting the send boundary and any remote id to [journal]. */
    fun submit(request: ImageEditRequest, journal: SubmitJournal): EditJob =
        if (journal.beforeSend()) submit(request) else NotSentJob

    /**
     * Follows an already accepted request without submitting again (no POST). Only providers whose
     * [ProviderCapabilities.remoteRecovery] is [RemoteRecovery.QUERY_BY_PERSISTED_ID] support it;
     * a handle for another provider or endpoint is refused (null), never attached to the wrong job.
     */
    fun resume(handle: RemoteHandle): EditJob? = null
    fun estimateCost(request: ImageEditRequest): CostEstimate? =
        if (ImageEditRequestValidator.validate(request, capabilities()) is Admission.Accepted)
            capabilities().estimatedCostPerRequest else null
}

/** A job that ended before anything was sent, because the journal refused the send. */
private object NotSentJob : EditJob {
    override val id: String = "not-sent"
    override fun poll(): JobSnapshot = JobSnapshot.Completed(EditOutcome.Cancelled)
    override fun cancel() = Unit
}
