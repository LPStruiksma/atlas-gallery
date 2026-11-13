package nl.atlas.gallery.featureface.onnx

/**
 * #552: CPU budget of the face models (YuNet, SFace) on ONNX Runtime.
 *
 * Measured, not guessed: on the 2-vCPU API35 emulator one intra-op thread
 * is at least as fast as two and clearly faster than four (VS-B1: SFace p50
 * 135-145 ms at 1 thread, 215-240 ms at 4; YuNet 14.5 ms vs 38-50 ms),
 * because ORT's spinning workers fight the scanning thread for the same two
 * cores. With more cores, more threads pay off (SFace int8 on a 4-core x86
 * host: 75 ms at 1 thread, 47 ms at 2, 31 ms at 4), capped at 4 per session
 * so the UI keeps its cores.
 */
object FaceOnnxThreads {
    fun intraOpFor(cores: Int): Int = if (cores <= 2) 1 else (cores / 2).coerceAtMost(4)

    val intraOp: Int by lazy { intraOpFor(Runtime.getRuntime().availableProcessors()) }
}
