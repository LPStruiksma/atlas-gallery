package nl.atlas.gallery.featureaiedit.provider.hosted.fal

import nl.atlas.gallery.featureaiedit.provider.CapabilityKey
import nl.atlas.gallery.featureaiedit.provider.EditImage
import nl.atlas.gallery.featureaiedit.provider.EditOutcome
import nl.atlas.gallery.featureaiedit.provider.FailureKind
import nl.atlas.gallery.featureaiedit.provider.ImageEditProvider
import nl.atlas.gallery.featureaiedit.provider.ImageEditProviderContract
import nl.atlas.gallery.featureaiedit.provider.ImageEditRequest
import nl.atlas.gallery.featureaiedit.provider.ImageFormat
import nl.atlas.gallery.featureaiedit.provider.JobSnapshot
import nl.atlas.gallery.featureaiedit.provider.OptionValue
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/**
 * Synthetic fal queue: replies are written from the published client types, the key is
 * assembled at runtime, images are generated PNGs. The real [TransportFalExchange] and
 * `EditHttpTransport` run against loopback, so auth, hosts and caps are the production paths.
 */
private val FAKE_KEY = listOf("fake", "fal", "key", "4242").joinToString(":")
private const val RESULT_URL = "https://v3b.fal.media/files/synthetic/out.png"

/** Scriptable fake fal queue for one request id. */
private class FakeFalQueue(
    val app: String = "fal-ai/qwen-image-edit-2511",
    val statuses: List<String> = listOf("IN_QUEUE", "IN_PROGRESS", "COMPLETED"),
    val submit: () -> FakeResponse = { FakeResponse.json("""{"request_id":"req-1","status":"IN_QUEUE","queue_position":2}""") },
    val result: () -> FakeResponse = {
        FakeResponse.json("""{"images":[{"url":"$RESULT_URL","content_type":"image/png","width":8,"height":8}],
            "has_nsfw_concepts":[false],"seed":1234,"prompt":"synthetic"}""")
    },
    val media: () -> FakeResponse = { FakeResponse(200, SyntheticPng.encode(8, 8), "image/png") },
    val statusDelayMs: Long = 0
) {
    private val polls = AtomicInteger()
    val appPath = app.split('/').take(2).joinToString("/")

    fun respond(request: FakeRequest): FakeResponse = when {
        request.method == "POST" && request.path == "/$app" -> submit()
        request.method == "GET" && request.path == "/$appPath/requests/req-1/status" -> {
            val index = polls.getAndIncrement().coerceAtMost(statuses.size - 1)
            FakeResponse.json("""{"status":"${statuses[index]}","request_id":"req-1","queue_position":${statuses.size - index}}""",
                delayMs = statusDelayMs)
        }
        request.method == "GET" && request.path == "/$appPath/requests/req-1" -> result()
        request.method == "PUT" && request.path == "/$appPath/requests/req-1/cancel" -> FakeResponse.json("""{"status":"CANCELLATION_REQUESTED"}""", 202)
        request.method == "GET" && request.path == "/files/synthetic/out.png" -> media()
        else -> FakeResponse(404)
    }
}

private class FalHarness(val queue: FakeFalQueue = FakeFalQueue()) : AutoCloseable {
    val server = FakeProviderServer { queue.respond(it) }
    val output: File = Files.createTempDirectory("fal-out").toFile()
    val loader = SyntheticImageLoader()

    fun provider(
        key: String? = FAKE_KEY,
        model: FalModel = FalModel.forEndpoint(queue.app),
        timing: FalTiming = FalTiming(pollWaitMs = 50, statusIntervalMs = 10, connectTimeoutMs = 1_000,
            readTimeoutMs = 2_000, callTimeoutMs = 5_000, jobTimeoutMs = 10_000)
    ) = FalImageEditProvider(
        model = model, apiKey = { key }, images = loader, outputDirectory = output,
        exchange = TransportFalExchange(server.transport()), timing = timing
    )

    fun calls(method: String) = server.requests.filter { it.method == method }

