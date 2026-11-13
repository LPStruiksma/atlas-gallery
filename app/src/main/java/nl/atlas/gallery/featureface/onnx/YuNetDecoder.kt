package nl.atlas.gallery.featureface.onnx

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import nl.atlas.gallery.featureface.FaceDiscoveryContract

/**
 * One YuNet face in model-input pixels (the 640x640 letterboxed canvas), or after
 * [YuNetLetterbox.toFrame] in decoded-frame pixels. Landmarks are five (x, y) points:
 * right eye, left eye, nose tip, right mouth corner, left mouth corner (as in OpenCV),
 * kept for SFace alignment (D2-2). Not an identity.
 */
class YuNetFace(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val score: Float,
    val landmarks: FloatArray
) {
    init {
        require(landmarks.size == 10) { "YuNet has five landmarks" }
        require(width > 0f && height > 0f && score.isFinite())
    }
    val right: Float get() = left + width
    val bottom: Float get() = top + height
}

/**
 * Pure-Kotlin port of OpenCV's `FaceDetectorYN::postProcess` for the 2023mar export:
 * score = sqrt(clamp(cls) * clamp(obj)); box centre = (col + dx, row + dy) * stride;
 * size = exp(dw|dh) * stride; then greedy NMS. No Android or ONNX types, so it is JVM-testable.
 */
object YuNetDecoder {
    fun decode(
        outputs: Map<String, FloatArray>,
        inputSize: Int = YuNetModel.INPUT_SIZE,
        scoreThreshold: Float = YuNetModel.DEFAULT_SCORE_THRESHOLD,
        nmsThreshold: Float = YuNetModel.DEFAULT_NMS_THRESHOLD,
        maxFaces: Int = FaceDiscoveryContract.MAX_FACES_PER_IMAGE
    ): List<YuNetFace> {
        require(inputSize > 0 && YuNetModel.STRIDES.all { inputSize % it == 0 })
        require(scoreThreshold in 0f..1f && nmsThreshold in 0f..1f && maxFaces >= 0)
        val candidates = ArrayList<YuNetFace>()
        for (stride in YuNetModel.STRIDES) {
            val cols = inputSize / stride
            val n = cols * cols
            val cls = outputs.tensor("cls_$stride", n)
            val obj = outputs.tensor("obj_$stride", n)
            val bbox = outputs.tensor("bbox_$stride", n * 4)
            val kps = outputs.tensor("kps_$stride", n * 10)
            for (i in 0 until n) {
                val score = sqrt(cls[i].clamp01() * obj[i].clamp01())
                if (!(score >= scoreThreshold)) continue
                val row = i / cols
                val col = i % cols
                val cx = (col + bbox[i * 4]) * stride
                val cy = (row + bbox[i * 4 + 1]) * stride
                val w = exp(bbox[i * 4 + 2]) * stride
                val h = exp(bbox[i * 4 + 3]) * stride
                if (!(cx.isFinite() && cy.isFinite() && w.isFinite() && h.isFinite() && w > 0f && h > 0f)) continue
                val marks = FloatArray(10) { k ->
                    val offset = if (k % 2 == 0) col else row
                    (kps[i * 10 + k] + offset) * stride
                }
                candidates += YuNetFace(cx - w / 2f, cy - h / 2f, w, h, score, marks)
            }
        }
        return nms(candidates, nmsThreshold).take(maxFaces)
    }

    /** Greedy NMS, highest score first; ties keep the earlier candidate (stable sort). */
    fun nms(faces: List<YuNetFace>, iouThreshold: Float): List<YuNetFace> {
        val kept = ArrayList<YuNetFace>()
        for (face in faces.sortedByDescending { it.score }) {
            if (kept.none { iou(it, face) > iouThreshold }) kept += face
        }
        return kept
    }

    fun iou(a: YuNetFace, b: YuNetFace): Float {
        val w = min(a.right, b.right) - max(a.left, b.left)
        val h = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (w <= 0f || h <= 0f) return 0f
        val inter = w * h
        return inter / (a.width * a.height + b.width * b.height - inter)
    }

    private fun Map<String, FloatArray>.tensor(name: String, size: Int): FloatArray {
        val values = this[name] ?: throw IllegalArgumentException("YuNet output $name missing")
        require(values.size == size) { "YuNet output $name has ${values.size} values, expected $size" }
        return values
    }

    private fun Float.clamp01(): Float = if (this.isNaN()) 0f else this.coerceIn(0f, 1f)
}
