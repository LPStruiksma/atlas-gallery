package nl.atlas.gallery.featureaiedit.provider.local

import java.io.Closeable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import nl.atlas.gallery.featureaiedit.provider.Admission
import nl.atlas.gallery.featureaiedit.provider.CostEstimate
import nl.atlas.gallery.featureaiedit.provider.EditImage
import nl.atlas.gallery.featureaiedit.provider.EditJob
import nl.atlas.gallery.featureaiedit.provider.EditOutcome
import nl.atlas.gallery.featureaiedit.provider.FailureKind
import nl.atlas.gallery.featureaiedit.provider.ImageEditProvider
import nl.atlas.gallery.featureaiedit.provider.ImageEditRequest
import nl.atlas.gallery.featureaiedit.provider.ImageEditRequestValidator
import nl.atlas.gallery.featureaiedit.provider.ImageFormat
import nl.atlas.gallery.featureaiedit.provider.JobSnapshot
import nl.atlas.gallery.featureaiedit.provider.ProviderCapabilities

/**
 * One opened source + mask + output for a job. [source] and [target] may be the same
 * buffer. [commit] produces the result image; [discard] removes anything written.
 */
interface EraseCanvas : Closeable {
    val source: ArgbRegionReader
    val target: ArgbRegionWriter
    /** Null when the request has no mask (nothing to erase). */
    val mask: MaskRegionReader?
    fun commit(format: ImageFormat): EditImage
    fun discard() {}
    override fun close() {}
}

/** Adapters own decoding and image IO (the contract keeps images as handles). */
fun interface EraseImageIo {
    fun open(source: EditImage, mask: EditImage?, jobId: String): EraseCanvas
}

/** Thrown by an engine or IO to report a typed failure (fixtures and IO errors). */
class EraseEngineFailure(val kind: FailureKind, val retryable: Boolean, message: String) : RuntimeException(message)

/** Fixture-only: the local engine itself never refuses content. */
class EraseEngineRefusal(val code: String, message: String) : RuntimeException(message)

/**
 * AIE-22 (#434): free, private, on-device Erase. Removes the masked region with LaMa on
 * ONNX Runtime; the photo never leaves the phone and no network code exists on this path.
 *
 * - Needs a mask: white/opaque mask pixels are erased, everything else stays bit-identical
 *   (see [AndroidEraseImageIo] for the PNG convention). No mask, or an empty one, returns
 *   the source unchanged.
 * - The instruction is optional and ignored ("erase" is the only operation). The shared
 *   validator requires `supportsInstructionEdit`, so it is declared, and a blank
 *   instruction is admitted.
 * - Output is always the source's own resolution, as PNG (lossless, so kept pixels survive).
 * - One erase runs at a time on [executor]; the model session is opened per job and closed
 *   after it, so its ~0.5 GB of runtime memory is released between edits.
 */
