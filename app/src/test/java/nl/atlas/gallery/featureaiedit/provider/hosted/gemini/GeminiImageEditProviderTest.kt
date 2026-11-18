package nl.atlas.gallery.featureaiedit.provider.hosted.gemini

import nl.atlas.gallery.featureaiedit.network.EditHttpConfig
import nl.atlas.gallery.featureaiedit.provider.EditImage
import nl.atlas.gallery.featureaiedit.provider.EditOutcome
import nl.atlas.gallery.featureaiedit.provider.FailureKind
import nl.atlas.gallery.featureaiedit.provider.ImageEditProvider
import nl.atlas.gallery.featureaiedit.provider.ImageEditProviderContract
import nl.atlas.gallery.featureaiedit.provider.ImageEditRequest
import nl.atlas.gallery.featureaiedit.provider.ImageFormat
import nl.atlas.gallery.featureaiedit.provider.JobSnapshot
import nl.atlas.gallery.featureaiedit.provider.OutputSpec
import nl.atlas.gallery.featureaiedit.provider.hosted.FakeProviderServer
import nl.atlas.gallery.featureaiedit.provider.hosted.FakeRequest
import nl.atlas.gallery.featureaiedit.provider.hosted.FakeResponse
import nl.atlas.gallery.featureaiedit.provider.hosted.HostedJson
import nl.atlas.gallery.featureaiedit.provider.hosted.SyntheticImageLoader
import nl.atlas.gallery.featureaiedit.provider.hosted.SyntheticPng
import nl.atlas.gallery.featureaiedit.provider.hosted.arr
import nl.atlas.gallery.featureaiedit.provider.hosted.obj
import nl.atlas.gallery.featureaiedit.provider.hosted.str
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64

/**
 * Fixtures are synthetic replies written from the documented `generateContent` shapes; no
 * network beyond loopback, no key, no photo. The fake key is assembled at runtime.
 */
private val FAKE_KEY = listOf("fake", "gemini", "key", "0123456789").joinToString("-")

private fun successReply(png: ByteArray = SyntheticPng.encode(8, 8), extraParts: String = "") = """
    {"candidates":[{"content":{"role":"model","parts":[$extraParts
      {"inlineData":{"mimeType":"image/png","data":"${Base64.getEncoder().encodeToString(png)}"}}]},
      "finishReason":"STOP","index":0}],
     "usageMetadata":{"promptTokenCount":1300,"candidatesTokenCount":1290},
     "modelVersion":"gemini-3.1-flash-image","responseId":"synthetic-response-1"}
""".trimIndent()

private class GeminiHarness(responder: (FakeRequest) -> FakeResponse) : AutoCloseable {
    val server = FakeProviderServer(responder)
    val output: File = Files.createTempDirectory("gemini-out").toFile()
    val loader = SyntheticImageLoader()

    fun provider(
        key: String? = FAKE_KEY,
        model: GeminiModel = GeminiModel.DEFAULT,
        readTimeoutMs: Int = 2_000,
        pollWaitMs: Long = 50,
        maxReplyBytes: Int = 40_000_000
    ) = GeminiImageEditProvider(
        model = model,
        apiKey = { key },
        images = loader,
        outputDirectory = output,
        transport = server.transport(),
        httpConfig = EditHttpConfig(
            GeminiImageEditProvider.API_BASE, setOf(GeminiImageEditProvider.API_HOST),
            connectTimeoutMs = 1_000, readTimeoutMs = readTimeoutMs, overallTimeoutMs = 5_000, maxAttempts = 1
        ),
        pollWaitMs = pollWaitMs,
        maxReplyBytes = maxReplyBytes
    )

    override fun close() {
        server.close()
        output.deleteRecursively()
    }
}

private fun request(
    output: OutputSpec = OutputSpec(8, 8, ImageFormat.PNG),
    references: List<EditImage> = emptyList()
) = ImageEditRequest(
    source = EditImage("synthetic-source", 8, 8, ImageFormat.PNG),
    instruction = "Make the synthetic sky \"warmer\"",
    references = references,
    output = output
)

class GeminiImageEditProviderContractTest : ImageEditProviderContract() {
    private val harnesses = mutableListOf<GeminiHarness>()

    @After fun closeAll() = harnesses.forEach { it.close() }

    override fun providerFor(fixture: Fixture): ImageEditProvider {
        val harness = GeminiHarness { _ ->
            when (fixture) {
                Fixture.SUCCESS -> FakeResponse.json(successReply())
                Fixture.REFUSAL -> FakeResponse.json("""{"promptFeedback":{"blockReason":"SAFETY"}}""")
                Fixture.FAILURE -> FakeResponse(200, dropConnection = true)
                Fixture.SLOW_ASYNC -> FakeResponse.json(successReply(), delayMs = 1_500)
                Fixture.CANCELLABLE_ASYNC -> FakeResponse.json(successReply(), delayMs = 120)
            }
        }
        harnesses.add(harness)
        return harness.provider(pollWaitMs = 50)
    }
}

