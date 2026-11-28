package nl.atlas.gallery.featuregrid

import android.view.Choreographer
import androidx.test.platform.app.InstrumentationRegistry

/**
 * PERF-HASH (#592) measurement plumbing. Everything here is owned by the main
 * thread: the test thread only starts, stops and snapshots through
 * [onMain], so a snapshot never races a callback that is still running.
 */
internal fun <T> onMain(block: () -> T): T {
    var result: Result<T>? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
    return checkNotNull(result) { "main-thread block did not run" }.getOrThrow()
}

/**
 * Waits for [count] real Choreographer frames: PERF-HASH measurement windows
 * are defined in frames, as PERF-A2A's post-release frames are. Fails if the
 * display stops producing frames.
 */
internal fun awaitChoreographerFrames(count: Int) {
    val latch = java.util.concurrent.CountDownLatch(count)
    val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            latch.countDown()
            if (latch.count > 0) Choreographer.getInstance().postFrameCallback(this)
        }
    }
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        Choreographer.getInstance().postFrameCallback(callback)
    }
    try {
        check(latch.await(count * 50L + 5_000L, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            "display produced fewer than $count frames"
        }
    } finally {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            Choreographer.getInstance().removeFrameCallback(callback)
        }
    }
}

/**
 * Re-arming Choreographer.FrameCallback that records every frame time. [stop]
 * and [close] disarm it ON the main thread, so once they return nothing more
 * is appended, also when the measured body threw.
 */
internal class FrameIntervalRecorder : AutoCloseable {
    private val stamps = ArrayList<Long>()
    private var armed = false
    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!armed) return
            stamps.add(frameTimeNanos)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun start() = onMain {
        check(!armed) { "recorder already running" }
        stamps.clear()
        armed = true
        Choreographer.getInstance().postFrameCallback(callback)
    }

    /** Disarms and returns the positive frame-to-frame intervals in ns. */
    fun stop(): List<Long> = onMain {
        disarm()
        stamps.zipWithNext { a, b -> b - a }.filter { it > 0 }
    }

    /** Frames seen so far (main-thread read). */
    fun frames(): Int = onMain { stamps.size }

    override fun close() = onMain { disarm() }

    private fun disarm() {
        armed = false
        Choreographer.getInstance().removeFrameCallback(callback)
    }
}

/**
 * Per-draw tile sampler, fed from the overlay's own draw pass so "covered"
 * means a placeholder was really drawn over that tile, in the same geometry,
 * in that frame. Main thread only; [start]/[stop] go through [onMain].
 */
internal class TileCoverageSampler {
    class Snapshot(
        val frames: Int, val tileFrames: Long, val blankTileFrames: Long, val coveredTileFrames: Long,
        val framesWithBlank: Int, val framesWithUncovered: Int,
        val blankEpisodes: List<Long>, val uncoveredEpisodes: List<Long>
    ) {
        val uncoveredTileFrames: Long get() = blankTileFrames - coveredTileFrames
    }

    var recording = false
        private set
    private var frames = 0
    private var tileFrames = 0L
    private var blankTileFrames = 0L
    private var coveredTileFrames = 0L
    private var framesWithBlank = 0
    private var framesWithUncovered = 0
    private val blankSince = HashMap<Int, Long>()
    private val uncoveredSince = HashMap<Int, Long>()
    private val blankEpisodes = ArrayList<Long>()
    private val uncoveredEpisodes = ArrayList<Long>()
    private var frameNanos = -1L
    private var lastFrameNanos = -1L
    private var frameBlank = 0
    private var frameUncovered = 0
    private val seen = HashSet<Int>()

    fun start() = onMain {
        frames = 0; tileFrames = 0; blankTileFrames = 0; coveredTileFrames = 0
        framesWithBlank = 0; framesWithUncovered = 0
        blankSince.clear(); uncoveredSince.clear(); blankEpisodes.clear(); uncoveredEpisodes.clear()
        frameNanos = -1L; lastFrameNanos = -1L
        recording = true
    }

    /** False when this draw belongs to a frame already sampled (a second draw in one frame). */
    fun beginFrame(nanos: Long): Boolean {
        if (!recording || nanos <= lastFrameNanos) return false
        frameNanos = nanos
        frameBlank = 0
        frameUncovered = 0
        seen.clear()
        return true
    }

    /** One visible media tile in the current frame. */
    fun tile(index: Int, blank: Boolean, covered: Boolean) {
        seen += index
        tileFrames++
        if (!blank) {
            blankSince.remove(index)?.let { blankEpisodes += frameNanos - it }
            uncoveredSince.remove(index)?.let { uncoveredEpisodes += frameNanos - it }
            return
        }
        frameBlank++
        blankTileFrames++
        blankSince.getOrPut(index) { frameNanos }
        if (covered) {
            coveredTileFrames++
            uncoveredSince.remove(index)?.let { uncoveredEpisodes += frameNanos - it }
        } else {
            frameUncovered++
            uncoveredSince.getOrPut(index) { frameNanos }
        }
    }

    fun endFrame() {
        // A blank tile that left the viewport ends its episode in this frame.
        endMissing(blankSince, blankEpisodes)
        endMissing(uncoveredSince, uncoveredEpisodes)
        frames++
        if (frameBlank > 0) framesWithBlank++
        if (frameUncovered > 0) framesWithUncovered++
        lastFrameNanos = frameNanos
    }

    private fun endMissing(open: HashMap<Int, Long>, done: ArrayList<Long>) {
        val it = open.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key !in seen) { done += frameNanos - e.value; it.remove() }
        }
    }

    /** Stops sampling; episodes still open end at the last sampled frame. */
    fun stop(): Snapshot = onMain {
        recording = false
        val end = lastFrameNanos
        if (end > 0) {
            blankSince.values.forEach { blankEpisodes += end - it }
            uncoveredSince.values.forEach { uncoveredEpisodes += end - it }
        }
        blankSince.clear(); uncoveredSince.clear()
        Snapshot(frames, tileFrames, blankTileFrames, coveredTileFrames, framesWithBlank,
            framesWithUncovered, blankEpisodes.toList(), uncoveredEpisodes.toList())
    }
}
