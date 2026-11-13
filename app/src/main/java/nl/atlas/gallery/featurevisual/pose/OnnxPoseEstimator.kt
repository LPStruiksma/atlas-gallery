package nl.atlas.gallery.featurevisual.pose

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import java.io.Closeable
import java.io.IOException
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pins for the bundled MediaPipe person detector and BlazePose full landmark model (OpenCV Zoo
 * ONNX exports, 2023mar, Apache-2.0). Provenance: `app/src/main/assets/models/blazepose/SOURCE.md`.
 * Nothing is downloaded at run time.
 */
object PoseModel {
    const val ASSET_DIR = "models/blazepose"
    const val DETECTOR_ASSET = "$ASSET_DIR/person_detection_mediapipe_2023mar.onnx"
    const val LANDMARK_ASSET = "$ASSET_DIR/pose_estimation_mediapipe_2023mar.onnx"
    const val LICENSE_ASSET_PATH = "$ASSET_DIR/LICENSE"

    const val DETECTOR_SHA256 = "47fd5599d6fa17608f03e0eb0ae230baa6e597d7e8a2c8199fe00abea55a701f"
    const val DETECTOR_SIZE_BYTES = 11_990_159
    const val LANDMARK_SHA256 = "9d89c599319a18fb7d2e28451a883476164543182bafca5f09eb2cf767ed2f3f"
    const val LANDMARK_SIZE_BYTES = 5_557_238

    /**
     * Stored with every pose descriptor; a store refuses to compare descriptors of different
     * versions. Bump the suffix by hand when pre- or post-processing or [PoseDescriptor] changes.
     */
    const val POSE_VERSION = "blazepose-2023mar:" + "47fd5599" + "-" + "9d89c599" + ":d1"

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Fail closed: a truncated, swapped or tampered model never reaches the runtime. */
    fun requirePinned(bytes: ByteArray, sha256: String, sizeBytes: Int, what: String): ByteArray {
        if (bytes.size != sizeBytes || sha256Hex(bytes) != sha256) {
            throw PoseModelIntegrityFailure("Bundled $what does not match its SHA-256 pin")
        }
        return bytes
    }
}

/** A bundled pose model is missing, truncated or does not match its SHA-256 pin. */
class PoseModelIntegrityFailure(message: String) : IOException(message)

/** The most confident person box: score, hip centre and full-body point, in source pixels. */
class PersonDetection(val score: Float, val hipX: Float, val hipY: Float, val fullX: Float, val fullY: Float)

/**
 * On-device body pose for query-by-image (goal #468, VS-S2p): MediaPipe person detector →
 * BlazePose full → 33 metric world landmarks → [PoseDescriptor]. A 1:1 port of
 * `tools/visual_search_bench/blazepose.py` on ONNX Runtime (CPU only, no network), with the
 * pre- and post-processing in [BlazePoseMath] so JVM tests can check it against the Python tool.
 *
 * [describe] returns a [PoseDescriptor.SIZE]-float descriptor, or null when there is no body
 * (no person box above [detScore], or landmark confidence below [poseConf]).
 * Blocking and serialised; call it from a worker thread. Pixels and landmarks stay in the process.
 */
