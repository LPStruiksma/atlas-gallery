package nl.atlas.gallery.featurevisual

import nl.atlas.gallery.featurevisual.store.VisualIndexLease
import nl.atlas.gallery.featurevisual.store.VisualMedia
import nl.atlas.gallery.featurevisual.store.VisualVectorWrite
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

/** JVM-only: the decode/embedding lambda never needs Android Bitmap. */
class VisualScanStageTest {
    private val media = (0 until 12).map {
        VisualMedia("content://synthetic/image/$it", "generation:1")
    }

    private class FakePort : VisualScanIndexPort {
        var accepted = true
        var claimed = 0
        var completed: List<VisualVectorWrite> = emptyList()
        var skips: List<VisualIndexLease> = emptyList()
        var released = 0
        var claimAllowed = true
        override fun claimAuthorized(
            epoch: String, modelVersion: String, authorized: List<VisualMedia>, maxItems: Int
        ): List<VisualIndexLease> {
            if (!claimAllowed) return emptyList()
            val items = authorized.take(maxItems)
            claimed += items.size
            return items.mapIndexed { i, item ->
                VisualIndexLease(item, modelVersion, 4, epoch, "token-$i")
            }
        }
        override fun completeBatch(
            writes: List<VisualVectorWrite>, skipped: List<VisualIndexLease>
        ): Boolean {
            completed = writes
            skips = skipped
            return accepted
        }
        override fun releaseBatch(leases: List<VisualIndexLease>): Int {
            released += leases.size
            return leases.size
        }
    }

    @Test fun onlyEightAuthorizedImagesEnterAnAtomicCommit() {
        val port = FakePort()
        val visited = mutableListOf<String>()
        val stage = VisualScanStage(port, "fake-v1", 4, { item ->
            visited += item.key
            vec(1f, 0f, 0f, 0f)
        }, { 0L })
        val result = stage.runBatch("grant-A", media)
        assertEquals(8, result.claimed)
        assertEquals(8, result.indexed)
        assertFalse(result.held)
        assertEquals(media.take(8).map { it.key }, visited)
        assertEquals(8, port.completed.size)
        assertEquals(8, port.released)
    }

    @Test fun timeBudgetStopsWithoutPublishingUnprocessedLeases() {
        val port = FakePort()
        var ticks = 0L
        val stage = VisualScanStage(port, "fake-v1", 4,
            { vec(0f, 1f, 0f, 0f) },
            { ticks++ * 1_000L })
        val result = stage.runBatch("grant-A", media, VisualScanBudget(maxElapsedMs = 2_000L))
        assertEquals(1, result.indexed)
        assertTrue(result.held)
        assertEquals(1, port.completed.size)
        assertEquals(8, port.released)
    }

    @Test fun holdPublishesNoPartialBatch() {
        val port = FakePort()
        var called = 0
        val stage = VisualScanStage(port, "fake-v1", 4, {
            called++
            if (called == 2) VisualEmbedOutcome.Hold else vec(1f, 0f, 0f, 0f)
        }, { 0L })
        val result = stage.runBatch("grant-A", media)
        assertEquals(0, result.indexed)
        assertTrue(result.held)
        assertEquals(VisualScanStop.HELD, result.stop)
        assertTrue(port.completed.isEmpty())
        assertEquals(8, port.released)
    }

    @Test fun unreadableItemIsSkippedAndTheRestIsIndexedInOneCommit() {
        val port = FakePort()
        val stage = VisualScanStage(port, "fake-v1", 4, { item ->
            if (item == media[0]) VisualEmbedOutcome.Unreadable("corrupt") else vec(1f, 0f, 0f, 0f)
        }, { 0L })
        val result = stage.runBatch("grant-A", media)
        assertEquals(8, result.claimed)
        assertEquals(7, result.indexed)
        assertEquals(1, result.skipped)
        assertFalse(result.held)
        assertEquals(VisualScanStop.COMPLETED, result.stop)
        assertEquals(listOf(media[0]), port.skips.map { it.media })
        assertEquals(media.subList(1, 8), port.completed.map { it.lease.media })
    }

