package nl.atlas.gallery.featurevisual

import java.io.IOException
import nl.atlas.gallery.featurevisual.store.VisualIndexLease
import nl.atlas.gallery.featurevisual.store.VisualMedia
import nl.atlas.gallery.featurevisual.store.VisualMomentsWrite
import nl.atlas.gallery.featurevisual.store.VisualVectorWrite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VS-V2 (#468) on the JVM: the video budget (photos first, one video per
 * step, bounded moments), typed video skips, and the #482 follow-up failure
 * count. Keyframes are plain strings; no Android Bitmap or real media.
 */
class VisualVideoScanTest {
    private val photos = (0 until 10).map { VisualMedia("content://synthetic/image/$it", "g1") }
    private val videos = (0 until 3).map { VisualMedia("content://synthetic/video/$it", "g1") }

    /** Counts failures per item like VisualIndexStore; records every completion. */
    private class FakePort : VisualScanIndexPort {
        val failures = HashMap<VisualMedia, Int>()
        var writes: List<VisualVectorWrite> = emptyList()
        var skips: List<VisualIndexLease> = emptyList()
        var videos: List<VisualMomentsWrite> = emptyList()
        var completions = 0
        var released = 0
        override fun claimAuthorized(
            epoch: String, modelVersion: String, authorized: List<VisualMedia>, maxItems: Int
        ) = authorized.take(maxItems).mapIndexed { i, m ->
            VisualIndexLease(m, modelVersion, 2, epoch, "t$i")
        }
        override fun completeBatch(
            writes: List<VisualVectorWrite>, skipped: List<VisualIndexLease>
        ) = completeBatch(writes, skipped, emptyList())
        override fun completeBatch(
            writes: List<VisualVectorWrite>, skipped: List<VisualIndexLease>,
            videos: List<VisualMomentsWrite>
        ): Boolean {
            completions++
            this.writes = writes; this.skips = skipped; this.videos = videos
            (writes.map { it.lease } + skipped + videos.map { it.lease })
                .forEach { failures.remove(it.media) }
            return true
        }
        override fun releaseBatch(leases: List<VisualIndexLease>): Int {
            released += leases.size
            return leases.size
        }
        override fun recordFailure(lease: VisualIndexLease): Int =
            failures.merge(lease.media, 1, Int::plus)!!
    }

    private fun frames(vararg times: Long) =
        VisualVideoFrames.Sampled(times.map { VisualKeyframe(it, "frame@$it") })

    private fun embedVideo(
        sample: (VisualMedia) -> VisualVideoFrames<String>,
        embed: (String) -> FloatArray = { floatArrayOf(1f, 0f) },
        budget: VisualVideoBudget = VisualVideoBudget(),
        clock: () -> Long = { 0L },
        canContinue: () -> Boolean = { true },
        recycled: MutableList<String> = mutableListOf()
    ) = VisualScanStage.embedVideoWith(videos[0], sample, embed, { recycled += it },
        budget, clock, canContinue)

    @Test fun aWindowWithPhotosNeverRunsAVideo() {
        val window = listOf(videos[0] to true, photos[0] to false, videos[1] to true,
            photos[1] to false)
        assertEquals(listOf(photos[0] to false, photos[1] to false),
            VisualStepPlan.select(window) { it.second })
        // Only a window without photos runs a video, and then exactly one.
        assertEquals(listOf(videos[0] to true),
            VisualStepPlan.select(listOf(videos[0] to true, videos[1] to true)) { it.second })
        assertTrue(VisualStepPlan.select(emptyList<Pair<VisualMedia, Boolean>>()) { it.second }
            .isEmpty())
    }

    @Test fun momentsAreCappedAndSpreadEvenly() {
        val budget = VisualVideoBudget(maxMoments = 4)
        assertEquals(listOf(0, 3, 6, 9), budget.pick((0..9).toList()))
        assertEquals(listOf(0, 1, 2), budget.pick(listOf(0, 1, 2)))
        assertEquals(listOf(0), VisualVideoBudget(maxMoments = 1).pick((0..59).toList()))
        val all = (0 until 60).toList()
        val twelve = VisualVideoBudget().pick(all)
        assertEquals(12, twelve.size)
        assertEquals(0, twelve.first())
        assertEquals(59, twelve.last())
        assertEquals(twelve.sorted().distinct(), twelve)
    }

    @Test fun aVideoBecomesItsKeyframeMomentsAndEveryFrameIsRecycled() {
        val recycled = mutableListOf<String>()
        val embedded = mutableListOf<String>()
        val outcome = embedVideo({ frames(0, 4_000, 6_000) }, {
            embedded += it; floatArrayOf(1f, 0f)
        }, recycled = recycled)
        outcome as VisualEmbedOutcome.Moments
        assertEquals(listOf(0L, 4_000L, 6_000L), outcome.moments.map { it.frameMs })
        assertEquals(3, embedded.size)
        assertEquals(listOf("frame@0", "frame@4000", "frame@6000"), recycled)
    }