class OnnxPoseEstimator private constructor(
    private val env: OrtEnvironment,
    private val detector: OrtSession,
    private val landmarks: OrtSession,
    private val options: OrtSession.SessionOptions,
    private val detScore: Float,
    private val poseConf: Float,
) : Closeable {
    private val lock = Any()
    private var closed = false
    private val detInput = detector.inputNames.single()
    private val landmarkInput = landmarks.inputNames.single()
    private val detRegName: String
    private val detClsName: String
    private val confName: String
    private val worldName: String
    private val detCanvas = FloatArray(3 * BlazePoseMath.DET_SIZE * BlazePoseMath.DET_SIZE)
    private val poseCrop = FloatArray(BlazePoseMath.POSE_SIZE * BlazePoseMath.POSE_SIZE * 3)

    init {
        // As blazepose.py: pick outputs by shape, not by the exporter's names.
        val det = detector.outputInfo.mapValues { (it.value.info as TensorInfo).shape.last() }
        detRegName = det.entries.single { it.value == 12L }.key
        detClsName = det.entries.single { it.value == 1L }.key
        val pose = landmarks.outputInfo.filterValues { (it.info as TensorInfo).shape.size == 2 }
            .mapValues { (it.value.info as TensorInfo).shape.last() }
        confName = pose.entries.single { it.value == 1L }.key
        worldName = pose.entries.single { it.value == 117L }.key
    }

    val poseVersion: String get() = PoseModel.POSE_VERSION

    /** Pose descriptor of an upright bitmap (EXIF orientation applied), or null without a body. */
    fun describe(bitmap: Bitmap): FloatArray? {
        check(!bitmap.isRecycled) { "Image already released" }
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        return describeArgb(pixels, w, h)
    }

    /** Pose descriptor of an upright ARGB frame (row-major, [width]×[height]), or null. */
    fun describeArgb(argb: IntArray, width: Int, height: Int): FloatArray? =
        worldLandmarks(argb, width, height)?.let(PoseDescriptor::fromWorld)

    /** `BlazePose.world_landmarks`: 33 × 3 metric landmarks (y down, hip-centred), or null. */
    fun worldLandmarks(argb: IntArray, width: Int, height: Int): FloatArray? = synchronized(lock) {
        check(!closed) { "Pose estimator closed" }
        require(width > 0 && height > 0 && argb.size >= width * height) { "Bad frame ${width}x$height" }
        val person = detect(argb, width, height) ?: return null
        val roi = BlazePoseMath.roi(person) ?: return null
        BlazePoseMath.warpCropInto(argb, width, height, roi.forward, poseCrop)
        val s = BlazePoseMath.POSE_SIZE.toLong()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(poseCrop), longArrayOf(1, s, s, 3)).use { tensor ->
            landmarks.run(mapOf(landmarkInput to tensor)).use { result ->
                val conf = floats(result, confName)[0]
                if (!(conf >= poseConf)) return null
                return BlazePoseMath.unrotate(floats(result, worldName), roi.degrees)
            }
        }
    }

    /** `BlazePose.detect`: the most confident person, or null. */
    fun detect(argb: IntArray, width: Int, height: Int): PersonDetection? = synchronized(lock) {
        check(!closed) { "Pose estimator closed" }
        val letterbox = BlazePoseMath.letterboxInto(argb, width, height, detCanvas)
        val d = BlazePoseMath.DET_SIZE.toLong()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(detCanvas), longArrayOf(1, 3, d, d)).use { tensor ->
            detector.run(mapOf(detInput to tensor)).use { result ->
                return BlazePoseMath.decodeBest(
                    floats(result, detRegName), floats(result, detClsName), letterbox, detScore,
                )
            }
        }
    }

    private fun floats(result: OrtSession.Result, name: String): FloatArray {
        val value = result.get(name).orElseThrow { IllegalStateException("Pose model output $name missing") }
        val buffer = (value as OnnxTensor).floatBuffer
        return FloatArray(buffer.remaining()).also { buffer.get(it) }
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            try { detector.close() } finally { try { landmarks.close() } finally { options.close() } }
        }
    }

    companion object {
        /** `blazepose.BlazePose` defaults. */
        const val DEFAULT_DET_SCORE = 0.5f
        const val DEFAULT_POSE_CONF = 0.8f

        /** Reads both models from app assets, checks their SHA-256 pins, opens ORT sessions. */
        fun open(context: Context, threads: Int = 1): OnnxPoseEstimator = fromAssets(context.assets, threads)

        fun fromAssets(assets: AssetManager, threads: Int = 1): OnnxPoseEstimator {
            val det = PoseModel.requirePinned(
                assets.open(PoseModel.DETECTOR_ASSET).use { it.readBytes() },
                PoseModel.DETECTOR_SHA256, PoseModel.DETECTOR_SIZE_BYTES, "person detector",
            )
            val pose = PoseModel.requirePinned(
                assets.open(PoseModel.LANDMARK_ASSET).use { it.readBytes() },
                PoseModel.LANDMARK_SHA256, PoseModel.LANDMARK_SIZE_BYTES, "pose landmark model",
            )
            return fromModelBytes(det, pose, threads)
        }

        /** Opens sessions on already verified model bytes. */
        fun fromModelBytes(
            detectorBytes: ByteArray,
            landmarkBytes: ByteArray,
            threads: Int = 1,
            detScore: Float = DEFAULT_DET_SCORE,
            poseConf: Float = DEFAULT_POSE_CONF,
        ): OnnxPoseEstimator {
            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(threads)
                setInterOpNumThreads(1)
                setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR)
            }
            var det: OrtSession? = null
            var pose: OrtSession? = null
            try {
                det = env.createSession(detectorBytes, options)
                pose = env.createSession(landmarkBytes, options)
                return OnnxPoseEstimator(env, det, pose, options, detScore, poseConf)
            } catch (error: Throwable) {
                try { det?.close(); pose?.close() } finally { options.close() }
                throw error
            }
        }
    }
}

