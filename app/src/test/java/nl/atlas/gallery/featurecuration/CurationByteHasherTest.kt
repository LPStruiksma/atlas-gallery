package nl.atlas.gallery.featurecuration

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CancellationException
import org.junit.Test

class CurationByteHasherTest {
    @Test fun completeReadMatchesKnownSha256WithoutWriting() {
        val bytes = "abc".toByteArray()
        val got = hashCurationBytes(bytes.size.toLong(), { ByteArrayInputStream(bytes) }, { true })!!
        check(got.sha256 == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        check(got.bytes == bytes.size.toLong())
    }
    @Test fun unauthorizedSourceCannotEvenBeOpened() {
        var opened = false
        val got = hashCurationBytes(10, { opened = true; ByteArrayInputStream(ByteArray(10)) }, { false })
        check(got == null && !opened)
    }
    @Test fun zeroAndOverBudgetSizeSkipOpen() {
        var opens = 0
        val open = { opens++; ByteArrayInputStream(byteArrayOf(1)) }
        check(hashCurationBytes(0,open,{true}) == null)
        check(hashCurationBytes(5,open,{true},maxBytes=4) == null)
        check(opens == 0)
    }
    @Test fun shortenedOrGrownStreamIsNotExactEvidence() {
        check(hashCurationBytes(4,{ByteArrayInputStream(byteArrayOf(1,2,3))},{true}) == null)
        check(hashCurationBytes(2,{ByteArrayInputStream(byteArrayOf(1,2,3))},{true}) == null)
    }
    @Test fun revokedMidReadAbstainsAndClosesSource() {
        var authorized = true
        var closed = false
        val input = object : ByteArrayInputStream(ByteArray(128 * 1024)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = super.read(b,off,len)
                authorized = false
                return n
            }
            override fun close() { closed = true; super.close() }
        }
        check(hashCurationBytes(128 * 1024L,{input},{authorized}) == null)
        check(closed)
    }
    @Test fun ioAndSecurityFailuresReturnUnknownButCancellationPropagates() {
        check(hashCurationBytes(1,{throw IOException("fictional")},{true}) == null)
        check(hashCurationBytes(1,{throw SecurityException("fictional")},{true}) == null)
        try {
            hashCurationBytes(1,{ByteArrayInputStream(byteArrayOf(1))},{true},
                checkCancelled = { throw CancellationException("stop") })
            error("cancellation swallowed")
        } catch (e: CancellationException) { check(e.message == "stop") }
    }
    @Test fun zeroLengthReadLoopFailsClosed() {
        val input = object : InputStream() {
            override fun read(): Int = 1
            override fun read(b: ByteArray, off: Int, len: Int): Int = 0
        }
        check(hashCurationBytes(10,{input},{true}) == null)
    }

    /** E2d (#445): a synthetic stream of [size] bytes, 64 KiB per read, no big buffer. */
    private class Synthetic(private val size: Long, private val onRead: (Long) -> Unit = {}) :
        InputStream() {
        var served = 0L
        override fun read(): Int = if (served >= size) -1 else { served++; 0 }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (served >= size) return -1
            val n = minOf(len.toLong(), size - served).toInt()
            java.util.Arrays.fill(b, off, off + n, 7)
            served += n
            onRead(served)
            return n
        }
    }

    @Test fun grantIsReadAboutOncePerMibNotPerChunk() {
        val mib = 1024L * 1024
        val size = 64 * mib
        var grantReads = 0
        val input = Synthetic(size)
        val got = hashCurationBytes(size, { input }, { grantReads++; true })
        check(got != null && got.bytes == size && input.served == size)
        // Before open, before the first byte, once per MiB, after the last byte.
        check(grantReads <= 64 + 3) { "grant read $grantReads times for 64 MiB" }
        check(grantReads >= 64) { "grant read only $grantReads times" }
        // The old per-64-KiB loop read it 1,024+ times for the same file.
    }

    @Test fun throttledGrantStillFailsClosedWhenRevokedMidFile() {
        val mib = 1024L * 1024
        var authorized = true
        var grantReadsAfterRevoke = 0
        val input = Synthetic(16 * mib) { served -> if (served >= 3 * mib + 1) authorized = false }
        val got = hashCurationBytes(16 * mib, { input }, {
            if (!authorized) grantReadsAfterRevoke++
            authorized
        })
        check(got == null)
        // Caught at the next MiB boundary: it stopped reading, not at the end of the file.
        check(grantReadsAfterRevoke == 1 && input.served <= 5 * mib) { "served ${input.served}" }
    }

    @Test fun revokedDuringTheLastPartialMibIsCaughtAfterTheLastByte() {
        val mib = 1024L * 1024
        val size = 2 * mib + 4096
        var authorized = true
        val input = Synthetic(size) { served -> if (served == size) authorized = false }
        check(hashCurationBytes(size, { input }, { authorized }) == null)
        check(input.served == size)
    }

    @Test fun grantIsCheckedBeforeTheFirstByteEvenAfterOpening() {
        var authorized = true
        val input = Synthetic(10) { error("read after revoke") }
        val got = hashCurationBytes(10, { authorized = false; input }, { authorized })
        check(got == null && input.served == 0L)
    }
}
