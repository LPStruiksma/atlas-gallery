package nl.atlas.gallery.featureidentity.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Review of #688: the private source copy never outlives its launch, also when Back comes mid-copy. */
class SwapSourceFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Hands out one chunk, then blocks until [release]; reports the first read through [started]. */
    private class SlowStream(private val started: CountDownLatch, private val release: CountDownLatch) : InputStream() {
        private var sent = 0
        override fun read(): Int = throw UnsupportedOperationException()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (sent == 0) { started.countDown(); sent = 1; b.fill(7, off, off + len); return len }
            release.await(10, TimeUnit.SECONDS)
            if (sent >= 3) return -1
            sent++; b.fill(7, off, off + len); return len
        }
    }

    @Test fun copyKeepsOneSessionAndReusesACompleteCopy() = runBlocking {
        val cache = tmp.root
        val a = SwapSourceFiles.copy(cache, "a") { ByteArray(10) { 1 }.inputStream() }!!
        assertEquals(10L, a.length())
        assertEquals(a, SwapSourceFiles.copy(cache, "a") { throw AssertionError("must reuse") })
        SwapSourceFiles.copy(cache, "b") { ByteArray(4).inputStream() }
        assertFalse("other session cleared on the next launch", SwapSourceFiles.dir(cache, "a").exists())
        assertTrue(SwapSourceFiles.dir(cache, "b").resolve("source").isFile)
    }

    @Test fun failuresLeaveNoFolder() = runBlocking {
        val cache = tmp.root
        assertNull(SwapSourceFiles.copy(cache, "s") { null })
        assertFalse(SwapSourceFiles.dir(cache, "s").exists())
        assertNull(SwapSourceFiles.copy(cache, "s") { throw IOException("gone") })
        assertFalse(SwapSourceFiles.dir(cache, "s").exists())
        assertNull("empty source", SwapSourceFiles.copy(cache, "s") { ByteArray(0).inputStream() })
        assertFalse(SwapSourceFiles.dir(cache, "s").exists())
    }

    @Test fun backDuringCopyCancelsAndRemovesThePartialFile() = runBlocking {
        val cache = tmp.root
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val copy = async(Dispatchers.Default) { SwapSourceFiles.copy(cache, "s") { SlowStream(started, release) } }
        assertTrue(started.await(10, TimeUnit.SECONDS))
        assertTrue("partial copy on disk", SwapSourceFiles.dir(cache, "s").resolve("source.part").isFile)
        // Back while preparing: the composition is cancelled and the entry asks for removal.
        copy.cancel()
        val discard = SwapSourceFiles.discard(cache, "s")
        release.countDown()
        withTimeout(10_000) { discard.join() }
        assertFalse(SwapSourceFiles.dir(cache, "s").exists())
    }

    @Test fun removalAskedDuringACopyThatFinishesStillRuns() = runBlocking {
        val cache = tmp.root
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val copied = CompletableDeferred<Boolean>()
        val copy = async(Dispatchers.Default) {
            SwapSourceFiles.copy(cache, "s") { SlowStream(started, release) }.also { copied.complete(it != null) }
        }
        assertTrue(started.await(10, TimeUnit.SECONDS))
        val discard = SwapSourceFiles.discard(cache, "s") // waits for the running copy
        release.countDown()
        assertTrue("copy completed", copied.await())
        withTimeout(10_000) { discard.join() }
        copy.await()
        assertFalse("final source removed after the copy", SwapSourceFiles.dir(cache, "s").exists())
    }
}
