package nl.atlas.gallery.featureaiedit.provider.hosted

import nl.atlas.gallery.featureaiedit.provider.EditOutcome
import nl.atlas.gallery.featureaiedit.provider.ImageFormat
import nl.atlas.gallery.featureaiedit.provider.JobSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch

class HostedJsonTest {
    @Test fun parsesDocumentedShapes() {
        val value = HostedJson.parse("""{"a":[1,-2.5e3,true,false,null],"b":{"c":"x\"y\u00e9\n"}}""").obj()!!
        assertEquals(listOf(1L, -2500.0, true, false, null), value["a"].arr())
        assertEquals("x\"yé\n", value["b"].obj()!!["c"].str())
    }

    @Test fun rejectsMalformedAndDeepInput() {
        for (bad in listOf("", "{", "[1,]", "{\"a\" 1}", "01", "\"\u0001\"", "tru", "{} x", "[" .repeat(100) + "]".repeat(100))) {
            assertNull(bad, HostedJson.parseOrNull(bad.toByteArray()))
        }
    }

    @Test fun quoteRoundTrips() {
        val text = "line\nquote\" back\\ tab\t ctl\u0001 sep  é"
        assertEquals(text, HostedJson.parse(HostedJson.quote(text)))
    }
}

class ImageProbeTest {
    @Test fun probesPngFromBytes() {
        val probe = ImageProbe.probe(SyntheticPng.encode(31, 17))!!
        assertEquals(ImageProbe.Probe(ImageFormat.PNG, 31, 17), probe)
    }

    @Test fun probesJpegSofAfterOtherSegments() {
        val jpeg = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(),
            0xff.toByte(), 0xe0.toByte(), 0x00, 0x04, 0x00, 0x00, // APP0, 2 payload bytes
            0xff.toByte(), 0xc0.toByte(), 0x00, 0x0b, 0x08, 0x01, 0x2c, 0x02, 0x58, 0x01, 0x01, 0x11, 0x00,
            0xff.toByte(), 0xd9.toByte()
        )
        assertEquals(ImageProbe.Probe(ImageFormat.JPEG, 600, 300), ImageProbe.probe(jpeg))
    }

    @Test fun probesWebpVariants() {
        fun riff(chunk: String, payload: ByteArray): ByteArray =
            "RIFF".toByteArray() + byteArrayOf(0, 0, 0, 0) + "WEBP".toByteArray() + chunk.toByteArray() +
                byteArrayOf(0, 0, 0, 0) + payload + ByteArray(16)
        val vp8x = riff("VP8X", byteArrayOf(0, 0, 0, 0, 0x3f, 0x01, 0x00, 0x1f, 0x01, 0x00)) // 320 x 288
        assertEquals(ImageProbe.Probe(ImageFormat.WEBP, 320, 288), ImageProbe.probe(vp8x))
        val vp8 = riff("VP8 ", byteArrayOf(0, 0, 0, 0x9d.toByte(), 0x01, 0x2a, 0x40, 0x01, 0xf0.toByte(), 0x00))
        assertEquals(ImageProbe.Probe(ImageFormat.WEBP, 320, 240), ImageProbe.probe(vp8))
    }

    @Test fun rejectsLabelsThatAreNotImages() {
        assertNull(ImageProbe.probe("PNG image 1024x1024".toByteArray()))
        assertNull(ImageProbe.probe(ByteArray(0)))
        assertNull(ImageProbe.probe(SyntheticPng.encode(4, 4).copyOf(20)))
    }
}

class HostedEditJobTest {
    @Test fun cancelWinsOverLateCompletionAndRunsProviderCancel() {
        val release = CountDownLatch(1)
        val remoteCancel = CountDownLatch(1)
        val job = HostedEditJob("t", 10, null, { context ->
            release.await()
            if (context.isCancelled) EditOutcome.Cancelled else EditOutcome.Failed(
                nl.atlas.gallery.featureaiedit.provider.FailureKind.UNKNOWN, false)
        }, onCancel = { remoteCancel.countDown() })
        assertTrue(job.poll() is JobSnapshot.Pending)
        job.cancel()
        release.countDown()
        assertEquals(EditOutcome.Cancelled, (job.poll() as JobSnapshot.Completed).outcome)
        assertTrue(remoteCancel.await(2, java.util.concurrent.TimeUnit.SECONDS))
    }

    @Test fun workerExceptionsBecomeTypedFailuresWithoutMessages() {
        val job = HostedEditJob("t", 500, null, { throw IllegalStateException("secret detail") })
        val outcome = (job.poll() as JobSnapshot.Completed).outcome as EditOutcome.Failed
        assertEquals("ADAPTER_ERROR", outcome.message)
    }
}
