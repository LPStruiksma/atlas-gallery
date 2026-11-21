package nl.atlas.gallery

/**
 * FLOW-5 (#714): opt-in synthetic instrumentation of the thumbnail-tap path.
 *
 * Counts Compose page commits, new Coil model construction and preview Loading
 * transitions separately from LazyRow animation callbacks. It never retains
 * a URI, media name, bitmap, decode bytes, or device data. This is a request
 * trace, NOT a claim that Coil's internal decoder work is instrumented.
 */
internal class ViewerFilmstripMotionProbe {
    data class Snapshot(
        val stripFrames: Int,
        val pageCompositions: Map<Int, Int>,
        val previewRequests: Map<Int, Int>,
        val baseRequests: Map<Int, Int>,
        val previewLoading: Map<Int, Int>
    ) {
        fun compositionsAt(index: Int) = pageCompositions[index] ?: 0
        fun requestsAt(index: Int) =
            (previewRequests[index] ?: 0) + (baseRequests[index] ?: 0)
        fun loadingAt(index: Int) = previewLoading[index] ?: 0
    }

    private var frameCount = 0
    private val compositions = mutableMapOf<Int, Int>()
    private val previews = mutableMapOf<Int, Int>()
    private val bases = mutableMapOf<Int, Int>()
    private val loading = mutableMapOf<Int, Int>()

    @Synchronized fun stripFrame() { frameCount++ }
    @Synchronized fun pageComposed(index: Int) {
        compositions[index] = (compositions[index] ?: 0) + 1
    }
    @Synchronized fun previewRequested(index: Int) {
        previews[index] = (previews[index] ?: 0) + 1
    }
    @Synchronized fun baseRequested(index: Int) {
        bases[index] = (bases[index] ?: 0) + 1
    }
    @Synchronized fun previewLoading(index: Int) {
        loading[index] = (loading[index] ?: 0) + 1
    }
    @Synchronized fun snapshot(): Snapshot = Snapshot(
        frameCount, compositions.toMap(), previews.toMap(), bases.toMap(), loading.toMap()
    )
}
