package nl.atlas.gallery.featureidentity.head

import nl.atlas.gallery.featureaiedit.provider.EditImage
import nl.atlas.gallery.featureaiedit.provider.ImageFormat
import nl.atlas.gallery.featureidentity.engine.EngineLoadResult
import nl.atlas.gallery.featureidentity.engine.EngineRoute
import nl.atlas.gallery.featureidentity.engine.FaceBox
import nl.atlas.gallery.featureidentity.engine.FacePoint
import nl.atlas.gallery.featureidentity.engine.IdentityCancellation
import nl.atlas.gallery.featureidentity.engine.IdentityCandidateSession
import nl.atlas.gallery.featureidentity.engine.IdentityEditMode
import nl.atlas.gallery.featureidentity.engine.IdentityFailureKind
import nl.atlas.gallery.featureidentity.engine.IdentityReferenceSet
import nl.atlas.gallery.featureidentity.engine.IdentityRunStatus
import nl.atlas.gallery.featureidentity.engine.IdentitySessionInput
import nl.atlas.gallery.featureidentity.engine.TargetFace
import nl.atlas.gallery.featureidentity.save.IdentityEditSave
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Synthetic fixtures only: generated pixels, made-up handles and a fake transport. No real
 * images, people, servers, models or network. Proves the contract, not photographic fidelity
 * (that verdict is #1092).
 */
class HeadSwapEngineTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var root: File
    private val opened = ArrayList<IdentityCandidateSession>()

    @Before fun setUp() { root = tmp.newFolder("atlas-identity") }
    @After fun tearDown() { opened.forEach { it.close() } }

    private val sha = "a".repeat(64)
    private val adapter = HeadAdapterIdentity(adapterSha256 = sha)
    private fun approval(
        route: EngineRoute = EngineRoute.SELF_HOSTED,
        approved: Boolean = true,
        identity: HeadAdapterIdentity = adapter
    ) = HeadRouteApproval(route, approved, identity)

    private fun admitted(
        provider: String = HeadAdapterIdentity.HEAD_PROVIDER_ID,
        model: String = HeadAdapterIdentity.HEAD_MODEL_ID,
        fullHead: Boolean = true,
        adapterSha: String? = sha,
        maxRefs: Int = 4,
        maxOutputPixels: Long = 4_194_304L
    ) = HeadServerCapabilities(provider, listOf(model), fullHead, true, true, maxRefs, 4_194_304L, maxOutputPixels,
        adapterSha, 32, 2048)

    /** Fake head server: replaces a head-shaped region (bigger than the prior) with seed colours. */
    private inner class FakeTransport(
        var handshake: HeadHandshake = HeadHandshake.Reachable(admitted()),
        var reply: ((HeadTransportRequest, IntArray) -> HeadTransportResult)? = null,
        val onSubmit: (HeadTransportRequest, IdentityCancellation) -> Unit = { _, _ -> }
    ) : SelfHostedHeadTransport {
        var handshakes = 0
        val jobs = ArrayList<HeadTransportRequest>()

        override fun handshake(): HeadHandshake { handshakes++; return handshake }

        override fun submit(request: HeadTransportRequest, cancellation: IdentityCancellation): HeadTransportResult {
            jobs += request
            onSubmit(request, cancellation)
            val c = request.crop
            val src = request.target.pixels
            val original = IntArray(c.width * c.height) { i ->
                src[(c.top + i / c.width) * request.target.width + c.left + i % c.width]
            }
            reply?.let { return it(request, original) }
            val colour = (0xff shl 24) or ((request.seed * 2654435761L).toInt() and 0xffffff) or 0x404040
            val cx = c.width / 2.0; val cy = c.height * 0.45
            val gen = IntArray(original.size) { i ->
                val x = i % c.width; val y = i / c.width
                val dx = (x - cx) / (c.width * 0.47); val dy = (y - cy) / (c.height * 0.47)
                if (dx * dx + dy * dy <= 1.0) colour else original[i]
            }
            return HeadTransportResult.Success(gen, c.width, c.height,
                HeadAdapterIdentity.HEAD_PROVIDER_ID, HeadAdapterIdentity.HEAD_MODEL_ID, sha)
        }
    }

    private fun source(w: Int = 120, h: Int = 120) = EditImage(
        "synthetic-source", w, h, ImageFormat.PNG,
        List(w * h) { i -> val v = ((i % w) * 2 + (i / w)) and 255; (0xff shl 24) or (v shl 16) or (v shl 8) or v }
    )

    private fun input(
        mode: IdentityEditMode = IdentityEditMode.HEAD,
        box: FaceBox = FaceBox(44, 44, 76, 76),
        refs: Int = 5,
        src: EditImage = source(),
        refSize: Int = 32
    ) = IdentitySessionInput(
        source = src,
        target = TargetFace(box, listOf(
            FacePoint(box.left + box.width * 0.3f, box.top + box.height * 0.35f),
            FacePoint(box.left + box.width * 0.7f, box.top + box.height * 0.35f),
            FacePoint(box.left + box.width * 0.5f, box.top + box.height * 0.55f),
            FacePoint(box.left + box.width * 0.35f, box.top + box.height * 0.75f),
            FacePoint(box.left + box.width * 0.65f, box.top + box.height * 0.75f)
        )),
        references = IdentityReferenceSet("synthetic-profile", "default",
            List(refs) { EditImage("synthetic-ref-$it", refSize, refSize, ImageFormat.PNG) }),
        mode = mode
    )

    private fun open(
        engine: HeadSwapEngine,
        input: IdentitySessionInput = input(),
        route: EngineRoute = EngineRoute.SELF_HOSTED
    ) = IdentityCandidateSession.open(engine, input, root, route, sessionSeed = 7L).also { opened += it }

    private fun sessionFiles(): List<File> = root.walkTopDown().filter { it.isFile }.toList()

    @Test fun admittedRouteMakesDistinctWholeHeadCandidatesWithLineage() {
        val transport = FakeTransport()
        val engine = HeadSwapEngine(approval(), transport)
        val session = open(engine)
        val run = session.generate(3)
        assertEquals(IdentityRunStatus.COMPLETE, run.status)
        assertEquals(3, run.newCandidates.size)
        assertEquals(3, transport.jobs.size)
        assertEquals(3, transport.jobs.map { it.seed }.toSet().size)
        for (c in run.newCandidates) {
            assertEquals(IdentityEditMode.HEAD, c.mode)
            assertEquals(EngineRoute.SELF_HOSTED, c.route)
            assertEquals(HeadSwapEngine.ENGINE_ID, c.engineId)
            assertEquals("1+adapter-${sha.take(12)}", c.engineVersion)
            val file = c.file!!
            assertTrue(file.isFile && file.name == HeadSwapEngine.OUTPUT_NAME)
            assertTrue(file.canonicalPath.startsWith(root.canonicalPath))
        }
        val lineage = IdentityEditSave.provenance(run.newCandidates[1], "synthetic-profile", "default", null)
        assertEquals("head", lineage.kind)
        assertEquals("self_hosted", lineage.route)
        assertEquals(run.newCandidates[1].variation.seed, lineage.seed)
        assertEquals(engine.version, lineage.engineVersion)
    }

    @Test fun jobCarriesWholeHeadCropMaskInsideCropAndProtocolSeed() {
        val transport = FakeTransport()
        open(HeadSwapEngine(approval(), transport)).generate(3)
        val job = transport.jobs.first()
        val face = FaceBox(44, 44, 76, 76)
        assertTrue("crop must reach the crown above the face", job.crop.top < face.top - face.height / 2)
        assertTrue("crop must include the ears", job.crop.left < face.left && job.crop.right > face.right)
        assertTrue("crop must include the neck boundary", job.crop.bottom > face.bottom + face.height / 4)
        val bounds = job.mask.bounds()!!
        assertTrue(bounds.left >= job.crop.left && bounds.top >= job.crop.top &&
            bounds.right <= job.crop.right && bounds.bottom <= job.crop.bottom)
        assertTrue("prior must cover hair above the face", job.mask[60, face.top - 4] == 255)
        assertTrue(transport.jobs.all { it.seed in 0..HeadTransportRequest.MAX_SEED })
        assertEquals(HeadSwapEngine.INSTRUCTION, job.instruction)
    }

    @Test fun everyPixelOutsideTheHeadCropIsBitIdenticalAndTheOriginalIsUntouched() {
        val src = source()
        val before = src.pixels.toList()
        val transport = FakeTransport()
        val run = open(HeadSwapEngine(approval(), transport), input(src = src)).generate(3)
        assertEquals(before, src.pixels)
        val crop = transport.jobs.first().crop
        val out = run.newCandidates.first().image.pixels
        var changedInside = 0
        for (y in 0 until src.height) for (x in 0 until src.width) {
            val i = y * src.width + x
            if (!crop.contains(x, y)) assertEquals("pixel $x,$y", src.pixels[i], out[i])
            else if (out[i] != src.pixels[i]) changedInside++
        }
        assertTrue(changedInside > crop.width * crop.height / 3)
        // The decoded output file holds the same full-resolution image.
        assertEquals(src.width, run.newCandidates.first().image.width)
    }

    @Test fun referenceSelectionFollowsTheAcceptedAdapterLimit() {
        val transport = FakeTransport()
        val engine = HeadSwapEngine(approval(identity = adapter.copy(maxReferences = 2)), transport)
        val run = open(engine).generate(4)
        assertEquals(IdentityRunStatus.COMPLETE, run.status)
        assertTrue(transport.jobs.all { it.references.size == 2 })
        assertTrue(transport.jobs.map { j -> j.references.map { it.handle } }.toSet().size > 1)
        run.newCandidates.forEach { assertEquals(2, it.variation.referenceIndices.size) }
    }

    @Test fun missingOrUnapprovedRouteIsRouteNotApproved() {
        for (engine in listOf(
            HeadSwapEngine(null, FakeTransport()),
            HeadSwapEngine(approval(approved = false), FakeTransport()),
            HeadSwapEngine(approval(route = EngineRoute.PAID), FakeTransport()),
            HeadSwapEngine(approval(route = EngineRoute.LOCAL), FakeTransport())
        )) {
            val load = engine.load()
            assertEquals(IdentityFailureKind.ROUTE_NOT_APPROVED, (load as EngineLoadResult.Unavailable).kind)
            val run = open(engine).generate(3)
            assertEquals(IdentityFailureKind.ROUTE_NOT_APPROVED, run.problem!!.kind)
        }
        // The session itself refuses when the user's chosen route is not self-hosted.
        val transport = FakeTransport()
        val run = open(HeadSwapEngine(approval(), transport), route = EngineRoute.LOCAL).generate(3)
        assertEquals(IdentityFailureKind.ROUTE_NOT_APPROVED, run.problem!!.kind)
        assertEquals(0, transport.handshakes)
    }

    @Test fun missingTransportOrCapabilityIsModeUnavailable() {
        assertUnavailable(HeadSwapEngine(approval(), null))
        assertUnavailable(HeadSwapEngine(approval(), FakeTransport(HeadHandshake.Reachable(admitted(fullHead = false)))))
    }

    @Test fun todaysScaffoldCapabilitiesNeverAdmitFullHead() {
        // Shape of GET /v1/capabilities from tools/ai_edit_server Flux2HeadBackend (#1017/#1063).
        val json = mapOf(
            "schema_version" to 1L, "protocol_version" to "1.0",
            "provider_id" to "self-hosted-flux2-head",
            "display_name" to "FLUX.2 head admission scaffold (unavailable)",
            "models" to listOf(mapOf("id" to HeadAdapterIdentity.HEAD_MODEL_ID, "display_name" to "x")),
            "features" to mapOf(
                "instruction_edit" to false, "mask_edit" to true,
                "reference_images" to mapOf("max_count" to 1L), "candidates" to mapOf("max_count" to 1L),
                "multi_turn" to false, "seed" to true, "async_jobs" to true, "streaming" to false,
                "input_formats" to listOf("image/png", "image/jpeg"), "output_formats" to listOf("image/png"),
                "max_input_pixels" to 4_194_304L, "max_output_pixels" to 4_194_304L,
                "aspect_constraints" to null, "cost_estimate" to null,
                "full_head" to false, "lineage" to false
            ),
            "advanced_parameters" to listOf(
                mapOf("name" to "seed", "type" to "integer", "minimum" to 0L, "maximum" to 4_294_967_295L),
                mapOf("name" to "head_width", "type" to "integer", "minimum" to 32L, "maximum" to 2048L),
                mapOf("name" to "head_height", "type" to "integer", "minimum" to 32L, "maximum" to 2048L)
            )
        )
        val caps = HeadServerCapabilities.fromProtocolJson(json)!!
        assertFalse(caps.fullHead)
        assertNull(caps.adapterSha256)
        assertEquals(1, caps.maxReferences)
        assertFalse(HeadSwapEngine.admits(caps, adapter))
        assertUnavailable(HeadSwapEngine(approval(), FakeTransport(HeadHandshake.Reachable(caps))))
        // Even a future full_head flag without the adapter identity stays closed.
        val flagged = json + ("features" to (json["features"] as Map<*, *> + ("full_head" to true)))
        @Suppress("UNCHECKED_CAST")
        val flaggedCaps = HeadServerCapabilities.fromProtocolJson(flagged as Map<String, Any?>)!!
        assertTrue(flaggedCaps.fullHead)
        assertFalse(HeadSwapEngine.admits(flaggedCaps, adapter))
        assertNull(HeadServerCapabilities.fromProtocolJson(json + ("protocol_version" to "2.0")))
        assertEquals(4_194_304L, caps.maxOutputPixels)
    }

    @Test fun malformedAdvertisedLimitsFailAdmissionInsteadOfDefaulting() {
        fun caps(features: Map<String, Any?>, params: List<Map<String, Any?>>) = HeadServerCapabilities.fromProtocolJson(mapOf(
            "schema_version" to 1L, "protocol_version" to "1.0", "provider_id" to "self-hosted-flux2-head",
            "models" to listOf(mapOf("id" to HeadAdapterIdentity.HEAD_MODEL_ID)),
            "features" to features, "advanced_parameters" to params
        ))
        val features = mapOf<String, Any?>(
            "mask_edit" to true, "seed" to true, "full_head" to true,
            "reference_images" to mapOf("max_count" to 1L),
            "max_input_pixels" to 4_194_304L, "max_output_pixels" to 4_194_304L,
            "head_adapter" to mapOf("sha256" to sha)
        )
        val good = listOf(mapOf<String, Any?>("name" to "head_width", "minimum" to 32L, "maximum" to 1024L))
        val parsed = caps(features, good)!!
        assertEquals(1024, parsed.maxCropEdge)
        assertTrue(HeadSwapEngine.admits(parsed, adapter))
        assertNull(caps(features, listOf(mapOf("name" to "head_width", "minimum" to "32"))))
        assertNull(caps(features, listOf(mapOf("name" to "head_height", "maximum" to 1.5))))
        assertNull(caps(features, listOf(mapOf("name" to "head_height", "maximum" to -4L))))
        assertNull(caps(features, listOf(mapOf("name" to "head_width", "minimum" to 900L, "maximum" to 100L))))
        assertNull(caps(features - "max_output_pixels", good))
        assertNull(caps(features + ("max_output_pixels" to "big"), good))
    }

    @Test fun adapterMismatchIsModeUnavailable() {
        assertUnavailable(HeadSwapEngine(approval(), FakeTransport(HeadHandshake.Reachable(admitted(adapterSha = "b".repeat(64))))))
        assertUnavailable(HeadSwapEngine(approval(), FakeTransport(HeadHandshake.Reachable(admitted(adapterSha = null)))))
        assertUnavailable(HeadSwapEngine(approval(), FakeTransport(HeadHandshake.Reachable(admitted(model = "other-model")))))
        assertUnavailable(HeadSwapEngine(approval(identity = adapter.copy(maxReferences = 3)),
            FakeTransport(HeadHandshake.Reachable(admitted(maxRefs = 2)))))
    }

    @Test fun stockOrUnknownBackendsNeverServeHead() {
        for (provider in listOf("atlas-fake", "diffusers-flux", "diffusers-qwen", "comfyui", "unknown")) {
            assertUnavailable(HeadSwapEngine(approval(), FakeTransport(HeadHandshake.Reachable(admitted(provider = provider)))))
            try {
                HeadAdapterIdentity(providerId = provider, adapterSha256 = sha)
                fail("$provider accepted as a head adapter")
            } catch (expected: IllegalArgumentException) { }
        }
    }

    @Test fun offlineHandshakeIsUnavailableWithoutFallback() {
        for (reason in HeadTransportFailure.values()) {
            val transport = FakeTransport(HeadHandshake.Unreachable(reason))
            val run = open(HeadSwapEngine(approval(), transport)).generate(3)
            assertEquals(IdentityRunStatus.FAILED, run.status)
            assertEquals(IdentityFailureKind.MODE_UNAVAILABLE, run.problem!!.kind)
            assertTrue(transport.jobs.isEmpty())
        }
    }

    @Test fun submitErrorsRefusalAndExceptionsFailWithoutFallbackOrLeftovers() {
        val replies = listOf<(HeadTransportRequest, IntArray) -> HeadTransportResult>(
            { _, _ -> HeadTransportResult.Failed(HeadTransportFailure.OFFLINE) },
            { _, _ -> HeadTransportResult.Failed(HeadTransportFailure.TIMEOUT) },
            { _, _ -> HeadTransportResult.Failed(HeadTransportFailure.SERVER) },
            { _, _ -> HeadTransportResult.Refused("POLICY") },
            { _, _ -> throw IllegalStateException("synthetic") }
        )
        for (reply in replies) {
            val transport = FakeTransport(reply = reply)
            val session = open(HeadSwapEngine(approval(), transport))
            val run = session.generate(3)
            assertEquals(IdentityRunStatus.FAILED, run.status)
            assertEquals(IdentityFailureKind.UNKNOWN, run.problem!!.kind)
            assertEquals("one attempt, no retry or other route", 1, transport.jobs.size)
            assertTrue(session.candidates.isEmpty())
            assertTrue(sessionFiles().isEmpty())
        }
    }

    @Test fun faceModeIsNeverServedByTheHeadEngine() {
        val transport = FakeTransport()
        val run = open(HeadSwapEngine(approval(), transport), input(mode = IdentityEditMode.FACE)).generate(3)
        assertEquals(IdentityFailureKind.MODE_UNAVAILABLE, run.problem!!.kind)
        assertTrue(transport.jobs.isEmpty())
    }

    @Test fun cancelDuringSubmitStopsAndCleansUp() {
        lateinit var session: IdentityCandidateSession
        var sawToken = false
        val transport = FakeTransport(onSubmit = { _, token ->
            session.cancel()
            sawToken = token.isCancelled()
        }, reply = { _, _ -> HeadTransportResult.Cancelled })
        session = open(HeadSwapEngine(approval(), transport))
        val run = session.generate(3)
        assertEquals(IdentityRunStatus.CANCELLED, run.status)
        assertTrue("transport sees the cancellation", sawToken)
        assertTrue(session.candidates.isEmpty())
        assertTrue(sessionFiles().isEmpty())
    }

    @Test fun cancelAfterASuccessfulReplyDiscardsTheResult() {
        lateinit var session: IdentityCandidateSession
        val transport = FakeTransport(onSubmit = { _, _ -> session.cancel() })
        session = open(HeadSwapEngine(approval(), transport))
        val run = session.generate(3)
        assertEquals(IdentityRunStatus.CANCELLED, run.status)
        assertTrue(sessionFiles().isEmpty())
    }

    @Test fun warmSessionHandshakesOnceAcrossMore() {
        val transport = FakeTransport()
        val session = open(HeadSwapEngine(approval(), transport))
        assertEquals(IdentityRunStatus.COMPLETE, session.generate(3).status)
        assertEquals(IdentityRunStatus.COMPLETE, session.more(2).status)
        assertEquals(1, transport.handshakes)
        assertEquals(5, session.candidates.size)
        session.close()
        assertTrue(sessionFiles().isEmpty())
    }

    @Test fun wrongSizedOrForeignReplyIsRejected() {
        val small = FakeTransport(reply = { r, o ->
            HeadTransportResult.Success(o.copyOf(o.size - r.crop.width), r.crop.width, r.crop.height - 1,
                HeadAdapterIdentity.HEAD_PROVIDER_ID, HeadAdapterIdentity.HEAD_MODEL_ID, sha)
        })
        assertEquals(IdentityFailureKind.UNKNOWN, open(HeadSwapEngine(approval(), small)).generate(3).problem!!.kind)
        val swapped = FakeTransport(reply = { r, o ->
            HeadTransportResult.Success(o, r.crop.width, r.crop.height,
                HeadAdapterIdentity.HEAD_PROVIDER_ID, HeadAdapterIdentity.HEAD_MODEL_ID, "c".repeat(64))
        })
        assertEquals(IdentityFailureKind.MODE_UNAVAILABLE,
            open(HeadSwapEngine(approval(), swapped)).generate(3).problem!!.kind)
    }

    @Test fun tooSmallFaceAndTooLargePhotoAreReported() {
        val tiny = input(box = FaceBox(50, 50, 60, 60))
        assertEquals(IdentityFailureKind.FACE_TOO_SMALL,
            open(HeadSwapEngine(approval(), FakeTransport()), tiny).generate(3).problem!!.kind)
        val limited = FakeTransport(HeadHandshake.Reachable(admitted().copy(maxInputPixels = 10_000L)))
        assertEquals(IdentityFailureKind.PHOTO_TOO_LARGE,
            open(HeadSwapEngine(approval(), limited)).generate(3).problem!!.kind)
    }

    @Test fun oversizedReferenceOrCropOutputIsRefusedBeforeSubmit() {
        val transport = FakeTransport(HeadHandshake.Reachable(admitted().copy(maxInputPixels = 20_000L)))
        val run = open(HeadSwapEngine(approval(), transport), input(refSize = 200)).generate(3)
        assertEquals(IdentityFailureKind.PHOTO_TOO_LARGE, run.problem!!.kind)
        assertTrue(transport.jobs.isEmpty())
        // Crop is 62 x 71 here, so an output ceiling of 3,000 pixels must stop it.
        val small = FakeTransport(HeadHandshake.Reachable(admitted(maxOutputPixels = 3_000L)))
        val run2 = open(HeadSwapEngine(approval(), small)).generate(3)
        assertEquals(IdentityFailureKind.PHOTO_TOO_LARGE, run2.problem!!.kind)
        assertTrue(small.jobs.isEmpty())
        val edge = FakeTransport(HeadHandshake.Reachable(admitted().copy(maxCropEdge = 40)))
        assertEquals(IdentityFailureKind.PHOTO_TOO_LARGE,
            open(HeadSwapEngine(approval(), edge)).generate(3).problem!!.kind)
        assertTrue(edge.jobs.isEmpty())
    }

    @Test fun revokedAdapterStaysUnavailableAcrossMoreWithoutNewSubmits() {
        val transport = FakeTransport(reply = { r, o ->
            HeadTransportResult.Success(o, r.crop.width, r.crop.height,
                HeadAdapterIdentity.HEAD_PROVIDER_ID, HeadAdapterIdentity.HEAD_MODEL_ID, "c".repeat(64))
        })
        val engine = HeadSwapEngine(approval(), transport)
        val session = open(engine)
        val first = session.generate(3)
        assertEquals(IdentityFailureKind.MODE_UNAVAILABLE, first.problem!!.kind)
        assertTrue(engine.adapterRevoked)
        repeat(2) {
            val more = session.more(1)
            assertEquals(IdentityRunStatus.FAILED, more.status)
            assertEquals(IdentityFailureKind.MODE_UNAVAILABLE, more.problem!!.kind)
        }
        assertEquals("no submit after revocation", 1, transport.jobs.size)
        assertEquals(1, transport.handshakes)
        // A fresh load (after idle release or a new session) is refused too.
        assertEquals(IdentityFailureKind.MODE_UNAVAILABLE, (engine.load() as EngineLoadResult.Unavailable).kind)
        val again = open(engine).generate(3)
        assertEquals(IdentityFailureKind.MODE_UNAVAILABLE, again.problem!!.kind)
        assertEquals(1, transport.jobs.size)
        assertTrue(session.candidates.isEmpty())
    }

    @Test fun versionPinsTheAcceptedAdapter() {
        val other = HeadSwapEngine(approval(identity = adapter.copy(adapterSha256 = "d".repeat(64))), FakeTransport())
        assertNotEquals(HeadSwapEngine(approval(), FakeTransport()).version, other.version)
        assertEquals(HeadSwapEngine.ENGINE_VERSION, HeadSwapEngine(null, null).version)
    }

    private fun assertUnavailable(engine: HeadSwapEngine) {
        val load = engine.load()
        assertEquals(IdentityFailureKind.MODE_UNAVAILABLE, (load as EngineLoadResult.Unavailable).kind)
        val run = open(engine).generate(3)
        assertEquals(IdentityRunStatus.FAILED, run.status)
        assertEquals(IdentityFailureKind.MODE_UNAVAILABLE, run.problem!!.kind)
    }
}