    override fun close() {
        server.close()
        output.deleteRecursively()
    }
}

private fun FalModel.Companion.forEndpoint(endpoint: String) = CATALOG.single { it.endpoint == endpoint }

private fun request(
    output: OutputSpec = OutputSpec(8, 8, ImageFormat.PNG),
    references: List<EditImage> = emptyList(),
    options: Map<CapabilityKey, OptionValue> = emptyMap()
) = ImageEditRequest(
    source = EditImage("synthetic-source", 8, 8, ImageFormat.PNG),
    instruction = "Remove the synthetic lamp post",
    references = references,
    output = output,
    options = options
)

class FalImageEditProviderContractTest : ImageEditProviderContract() {
    private val harnesses = mutableListOf<FalHarness>()

    @After fun closeAll() = harnesses.forEach { it.close() }

    override fun providerFor(fixture: Fixture): ImageEditProvider {
        val queue = when (fixture) {
            Fixture.SUCCESS -> FakeFalQueue(statuses = listOf("COMPLETED"))
            Fixture.REFUSAL -> FakeFalQueue(statuses = listOf("COMPLETED"), result = {
                FakeResponse.json("""{"images":[{"url":"$RESULT_URL"}],"has_nsfw_concepts":[true]}""")
            })
            Fixture.FAILURE -> FakeFalQueue(submit = { FakeResponse(200, dropConnection = true) })
            Fixture.SLOW_ASYNC -> FakeFalQueue(statuses = listOf("IN_QUEUE"))
            Fixture.CANCELLABLE_ASYNC -> FakeFalQueue(statuses = listOf("IN_QUEUE", "IN_PROGRESS", "COMPLETED"), statusDelayMs = 60)
        }
        return FalHarness(queue).also { harnesses.add(it) }.provider()
    }
}

class FalImageEditProviderTest {
    @Test fun queuedRunningDoneFollowsDocumentedContract() {
        FalHarness(FakeFalQueue(statusDelayMs = 40)).use { h ->
            val events = mutableListOf<JobSnapshot>()
            val result = h.provider().submit(request()).stream(maxPolls = 200) { events.add(it) } as EditOutcome.Success

            val submit = h.calls("POST").single()
            assertEquals(FalImageEditProvider.QUEUE_HOST, submit.host)
            assertEquals("/fal-ai/qwen-image-edit-2511", submit.path)
            assertEquals("Key $FAKE_KEY", submit.headers["authorization"])
            // #464: every submit asks fal to expire its public result objects after one hour.
            assertEquals("""{"expiration_duration_seconds":3600}""",
                submit.headers["x-fal-object-lifecycle-preference"])
            val input = HostedJson.parse(submit.text).obj()!!
            assertEquals("Remove the synthetic lamp post", input["prompt"].str())
            val uri = input["image_urls"].arr()!!.single().str()!!
            assertTrue(uri.startsWith("data:image/png;base64,"))
            assertTrue(Base64.getDecoder().decode(uri.substringAfter(",")).contentEquals(SyntheticPng.encode(8, 8)))
            assertEquals("png", input["output_format"].str())
            assertEquals(1L, input["num_images"])
            assertEquals(8L, input["image_size"].obj()!!["width"])

            val gets = h.calls("GET")
            assertTrue(gets.count { it.path.endsWith("/status") } >= 3)
            gets.filter { it.host == FalImageEditProvider.QUEUE_HOST }.forEach {
                assertEquals("Key $FAKE_KEY", it.headers["authorization"])
            }
            assertTrue(gets.none { it.headers.containsKey("x-fal-object-lifecycle-preference") })
            val download = gets.single { it.path == "/files/synthetic/out.png" }
            assertEquals("v3b.fal.media", download.host)
            assertNull("key never sent to the media host", download.headers["authorization"])

            val image = result.images.single()
            assertEquals(8, image.width)
            assertEquals(ImageFormat.PNG, image.format)
            assertTrue(File(image.handle).parentFile == h.output && image.handle.endsWith(".png"))
            assertEquals("fal-ai/qwen-image-edit-2511", result.providerModel)
            assertEquals("req-1", result.metadata["requestId"])
            assertEquals("1234", result.metadata["seed"])
            assertTrue(events.any { it is JobSnapshot.Pending })
            assertTrue(h.calls("PUT").isEmpty())
        }
    }

