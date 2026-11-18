package nl.atlas.gallery

import org.junit.Assert.*
import org.junit.Test

/** Synthetic admission tests; no Android database, model bytes or user media. */
class LazyStoreAdmissionTest {
    @Test fun holdersDoNoWorkUntilExplicitBackgroundAdmission() {
        var opens = 0
        val holder = LazyBackgroundHolder("People database") { ++opens; "opened" }
        assertEquals(0, opens)
        assertFalse(holder.isOpened)
        assertEquals("opened", holder.get())
        assertEquals(1, opens)
        assertTrue(holder.isOpened)
        assertEquals("opened", holder.get())
        assertEquals(1, opens)
    }

    @Test fun failedOpenIsRetriedWithoutPoisoningTheHolder() {
        var attempts = 0
        val holder = LazyBackgroundHolder("model") {
            if (++attempts == 1) error("synthetic unavailable")
            42
        }
        assertThrows(IllegalStateException::class.java) { holder.get() }
        assertFalse(holder.isOpened)
        assertEquals(42, holder.get())
        assertEquals(2, attempts)
    }

    @Test fun noMainThreadAdmissionIncludingAfterSuccessfulWarmup() {
        val owner = Thread.currentThread()
        val holder = LazyBackgroundHolder("model", { "ok" }, isMainThread = { Thread.currentThread() === owner })
        assertThrows(IllegalStateException::class.java) { holder.get() }
        assertFalse(holder.isOpened)
        val worker = Thread { assertEquals("ok", holder.get()) }
        worker.start()
        worker.join()
        assertTrue(holder.isOpened)
        assertThrows(IllegalStateException::class.java) { holder.get() }
    }
}
