package nl.atlas.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeMemorySamplerTest {
    @Test fun conversionRunsOnlyOnInjectedBackgroundBoundaryAndConcurrentRequestsCoalesce() {
        val aggregate = PerformanceAggregates()
        val queued = ArrayDeque<() -> Unit>()
        var now = 100L
        var reads = 0
        val sampler = NativeMemorySampler(aggregate, { queued.addLast(it) }, { now }, {
            reads++
            NativeMemoryReading(3L * 1024 * 1024, 5L * 1024, 7L, 9L)
        })
        assertTrue(sampler.request())
        assertFalse(sampler.request())
        assertEquals(0, reads)
        queued.removeFirst().invoke()
        assertEquals(3.0, aggregate.snapshot("native_heap_mib").max!!, 0.0)
        assertEquals(5.0, aggregate.snapshot("process_pss_mib").max!!, 0.0)
        now += 59_999
        assertFalse(sampler.request())
        now++
        assertTrue(sampler.request())
    }

    @Test fun invalidReadingIsMissingDataAndIsNotPersisted() {
        val aggregate = PerformanceAggregates()
        var persisted = 0
        val sampler = NativeMemorySampler(aggregate, { it() }, { 1L }, {
            NativeMemoryReading(-1L, 2L, 3L, 4L)
        }, { persisted++ })
        sampler.request()
        assertEquals(0, aggregate.snapshot("native_heap_mib").count)
        assertEquals(0, persisted)
    }

    @Test fun rejectedDispatchDoesNotPermanentlySuppressSampling() {
        val aggregate = PerformanceAggregates()
        var sends = 0
        val sampler = NativeMemorySampler(aggregate, { work ->
            if (sends++ == 0) throw IllegalStateException("synthetic rejection")
            work()
        }, { 100L }, {
            NativeMemoryReading(1024L * 1024, 2048L, 300L, 400L)
        })
        assertFalse(sampler.request())
        assertTrue(sampler.request())
        assertEquals(1, aggregate.snapshot("native_heap_mib").count)
    }

    @Test fun failedReadAndPersistenceNeverCrashTheCaller() {
        val aggregate = PerformanceAggregates()
        val badRead = NativeMemorySampler(aggregate, { it() }, { 1L }, {
            throw IllegalStateException("synthetic Debug failure")
        })
        assertTrue(badRead.request())
        assertEquals(0, aggregate.snapshot("native_heap_mib").count)
        val badPersist = NativeMemorySampler(aggregate, { it() }, { 1L }, {
            NativeMemoryReading(1024L * 1024, 1024L, 3L, 4L)
        }, { throw java.io.IOException("synthetic disk failure") })
        assertTrue(badPersist.request())
        assertEquals(1, aggregate.snapshot("native_heap_mib").count)
    }

    @Test fun crashContextDistinguishesCurrentFromPriorProcessWithoutAttributingAnExit() {
        val sample = NativeMemoryReading(12L * 1024 * 1024, 34L * 1024, 56L, 78L)
        assertEquals("Last native/PSS sample (this process): native_heap_mib=12 pss_mib=34 captured_ms=56",
            formatNativeMemorySample(sample, 78L))
        assertEquals("Last native/PSS sample (prior process): native_heap_mib=12 pss_mib=34 captured_ms=56",
            formatNativeMemorySample(sample, 79L))
    }
}