    /**
     * #480 item 1: an image that never decodes, at the front of the queue,
     * must not hold the items behind it. A store-like fake keeps queue state.
     */
    @Test fun permanentlyUndecodableFirstItemDoesNotBlockTheQueue() {
        val queued = media.take(8).toMutableList()
        val done = mutableSetOf<VisualMedia>()
        val skippedForever = mutableSetOf<VisualMedia>()
        val port = object : VisualScanIndexPort {
            override fun claimAuthorized(
                epoch: String, modelVersion: String, authorized: List<VisualMedia>, maxItems: Int
            ) = authorized.filter { it in queued }.take(maxItems).mapIndexed { i, m ->
                VisualIndexLease(m, modelVersion, 4, epoch, "t$i")
            }
            override fun completeBatch(
                writes: List<VisualVectorWrite>, skipped: List<VisualIndexLease>
            ): Boolean {
                writes.forEach { queued.remove(it.lease.media); done += it.lease.media }
                skipped.forEach { queued.remove(it.media); skippedForever += it.media }
                return true
            }
            override fun releaseBatch(leases: List<VisualIndexLease>) = 0
        }
        val decodes = mutableListOf<VisualMedia>()
        val stage = VisualScanStage(port, "fake-v1", 4, { item ->
            decodes += item
            VisualScanStage.decodeAndEmbedWith(item,
                decode = { if (it == media[0]) throw IOException("synthetic corrupt") else it },
                embed = { floatArrayOf(1f, 0f, 0f, 0f) }, recycle = {})
        }, { 0L })
        var passes = 0
        // Small batches, like a host under a tight budget: three passes at most.
        while (queued.isNotEmpty() && passes < 3) {
            stage.runBatch("grant-A", media.take(8), VisualScanBudget(maxItems = 3))
            passes++
        }
        assertTrue(queued.isEmpty())
        assertEquals(media.subList(1, 8).toSet(), done)
        assertEquals(setOf(media[0]), skippedForever)
        // The unreadable image was tried once, not on every pass.
        assertEquals(1, decodes.count { it == media[0] })
    }

    @Test fun decodeAndEmbedMapsFailuresToTypedOutcomes() {
        val m = media[0]
        fun outcome(decode: (VisualMedia) -> String?, embed: (String) -> FloatArray = {
            floatArrayOf(1f)
        }): VisualEmbedOutcome {
            val recycled = mutableListOf<String>()
            return VisualScanStage.decodeAndEmbedWith(m, decode, embed) { recycled += it }
        }
        assertTrue(outcome({ null }) is VisualEmbedOutcome.Unreadable)
        assertTrue(outcome({ throw IOException("bad header") }) is VisualEmbedOutcome.Unreadable)
        assertTrue(outcome({ throw IllegalArgumentException("unsupported") })
            is VisualEmbedOutcome.Unreadable)
        assertSame(VisualEmbedOutcome.Hold, outcome({ throw SecurityException("revoked") }))
        assertTrue(outcome({ "img" }, { throw IllegalArgumentException("0 px") })
            is VisualEmbedOutcome.Unreadable)
        assertSame(VisualEmbedOutcome.Hold,
            outcome({ "img" }, { throw IllegalStateException("closed") }))
        assertTrue(outcome({ "img" }) is VisualEmbedOutcome.Vector)
        // The decoded image is always released, even when embedding fails.
        val recycled = mutableListOf<String>()
        VisualScanStage.decodeAndEmbedWith(m, { "img" },
            { throw IllegalArgumentException() }) { recycled += it }
        assertEquals(listOf("img"), recycled)
    }

    @Test fun guardStopsBeforeTheNextItemAndKeepsFinishedWork() {
        val port = FakePort()
        var allowed = 3
        val stage = VisualScanStage(port, "fake-v1", 4, { vec(1f, 0f, 0f, 0f) }, { 0L })
        val result = stage.runBatch("grant-A", media, canContinue = { allowed-- > 0 })
        assertEquals(3, result.indexed)
        assertTrue(result.held)
        assertEquals(VisualScanStop.BUDGET_OR_GUARD, result.stop)
        assertEquals(8, port.released)
    }

    @Test fun guardFalseAtStartDecodesNothing() {
        val port = FakePort()
        val stage = VisualScanStage(port, "fake-v1", 4,
            { fail("Must not decode while the guard holds"); VisualEmbedOutcome.Hold }, { 0L })
        val result = stage.runBatch("grant-A", media, canContinue = { false })
        assertEquals(0, result.indexed)
        assertEquals(VisualScanStop.BUDGET_OR_GUARD, result.stop)
        assertTrue(port.completed.isEmpty())
        assertEquals(8, port.released)
    }

    /** #480 item 2: one slow item may overrun the cooperative budget once, never twice. */
    @Test fun slowItemOverrunsTheBudgetAtMostOnce() {
        val port = FakePort()
        var now = 0L
        val stage = VisualScanStage(port, "fake-v1", 4, {
            now += 5_000L // each embedding alone exceeds the 2 s budget
            vec(1f, 0f, 0f, 0f)
        }, { now })
        val result = stage.runBatch("grant-A", media)
        assertEquals(1, result.indexed)
        assertEquals(5_000L, now)
        assertEquals(VisualScanStop.BUDGET_OR_GUARD, result.stop)
    }