    @Test fun onlyTheBudgetedMomentsAreEmbeddedButAllAreRecycled() {
        val recycled = mutableListOf<String>()
        var embeds = 0
        val times = LongArray(60) { it * 2_000L }
        val outcome = embedVideo({ frames(*times) }, { embeds++; floatArrayOf(1f, 0f) },
            budget = VisualVideoBudget(maxMoments = 12), recycled = recycled)
        assertEquals(12, (outcome as VisualEmbedOutcome.Moments).moments.size)
        assertEquals(12, embeds)
        assertEquals(60, recycled.size)
    }

    @Test fun aSlowVideoKeepsTheMomentsEmbeddedWithinItsTimeBudget() {
        var now = 0L
        val outcome = embedVideo({ frames(0, 2_000, 4_000, 6_000) }, {
            now += 7_000L; floatArrayOf(1f, 0f)
        }, budget = VisualVideoBudget(maxElapsedMs = 20_000L), clock = { now })
        // 7 s per keyframe: three fit (21 s overruns by at most one), the fourth never starts.
        assertEquals(listOf(0L, 2_000L, 4_000L),
            (outcome as VisualEmbedOutcome.Moments).moments.map { it.frameMs })
    }

    @Test fun corruptDrmOrRevokedVideosAreTypedOutcomes() {
        assertEquals(VisualEmbedOutcome.Unreadable("UNREADABLE_VIDEO"),
            embedVideo({ VisualVideoFrames.Skipped("UNREADABLE_VIDEO") }))
        assertEquals(VisualEmbedOutcome.Unreadable("NO_DECODABLE_FRAMES"),
            embedVideo({ frames() }))
        assertTrue(embedVideo({ throw IOException("truncated moov") })
            is VisualEmbedOutcome.Unreadable)
        assertSame(VisualEmbedOutcome.Hold, embedVideo({ throw SecurityException("revoked") }))
        // Out-of-order or negative keyframe times are never stored.
        assertEquals(VisualEmbedOutcome.Unreadable("INVALID_KEYFRAMES"),
            embedVideo({ frames(2_000, 0) }))
        // Every keyframe rejected by the embedder: nothing to index, a skip.
        assertTrue(embedVideo({ frames(0, 2_000) }, { throw IllegalArgumentException() })
            is VisualEmbedOutcome.Unreadable)
        // A closed embedder or a failed guard holds; an OrtException-like error is counted.
        assertSame(VisualEmbedOutcome.Hold,
            embedVideo({ frames(0) }, { throw IllegalStateException("closed") }))
        assertSame(VisualEmbedOutcome.Hold, embedVideo({ frames(0, 2_000) }, canContinue = { false }))
        assertEquals(VisualEmbedOutcome.Failed("Exception"),
            embedVideo({ frames(0) }, { throw Exception("ort") }))
    }

    @Test fun aVideoStepCommitsItsMomentsAtomically() {
        val port = FakePort()
        val stage = VisualScanStage(port, "fake-v1", 2, { media ->
            embedVideo({ frames(0, 4_000) }).also { assertEquals(videos[0], media) }
        }, { 0L })
        val result = stage.runBatch("grant-A", listOf(videos[0]))
        assertEquals(1, result.indexed)
        assertEquals(VisualScanStop.COMPLETED, result.stop)
        assertEquals(1, port.completions)
        assertEquals(listOf(0L, 4_000L), port.videos.single().moments.map { it.frameMs })
        assertTrue(port.writes.isEmpty())
    }

    @Test fun anItemThatAlwaysFailsBecomesASkipAfterThreeTries() {
        val port = FakePort()
        val bad = photos[2]
        var tries = 0
        val stage = VisualScanStage(port, "fake-v1", 2, { media ->
            if (media == bad) { tries++; VisualEmbedOutcome.Failed("OrtException") }
            else VisualEmbedOutcome.Vector(floatArrayOf(1f, 0f))
        }, { 0L })
        val window = photos.take(4)
        repeat(VisualScanStage.MAX_ITEM_FAILURES - 1) {
            val held = stage.runBatch("grant-A", window)
            assertEquals(VisualScanStop.HELD, held.stop)
            assertEquals("nothing published from a failed step", 0, port.completions)
        }
        val last = stage.runBatch("grant-A", window)
        assertEquals(VisualScanStop.COMPLETED, last.stop)
        assertEquals(3, last.indexed)
        assertEquals(listOf(bad), port.skips.map { it.media })
        assertEquals(VisualScanStage.MAX_ITEM_FAILURES, tries)
        assertFalse("a skip clears the count", bad in port.failures)
    }

    @Test fun aNanOutputIsTheItemsFailureNotACrash() {
        val port = FakePort()
        val stage = VisualScanStage(port, "fake-v1", 2, { media ->
            if (media == photos[0]) VisualEmbedOutcome.Vector(floatArrayOf(Float.NaN, 0f))
            else VisualEmbedOutcome.Vector(floatArrayOf(1f, 0f))
        }, { 0L })
        assertEquals(VisualScanStop.HELD, stage.runBatch("grant-A", photos.take(3)).stop)
        assertEquals(1, port.failures[photos[0]])
        // Wrong-size moments count the same way.
        val video = VisualScanStage(port, "fake-v1", 2, {
            VisualEmbedOutcome.Moments(listOf(
                nl.atlas.gallery.featurevisual.store.VisualMoment(0, floatArrayOf(1f))))
        }, { 0L })
        assertEquals(VisualScanStop.HELD, video.runBatch("grant-A", listOf(videos[1])).stop)
        assertEquals(1, port.failures[videos[1]])
        assertEquals(0, port.completions)
    }