    @Test fun kontextUsesSingleImageAspectPresetAndOwnerAliasPaths() {
        FalHarness(FakeFalQueue(app = "fal-ai/flux-pro/kontext", statuses = listOf("COMPLETED"))).use { h ->
            val outcome = h.provider().submit(request(OutputSpec(1600, 900, ImageFormat.JPEG),
                options = mapOf(CapabilityKey("safety_tolerance") to OptionValue.TextValue("5"),
                    CapabilityKey("seed") to OptionValue.IntegerValue(7)))).await(maxPolls = 200)
            assertTrue(outcome.toString(), outcome is EditOutcome.Success)
            val input = HostedJson.parse(h.calls("POST").single().text).obj()!!
            assertEquals("/fal-ai/flux-pro/kontext", h.calls("POST").single().path)
            assertTrue(input["image_url"].str()!!.startsWith("data:image/png;base64,"))
            assertNull(input["image_urls"])
            assertEquals("16:9", input["aspect_ratio"].str())
            assertEquals("jpeg", input["output_format"].str())
            assertEquals("5", input["safety_tolerance"].str())
            assertEquals(7L, input["seed"])
            assertTrue(h.calls("GET").any { it.path == "/fal-ai/flux-pro/requests/req-1/status" })
            assertTrue(h.calls("GET").any { it.path == "/fal-ai/flux-pro/requests/req-1" })
        }
    }

    @Test fun safetyCheckerFlagIsARefusalAndNothingIsDownloaded() {
        FalHarness(FakeFalQueue(statuses = listOf("COMPLETED"), result = {
            FakeResponse.json("""{"images":[{"url":"$RESULT_URL"}],"has_nsfw_concepts":[true]}""")
        })).use { h ->
            val result = h.provider().submit(request()).await(maxPolls = 200) as EditOutcome.Refused
            assertEquals("fal:has_nsfw_concepts", result.providerCode)
            assertTrue(h.calls("GET").none { it.path.startsWith("/files/") })
            assertTrue(h.output.listFiles()!!.isEmpty())
        }
    }

    @Test fun submitFailuresMapToTypedOutcomesWithoutReplay() {
        val cases = listOf(401 to FailureKind.AUTH, 403 to FailureKind.AUTH,
            402 to FailureKind.BILLING, 429 to FailureKind.QUOTA,
            422 to FailureKind.INVALID_REQUEST, 500 to FailureKind.SERVER)
        for ((status, kind) in cases) {
            FalHarness(FakeFalQueue(submit = { FakeResponse.json("""{"detail":"synthetic"}""", status) })).use { h ->
                val result = h.provider().submit(request()).await(maxPolls = 200) as EditOutcome.Failed
                assertEquals("status $status", kind, result.kind)
                if (status == 402) {
                    assertFalse(result.retryable)
                    assertEquals("HTTP_BILLING", result.message)
                    assertFalse(result.toString().contains("synthetic"))
                }
                assertEquals(1, h.calls("POST").size)
                assertTrue(h.calls("GET").isEmpty())
            }
        }
    }

    @Test fun failedRunOnResultCallIsReportedNotDownloaded() {
        FalHarness(FakeFalQueue(statuses = listOf("COMPLETED"), result = { FakeResponse.json("""{"detail":[]}""", 422) })).use { h ->
            val result = h.provider().submit(request()).await(maxPolls = 200) as EditOutcome.Failed
            assertEquals(FailureKind.INVALID_REQUEST, result.kind)
            assertEquals("RESULT_REJECTED", result.message)
        }
    }

