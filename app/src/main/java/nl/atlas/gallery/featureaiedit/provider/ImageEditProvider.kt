package nl.atlas.gallery.featureaiedit.provider

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

data class CostEstimate(val currencyCode: String, val minorUnits: Long, val note: String = "")

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
    val advancedParameters: Map<CapabilityKey, OptionSchema> = emptyMap()
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

enum class FailureKind { NETWORK, TIMEOUT, AUTH, BILLING, QUOTA, INVALID_REQUEST, SERVER, UNKNOWN }

sealed interface EditOutcome {
    data class Success(
        val images: List<EditImage>,
        val providerModel: String,
        val providerVersion: String,
        val metadata: Map<String, String> = emptyMap()
    ) : EditOutcome
    data class Refused(val providerCode: String, val providerMessage: String) : EditOutcome
    data class Failed(val kind: FailureKind, val retryable: Boolean, val message: String = "") : EditOutcome
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

interface ImageEditProvider {
    val id: String
    val displayName: String
    fun capabilities(): ProviderCapabilities
    /** Invalid requests complete with Failed(INVALID_REQUEST); only the selected image may be supplied. */
    fun submit(request: ImageEditRequest): EditJob
    fun estimateCost(request: ImageEditRequest): CostEstimate? =
        if (ImageEditRequestValidator.validate(request, capabilities()) is Admission.Accepted)
            capabilities().estimatedCostPerRequest else null
}
