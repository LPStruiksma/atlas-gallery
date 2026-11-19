package nl.atlas.gallery.featureface.search

import java.io.File
import java.util.concurrent.TimeUnit
import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featureface.onnx.SFaceModel
import nl.atlas.gallery.featurepeople.cluster.ClusterReviewFacts
import nl.atlas.gallery.featurepersistence.IndexedFaceBox
import nl.atlas.gallery.featurepersistence.IndexedMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #64: run the real packed-corpus builder and query engine in a separate
 * 96 MiB JVM, not just in the Gradle test worker's much larger heap.
 * All faces and media are deterministic inventions; no Android or device data.
 */
class FaceSearchCorpusHeapTest {
    @Test fun oneHundredThousandFacesFitNinetySixMiB() {
        val java = File(File(System.getProperty("java.home"), "bin"), "java").absolutePath
        val anchors = listOf(
            FaceSearchCorpusHeapProbe::class.java, FaceSearchCorpus::class.java,
            FaceQueryEngine::class.java, SFaceModel::class.java,
            NativeFaceObservationId::class.java, IndexedFaceBox::class.java,
            ClusterReviewFacts::class.java, kotlin.Unit::class.java
        )
        // Gradle isolates test classes in a worker classloader; java.class.path
        // alone can contain only the worker launcher. Include the actual code
        // locations of the compiled test, app, and Kotlin standard library.
        val classpath = (listOf(System.getProperty("java.class.path")) +
            anchors.mapNotNull { it.protectionDomain?.codeSource?.location?.toURI()?.let(::File)?.path } +
            listOfNotNull(runCatching { Class.forName("ai.onnxruntime.OrtEnvironment")
                .protectionDomain?.codeSource?.location?.toURI()?.let(::File)?.path }.getOrNull()))
            .distinct().joinToString(File.pathSeparator)
        val child = ProcessBuilder(java, "-Xmx96m", "-cp", classpath,
            FaceSearchCorpusHeapProbe::class.java.name).redirectErrorStream(true).start()
        if (!child.waitFor(120, TimeUnit.SECONDS)) {
            child.destroyForcibly()
            throw AssertionError("Synthetic face corpus heap probe timed out")
        }
        val log = child.inputStream.bufferedReader().readText()
        assertEquals("96 MiB face corpus heap probe: $log", 0, child.exitValue())
        assertTrue("Probe must report the checked row count: $log",
            log.contains("FACE_CORPUS_HEAP_PASS faces=100000"))
    }
}

/** Only the forked JVM invokes this entry point; no persistent user records. */
object FaceSearchCorpusHeapProbe {
    @JvmStatic fun main(args: Array<String>) {
        check(Runtime.getRuntime().maxMemory() <= 96L * 1024L * 1024L) {
            "The synthetic probe was not memory-constrained"
        }
        val model = SFaceModel.EMBEDDER_VERSION
        val box = IndexedFaceBox(0.1f, 0.2f, 0.6f, 0.8f)
        val vector = FloatArray(SFaceModel.DIMENSION) { if (it == 0) 1f else 0f }
        val builder = FaceSearchCorpus.Builder(model, 100_000)
        for (n in 1..100_000) {
            builder.add(NativeFaceObservationId(n.toString(16).padStart(32, '0')),
                IndexedMedia("synthetic-media-$n", "rev-1"), box, null, vector)
        }
        val corpus = builder.build(ClusterReviewFacts.NONE, clustersPublished = true)
        check(corpus.faceCount == 100_000)
        // Orthogonal query: scans every face without materialising hit objects.
        val other = FloatArray(SFaceModel.DIMENSION) { if (it == 1) 1f else 0f }
        val result = FaceQueryEngine.search(corpus, FaceQuery(model, listOf(other)))
        check(result.scannedFaces == 100_000 && result.hits.isEmpty())
        println("FACE_CORPUS_HEAP_PASS faces=100000")
    }
}