    @Test fun documentedStructuredPolicyErrorIsARefusalWithoutMessageLeakage() {
        val privateMessage = "private moderation explanation"
        FalHarness(FakeFalQueue(statuses = listOf("COMPLETED"), result = {
            FakeResponse.json("""{"detail":[{"type":"content_policy_violation","msg":"$privateMessage"}]}""", 422)
        })).use { h ->
            val result = h.provider().submit(request()).await(maxPolls = 200) as EditOutcome.Refused
            assertEquals("fal:content_policy_violation", result.providerCode)
            assertFalse(result.toString().contains(privateMessage))
        }
    }

    @Test fun cancelDuringQueueCallsDocumentedCancelEndpoint() {
        FalHarness(FakeFalQueue(statuses = listOf("IN_QUEUE"))).use { h ->
            val job = h.provider().submit(request())
            var guard = 0
            while (h.calls("GET").none { it.path.endsWith("/status") } && guard++ < 100) job.poll()
            job.cancel()
            job.cancel()
            assertEquals(EditOutcome.Cancelled, (job.poll() as JobSnapshot.Completed).outcome)
            var wait = 0
            while (h.calls("PUT").isEmpty() && wait++ < 100) Thread.sleep(20)
            val put = h.calls("PUT").single()
            assertEquals("/fal-ai/qwen-image-edit-2511/requests/req-1/cancel", put.path)
            assertEquals("Key $FAKE_KEY", put.headers["authorization"])
            assertTrue(h.output.listFiles()!!.isEmpty())
        }
    }

    @Test fun resultHostOutsideAllowlistIsRejected() {
        val urls = listOf(
            "https://evil.example/files/synthetic/out.png",
            "http://v3b.fal.media/files/synthetic/out.png",
            "https://v3b.fal.media.evil.example/files/synthetic/out.png",
            "https://user@v3b.fal.media/files/synthetic/out.png",
            "https://v3b.fal.media:8443/files/synthetic/out.png",
            "https://v3b.fal.media/files/synthetic/out.png?token=x",
            "data:image/png;base64,AAAA",
            "https://fal.run/files/synthetic/out.png",
            // #464: official examples for other fal models use this public bucket; it is not
            // allowlisted, so a result there fails closed instead of widening the hosts.
            "https://storage.googleapis.com/falserverless/files/synthetic/out.png",
            "https://falserverless.storage.googleapis.com/files/synthetic/out.png",
            "https://fal.ai/files/synthetic/out.png",
            "https://cdn.fal.ai/files/synthetic/out.png"
        )
        for (url in urls) {
            FalHarness(FakeFalQueue(statuses = listOf("COMPLETED"), result = {
                FakeResponse.json("""{"images":[{"url":${HostedJson.quote(url)}}],"has_nsfw_concepts":[false]}""")
            })).use { h ->
                val result = h.provider().submit(request()).await(maxPolls = 200) as EditOutcome.Failed
                assertEquals(url, "RESULT_HOST_REJECTED", result.message)
                assertTrue(h.calls("GET").none { it.path.startsWith("/files/") })
                assertTrue(url, h.output.listFiles().orEmpty().isEmpty())
            }
        }
        assertTrue(FalImageEditProvider.isFalMediaHost("fal.media"))
        assertTrue(FalImageEditProvider.isFalMediaHost("v3.fal.media"))
        assertFalse(FalImageEditProvider.isFalMediaHost("evilfal.media"))
        assertFalse(FalImageEditProvider.isFalMediaHost("x.fal.media.example"))
        assertFalse(FalImageEditProvider.isFalMediaHost("storage.googleapis.com"))
        assertFalse(FalImageEditProvider.isFalMediaHost("fal.ai"))
    }