    @Test fun aStaleLeaseOrAPortWithoutCountsOnlyHolds() {
        // The default port counts nothing (0): the step holds and never skips.
        val port = object : VisualScanIndexPort {
            var completed = false
            override fun claimAuthorized(
                epoch: String, modelVersion: String, authorized: List<VisualMedia>, maxItems: Int
            ) = authorized.take(maxItems).map { VisualIndexLease(it, modelVersion, 2, epoch, "t") }
            override fun completeBatch(
                writes: List<VisualVectorWrite>, skipped: List<VisualIndexLease>
            ): Boolean { completed = true; return true }
            override fun releaseBatch(leases: List<VisualIndexLease>) = leases.size
        }
        val stage = VisualScanStage(port, "fake-v1", 2,
            { VisualEmbedOutcome.Failed("OrtException") }, { 0L })
        repeat(5) { assertEquals(VisualScanStop.HELD, stage.runBatch("g", photos.take(1)).stop) }
        assertFalse(port.completed)
        // ...and moments cannot reach a port that cannot store them.
        val videoStage = VisualScanStage(port, "fake-v1", 2, {
            VisualEmbedOutcome.Moments(listOf(
                nl.atlas.gallery.featurevisual.store.VisualMoment(0, floatArrayOf(1f, 0f))))
        }, { 0L })
        assertEquals(VisualScanStop.HELD, videoStage.runBatch("g", listOf(videos[0])).stop)
        assertFalse(port.completed)
    }

    @Test fun decodeAndEmbedCountsAnUnexpectedEmbedderError() {
        val outcome = VisualScanStage.decodeAndEmbedWith(photos[0], { "img" },
            { throw Exception("synthetic OrtException") }) { }
        assertEquals(VisualEmbedOutcome.Failed("Exception"), outcome)
    }

    // ---- #482 follow-up (b): the candidate window scan (review #486 note 1) ----

    private val queue = (0 until 200).map { VisualMedia("content://synthetic/q%03d".format(it), "g1") }

    /** Keyset windows of 64 over [items], like VisualIndexStore.queuedCandidates. */
    private fun reader(items: List<VisualMedia>, reads: MutableList<VisualMedia?> = mutableListOf()) =
        { after: VisualMedia? ->
            reads += after
            items.filter { after == null || it.key > after.key }.take(64)
        }

    @Test fun unauthorizedWindowsAreSkippedAndTheCursorKeepsThePlace() {
        val authorized = setOf(queue[150])
        val reads = mutableListOf<VisualMedia?>()
        val scan = VisualWindowScan.next(null, 16, reader(queue, reads)) {
            if (it in authorized) false else null
        }!!
        assertEquals(listOf(queue[150] to false), scan.window)
        // Windows 0..63 and 64..127 had nothing: the third (from 127) has item 150.
        assertEquals(listOf(null, queue[63], queue[127]), reads)
        assertEquals("the next step re-reads the productive window", queue[127], scan.cursor)
    }

    @Test fun theScanWrapsOnceAndThenReportsNothing() {
        // Cursor near the end; the only authorized item lies before it.
        val wrapped = VisualWindowScan.next(queue[190], 16, reader(queue)) {
            if (it == queue[5]) true else null
        }!!
        assertEquals(listOf(queue[5] to true), wrapped.window)
        assertEquals(null, wrapped.cursor)
        // Nothing authorized anywhere: one full pass, then empty with the cursor reset.
        val reads = mutableListOf<VisualMedia?>()
        val none = VisualWindowScan.next(queue[100], 16, reader(queue, reads)) { null }!!
        assertTrue(none.window.isEmpty())
        assertEquals(null, none.cursor)
        // 101..199 (2 windows + end), then 0..199 from the start (4 windows + end).
        assertEquals(8, reads.size)
    }

    @Test fun aStepReadsAtMostItsWindowBudgetAndKeepsTheCursor() {
        val big = (0 until 2_000).map { VisualMedia("content://synthetic/b%04d".format(it), "g1") }
        val reads = mutableListOf<VisualMedia?>()
        val scan = VisualWindowScan.next(null, 16, reader(big, reads)) { null }!!
        assertEquals(16, reads.size)
        assertTrue(scan.window.isEmpty())
        assertEquals(big[16 * 64 - 1], scan.cursor)
        // A gone epoch reads null: the step holds without moving anything.
        assertEquals(null, VisualWindowScan.next(null, 16, { null }) { false })
    }

    @Test fun aRefusedButStillAuthorizedVideoIsACountedFailure() {
        assertEquals(VisualEmbedOutcome.Failed("PROVIDER_REFUSED"),
            embedVideo({ VisualVideoFrames.Failed("PROVIDER_REFUSED") }))
    }
}
