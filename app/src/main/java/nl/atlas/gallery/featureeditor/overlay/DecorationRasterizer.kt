package nl.atlas.gallery.featureeditor.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import nl.atlas.gallery.featureeditor.engine.RenderStage
import kotlin.math.min

/** Bitmap source is EDIT-3's content-addressed asset store, never a file path in the recipe. */
fun interface DecorationBitmapResolver {
    fun resolve(sha256: String): Bitmap?
}

/**
 * CPU reference for EDIT-2's RenderStage.Overlays, at preview OR export size.
 *
 * Call this AFTER geometry/adjustment/LUT stages. It draws onto the supplied output Canvas,
 * preserving its existing pixels. All decorations are drawn into one transparent saveLayer;
 * PorterDuff.CLEAR erases earlier decorations in that layer, never the underlying photograph.
 * Missing sticker pixels are an error, not silently omitted from a saved export.
 */
object DecorationRasterizer {
    fun draw(
        canvas: Canvas,
        stage: RenderStage.Overlays,
        sourceWidth: Int,
        sourceHeight: Int,
        outputWidth: Int,
        outputHeight: Int,
        bitmaps: DecorationBitmapResolver,
    ) {
        require(outputWidth > 0 && outputHeight > 0) { "Invalid decoration output size" }
        val space = DecorationSpace(sourceWidth, sourceHeight, stage.sourceToOutput)
        val bounds = RectF(0f, 0f, outputWidth.toFloat(), outputHeight.toFloat())
        val isolated = canvas.saveLayer(bounds, null)
        try {
            stage.layers.forEach { raw ->
                val layer = DecorationCodec.decode(raw)
                val p = layer.placement
                // The eraser must act on the shared decoration layer, not a nested layer.
                val clearing = layer is StrokeLayer && layer.mode == StrokeMode.ERASER
                val alpha = (p.opacity * 255.0).toInt().coerceIn(0, 255)
                if (alpha == 0) return@forEach
                val layerSave = if (clearing) canvas.save() else canvas.saveLayerAlpha(bounds, alpha)
                try {
                    val values = space.localToOutputMatrix(p).values()
                    val transform = Matrix().apply {
                        setValues(FloatArray(9) { values[it].toFloat() })
                    }
                    canvas.concat(transform)
                    when (layer) {
                        is StrokeLayer -> stroke(canvas, layer)
                        is ShapeLayer -> shape(canvas, layer)
                        is TextLayer -> text(canvas, layer)
                        is EmojiLayer -> emoji(canvas, layer)
                        is StickerLayer -> sticker(canvas, layer, bitmaps)
                    }
                } finally {
                    canvas.restoreToCount(layerSave)
                }
            }
        } finally {
            canvas.restoreToCount(isolated)
        }
    }

    private fun inkPaint(argb: Int, width: Double, fill: Boolean = false) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = argb
            style = if (fill) Paint.Style.FILL else Paint.Style.STROKE
            strokeWidth = width.toFloat()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

