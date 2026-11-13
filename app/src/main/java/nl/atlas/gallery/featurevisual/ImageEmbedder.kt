package nl.atlas.gallery.featurevisual

import android.graphics.Bitmap

/**
 * VS-S1a supplies the real ONNX implementation. Each result is a finite,
 * non-zero, L2-normalised vector in this model's embedding space.
 * The caller owns lifecycle and must close the implementation when finished.
 */
interface ImageEmbedder : AutoCloseable {
    val modelVersion: String
    val dimension: Int
    fun embed(bitmap: Bitmap): FloatArray
}
