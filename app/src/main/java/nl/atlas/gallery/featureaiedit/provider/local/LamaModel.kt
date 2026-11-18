package nl.atlas.gallery.featureaiedit.provider.local

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * AIE-22 (#434): pins for the bundled LaMa inpainting model (OpenCV Zoo
 * `inpainting_lama_2025jan.onnx`, Apache-2.0, upstream advimman/lama big-lama).
 * Provenance, licence and measurements: `app/src/main/assets/models/lama/SOURCE.md` and
 * `docs/ai-edit/ON_DEVICE_ERASE.md`.
 *
 * The file is 92.6 MB, over the 60,000,000-byte part size this repository uses for models
 * (GitHub refuses files over 100 MB and the project uses no Git LFS), so it ships as two
 * ordered asset parts that [LamaModelFile] joins once into app-private no-backup storage.
 * Nothing is downloaded.
 */
object LamaModel {
    const val ASSET_DIR = "models/lama"
    const val FILE_NAME = "inpainting_lama_2025jan.onnx"
    val PART_ASSETS: List<String> = listOf(
        "$ASSET_DIR/$FILE_NAME.part0",
        "$ASSET_DIR/$FILE_NAME.part1",
    )
    const val LICENSE_ASSET_PATH = "$ASSET_DIR/LICENSE"

    /** SHA-256 and size of the joined file; equal to the OpenCV Zoo Git LFS object id. */
    const val SHA256 = "7df918ac3921d3daf0aae1d219776cf0dc4e4935f035af81841b40adcf74fdf2"
    const val SIZE_BYTES = 92_591_623L

    /** Reported as the edit's provider model/version in lineage. */
    const val MODEL_NAME = "lama-big.opencv-zoo-2025jan.int8"
    const val MODEL_VERSION = "7df918ac3921"

    /** Fixed model geometry: NCHW float32, image in 0..1 RGB, binary mask, output 0..255 RGB. */
    const val SIZE = 512
    const val IMAGE_INPUT = "image"
    const val MASK_INPUT = "mask"
    const val OUTPUT = "output"
}

/** The bundled inpainting model is missing, truncated or does not match its SHA-256 pin. */
class LamaModelIntegrityFailure(message: String) : IOException(message)

/**
 * Joins the model's asset parts into one file, verifying the whole-file SHA-256 on every
 * open. Fail closed: on any mismatch the file is deleted and [LamaModelIntegrityFailure] is
 * thrown, so a bad file never reaches ONNX Runtime. Pure `java.io` (JVM-testable).
 * Same design as the SigLIP model file, kept separate so the two features stay independent.
 */
object LamaModelFile {
    private val lock = Any()

    fun materialize(
        dir: File,
        parts: List<() -> InputStream>,
        sha256: String = LamaModel.SHA256,
        sizeBytes: Long = LamaModel.SIZE_BYTES,
        fileName: String = LamaModel.FILE_NAME,
    ): File = synchronized(lock) {
        val target = File(dir, fileName)
        if (target.isFile) {
            if (target.length() == sizeBytes && sha256Hex(target) == sha256) return target
            target.delete()
        }
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create model directory $dir")
        val tmp = File(dir, "$fileName.tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        try {
            FileOutputStream(tmp).use { out ->
                val buffer = ByteArray(1 shl 16)
                for (open in parts) {
                    open().use { input ->
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            written += n
                            if (written > sizeBytes) {
                                throw LamaModelIntegrityFailure("Bundled erase model is larger than its pin")
                            }
                            out.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                        }
                    }
                }
                out.fd.sync()
            }
            if (written != sizeBytes || digest.digest().toHex() != sha256) {
                throw LamaModelIntegrityFailure("Bundled erase model does not match its SHA-256 pin")
            }
            if (!tmp.renameTo(target)) throw IOException("Cannot move model file into place at $target")
            return target
        } catch (error: Throwable) {
            tmp.delete()
            target.delete()
            throw error
        }
    }

    fun delete(dir: File): Boolean = synchronized(lock) { dir.deleteRecursively() || !dir.exists() }

    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