    @Test fun oversizedOrFakeResultImagesAreNeverStored() {
        val bodies = listOf(
            ByteArray(33_000_000) { 1 },         // above the transport's 32 MB download cap
            "<html>not an image</html>".toByteArray(),
            ByteArray(0)
        )
        for (body in bodies) {
            FalHarness(FakeFalQueue(statuses = listOf("COMPLETED"), media = { FakeResponse(200, body, "image/png") })).use { h ->
                val result = h.provider(timing = FalTiming(pollWaitMs = 50, statusIntervalMs = 10, connectTimeoutMs = 1_000,
                    readTimeoutMs = 10_000, callTimeoutMs = 20_000, jobTimeoutMs = 30_000))
                    .submit(request()).await(maxPolls = 1_000)
                assertTrue("${body.size}: $result", result is EditOutcome.Failed)
                assertTrue(h.output.listFiles()!!.isEmpty())
            }
        }
    }

    @Test fun malformedRepliesFailClosed() {
        val queues = listOf(
            FakeFalQueue(submit = { FakeResponse.json("not json") }),
            FakeFalQueue(submit = { FakeResponse.json("""{"request_id":"../../etc"}""") }),
            FakeFalQueue(statuses = listOf("EXPLODED")),
            FakeFalQueue(statuses = listOf("COMPLETED"), result = { FakeResponse.json("""{"images":[]}""") }),
            FakeFalQueue(statuses = listOf("COMPLETED"), result = { FakeResponse.json("[]") })
        )
        for (queue in queues) {
            FalHarness(queue).use { h ->
                val result = h.provider().submit(request()).await(maxPolls = 200)
                assertTrue(result.toString(), result is EditOutcome.Failed)
                assertFalse((result as EditOutcome.Failed).retryable)
                assertTrue(h.output.listFiles()!!.isEmpty())
            }
        }
    }

    @Test fun queueTimeoutCancelsRemotely() {
        FalHarness(FakeFalQueue(statuses = listOf("IN_QUEUE"))).use { h ->
            val result = h.provider(timing = FalTiming(pollWaitMs = 50, statusIntervalMs = 10, connectTimeoutMs = 1_000,
                readTimeoutMs = 2_000, callTimeoutMs = 5_000, jobTimeoutMs = 200)).submit(request()).await(maxPolls = 200)
            assertEquals(FailureKind.TIMEOUT, (result as EditOutcome.Failed).kind)
            assertEquals(1, h.calls("PUT").size)
        }
    }

    @Test fun missingKeyFailsBeforeAnyUpload() {
        FalHarness().use { h ->
            val result = h.provider(key = null).submit(request()).await(maxPolls = 200) as EditOutcome.Failed
            assertEquals(FailureKind.AUTH, result.kind)
            assertTrue(h.server.requests.isEmpty())
            assertTrue(h.loader.loaded.isEmpty())
        }
    }

    @Test fun secretNeverAppearsInOutcomesOrToString() {
        val queues = listOf(
            FakeFalQueue(submit = { FakeResponse.json("""{"detail":"bad key $FAKE_KEY"}""", 401) }),
            FakeFalQueue(statuses = listOf("COMPLETED"), result = { FakeResponse.json("""{"images":[{"url":"https://$FAKE_KEY.fal.media/x.png"}]}""") }),
            FakeFalQueue(statuses = listOf("COMPLETED")),
            FakeFalQueue(submit = { FakeResponse(200, dropConnection = true) })
        )
        for (queue in queues) {
            FalHarness(queue).use { h ->
                val provider = h.provider()
                val text = provider.submit(request()).await(maxPolls = 200).toString() + provider + provider.capabilities()
                assertFalse(text, text.contains(FAKE_KEY))
                assertFalse(text, text.contains("4242"))
            }
        }
    }

