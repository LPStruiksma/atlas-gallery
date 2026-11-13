package nl.atlas.gallery.featureface.onnx

import java.security.MessageDigest

/**
 * Pins for the bundled YuNet face detector (OpenCV Zoo `face_detection_yunet_2023mar`, MIT).
 * Provenance and licence: `app/src/main/assets/models/yunet/SOURCE.md` and `LICENSE`.
 * The model is read from app assets only; nothing is downloaded at run time.
 */
object YuNetModel {
    const val ASSET_PATH = "models/yunet/face_detection_yunet_2023mar.onnx"
    const val LICENSE_ASSET_PATH = "models/yunet/LICENSE"
    const val SHA256 = "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4"
    const val SIZE_BYTES = 232_589

    /** Persisted with every observation; changes whenever the model or decoding changes. */
    const val DETECTOR_VERSION = "yunet-2023mar-8f2383e4-d1"

    /** The 2023mar export has a fixed `[1, 3, 640, 640]` float input (BGR, 0..255). */
    const val INPUT_NAME = "input"
    const val INPUT_SIZE = 640
    val STRIDES = intArrayOf(8, 16, 32)

    /** OpenCV `FaceDetectorYN` defaults. */
    const val DEFAULT_SCORE_THRESHOLD = 0.9f
    const val DEFAULT_NMS_THRESHOLD = 0.3f

    fun outputNames(): List<String> = listOf("cls", "obj", "bbox", "kps")
        .flatMap { head -> STRIDES.map { "${head}_$it" } }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Fail closed: a truncated, swapped or tampered model never reaches the runtime. */
    fun requirePinned(bytes: ByteArray): ByteArray {
        if (bytes.size != SIZE_BYTES || sha256Hex(bytes) != SHA256) {
            throw FaceModelIntegrityFailure("Bundled face detector does not match its SHA-256 pin")
        }
        return bytes
    }
}

class FaceModelIntegrityFailure(message: String) : IllegalStateException(message)