class GeminiImageEditProviderTest {
    private fun outcome(harness: GeminiHarness, req: ImageEditRequest = request(), key: String? = FAKE_KEY): EditOutcome =
        harness.provider(key = key).submit(req).await()

    @Test fun requestMatchesDocumentedWireFormat() {
        GeminiHarness { FakeResponse.json(successReply()) }.use { h ->
            val reference = EditImage("synthetic-reference", 8, 8, ImageFormat.PNG)
            val result = outcome(h, request(OutputSpec(1600, 900, ImageFormat.PNG), listOf(reference)))
            assertTrue(result is EditOutcome.Success)
            val sent = h.server.requests.single()
            assertEquals("POST", sent.method)
            assertEquals(GeminiImageEditProvider.API_HOST, sent.host)
            assertEquals("/v1beta/models/gemini-3.1-flash-image:generateContent", sent.path)
            assertEquals(FAKE_KEY, sent.headers["x-goog-api-key"])
            assertFalse("key never in the URL", sent.path.contains(FAKE_KEY))
            assertNull(sent.headers["authorization"])
            assertTrue(sent.headers["content-type"]!!.startsWith("application/json"))

            val body = HostedJson.parse(sent.text).obj()!!
            val parts = body["contents"].arr()!!.single().obj()!!["parts"].arr()!!
            assertEquals("Make the synthetic sky \"warmer\"", parts[0].obj()!!["text"].str())
            assertEquals(3, parts.size)
            for (part in parts.drop(1)) {
                val inline = part.obj()!!["inlineData"].obj()!!
                assertEquals("image/png", inline["mimeType"].str())
                assertTrue(Base64.getDecoder().decode(inline["data"].str()!!).contentEquals(SyntheticPng.encode(8, 8)))
            }
            assertEquals(listOf("synthetic-source", "synthetic-reference"), h.loader.loaded)
            val config = body["generationConfig"].obj()!!
            assertEquals(listOf("IMAGE"), config["responseModalities"].arr())
            val image = config["imageConfig"].obj()!!
            assertEquals("16:9", image["aspectRatio"].str())
            assertEquals("2K", image["imageSize"].str())
        }
    }

    @Test fun successStoresVerifiedImageWithProvenance() {
        val png = SyntheticPng.encode(12, 7)
        GeminiHarness { FakeResponse.json(successReply(png)) }.use { h ->
            val result = outcome(h) as EditOutcome.Success
            val image = result.images.single()
            assertEquals(12, image.width)
            assertEquals(7, image.height)
            assertEquals(ImageFormat.PNG, image.format)
            val file = File(image.handle)
            assertTrue(file.isFile && file.parentFile == h.output)
            assertTrue(file.readBytes().contentEquals(png))
            assertEquals("gemini-3.1-flash-image", result.providerModel)
            assertEquals("gemini-3.1-flash-image", result.providerVersion)
            assertEquals("synthetic-response-1", result.metadata["responseId"])
            assertEquals("STOP", result.metadata["finishReason"])
        }
    }

    @Test fun thoughtImagesAreIgnoredInFavourOfFinalImage() {
        val thought = SyntheticPng.encode(2, 2, seed = 1)
        val final = SyntheticPng.encode(9, 9, seed = 2)
        val extra = """{"thought":true,"inlineData":{"mimeType":"image/png","data":"${Base64.getEncoder().encodeToString(thought)}"}},
                       {"text":"Here is the edit."},"""
        GeminiHarness { FakeResponse.json(successReply(final, extra)) }.use { h ->
            val result = outcome(h) as EditOutcome.Success
            assertEquals(9, result.images.single().width)
        }
    }

    @Test fun promptAndOutputSafetyBlocksAreRefusalsNotFailures() {
        val cases = mapOf(
            """{"promptFeedback":{"blockReason":"PROHIBITED_CONTENT"}}""" to "prompt:PROHIBITED_CONTENT",
            """{"candidates":[{"finishReason":"IMAGE_SAFETY","content":{"parts":[]}}]}""" to "finish:IMAGE_SAFETY",
            """{"candidates":[{"finishReason":"SAFETY"}]}""" to "finish:SAFETY",
            """{"candidates":[{"finishReason":"IMAGE_PROHIBITED_CONTENT"}]}""" to "finish:IMAGE_PROHIBITED_CONTENT"
        )
        for ((reply, code) in cases) {
            GeminiHarness { FakeResponse.json(reply) }.use { h ->
                val result = outcome(h)
                assertTrue(reply, result is EditOutcome.Refused)
                assertEquals(code, (result as EditOutcome.Refused).providerCode)
            }
        }
    }