/**
 * Pre- and post-processing of `blazepose.py`, without ONNX Runtime or Android types, so JVM tests
 * can compare each step with the Python tool (OpenCV 5.0 `resize`/`warpAffine`, NumPy float32).
 */
object BlazePoseMath {
    const val DET_SIZE = 224
    const val POSE_SIZE = 256
    const val ANCHORS = 2254

    /** `ssd_anchors()`: 2254 (x, y) centres: 28×28×2, 14×14×2, 7×7×6. */
    val anchors: FloatArray by lazy {
        val out = FloatArray(2 * ANCHORS)
        var i = 0
        for ((n, per) in listOf(28 to 2, 14 to 2, 7 to 6)) {
            for (y in 0 until n) for (x in 0 until n) {
                val cx = ((x + 0.5) / n).toFloat()
                val cy = ((y + 0.5) / n).toFloat()
                repeat(per) { out[i++] = cx; out[i++] = cy }
            }
        }
        out
    }

    /** Where the image sits in the 224×224 detector canvas. */
    class Letterbox(val ratio: Double, val left: Int, val top: Int, val scale: Int, val padX: Float, val padY: Float)

    /**
     * Detector input: RGB scaled to [-1, 1], resized keeping aspect (OpenCV `INTER_LINEAR`, half-pixel
     * centres, no antialiasing) and centred on a zero canvas; NCHW into [out] (3 × 224 × 224).
     */
    fun letterboxInto(argb: IntArray, w: Int, h: Int, out: FloatArray): Letterbox {
        val ratio = min(DET_SIZE.toDouble() / h, DET_SIZE.toDouble() / w)
        val nh = (h * ratio).toInt()
        val nw = (w * ratio).toInt()
        val left = (DET_SIZE - nw) / 2
        val top = (DET_SIZE - nh) / 2
        out.fill(0f)
        val plane = DET_SIZE * DET_SIZE
        val xs = linearTaps(w, nw)
        val ys = linearTaps(h, nh)
        for (dy in 0 until nh) {
            val y0 = ys.i0[dy]; val y1 = ys.i1[dy]; val fy = ys.f[dy]
            for (dx in 0 until nw) {
                val x0 = xs.i0[dx]; val x1 = xs.i1[dx]; val fx = xs.f[dx]
                val p00 = argb[y0 * w + x0]; val p01 = argb[y0 * w + x1]
                val p10 = argb[y1 * w + x0]; val p11 = argb[y1 * w + x1]
                val o = (top + dy) * DET_SIZE + left + dx
                for (c in 0 until 3) {
                    val shift = 16 - 8 * c
                    val a = norm(p00, shift); val b = norm(p01, shift)
                    val d = norm(p10, shift); val e = norm(p11, shift)
                    val r0 = a + (b - a) * fx
                    val r1 = d + (e - d) * fx
                    out[c * plane + o] = r0 + (r1 - r0) * fy
                }
            }
        }
        val scale = max(w, h)
        return Letterbox(ratio, left, top, scale, (left / ratio).toFloat(), (top / ratio).toFloat())
    }

