package nl.atlas.gallery.featureeditor.auto

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin
import org.junit.Test

/** Reproducible microbenchmark; its output is evidence, not a timing assertion for shared CI. */
class AutoBenchmarkTest {
    @Test fun benchmarkAt512PixelLongEdge() {
        val width = 512
        val height = 384
        val angle = 3.5 * PI / 180.0
        val image = ByteArray(width * height) { index ->
            val x = index % width - width / 2.0
            val y = index / width - height / 2.0
            val distance = kotlin.math.abs(-sin(angle) * x + cos(angle) * y - 32 * round((-sin(angle) * x + cos(angle) * y) / 32))
            if (distance < 1.2) 235.toByte() else 20.toByte()
        }
        val bins = LongArray(256) { 10 }
        val statistics = ToneStatistics(bins, bins, bins)
        repeat(20) { AutoStraighten.suggest(image, width, height); AutoAdjust.suggest(statistics) }

        fun measure(action: () -> Unit): List<Double> = List(200) {
            val start = System.nanoTime()
            action()
            (System.nanoTime() - start) / 1_000_000.0
        }.sorted()
        fun report(name: String, samples: List<Double>) {
            println("AUTO benchmark $name: median=${samples[100]}ms p95=${samples[189]}ms max=${samples.last()}ms")
        }
        report("straighten", measure { AutoStraighten.suggest(image, width, height) })
        report("tone", measure { AutoAdjust.suggest(statistics) })
    }
}
