package nl.atlas.gallery.featureaiedit.provider.local

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import android.content.Context
import android.content.res.AssetManager
import java.io.File
import java.nio.FloatBuffer
import nl.atlas.gallery.featureface.onnx.FaceOnnxThreads

/**
 * LaMa on ONNX Runtime's CPU provider (the runtime ATLAS already ships for People and
 * visual search). No network, no NNAPI: the S24+ is an Exynos phone and its timings are
 * measured, not assumed (docs/ai-edit/ON_DEVICE_ERASE.md).
 *
 * Open per edit and close after it: the session holds roughly half a gigabyte while it runs.
 */
class OnnxLamaInpainter private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val options: OrtSession.SessionOptions,
) : LamaInpainter {
    private val lock = Any()
    @Volatile private var runOptions: OrtSession.RunOptions? = null
    private var closed = false

    override fun inpaint(image: FloatArray, mask: FloatArray, cancel: CancelSignal): FloatArray {
      synchronized(lock) {
        check(!closed) { "Inpainter closed" }
        val n = LamaModel.SIZE.toLong()
        require(image.size == (3 * n * n).toInt() && mask.size == (n * n).toInt())
        if (cancel.isCancelled()) throw EraseCancelled()
        OrtSession.RunOptions().use { run ->
            runOptions = run
            try {
                if (cancel.isCancelled()) throw EraseCancelled()
                OnnxTensor.createTensor(env, FloatBuffer.wrap(image), longArrayOf(1, 3, n, n)).use { imageTensor ->
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(mask), longArrayOf(1, 1, n, n)).use { maskTensor ->
                        val result = try {
                            session.run(mapOf(LamaModel.IMAGE_INPUT to imageTensor, LamaModel.MASK_INPUT to maskTensor), run)
                        } catch (error: Exception) {
                            if (cancel.isCancelled()) throw EraseCancelled()
                            throw error
                        }
                        result.use {
                            val value = it.get(LamaModel.OUTPUT)
                                .orElseThrow { IllegalStateException("Inpainter output ${LamaModel.OUTPUT} missing") }
                            val buffer = (value as OnnxTensor).floatBuffer
                            val out = FloatArray(buffer.remaining()).also { array -> buffer.get(array) }
                            for (v in out) check(v.isFinite()) { "Inpainter output is not finite" }
                            return out
                        }
                    }
                }
            } finally {
                runOptions = null
            }
        }
      }
    }

    /** Aborts a running [inpaint] from another thread. */
    override fun terminate() {
        runCatching { runOptions?.setTerminate(true) }
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            try { session.close() } finally { options.close() }
        }
    }

    companion object {
        /** Sub-directory of [Context.getNoBackupFilesDir] that holds the joined model. */
        const val MODEL_DIR = "models/lama"

        /** Intra-op threads: half the cores, at most 4 (same budget rule as the face models). */
        val defaultThreads: Int get() = FaceOnnxThreads.intraOp

        /**
         * Joins and verifies the bundled model on first use (92.6 MB written once to
         * no-backup storage; later opens re-hash it), then opens a session.
         */
        fun open(context: Context, threads: Int = defaultThreads): OnnxLamaInpainter =
            fromModelFile(materializeFromAssets(context.assets, File(context.noBackupFilesDir, MODEL_DIR)), threads)

        fun materializeFromAssets(assets: AssetManager, dir: File): File =
            LamaModelFile.materialize(dir, LamaModel.PART_ASSETS.map { path -> { assets.open(path) } })

        /** Deletes the joined copy (the asset parts stay; the next [open] re-joins them). */
        fun deleteModelCopy(context: Context): Boolean = LamaModelFile.delete(File(context.noBackupFilesDir, MODEL_DIR))

        fun fromModelFile(file: File, threads: Int = defaultThreads): OnnxLamaInpainter {
            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(threads)
                setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR)
            }
            val session = try { env.createSession(file.absolutePath, options) }
                catch (error: Throwable) { options.close(); throw error }
            try {
                check(session.inputNames == setOf(LamaModel.IMAGE_INPUT, LamaModel.MASK_INPUT)) {
                    "Unexpected inpainter inputs ${session.inputNames}"
                }
                check(LamaModel.OUTPUT in session.outputNames) { "Unexpected inpainter outputs ${session.outputNames}" }
            } catch (error: Throwable) {
                try { session.close() } finally { options.close() }
                throw error
            }
            return OnnxLamaInpainter(env, session, options)
        }
    }
}
