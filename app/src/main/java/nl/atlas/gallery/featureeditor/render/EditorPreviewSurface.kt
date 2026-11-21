package nl.atlas.gallery.featureeditor.render

import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RenderNode
import android.os.Build
import android.os.SystemClock
import android.os.Trace
import android.view.Surface
import nl.atlas.gallery.featureeditor.engine.RenderPlan
import java.util.concurrent.Executor
import kotlin.math.min

/**
 * The live editor preview (EDIT-2): renders a preview-size [RenderPlan] straight into a
 * [Surface] (a `SurfaceView` or `TextureView` that EDIT-4 owns) on the GPU, once per slider or
 * gesture change. It draws the same [SegmentProgram] as export, through the same AGSL pass, so the
 * preview shows what Save writes.
 *
 * Call [render] from one thread (the editor's render thread or main thread) with a plan built at
 * display size (`RenderPlan.build(recipe, maxLongEdge = surface long edge)`) and a proxy
 * [SourceProvider] (a downsampled decode). The output is centred and fitted into the surface.
 * Below API 33 the CPU backend renders a bitmap that is drawn instead (correct, slower).
 */
class EditorPreviewSurface(
    surface: Surface,
    private val width: Int,
    private val height: Int,
    assets: EditorAssets,
    /** sRGB unless the window is in wide colour gamut mode. */
    private val outputSpace: PixelSpace = PixelSpace.SRGB,
) : AutoCloseable {
    private val renderer = HardwareRenderer().apply {
        setSurface(surface)
        isOpaque = false
        setName("atlas-edit2-preview")
    }
    private val node = RenderNode("atlas-edit2-preview").apply { setPosition(0, 0, width, height) }
    private val gpu: AgslProgram? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) AgslProgram() else null
    private val inputs = PlanRenderer(assets)
    private val paint = Paint()
    private val callbacks = Executor { it.run() }

    /** Called with the time from [render] to the frame's GPU commit, in nanoseconds. */
    var onFrameCommitted: ((renderNanos: Long) -> Unit)? = null

    /** Draws [plan] from [source]. Returns the HardwareRenderer sync result. */
    fun render(plan: RenderPlan, source: SourceProvider): Int {
        val start = SystemClock.elapsedRealtimeNanos()
        Trace.beginSection("edit2_preview_frame")
        try {
            val program = SegmentProgram.of(plan)
            val ow = program.outputWidth; val oh = program.outputHeight
            val fit = min(1f, min(width.toFloat() / ow, height.toFloat() / oh))
            val dx = (width - ow * fit) / 2f; val dy = (height - oh * fit) / 2f
            val whole = IntRect.size(ow, oh)
            val canvas = node.beginRecording(width, height)
            try {
                canvas.drawColor(0, PorterDuff.Mode.CLEAR)
                canvas.translate(dx, dy)
                canvas.scale(fit, fit)
                if (gpu != null) {
                    val prepared = inputs.prepare(program, source, whole, inputs.regionScaleFor(program), strict = false)
                    if (prepared != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        paint.shader = gpu.bind(program, prepared.region, prepared.lut, outputSpace)
                        canvas.drawRect(0f, 0f, ow.toFloat(), oh.toFloat(), paint)
                    }
                    inputs.drawOverlays(canvas, program, whole, strict = false)
                } else {
                    val bitmap = inputs.renderTile(plan, source, whole, outputSpace, strict = false)
                    canvas.drawBitmap(bitmap, 0f, 0f, null)
                }
            } finally {
                node.endRecording()
            }
            renderer.setContentRoot(node)
            val listener = onFrameCommitted
            val request = renderer.createRenderRequest().setVsyncTime(System.nanoTime())
            if (listener != null) {
                request.setFrameCommitCallback(callbacks) { listener(SystemClock.elapsedRealtimeNanos() - start) }
            }
            return request.syncAndDraw()
        } finally {
            Trace.endSection()
        }
    }

    override fun close() {
        renderer.destroy()
        inputs.close()
        node.discardDisplayList()
    }
}