    @Test fun rejectedLeaseMakesWholeStepFailClosed() {
        val port = FakePort().apply { accepted = false }
        val stage = VisualScanStage(port, "fake-v1", 4,
            { vec(1f, 0f, 0f, 0f) }, { 0L })
        val result = stage.runBatch("grant-A", media)
        assertEquals(0, result.indexed)
        assertTrue(result.held)
        assertEquals(8, port.completed.size)
        assertEquals(8, port.released)
    }

    @Test fun embeddingErrorAlwaysReleasesEveryClaim() {
        val port = FakePort()
        val stage = VisualScanStage(port, "fake-v1", 4,
            { throw IllegalStateException("Synthetic embed failure") }, { 0L })
        try {
            stage.runBatch("grant-A", media)
            fail("Expected error")
        } catch (_: IllegalStateException) {
            assertEquals(8, port.released)
            assertTrue(port.completed.isEmpty())
        }
    }

    @Test fun emptyOrStaleAuthorizedScopeDoesNotDecode() {
        val port = FakePort().apply { claimAllowed = false }
        val stage = VisualScanStage(port, "fake-v1", 4,
            { fail("Must not decode without a valid claim"); VisualEmbedOutcome.Hold }, { 0L })
        val result = stage.runBatch("stale-grant", media)
        assertEquals(0, result.indexed)
        assertEquals(0, result.claimed)
        assertFalse(result.held)
        assertEquals(0, port.released)
    }

    private fun vec(vararg v: Float) = VisualEmbedOutcome.Vector(v)
}

/** The host's next-batch choice when faces and the visual stage share one worker. */
class VisualNextBatchTest {
    private fun result(stop: VisualScanStop, indexed: Int = 0, skipped: Int = 0) =
        VisualScanResult(claimed = 8, indexed = indexed, held = stop != VisualScanStop.COMPLETED,
            skipped = skipped, stop = stop)

    @Test fun visualProgressContinuesAfterFacesDrain() {
        val visual = nl.atlas.gallery.nextVisualBatchAfter(
            result(VisualScanStop.COMPLETED, indexed = 7, skipped = 1), stillQueued = true)
        assertEquals(nl.atlas.gallery.NextBatch.NOW, visual)
        assertEquals(nl.atlas.gallery.NextBatch.NOW,
            nl.atlas.gallery.combineNextBatch(nl.atlas.gallery.NextBatch.NONE, visual))
    }

    @Test fun guardStopWithoutProgressWaitsInsteadOfSpinning() {
        assertEquals(nl.atlas.gallery.NextBatch.LATER, nl.atlas.gallery.nextVisualBatchAfter(
            VisualScanResult(0, 0, held = true, stop = VisualScanStop.BUDGET_OR_GUARD), true))
        assertEquals(nl.atlas.gallery.NextBatch.NOW, nl.atlas.gallery.nextVisualBatchAfter(
            result(VisualScanStop.BUDGET_OR_GUARD, indexed = 2), true))
        assertEquals(nl.atlas.gallery.NextBatch.LATER, nl.atlas.gallery.nextVisualBatchAfter(
            result(VisualScanStop.HELD), true))
    }

    @Test fun drainedOrAbsentVisualStageQueuesNothing() {
        assertEquals(nl.atlas.gallery.NextBatch.NONE, nl.atlas.gallery.nextVisualBatchAfter(
            VisualScanResult(0, 0, held = false), stillQueued = false))
        assertEquals(nl.atlas.gallery.NextBatch.LATER, nl.atlas.gallery.nextVisualBatchAfter(
            VisualScanResult(0, 0, held = false), stillQueued = true))
        assertEquals(nl.atlas.gallery.NextBatch.NONE,
            nl.atlas.gallery.nextVisualBatchAfter(null, stillQueued = true))
        assertEquals(nl.atlas.gallery.NextBatch.LATER, nl.atlas.gallery.combineNextBatch(
            nl.atlas.gallery.NextBatch.LATER, nl.atlas.gallery.NextBatch.NONE))
        assertEquals(nl.atlas.gallery.NextBatch.NONE, nl.atlas.gallery.combineNextBatch(
            nl.atlas.gallery.NextBatch.NONE, nl.atlas.gallery.NextBatch.NONE))
    }
}
