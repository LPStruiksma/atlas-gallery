package nl.atlas.gallery.featureface.onnx

import kotlin.math.sqrt

/**
 * Pins for the bundled SFace face embedder (OpenCV Zoo `face_recognition_sface_2021dec`,
 * int8 export, Apache-2.0). Provenance and licence: `app/src/main/assets/models/sface/SOURCE.md`
 * and `LICENSE`. The model is read from app assets only; nothing is downloaded at run time.
 */
object SFaceModel {
    const val ASSET_PATH = "models/sface/face_recognition_sface_2021dec_int8.onnx"
    const val LICENSE_ASSET_PATH = "models/sface/LICENSE"
    const val SHA256 = "2b0e941e6f16cc048c20aee0c8e31f569118f65d702914540f7bfdc14048d78a"
    const val SIZE_BYTES = 9_896_933
    const val LICENSE_SHA256 = "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30"
    const val UPSTREAM_COMMIT = "48b10fa505aeb51fbd127fc60a5c660040e823b7"

    /**
     * Persisted with every embedding; clustering refuses to mix versions. Bump the suffix
     * whenever the model, the alignment or the pixel packing changes.
     */
    const val EMBEDDER_VERSION = "sface-2021dec-int8-2b0e941e-a1"

    /** Fixed `[1, 3, 112, 112]` float input: RGB planar, values 0..255 (OpenCV `blobFromImage`, swapRB). */
    const val INPUT_NAME = "data"
    const val OUTPUT_NAME = "fc1"
    const val INPUT_SIZE = 112
    const val DIMENSION = 128

    /**
     * OpenCV Zoo's published same-identity cosine cut-off for SFace. Reference only:
     * D2-3 clustering uses its own, stricter policy.
     */
    const val OPENCV_SAME_IDENTITY_COSINE = 0.363f

    /** Fail closed: a truncated, swapped or tampered model never reaches the runtime. */
    fun requirePinned(bytes: ByteArray): ByteArray {
        if (bytes.size != SIZE_BYTES || YuNetModel.sha256Hex(bytes) != SHA256) {
            throw FaceModelIntegrityFailure("Bundled face embedder does not match its SHA-256 pin")
        }
        return bytes
    }

    /** L2-normalises a raw model output; refuses wrong sizes, non-finite values and zero vectors. */
    fun l2Normalize(raw: FloatArray): FloatArray {
        require(raw.size == DIMENSION) { "SFace output has ${raw.size} values, expected $DIMENSION" }
        var sum = 0.0
        for (v in raw) {
            require(v.isFinite()) { "SFace output contains a non-finite value" }
            sum += v.toDouble() * v
        }
        val norm = sqrt(sum)
        require(norm > 1e-6) { "SFace output has no direction (zero vector)" }
        return FloatArray(raw.size) { (raw[it] / norm).toFloat() }
    }
}