class LocalEraseProvider(
    private val inpainterFactory: () -> LamaInpainter,
    private val io: EraseImageIo,
    private val engine: LocalEraseEngine = LocalEraseEngine(),
    private val executor: ExecutorService = defaultExecutor(),
    private val pollWaitMillis: Long = DEFAULT_POLL_WAIT_MILLIS,
) : ImageEditProvider {
    override val id = ID
    override val displayName = "On-device Erase (offline, free)"
    private val nextJob = AtomicLong(1)

    override fun capabilities() = ProviderCapabilities(
        supportsInstructionEdit = true,
        supportsMaskEdit = true,
        maxReferences = 0,
        maxCandidates = 1,
        supportsMultiTurn = false,
        supportsSeed = false,
        maxInputPixels = MAX_PIXELS,
        maxOutputPixels = MAX_PIXELS,
        aspectRange = null,
        inputFormats = setOf(ImageFormat.PNG, ImageFormat.JPEG, ImageFormat.WEBP),
        outputFormats = setOf(ImageFormat.PNG),
        supportsAsync = true,
        supportsStreaming = true,
        estimatedCostPerRequest = CostEstimate("EUR", 0, "On-device; free, nothing is uploaded"),
    )

    override fun submit(request: ImageEditRequest): EditJob {
        val jobId = "$id-${nextJob.getAndIncrement()}"
        val admitted = request.copy(instruction = request.instruction.ifBlank { "erase" })
        val admission = ImageEditRequestValidator.validate(admitted, capabilities())
        if (admission is Admission.Rejected) return LocalEraseJob.done(jobId, invalid(admission.reason.name))
        if (request.output.width != request.source.width || request.output.height != request.source.height) {
            return LocalEraseJob.done(jobId, invalid("OUTPUT_NOT_SOURCE_SIZE"))
        }
        val cancelled = AtomicBoolean(false)
        val progress = AtomicReference(0.0)
        val running = AtomicReference<LamaInpainter?>(null)
        val signal = CancelSignal { cancelled.get() || Thread.currentThread().isInterrupted }
        val future = executor.submit<EditOutcome> {
            run(jobId, request, signal, progress, running)
        }
        return LocalEraseJob(jobId, future, cancelled, progress, running, pollWaitMillis)
    }

    override fun estimateCost(request: ImageEditRequest): CostEstimate? =
        if (ImageEditRequestValidator.validate(
                request.copy(instruction = request.instruction.ifBlank { "erase" }), capabilities()
            ) is Admission.Accepted
        ) capabilities().estimatedCostPerRequest else null

    private fun run(
        jobId: String, request: ImageEditRequest, cancel: CancelSignal,
        progress: AtomicReference<Double>, running: AtomicReference<LamaInpainter?>,
    ): EditOutcome {
        var canvas: EraseCanvas? = null
        var committed = false
        try {
            canvas = io.open(request.source, request.mask, jobId)
            val mask = canvas.mask
            val report = if (mask == null) EraseReport(null, null, 1.0, 0, 0) else {
                val bounds = engine.maskBounds(mask, cancel)
                if (bounds == null) EraseReport(null, null, 1.0, 0, 0) else {
                    if (cancel.isCancelled()) throw EraseCancelled()
                    inpainterFactory().use { inpainter ->
                        running.set(inpainter)
                        try {
                            engine.erase(canvas.source, mask, canvas.target, inpainter, cancel) { progress.set(it) }
                        } finally { running.set(null) }
                    }
                }
            }
            if (cancel.isCancelled()) throw EraseCancelled()
            val image = canvas.commit(request.output.format)
            committed = true
            return EditOutcome.Success(
                images = listOf(image),
                providerModel = LamaModel.MODEL_NAME,
                providerVersion = LamaModel.MODEL_VERSION,
                metadata = buildMap {
                    put("on_device", "true")
                    put("operation", "erase")
                    put("erased_pixels", report.erasedPixels.toString())
                    report.window?.let { put("window", "${it.left},${it.top},${it.width}x${it.height}") }
                    put("model_scale", "%.4f".format(java.util.Locale.ROOT, report.modelScale))
                    put("inference_ms", report.inferenceMillis.toString())
                },
            )
        } catch (_: EraseCancelled) {
            return EditOutcome.Cancelled
        } catch (error: EraseEngineFailure) {
            return EditOutcome.Failed(error.kind, error.retryable, error.message ?: "")
        } catch (error: EraseEngineRefusal) {
            return EditOutcome.Refused(error.code, error.message ?: "")
        } catch (error: OutOfMemoryError) {
            return EditOutcome.Failed(FailureKind.UNKNOWN, retryable = false, message = "Not enough memory for this erase")
        } catch (error: Exception) {
            if (cancel.isCancelled()) return EditOutcome.Cancelled
            return EditOutcome.Failed(FailureKind.UNKNOWN, retryable = false, message = error.javaClass.simpleName)
        } finally {
            if (!committed) runCatching { canvas?.discard() }
            runCatching { canvas?.close() }
        }
    }

    companion object {
        const val ID = "atlas-on-device-erase-lama"
        /** 64 MP: covers 50 MP phone photos with margin; memory is bounded by banding. */
        const val MAX_PIXELS = 64_000_000L
        const val DEFAULT_POLL_WAIT_MILLIS = 500L

        private fun invalid(reason: String) =
            EditOutcome.Failed(FailureKind.INVALID_REQUEST, retryable = false, message = reason)

        fun defaultExecutor(): ExecutorService = Executors.newSingleThreadExecutor { task ->
            Thread(task, "atlas-local-erase").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
        }
    }
}

/**
 * Poll-based job over a background future. Each [poll] waits at most [pollWaitMillis] for
 * the result, so `await(maxPolls)` is bounded by `maxPolls × pollWaitMillis`.
 * Cancellation is idempotent and terminal: it stops the band loops, aborts a running
 * inference through ONNX Runtime, and discards any partial output.
 */
