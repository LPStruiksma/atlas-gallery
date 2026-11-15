package nl.atlas.gallery.featureaiedit.provider

import java.util.concurrent.atomic.AtomicLong

/**
 * Fully synthetic provider for JVM tests and offline UI development. There is no decoder,
 * MediaStore access, network stack, clock, thread, credential or paid inference call.
 * Pending polls simulate latency deterministically; callers own scheduling between polls.
 */
enum class MockMode { TINT, CROP, LABEL }

data class MockProviderConfig(
    val mode: MockMode = MockMode.TINT,
    val pendingPolls: Int = 0,
    val forcedOutcome: EditOutcome? = null,
    val maxPixels: Long = 4096L,
    val maxCandidates: Int = 2
) {
    init {
        require(pendingPolls >= 0)
        require(maxPixels in 1L..262_144L) { "Mock pixel budget is intentionally bounded" }
        require(maxCandidates in 1..4) { "Mock candidate count is intentionally bounded" }
        require(forcedOutcome !is EditOutcome.Success) { "Mock successful output must be generated within caps" }
    }
}

class MockImageEditProvider(private val config: MockProviderConfig = MockProviderConfig()) : ImageEditProvider {
    override val id = "atlas-synthetic-mock"
    override val displayName = "Synthetic Mock (offline)"
    private val nextJob = AtomicLong(1)

    override fun capabilities() = ProviderCapabilities(
        supportsInstructionEdit = true,
        supportsMaskEdit = false,
        maxReferences = 0,
        maxCandidates = config.maxCandidates,
        supportsMultiTurn = false,
        supportsSeed = false,
        maxInputPixels = config.maxPixels,
        maxOutputPixels = config.maxPixels,
        aspectRange = null,
        inputFormats = setOf(ImageFormat.PNG, ImageFormat.JPEG, ImageFormat.WEBP),
        outputFormats = setOf(ImageFormat.PNG),
        supportsAsync = config.pendingPolls > 0,
        supportsStreaming = config.pendingPolls > 0,
        estimatedCostPerRequest = CostEstimate("USD", 0, "Synthetic mock; no charge")
    )

    override fun submit(request: ImageEditRequest): EditJob {
        val jobId = "$id-${nextJob.getAndIncrement()}"
        val admission = ImageEditRequestValidator.validate(request, capabilities())
        if (admission is Admission.Rejected)
            return MockEditJob(jobId, EditOutcome.Failed(
                FailureKind.INVALID_REQUEST, retryable = false, message = admission.reason.name
            ), 0)

        val outcome = config.forcedOutcome ?: syntheticSuccess(request, (admission as Admission.Accepted).plan)
        return MockEditJob(jobId, outcome, config.pendingPolls)
    }

    private fun syntheticSuccess(request: ImageEditRequest, plan: EditExecutionPlan): EditOutcome.Success {
        val output = request.output
        val images = (0 until output.candidates).map { candidate ->
            val pixels = List(output.width * output.height) { index ->
                val x = index % output.width
                val y = index / output.width
                syntheticPixel(request.source, plan.source, output.width, output.height, x, y, candidate)
            }
            EditImage("mock://candidate/${candidate + 1}", output.width, output.height, output.format, pixels)
        }
        return EditOutcome.Success(
            images, providerModel = "atlas-synthetic-${config.mode.name.lowercase()}",
            providerVersion = "1",
            metadata = mapOf("synthetic" to "true", "operation" to config.mode.name.lowercase())
        )
    }

    private fun syntheticPixel(
        source: EditImage, plan: ResizePlan, outputW: Int, outputH: Int,
        x: Int, y: Int, candidate: Int
    ): Int {
        val effectiveW = if (plan is ResizePlan.Downscale) plan.width else source.width
        val effectiveH = if (plan is ResizePlan.Downscale) plan.height else source.height
        val crop = config.mode == MockMode.CROP
        val fractionX = (x + 0.5) / outputW
        val fractionY = (y + 0.5) / outputH
        val px = if (crop) 0.25 + 0.5 * fractionX else fractionX
        val py = if (crop) 0.25 + 0.5 * fractionY else fractionY
        val plannedX = (px * effectiveW).toInt().coerceIn(0, effectiveW - 1)
        val plannedY = (py * effectiveH).toInt().coerceIn(0, effectiveH - 1)
        val sx = ((plannedX + 0.5) * source.width / effectiveW).toInt().coerceIn(0, source.width - 1)
        val sy = ((plannedY + 0.5) * source.height / effectiveH).toInt().coerceIn(0, source.height - 1)
        val base = if (source.pixels.isNotEmpty()) {
            source.pixels[sy * source.width + sx]
        } else {
            // Stable opaque procedural pixels: no real image is needed or accessed.
            val v = (sx * 31 + sy * 17 + source.handle.hashCode()) and 255
            (0xff000000L or (v.toLong() shl 16) or
                (((v * 3) and 255).toLong() shl 8) or ((v * 7) and 255).toLong()).toInt()
        }
        return when (config.mode) {
            MockMode.CROP -> base
            MockMode.TINT -> {
                val a = base and 0xff000000.toInt()
                val r = ((base ushr 16) and 255)
                val g = ((base ushr 8) and 255)
                val b = (base and 255)
                a or ((r + 48 + candidate * 4).coerceAtMost(255) shl 16) or
                    ((g + 12).coerceAtMost(255) shl 8) or b
            }
            MockMode.LABEL -> labelPixel(x, y, base)
        }
    }

    private fun labelPixel(x: Int, y: Int, base: Int): Int {
        // Tiny recognizable "AI" glyph overlay. No platform fonts or text rasterizer needed.
        val a = listOf("010", "101", "111", "101", "101")
        val i = listOf("111", "010", "010", "010", "111")
        if (y !in 0..4 || x !in 0..6) return base
        val glyphOn = when (x) {
            in 0..2 -> a[y][x] == '1'
            in 4..6 -> i[y][x - 4] == '1'
            else -> false
        }
        return if (glyphOn) 0xffffff00.toInt() else 0xff202020.toInt()
    }
}

private class MockEditJob(
    override val id: String,
    private val outcome: EditOutcome,
    pendingPolls: Int
) : EditJob {
    private var remaining = pendingPolls
    private val originalPolls = pendingPolls
    private var cancelled = false
    private var completed: JobSnapshot.Completed? = null

    @Synchronized override fun poll(): JobSnapshot {
        completed?.let { return it }
        if (cancelled) {
            val result = JobSnapshot.Completed(EditOutcome.Cancelled)
            completed = result
            return result
        }
        if (remaining > 0) {
            remaining--
            return JobSnapshot.Pending((originalPolls - remaining).toDouble() / (originalPolls + 1))
        }
        val result = JobSnapshot.Completed(outcome)
        completed = result
        return result
    }

    @Synchronized override fun cancel() {
        if (completed == null) cancelled = true
    }
}
