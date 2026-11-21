package nl.atlas.gallery.featureeditor.render

import android.graphics.Bitmap
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import java.nio.ByteBuffer

/** Executes a [SegmentProgram] pixel pass for one output tile. */
interface PixelBackend : AutoCloseable {
    val name: String

    /**
     * Renders [tile] (output pixels) from [region] into a new mutable `ARGB_8888` bitmap of the
     * tile's size, labelled with [outputSpace].
     */
    fun render(program: SegmentProgram, region: SourceRegion, tile: IntRect, lut: Lut3D?, outputSpace: PixelSpace): Bitmap

    override fun close() = Unit

    companion object {
        /** The GPU backend where AGSL exists (API 33+), else the CPU reference. */
        fun best(): PixelBackend =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) AgslPixelBackend() else CpuPixelBackend()
    }
}

/** [CpuRenderer] on Android bitmaps: correct everywhere, used below API 33 and as the test oracle. */
class CpuPixelBackend : PixelBackend {
    override val name = "cpu"

    override fun render(program: SegmentProgram, region: SourceRegion, tile: IntRect, lut: Lut3D?, outputSpace: PixelSpace): Bitmap {
        val src = region.bitmap
        val bytes = ByteBuffer.allocate(src.byteCount)
        src.copyPixelsToBuffer(bytes)
        val rgba = RgbaRegion(src.width, src.height, bytes.array(), region.originX, region.originY, region.scale, region.space)
        val out = CpuRenderer.render(program, rgba, tile, lut, outputSpace)
        val bitmap = Bitmap.createBitmap(tile.width, tile.height, Bitmap.Config.ARGB_8888, true, RenderBitmaps.colorSpace(outputSpace))
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(out))
        return bitmap
    }
}

/**
 * GPU backend: draws the [AgslProgram] through a private [HardwareRenderer] into an
 * [ImageReader] surface and reads the tile back. Nothing is colour-managed on the way: the
 * surface is 8-bit and the shader writes final encoded values, which are labelled with the
 * output space on readback.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class AgslPixelBackend : PixelBackend {
    override val name = "agsl"
    private val program = AgslProgram()
    private val paint = Paint()
    private var reader: ImageReader? = null
    private var renderer: HardwareRenderer? = null
    private var width = 0
    private var height = 0

    private fun ensure(w: Int, h: Int) {
        if (reader != null && width >= w && height >= h) return
        closeTarget()
        width = maxOf(w, width); height = maxOf(h, height)
        val r = ImageReader.newInstance(
            width, height, PixelFormat.RGBA_8888, 2,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT,
        )
        reader = r
        renderer = HardwareRenderer().apply {
            setSurface(r.surface)
            isOpaque = false
            setName("atlas-edit2-tiles")
        }
    }

    override fun render(program: SegmentProgram, region: SourceRegion, tile: IntRect, lut: Lut3D?, outputSpace: PixelSpace): Bitmap {
        require(!tile.isEmpty) { "Empty tile" }
        ensure(tile.width, tile.height)
        val node = RenderNode("atlas-edit2-tile").apply { setPosition(0, 0, width, height) }
        val canvas = node.beginRecording(width, height)
        try {
            canvas.drawColor(0, PorterDuff.Mode.CLEAR)
            canvas.clipRect(0, 0, tile.width, tile.height)
            canvas.translate(-tile.left.toFloat(), -tile.top.toFloat())
            paint.shader = this.program.bind(program, region, lut, outputSpace)
            canvas.drawRect(tile.left.toFloat(), tile.top.toFloat(), tile.right.toFloat(), tile.bottom.toFloat(), paint)
        } finally {
            node.endRecording()
        }
        val r = requireNotNull(reader)
        val hw = requireNotNull(renderer)
        hw.setContentRoot(node)
        hw.createRenderRequest().setWaitForPresent(true).syncAndDraw()
        val image = acquire(r)
        try {
            val buffer = requireNotNull(image.hardwareBuffer) { "No hardware buffer" }
            try {
                val wrapped = requireNotNull(Bitmap.wrapHardwareBuffer(buffer, RenderBitmaps.colorSpace(outputSpace))) {
                    "Cannot wrap the tile buffer"
                }
                // Read back immutably first: on the API 35 emulator a direct HARDWARE -> mutable
                // copy came back fully transparent (diagnosed for #632), the immutable copy does not.
                val readBack = wrapped.copy(Bitmap.Config.ARGB_8888, false)
                wrapped.recycle()
                val cropped = if (tile.width == width && tile.height == height) readBack
                else Bitmap.createBitmap(readBack, 0, 0, tile.width, tile.height)
                val out = cropped.copy(Bitmap.Config.ARGB_8888, true)
                if (cropped !== readBack) cropped.recycle()
                readBack.recycle()
                return out
            } finally {
                buffer.close()
            }
        } finally {
            image.close()
        }
    }

    private fun acquire(r: ImageReader): android.media.Image {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (true) {
            r.acquireLatestImage()?.let { return it }
            check(SystemClock.uptimeMillis() < deadline) { "GPU tile did not arrive" }
            Thread.sleep(1)
        }
    }

    private fun closeTarget() {
        renderer?.destroy(); renderer = null
        reader?.close(); reader = null
    }

    override fun close() = closeTarget()
}
