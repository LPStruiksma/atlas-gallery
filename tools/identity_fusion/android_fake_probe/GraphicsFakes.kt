package android.graphics

import android.content.ContentResolver
import android.net.Uri

class Bitmap(val width: Int, val height: Int, val allocationByteCount: Int) {
    var isRecycled = false
    fun recycle() { isRecycled = true }
}
class ColorSpace {
    enum class Named { SRGB }
    companion object { fun get(n: Named) = ColorSpace() }
}
class Size(val width: Int, val height: Int)
class ImageDecoder {
    var targetW = -1
    var targetH = -1
    var chosenAllocator = -1
    var color: ColorSpace? = null
    fun setAllocator(v: Int) { chosenAllocator = v }
    fun setTargetColorSpace(v: ColorSpace) { color = v }
    fun setTargetSize(w: Int, h: Int) { targetW = w; targetH = h }
    class ImageInfo(val size: Size)
    class Source
    fun interface OnHeaderDecodedListener {
        fun onHeaderDecoded(decoder: ImageDecoder, info: ImageInfo, source: Source)
    }
    companion object {
        const val ALLOCATOR_SOFTWARE = 1
        var sourceWidth = 4032
        var sourceHeight = 3024
        var actualBytesPerPixel = 4
        var overrideBitmap: Bitmap? = null
        var lastBitmap: Bitmap? = null
        var lastDecoder: ImageDecoder? = null
        var fail: Exception? = null
        fun createSource(cr: ContentResolver, uri: Uri): Source = Source()
        fun decodeBitmap(src: Source, listener: OnHeaderDecodedListener): Bitmap {
            fail?.let { throw it }
            val d = ImageDecoder()
            lastDecoder = d
            listener.onHeaderDecoded(d, ImageInfo(Size(sourceWidth, sourceHeight)), src)
            val b = overrideBitmap ?: Bitmap(d.targetW, d.targetH,
                d.targetW * d.targetH * actualBytesPerPixel)
            lastBitmap = b
            return b
        }
    }
}