    private fun norm(p: Int, shift: Int): Float = ((p shr shift) and 0xFF).toFloat() / 127.5f - 1f

    class Taps(val i0: IntArray, val i1: IntArray, val f: FloatArray)

    /** OpenCV `INTER_LINEAR` source taps for resizing [src] samples to [dst]. */
    fun linearTaps(src: Int, dst: Int): Taps {
        val scale = src.toDouble() / dst
        val i0 = IntArray(dst); val i1 = IntArray(dst); val f = FloatArray(dst)
        for (d in 0 until dst) {
            val pos = ((d + 0.5) * scale - 0.5).toFloat()
            var s = floor(pos).toInt()
            var frac = pos - s
            if (s < 0) { s = 0; frac = 0f }
            if (s >= src - 1) { s = src - 1; frac = 0f }
            i0[d] = s; i1[d] = min(s + 1, src - 1); f[d] = frac
        }
        return Taps(i0, i1, f)
    }

    /**
     * The best person from raw detector outputs ([reg] 2254 × 12, [cls] 2254 logits), mapped back
     * to source pixels; null when no score exceeds [minScore]. blazepose.py runs NMS first and then
     * takes the best kept box; NMS always keeps the top-scoring box, so that is the plain arg-max.
     */
    fun decodeBest(reg: FloatArray, cls: FloatArray, box: Letterbox, minScore: Float): PersonDetection? {
        var best = -1
        var bestScore = 0.0
        for (i in 0 until ANCHORS) {
            val logit = cls[i].toDouble().coerceIn(-100.0, 100.0)
            val s = 1.0 / (1.0 + exp(-logit))
            if (s > minScore && (best < 0 || s > bestScore)) { best = i; bestScore = s }
        }
        if (best < 0) return null
        val ax = anchors[2 * best]; val ay = anchors[2 * best + 1]
        val scale = box.scale.toFloat()
        fun px(k: Int) = (reg[best * 12 + 4 + 2 * k] / DET_SIZE + ax) * scale - box.padX
        fun py(k: Int) = (reg[best * 12 + 5 + 2 * k] / DET_SIZE + ay) * scale - box.padY
        return PersonDetection(bestScore.toFloat(), px(0), py(0), px(1), py(1))
    }

    /** Landmark region: [forward] maps source pixels to the 256×256 crop; [degrees] the body tilt. */
    class Roi(val forward: DoubleArray, val degrees: Double)

    /**
     * Square region centred on the hip, radius = hip → full-body distance, rotated so the body axis
     * points up (as MediaPipe does). Null when the radius is under 4 px.
     */
    fun roi(p: PersonDetection): Roi? {
        val dx = p.fullX - p.hipX
        val dy = p.fullY - p.hipY
        val r = sqrt(dx * dx + dy * dy)
        if (r < 4f) return null
        var ang = PI / 2 - atan2(-dy.toDouble(), dx.toDouble())
        ang -= 2 * PI * floor((ang + PI) / (2 * PI))
        val deg = Math.toDegrees(ang)
        val m = rotationMatrix(p.hipX.toDouble(), p.hipY.toDouble(), deg)
        val s = POSE_SIZE / (2.0 * r)
        val tx = POSE_SIZE / 2 - s * p.hipX
        val ty = POSE_SIZE / 2 - s * p.hipY
        // t @ [m; 0 0 1] with t = [[s, 0, tx], [0, s, ty], [0, 0, 1]].
        val forward = doubleArrayOf(
            s * m[0], s * m[1], s * m[2] + tx,
            s * m[3], s * m[4], s * m[5] + ty,
        )
        return Roi(forward, deg)
    }