internal class LocalEraseJob(
    override val id: String,
    private val future: Future<EditOutcome>?,
    private val cancelled: AtomicBoolean,
    private val progress: AtomicReference<Double>,
    private val running: AtomicReference<LamaInpainter?>,
    private val pollWaitMillis: Long,
    private var completed: JobSnapshot.Completed? = null,
) : EditJob {

    @Synchronized override fun poll(): JobSnapshot {
        completed?.let { return it }
        if (cancelled.get()) return finish(EditOutcome.Cancelled)
        val f = future ?: return finish(EditOutcome.Failed(FailureKind.UNKNOWN, false, "No work"))
        return try {
            finish(f.get(pollWaitMillis, TimeUnit.MILLISECONDS))
        } catch (_: TimeoutException) {
            JobSnapshot.Pending(progress.get())
        } catch (_: CancellationException) {
            finish(EditOutcome.Cancelled)
        } catch (error: ExecutionException) {
            finish(EditOutcome.Failed(FailureKind.UNKNOWN, false, error.cause?.javaClass?.simpleName ?: ""))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            JobSnapshot.Pending(progress.get())
        }
    }

    @Synchronized override fun cancel() {
        if (completed != null) return
        if (cancelled.getAndSet(true)) return
        running.get()?.let { runCatching { it.terminate() } }
        future?.cancel(true)
    }

    private fun finish(outcome: EditOutcome): JobSnapshot.Completed {
        val result = JobSnapshot.Completed(if (cancelled.get()) EditOutcome.Cancelled else outcome)
        completed = result
        return result
    }

    companion object {
        fun done(id: String, outcome: EditOutcome) = LocalEraseJob(
            id, null, AtomicBoolean(false), AtomicReference(1.0), AtomicReference(null), 0,
            JobSnapshot.Completed(outcome),
        )
    }
}

/**
 * JVM/synthetic IO: images carry their pixels in [EditImage.pixels] (ARGB). A mask pixel
 * is erased when it is opaque and bright (alpha >= 128 and max(r,g,b) >= 128), the same
 * rule as [AndroidEraseImageIo].
 */
class SyntheticEraseImageIo : EraseImageIo {
    override fun open(source: EditImage, mask: EditImage?, jobId: String): EraseCanvas {
        if (source.pixels.isEmpty()) throw EraseEngineFailure(FailureKind.INVALID_REQUEST, false, "Synthetic source has no pixels")
        val w = source.width
        val h = source.height
        val buffer = source.pixels.toIntArray()
        val maskBits = mask?.let { m ->
            if (m.pixels.isEmpty()) throw EraseEngineFailure(FailureKind.INVALID_REQUEST, false, "Synthetic mask has no pixels")
            ByteArray(w * h) { i -> if (EraseMaskRule.isErase(m.pixels[i])) 1 else 0 }
        }
        val regions = IntArrayRegions(buffer, w, h)
        return object : EraseCanvas {
            override val source: ArgbRegionReader = regions
            override val target: ArgbRegionWriter = regions
            override val mask = maskBits?.let { ByteArrayMask(it, w, h) }
            override fun commit(format: ImageFormat) =
                EditImage("synthetic://erase/$jobId", w, h, format, buffer.toList())
        }
    }
}

/** The one mask convention for this provider (synthetic and Android). */
object EraseMaskRule {
    fun isErase(argb: Int): Boolean {
        val a = (argb ushr 24) and 255
        val r = (argb ushr 16) and 255
        val g = (argb ushr 8) and 255
        val b = argb and 255
        return a >= 128 && maxOf(r, g, b) >= 128
    }
}

/** Row-major ARGB buffer usable as both reader and writer (writes only changed pixels). */
class IntArrayRegions(private val data: IntArray, override val width: Int, override val height: Int) :
    ArgbRegionReader, ArgbRegionWriter {
    init { require(data.size == width * height) }
    override fun read(rect: PixelRect, out: IntArray) {
        for (y in rect.top until rect.bottom) {
            System.arraycopy(data, y * width + rect.left, out, (y - rect.top) * rect.width, rect.width)
        }
    }
    override fun write(rect: PixelRect, argb: IntArray, changed: BooleanArray) {
        for (y in rect.top until rect.bottom) for (x in rect.left until rect.right) {
            val i = (y - rect.top) * rect.width + (x - rect.left)
            if (changed[i]) data[y * width + x] = argb[i]
        }
    }
}

class ByteArrayMask(private val data: ByteArray, override val width: Int, override val height: Int) : MaskRegionReader {
    init { require(data.size == width * height) }
    override fun read(rect: PixelRect, out: ByteArray) {
        for (y in rect.top until rect.bottom) {
            System.arraycopy(data, y * width + rect.left, out, (y - rect.top) * rect.width, rect.width)
        }
    }
}