    @Test fun replyWithoutImageIsNeverASuccess() {
        val textOnly = """{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"I cannot see an image."}]}}]}"""
        GeminiHarness { FakeResponse.json(textOnly) }.use { h ->
            val result = outcome(h) as EditOutcome.Failed
            assertEquals(FailureKind.UNKNOWN, result.kind)
            assertEquals("NO_IMAGE:STOP", result.message)
            assertTrue(result.retryable)
        }
        GeminiHarness { FakeResponse.json("""{"candidates":[{"finishReason":"NO_IMAGE"}]}""") }.use { h ->
            assertEquals("NO_IMAGE:NO_IMAGE", (outcome(h) as EditOutcome.Failed).message)
        }
        GeminiHarness { FakeResponse.json("""{"candidates":[{"finishReason":"MAX_TOKENS"}]}""") }.use { h ->
            assertFalse((outcome(h) as EditOutcome.Failed).retryable)
        }
    }

    @Test fun httpStatusesMapToTypedFailuresWithoutReplay() {
        val cases = listOf(
            401 to FailureKind.AUTH, 403 to FailureKind.AUTH, 429 to FailureKind.QUOTA,
            500 to FailureKind.SERVER, 503 to FailureKind.SERVER, 400 to FailureKind.INVALID_REQUEST
        )
        for ((status, kind) in cases) {
            GeminiHarness { FakeResponse.json("""{"error":{"code":$status,"message":"synthetic"}}""", status) }.use { h ->
                val result = outcome(h) as EditOutcome.Failed
                assertEquals("status $status", kind, result.kind)
                assertEquals(status >= 500, result.retryable)
                assertEquals("POST is never replayed", 1, h.server.requests.size)
            }
        }
    }

    @Test fun timeoutIsRetryableAndNotReplayed() {
        GeminiHarness { FakeResponse.json(successReply(), delayMs = 1_000) }.use { h ->
            val result = h.provider(readTimeoutMs = 150).submit(request()).await() as EditOutcome.Failed
            assertEquals(FailureKind.TIMEOUT, result.kind)
            assertTrue(result.retryable)
            assertEquals(1, h.server.requests.size)
        }
    }

    @Test fun malformedRepliesFailClosed() {
        val replies = listOf(
            "not json",
            """{"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"image/png","data":"%%%"}}]}}]}""",
            """{"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"image/png","data":"${Base64.getEncoder().encodeToString("not an image".toByteArray())}"}}]}}]}""",
            """{"candidates":"nope"}""",
            "[1,2,3]"
        )
        for (reply in replies) {
            GeminiHarness { FakeResponse.json(reply) }.use { h ->
                val result = outcome(h)
                assertTrue(reply, result is EditOutcome.Failed)
                assertEquals(FailureKind.UNKNOWN, (result as EditOutcome.Failed).kind)
                assertFalse(result.retryable)
                assertTrue("no file left behind", h.output.listFiles()!!.isEmpty())
            }
        }
    }

    @Test fun largeReplyIsSpooledAndDecodedFromFile() {
        // About 5.3 MB of base64: far beyond the old 1 MB in-memory cap and the 64 KB inline limit.
        val big = SyntheticPng.encode(64, 48) + ByteArray(4_000_000) { (it * 31).toByte() }
        GeminiHarness { FakeResponse.json(successReply(big)) }.use { h ->
            val result = h.provider().submit(request()).await() as EditOutcome.Success
            val file = File(result.images.single().handle)
            assertEquals(64, result.images.single().width)
            assertTrue(file.readBytes().contentEquals(big))
            assertEquals("only the verified image remains", listOf(file), h.output.listFiles()!!.toList())
        }
    }

    @Test fun replyAboveCapIsRejectedOnceAndNeverStored() {
        val huge = SyntheticPng.encode(8, 8) + ByteArray(1_200_000)
        GeminiHarness { FakeResponse.json(successReply(huge)) }.use { h ->
            val result = h.provider(maxReplyBytes = 1_000_000).submit(request()).await()
            assertEquals(EditOutcome.Failed(FailureKind.SERVER, false, "HTTP_RESPONSE_TOO_LARGE"), result)
            assertEquals(1, h.server.requests.size)
            assertTrue(h.output.listFiles()!!.isEmpty())
        }
    }