    @Test fun capabilitiesAreHonestPerCatalogEntry() {
        FalHarness().use { h ->
            val qwen = h.provider(model = FalModel.QWEN_EDIT).capabilities()
            assertEquals(3, qwen.maxReferences)
            assertFalse(qwen.supportsMaskEdit)
            assertTrue(ImageFormat.WEBP in qwen.outputFormats)
            assertTrue(qwen.advancedParameters.isEmpty())
            val kontext = h.provider(model = FalModel.KONTEXT_PRO).capabilities()
            assertEquals(0, kontext.maxReferences)
            assertFalse(ImageFormat.WEBP in kontext.outputFormats)
            assertTrue(CapabilityKey("safety_tolerance") in kontext.advancedParameters)
            // Kontext takes one image: a reference is refused before any upload.
            val refused = h.provider(model = FalModel.KONTEXT_PRO)
                .submit(request(references = listOf(EditImage("ref", 8, 8, ImageFormat.PNG)))).await() as EditOutcome.Failed
            assertEquals(FailureKind.INVALID_REQUEST, refused.kind)
            val mask = h.provider().submit(request().copy(mask = EditImage("m", 8, 8, ImageFormat.PNG))).await() as EditOutcome.Failed
            assertEquals(FailureKind.INVALID_REQUEST, mask.kind)
            assertTrue(h.server.requests.isEmpty())
            assertTrue(FalModel.CATALOG.any { it.openWeights })
            assertEquals(FalModel.QWEN_EDIT, FalModel.DEFAULT)
        }
    }
}

/** #431 rev 4 row 6 and addendum A3/A4: send boundary, durable request id, resume without POST. */
class FalRecoveryTest {
    private class RecordingJournal(private val allow: Boolean = true) : nl.atlas.gallery.featureaiedit.provider.SubmitJournal {
        val events = mutableListOf<String>()
        var handle: nl.atlas.gallery.featureaiedit.provider.RemoteHandle? = null
        override fun beforeSend(): Boolean { events += "beforeSend"; return allow }
        override fun remoteAccepted(handle: nl.atlas.gallery.featureaiedit.provider.RemoteHandle) {
            events += "accepted"; this.handle = handle
        }
    }

    @Test fun journalSeesSendBoundaryThenValidatedIdBoundToProviderAndEndpoint() {
        FalHarness(FakeFalQueue(statuses = listOf("COMPLETED"))).use { h ->
            val journal = RecordingJournal()
            val provider = h.provider()
            assertEquals(nl.atlas.gallery.featureaiedit.provider.RemoteRecovery.QUERY_BY_PERSISTED_ID,
                provider.capabilities().remoteRecovery)
            val outcome = provider.submit(request(), journal).await(maxPolls = 200)
            assertTrue(outcome.toString(), outcome is EditOutcome.Success)
            assertEquals(listOf("beforeSend", "accepted"), journal.events)
            val handle = journal.handle!!
            assertEquals(provider.id, handle.providerId)
            assertEquals("fal-ai/qwen-image-edit-2511", handle.endpoint)
            assertEquals("req-1", handle.remoteId)
            assertNull("the adapter never fingerprints or stores the key", handle.keyFingerprint)
            assertFalse(handle.toString().contains("req-1"))
        }
    }

    @Test fun refusedSendStopsBeforeAnyRequest() {
        FalHarness().use { h ->
            val outcome = h.provider().submit(request(), RecordingJournal(allow = false)).await(maxPolls = 200)
            assertEquals(EditOutcome.Cancelled, outcome)
            assertTrue(h.server.requests.isEmpty())
        }
    }

    @Test fun lostSubmitReplyIsUncertainAndNeverReplayed() {
        FalHarness(FakeFalQueue(submit = { FakeResponse(200, dropConnection = true) })).use { h ->
            val journal = RecordingJournal()
            val failed = h.provider().submit(request(), journal).await(maxPolls = 200) as EditOutcome.Failed
            assertEquals(nl.atlas.gallery.featureaiedit.provider.Delivery.UNCERTAIN, failed.delivery)
            assertEquals(listOf("beforeSend"), journal.events)
            assertEquals(1, h.calls("POST").size)
        }
    }

    @Test fun answeredSubmitRefusalsAreDefiniteAndRateLimitIsNotCredit() {
        for ((status, kind) in listOf(402 to FailureKind.BILLING, 429 to FailureKind.QUOTA, 401 to FailureKind.AUTH)) {
            FalHarness(FakeFalQueue(submit = { FakeResponse.json("""{"detail":"synthetic"}""", status) })).use { h ->
                val failed = h.provider().submit(request(), RecordingJournal()).await(maxPolls = 200) as EditOutcome.Failed
                assertEquals(kind, failed.kind)
                assertEquals(nl.atlas.gallery.featureaiedit.provider.Delivery.ANSWERED, failed.delivery)
            }
        }
    }