    private fun stroke(canvas: Canvas, layer: StrokeLayer) {
        val erasing = layer.mode == StrokeMode.ERASER
        val paint = inkPaint(if (erasing) Color.BLACK else layer.colorArgb, layer.width)
        if (erasing) paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        val points = DecorationGeometry.smooth(layer.points)
        if (points.size == 1) {
            paint.style = Paint.Style.FILL
            canvas.drawCircle(points[0].x.toFloat(), points[0].y.toFloat(),
                (layer.width * (points[0].pressure ?: 1.0) / 2).toFloat(), paint)
            return
        }
        if (points.any { it.pressure != null }) {
            // Pressure is optional per point. Draw a smooth variable-width ribbon.
            points.zipWithNext().forEach { (a, b) ->
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = (layer.width * ((a.pressure ?: 1.0) + (b.pressure ?: 1.0)) / 2).toFloat()
                canvas.drawLine(a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat(), paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(b.x.toFloat(), b.y.toFloat(),
                    (layer.width * (b.pressure ?: 1.0) / 2).toFloat(), paint)
            }
        } else {
            val path = Path().apply {
                moveTo(points.first().x.toFloat(), points.first().y.toFloat())
                points.drop(1).forEach { lineTo(it.x.toFloat(), it.y.toFloat()) }
            }
            canvas.drawPath(path, paint)
        }
        paint.xfermode = null
    }

    private fun shape(canvas: Canvas, layer: ShapeLayer) {
        val a = layer.start; val b = layer.end
        val paint = inkPaint(layer.colorArgb, layer.width, layer.filled)
        val l = minOf(a.x, b.x).toFloat()
        val t = minOf(a.y, b.y).toFloat()
        val r = maxOf(a.x, b.x).toFloat()
        val bottom = maxOf(a.y, b.y).toFloat()
        when (layer.shape) {
            ShapeType.LINE -> canvas.drawLine(a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat(), paint)
            ShapeType.ARROW -> {
                paint.style = Paint.Style.STROKE
                canvas.drawLine(a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat(), paint)
                DecorationGeometry.arrowHead(a, b, layer.width).forEach { (tip, tail) ->
                    canvas.drawLine(tip.x.toFloat(), tip.y.toFloat(), tail.x.toFloat(), tail.y.toFloat(), paint)
                }
            }
            ShapeType.RECTANGLE -> canvas.drawRect(l, t, r, bottom, paint)
            ShapeType.ELLIPSE -> canvas.drawOval(l, t, r, bottom, paint)
        }
    }

    private fun text(canvas: Canvas, layer: TextLayer) {
        val lines = layer.text.split('\n')
        val left = -layer.boxWidth / 2
        val top = -layer.boxHeight / 2
        val right = layer.boxWidth / 2
        val bottom = layer.boxHeight / 2
        if (layer.background != TextBackground.NONE) {
            val background = inkPaint(layer.backgroundArgb, 0.0, fill = true)
            val rect = RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
            if (layer.background == TextBackground.ROUNDED) {
                canvas.drawRoundRect(rect, (layer.size * 0.25).toFloat(),
                    (layer.size * 0.25).toFloat(), background)
            } else {
                canvas.drawRect(rect, background)
            }
        }
        val paint = inkPaint(layer.colorArgb, 0.0, fill = true).apply {
            textSize = layer.size.toFloat()
            typeface = when (layer.face) {
                TextFace.SANS -> Typeface.create("sans-serif", Typeface.NORMAL)
                TextFace.SERIF -> Typeface.create("serif", Typeface.NORMAL)
                TextFace.MONO -> Typeface.create("monospace", Typeface.NORMAL)
                TextFace.SYSTEM -> Typeface.DEFAULT
            }
            textAlign = when (layer.align) {
                TextAlign.LEFT -> Paint.Align.LEFT
                TextAlign.CENTER -> Paint.Align.CENTER
                TextAlign.RIGHT -> Paint.Align.RIGHT
            }
        }
        val x = when (layer.align) {
            TextAlign.LEFT -> left
            TextAlign.CENTER -> 0.0
            TextAlign.RIGHT -> right
        }
        val saved = canvas.save()
        try {
            canvas.clipRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
            lines.forEachIndexed { index, line ->
                canvas.drawText(line, x.toFloat(),
                    (top + (index + 1) * layer.size * 1.25 - layer.size * 0.2).toFloat(), paint)
            }
        } finally {
            canvas.restoreToCount(saved)
        }
    }

    private fun emoji(canvas: Canvas, layer: EmojiLayer) {
        val paint = inkPaint(Color.WHITE, 0.0, fill = true).apply {
            textSize = layer.size.toFloat()
            typeface = Typeface.DEFAULT
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(layer.glyph, 0f, (layer.size * 0.33).toFloat(), paint)
    }

    private fun sticker(canvas: Canvas, layer: StickerLayer, bitmaps: DecorationBitmapResolver) {
        val bitmap = bitmaps.resolve(layer.contentHash)
            ?: throw IllegalStateException("Sticker bitmap missing from content store")
        require(!bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) {
            "Invalid sticker bitmap"
        }
        val rect = RectF((-layer.width / 2).toFloat(), (-layer.height / 2).toFloat(),
            (layer.width / 2).toFloat(), (layer.height / 2).toFloat())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(bitmap, null, rect, paint)
    }
}