    @Test fun cancellationDuringRequestIsTerminalAndStoresNothing() {
        GeminiHarness { FakeResponse.json(successReply(), delayMs = 800) }.use { h ->
            val job = h.provider(pollWaitMs = 20).submit(request())
            assertTrue(job.poll() is JobSnapshot.Pending)
            job.cancel()
            job.cancel()
            assertEquals(EditOutcome.Cancelled, (job.poll() as JobSnapshot.Completed).outcome)
            Thread.sleep(1_000)
            assertEquals(EditOutcome.Cancelled, (job.poll() as JobSnapshot.Completed).outcome)
            assertTrue(h.output.listFiles()!!.isEmpty())
        }
    }

    @Test fun missingKeyFailsBeforeAnyUpload() {
        GeminiHarness { FakeResponse.json(successReply()) }.use { h ->
            val result = outcome(h, key = null) as EditOutcome.Failed
            assertEquals(FailureKind.AUTH, result.kind)
            assertTrue(h.server.requests.isEmpty())
            assertTrue(h.loader.loaded.isEmpty())
        }
    }

    @Test fun secretNeverAppearsInOutcomesOrToString() {
        val replies = listOf(
            FakeResponse.json("""{"error":{"message":"API key $FAKE_KEY not valid"}}""", 400),
            FakeResponse.json("""{"error":{"message":"key $FAKE_KEY"}}""", 401),
            FakeResponse.json("""{"promptFeedback":{"blockReason":"$FAKE_KEY"}}"""),
            FakeResponse.json("""{"candidates":[{"finishReason":"$FAKE_KEY"}]}"""),
            FakeResponse.json(successReply()),
            FakeResponse(200, dropConnection = true)
        )
        for (reply in replies) {
            GeminiHarness { reply }.use { h ->
                val provider = h.provider()
                val text = provider.submit(request()).await().toString() + provider.toString() +
                    provider.capabilities().toString()
                assertFalse(text, text.contains(FAKE_KEY))
                assertFalse(text, text.contains("fake-gemini"))
            }
        }
    }

    @Test fun admissionIsHonestAboutMasksCandidatesAndReferences() {
        GeminiHarness { FakeResponse.json(successReply()) }.use { h ->
            val provider = h.provider()
            val caps = provider.capabilities()
            assertFalse(caps.supportsMaskEdit)
            assertEquals(1, caps.maxCandidates)
            assertEquals(13, caps.maxReferences)
            val mask = EditImage("synthetic-mask", 8, 8, ImageFormat.PNG)
            val masked = provider.submit(request().copy(mask = mask)).await() as EditOutcome.Failed
            assertEquals(FailureKind.INVALID_REQUEST, masked.kind)
            val lite = h.provider(model = GeminiModel.LITE)
            val refs = List(3) { EditImage("ref-$it", 8, 8, ImageFormat.PNG) }
            assertEquals(FailureKind.INVALID_REQUEST,
                (lite.submit(request(references = refs)).await() as EditOutcome.Failed).kind)
            assertTrue(h.server.requests.isEmpty())
        }
    }

    @Test fun sizeTierAndCostFollowRequestedOutput() {
        GeminiHarness { FakeResponse.json(successReply()) }.use { h ->
            val provider = h.provider()
            assertEquals("1K", provider.sizeFor(OutputSpec(1024, 1024)).label)
            assertEquals("2K", provider.sizeFor(OutputSpec(2048, 1536)).label)
            assertEquals("4K", provider.sizeFor(OutputSpec(4096, 3072)).label)
            assertEquals("3:4", provider.aspectFor(OutputSpec(3000, 4000)).label)
            assertEquals("21:9", provider.aspectFor(OutputSpec(2100, 900)).label)
            val cost = provider.estimateCost(request(OutputSpec(4096, 3072)))
            assertNotNull(cost)
            assertEquals("USD", cost!!.currencyCode)
            assertEquals(16L, cost.minorUnits)
            assertNull(h.provider(model = GeminiModel.LITE).estimateCost(request()))
            assertTrue(h.server.requests.isEmpty())
        }
    }

    @Test fun onlyTheGeminiHostIsAccepted() {
        GeminiHarness { FakeResponse.json(successReply()) }.use { h ->
            val error = runCatching {
                GeminiImageEditProvider(
                    apiKey = { FAKE_KEY }, images = h.loader, outputDirectory = h.output,
                    httpConfig = EditHttpConfig("https://evil.example/v1beta/", setOf("evil.example"))
                )
            }.exceptionOrNull()
            assertNotNull(error)
            assertFalse(error!!.message.orEmpty().contains(FAKE_KEY))
        }
    }

    @Test fun modelCatalogIsPinnedAndWellFormed() {
        assertEquals("gemini-3.1-flash-image", GeminiModel.DEFAULT.id)
        assertEquals(3, GeminiModel.CATALOG.map { it.id }.toSet().size)
        for (model in GeminiModel.CATALOG) {
            assertTrue(model.aspectRatios.any { it.label == "1:1" })
            assertTrue(model.sizes.first().label == "1K")
        }
    }
}