    @Test fun failureAfterAcceptanceIsUncertainEvenForAnsweredStatus() {
        FalHarness(FakeFalQueue(statuses = listOf("COMPLETED"), result = { FakeResponse.json("{}", 402) })).use { h ->
            val failed = h.provider().submit(request(), RecordingJournal()).await(maxPolls = 200) as EditOutcome.Failed
            assertEquals(nl.atlas.gallery.featureaiedit.provider.Delivery.UNCERTAIN, failed.delivery)
        }
    }

    @Test fun resumeQueriesTheSavedIdWithoutAnyPost() {
        FalHarness(FakeFalQueue(statuses = listOf("IN_PROGRESS", "COMPLETED"))).use { h ->
            val provider = h.provider()
            val handle = nl.atlas.gallery.featureaiedit.provider.RemoteHandle(provider.id, "fal-ai/qwen-image-edit-2511", "req-1")
            val outcome = provider.resume(handle)!!.await(maxPolls = 200)
            assertTrue(outcome.toString(), outcome is EditOutcome.Success)
            assertTrue(h.calls("POST").isEmpty())
            assertTrue(h.calls("GET").any { it.path == "/fal-ai/qwen-image-edit-2511/requests/req-1/status" })
        }
    }

    @Test fun resumeRefusesForeignOrMalformedHandles() {
        FalHarness().use { h ->
            val provider = h.provider()
            val ok = nl.atlas.gallery.featureaiedit.provider.RemoteHandle(provider.id, "fal-ai/qwen-image-edit-2511", "req-1")
            assertNull(provider.resume(ok.copy(providerId = "gemini:gemini-3.1-flash-image")))
            assertNull(provider.resume(ok.copy(endpoint = "fal-ai/flux-pro/kontext")))
            assertNull(provider.resume(ok.copy(remoteId = "../other")))
            assertNull(provider.resume(ok.copy(candidates = 0)))
            assertTrue(h.server.requests.isEmpty())
        }
    }

    @Test fun resumeLookupRefusedOrKeyRemovedIsCannotCheckNeverWrongKey() {
        for (status in listOf(401, 403, 404)) {
            FalHarness(FakeFalQueue(statuses = listOf("COMPLETED"))).use { h ->
                val server = FakeProviderServer { FakeResponse.json("{}", status) }
                server.use {
                    val provider = FalImageEditProvider(model = FalModel.QWEN_EDIT, apiKey = { FAKE_KEY }, images = h.loader,
                        outputDirectory = h.output, exchange = TransportFalExchange(server.transport()),
                        timing = FalTiming(pollWaitMs = 50, statusIntervalMs = 10, connectTimeoutMs = 1_000,
                            readTimeoutMs = 2_000, callTimeoutMs = 5_000, jobTimeoutMs = 10_000))
                    val handle = nl.atlas.gallery.featureaiedit.provider.RemoteHandle(provider.id, "fal-ai/qwen-image-edit-2511", "req-1")
                    val failed = provider.resume(handle)!!.await(maxPolls = 200) as EditOutcome.Failed
                    assertEquals("status $status", FailureKind.CANNOT_CHECK, failed.kind)
                    assertTrue(server.requests.none { it.method == "POST" })
                }
            }
        }
        FalHarness().use { h ->
            val provider = h.provider(key = null)
            val handle = nl.atlas.gallery.featureaiedit.provider.RemoteHandle(provider.id, "fal-ai/qwen-image-edit-2511", "req-1")
            val failed = provider.resume(handle)!!.await(maxPolls = 200) as EditOutcome.Failed
            assertEquals(FailureKind.CANNOT_CHECK, failed.kind)
            assertTrue(h.server.requests.isEmpty())
        }
    }
}