    /** `cv2.getRotationMatrix2D(center, angle, 1.0)` as a row-major 2 × 3 matrix. */
    fun rotationMatrix(cx: Double, cy: Double, degrees: Double): DoubleArray {
        val rad = Math.toRadians(degrees)
        val a = cos(rad)
        val b = sin(rad)
        return doubleArrayOf(a, b, (1 - a) * cx - b * cy, -b, a, b * cx + (1 - a) * cy)
    }

    /**
     * `cv2.warpAffine(rgb, forward, (256, 256), INTER_AREA → linear, BORDER_CONSTANT 0)`, then
     * `/ 255`: NHWC RGB floats into [out] (256 × 256 × 3). Each output pixel samples the source at
     * the inverse-mapped point, bilinearly, with zero outside the image, rounded to 8 bits as
     * OpenCV does for uint8 input.
     */
    fun warpCropInto(argb: IntArray, w: Int, h: Int, forward: DoubleArray, out: FloatArray) {
        val inv = invertAffine(forward)
        for (y in 0 until POSE_SIZE) {
            for (x in 0 until POSE_SIZE) {
                val sx = inv[0] * x + inv[1] * y + inv[2]
                val sy = inv[3] * x + inv[4] * y + inv[5]
                val x0 = floor(sx).toInt(); val y0 = floor(sy).toInt()
                val fx = sx - x0; val fy = sy - y0
                val o = (y * POSE_SIZE + x) * 3
                if (x0 < -1 || y0 < -1 || x0 >= w || y0 >= h) {
                    out[o] = 0f; out[o + 1] = 0f; out[o + 2] = 0f
                    continue
                }
                val p00 = pixel(argb, w, h, x0, y0); val p01 = pixel(argb, w, h, x0 + 1, y0)
                val p10 = pixel(argb, w, h, x0, y0 + 1); val p11 = pixel(argb, w, h, x0 + 1, y0 + 1)
                for (c in 0 until 3) {
                    val shift = 16 - 8 * c
                    val a = (p00 shr shift) and 0xFF; val b = (p01 shr shift) and 0xFF
                    val d = (p10 shr shift) and 0xFF; val e = (p11 shr shift) and 0xFF
                    val v = (1 - fy) * ((1 - fx) * a + fx * b) + fy * ((1 - fx) * d + fx * e)
                    out[o + c] = v.roundToInt().coerceIn(0, 255) / 255f
                }
            }
        }
    }

    /** ARGB at (x, y), or black (border value 0) outside the image. */
    private fun pixel(argb: IntArray, w: Int, h: Int, x: Int, y: Int): Int =
        if (x in 0 until w && y in 0 until h) argb[y * w + x] else 0

    /** Inverse of a 2 × 3 affine map (as `cv2.invertAffineTransform`). */
    fun invertAffine(m: DoubleArray): DoubleArray {
        val det = m[0] * m[4] - m[1] * m[3]
        require(det != 0.0) { "Singular affine map" }
        val a = m[4] / det; val b = -m[1] / det; val d = -m[3] / det; val e = m[0] / det
        return doubleArrayOf(a, b, -a * m[2] - b * m[5], d, e, -d * m[2] - e * m[5])
    }

    /**
     * Undoes the region's rotation on the landmark model's world output ([raw] 39 × 3, of which
     * the first 33 are body landmarks), so "down" is the image's down again. Returns 33 × 3.
     */
    fun unrotate(raw: FloatArray, degrees: Double): FloatArray {
        require(raw.size >= PoseDescriptor.LANDMARKS * 3) { "Landmark output too short: ${raw.size}" }
        val m = rotationMatrix(0.0, 0.0, degrees)
        val a = m[0].toFloat(); val b = m[1].toFloat()
        val out = FloatArray(PoseDescriptor.LANDMARKS * 3)
        for (k in 0 until PoseDescriptor.LANDMARKS) {
            val x = raw[3 * k]; val y = raw[3 * k + 1]
            // xy @ [[a, b], [-b, a]]
            out[3 * k] = x * a - y * b
            out[3 * k + 1] = x * b + y * a
            out[3 * k + 2] = raw[3 * k + 2]
        }
        return out
    }
}
