package nl.atlas.gallery.featureidentity.engine

import nl.atlas.gallery.featureaiedit.provider.CostEstimate
import nl.atlas.gallery.featureaiedit.provider.EditImage
import nl.atlas.gallery.featureaiedit.provider.ImageFormat
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

/** Synthetic fixtures only: generated pixels and made-up handles, no real images or people. */
class IdentityCandidateSessionTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var root: File
    private val opened = ArrayList<IdentityCandidateSession>()
    private var now = 1_000L

    @Before fun setUp() { root = tmp.newFolder("atlas-identity") }
    @After fun tearDown() { opened.forEach { it.close() } }

    private fun source(w: Int = 64, h: Int = 64) = EditImage(
        "synthetic-source", w, h, ImageFormat.PNG,
        List(w * h) { i -> val v = (i % w) * 3 and 255; (0xff shl 24) or (v shl 16) or (v shl 8) or v }
    )

    private fun input(
        mode: IdentityEditMode = IdentityEditMode.FACE,
        box: FaceBox = FaceBox(16, 16, 48, 48),
        refs: Int = 5,
        landmarks: List<FacePoint> = listOf(
            FacePoint(24f, 26f), FacePoint(40f, 26f), FacePoint(32f, 33f), FacePoint(26f, 40f), FacePoint(38f, 40f)
        )
    ) = IdentitySessionInput(
        source = source(),
        target = TargetFace(box, landmarks),
        references = IdentityReferenceSet(
            "synthetic-profile", "default",
            List(refs) { EditImage("synthetic-ref-$it", 32, 32, ImageFormat.PNG) }
        ),
        mode = mode
    )

    private fun open(
        engine: IdentityEditEngine,
        input: IdentitySessionInput = input(),
        route: EngineRoute = EngineRoute.LOCAL,
        idle: Long = 5_000L
    ) = IdentityCandidateSession.open(
        engine, input, root, route, clock = { now }, idleTimeoutMs = idle, sessionSeed = 42L
    ).also { opened += it }

    private fun filesUnder(dir: File): List<File> = dir.walkTopDown().filter { it.isFile }.toList()

    // --- Candidate count and variation -------------------------------------------------------

    @Test fun firstBatchMakesDistinctCandidatesFromOneLoad() {
        val engine = FakeIdentityEditEngine()
        val session = open(engine)
        val seen = ArrayList<Int>()
        val result = session.generate(4) { seen += it.number }

        assertEquals(IdentityRunStatus.COMPLETE, result.status)
        assertEquals(4, result.newCandidates.size)
        assertEquals(listOf(1, 2, 3, 4), seen)
        assertEquals(1, engine.loads.get())
        val c = result.newCandidates
        for (i in c.indices) for (j in i + 1 until c.size) {
            assertTrue("candidates $i and $j too similar",
                c[i].fingerprint.distance(c[j].fingerprint) >= IdentityCandidateSession.DEFAULT_DUPLICATE_DISTANCE)
        }
        assertEquals(4, c.map { it.variation.seed }.toSet().size)
        assertEquals(4, c.map { it.variation.blendStrength }.toSet().size)
        c.forEach {
            val file = assertNotNullFile(it.file)
            assertTrue(file.canonicalPath.startsWith(root.canonicalPath))
            assertArrayEquals(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()),
                file.readBytes().copyOf(4))
            assertEquals("atlas-fake-identity", it.engineId)
            assertEquals(EngineRoute.LOCAL, it.route)
        }
    }

    @Test fun threeIsAllowedAndOtherFirstBatchSizesAreNot() {
        assertEquals(3, open(FakeIdentityEditEngine()).generate(3).newCandidates.size)
        listOf(2, 5).forEach { n ->
            try { open(FakeIdentityEditEngine()).generate(n); fail("$n accepted") }
            catch (expected: IllegalArgumentException) { }
        }
    }

    @Test fun variationPolicyRotatesReferenceSubsetsSeedsAndBlends() {
        val policy = IdentityVariationPolicy(7L)
        val plans = (0 until 20).map { policy.planFor(it, referenceCount = 6, maxReferences = 3) }
        assertEquals(listOf(0, 1, 2), plans[0].referenceIndices)
        assertTrue(plans.all { it.referenceIndices.size == 3 && it.referenceIndices.all { i -> i in 0..5 } })
        assertTrue(plans.take(6).map { it.referenceIndices }.toSet().size >= 4)
        assertEquals(20, plans.map { it.seed }.toSet().size)
        assertTrue(plans.all { it.blendStrength in IdentityVariationPolicy.BLEND_LADDER })
        assertEquals(plans, (0 until 20).map { IdentityVariationPolicy(7L).planFor(it, 6, 3) })
        assertEquals(listOf(0, 1), policy.planFor(5, referenceCount = 2, maxReferences = 3).referenceIndices)
    }

    @Test fun requestsCarryOnlyTheChosenReferenceSubset() {
        val seen = ArrayList<List<String>>()
        val fake = FakeIdentityEditEngine(FakeIdentityEngineConfig(maxReferences = 2))
        val spy = object : IdentityEditEngine by fake {
            override fun load(): EngineLoadResult {
                val warm = (fake.load() as EngineLoadResult.Loaded).engine
                return EngineLoadResult.Loaded(object : WarmIdentityEngine by warm {
                    override fun generate(request: IdentityEditRequest, workspace: File, cancellation: IdentityCancellation):
                        IdentityEngineResult {
                        seen += request.references.references.map { it.handle }
                        assertEquals("default", request.references.variantId)
                        return warm.generate(request, workspace, cancellation)
                    }
                })
            }
        }
        open(spy).generate(3)
        assertEquals(listOf("synthetic-ref-0", "synthetic-ref-1"), seen[0])
        assertTrue(seen.all { it.size == 2 })
        assertTrue(seen.toSet().size > 1)
    }

    // --- Duplicate rejection ------------------------------------------------------------------

    @Test fun nearDuplicateIsRejectedAndAnotherPlanTried() {
        val engine = FakeIdentityEditEngine(FakeIdentityEngineConfig(duplicateOfPreviousOnCalls = setOf(1)))
        val session = open(engine)
        val result = session.generate(4)

        assertEquals(IdentityRunStatus.COMPLETE, result.status)
        assertEquals(4, result.newCandidates.size)
        assertEquals(1, result.rejectedAsDuplicates)
        assertEquals(5, engine.calls.get())
        val policy = IdentityVariationPolicy(42L)
        assertEquals(listOf(0, 2, 3, 4).map { policy.planFor(it, 5, 4).seed }, result.newCandidates.map { it.variation.seed })
        assertFalse("rejected attempt left files", File(root.listFiles()!!.single(), "attempt-1").exists())
    }

    @Test fun duplicateRetriesAreBounded() {
        val engine = FakeIdentityEditEngine(FakeIdentityEngineConfig(duplicateOfPreviousOnCalls = (1..100).toSet()))
        val result = open(engine).generate(4)

        assertEquals(IdentityRunStatus.COMPLETE, result.status)
        assertEquals(1, result.newCandidates.size)
        assertEquals(4 * IdentityCandidateSession.ATTEMPTS_PER_CANDIDATE, engine.calls.get())
        assertEquals(engine.calls.get() - 1, result.rejectedAsDuplicates)
    }

    // --- Warm reuse and idle release ----------------------------------------------------------

    @Test fun moreReusesTheWarmEngine() {
        val engine = FakeIdentityEditEngine()
        val session = open(engine)
        session.generate(4)
        val more = session.more(2)

        assertEquals(2, more.newCandidates.size)
        assertEquals(listOf(5, 6), more.newCandidates.map { it.number })
        assertEquals(6, session.candidates.size)
        assertEquals(1, engine.loads.get())
        assertEquals(0, engine.releases.get())
        val all = session.candidates
        for (i in all.indices) for (j in i + 1 until all.size)
            assertTrue(all[i].fingerprint.distance(all[j].fingerprint) >= IdentityCandidateSession.DEFAULT_DUPLICATE_DISTANCE)
    }

    @Test fun idleTimeoutReleasesAndTheNextRunReloads() {
        val engine = FakeIdentityEditEngine()
        val session = open(engine, idle = 5_000L)
        session.generate(3)
        now += 4_999L
        assertFalse(session.releaseIfIdle())
        assertTrue(session.isEngineWarm)
        now += 1L
        assertTrue(session.releaseIfIdle())
        assertFalse(session.isEngineWarm)
        assertEquals(1, engine.releases.get())
        assertFalse(session.releaseIfIdle())

        session.more(1)
        assertEquals(2, engine.loads.get())
        assertEquals(4, session.candidates.size)
    }

    @Test fun engineWithoutWarmReuseIsLoadedPerCandidateAndReleased() {
        val engine = FakeIdentityEditEngine(FakeIdentityEngineConfig(reusesWarmState = false))
        open(engine).generate(3)
        assertEquals(3, engine.loads.get())
        assertEquals(3, engine.releases.get())
    }

    // --- Cancel, failure and cleanup ----------------------------------------------------------

    @Test fun cancelMidCandidateStopsAndRemovesItsWork() {
        lateinit var session: IdentityCandidateSession
        val engine = FakeIdentityEditEngine(FakeIdentityEngineConfig(onStep = { call, step ->
            if (call == 1 && step == 1) session.cancel()
        }))
        session = open(engine)
        val result = session.generate(4)

        assertEquals(IdentityRunStatus.CANCELLED, result.status)
        assertEquals(1, result.newCandidates.size)
        assertEquals(2, engine.calls.get())
        val sessionDir = root.listFiles()!!.single()
        assertFalse(File(sessionDir, "attempt-1").exists())
        assertTrue(filesUnder(root).none { it.name.endsWith(".partial") })
        assertEquals(listOf(session.candidates.single().file!!.canonicalFile), filesUnder(root).map { it.canonicalFile })

        // The session stays usable after a cancel and still reuses the warm engine.
        assertEquals(2, session.more(2).newCandidates.size)
        assertEquals(1, engine.loads.get())

        session.close()
        assertTrue(filesUnder(root).isEmpty())
        assertTrue(root.listFiles()!!.isEmpty())
        assertEquals(1, engine.releases.get())
    }

    @Test fun cancelWithNothingRunningIsHarmless() {
        val session = open(FakeIdentityEditEngine())
        session.cancel()
        assertEquals(3, session.generate(3).newCandidates.size)
    }

    @Test fun engineFailureCleansUpAndExplainsInPlainWords() {
        val engine = FakeIdentityEditEngine(FakeIdentityEngineConfig(failOnCall = mapOf(2 to IdentityFailureKind.NO_FACE_FOUND)))
        val session = open(engine)
        val result = session.generate(4)

        assertEquals(IdentityRunStatus.FAILED, result.status)
        assertEquals(2, result.newCandidates.size)
        val problem = assertNotNullProblem(result.problem)
        assertEquals(IdentityFailureKind.NO_FACE_FOUND, problem.kind)
        assertTrue(problem.nextAction.isNotBlank())
        assertFalse(File(root.listFiles()!!.single(), "attempt-2").exists())
        assertTrue(filesUnder(root).none { it.name.endsWith(".partial") })
        assertEquals(2, filesUnder(root).size)
    }

    @Test fun outOfMemoryReleasesTheEngineAndCleansUp() {
        val engine = FakeIdentityEditEngine(FakeIdentityEngineConfig(outOfMemoryOnCall = 0))
        val session = open(engine)
        val result = session.generate(3)

        assertEquals(IdentityFailureKind.OUT_OF_MEMORY, result.problem?.kind)
        assertEquals(1, engine.releases.get())
        assertFalse(session.isEngineWarm)
        assertTrue(filesUnder(root).isEmpty())
    }

    @Test fun unexpectedEngineExceptionBecomesAPlainProblem() {
        val fake = FakeIdentityEditEngine()
        val throwing = object : IdentityEditEngine by fake {
            override fun load() = EngineLoadResult.Loaded(object : WarmIdentityEngine {
                override fun close() {}
                override fun generate(request: IdentityEditRequest, workspace: File, cancellation: IdentityCancellation):
                    IdentityEngineResult {
                    File(workspace, "half-written.bin").writeBytes(ByteArray(8))
                    throw IllegalStateException("synthetic")
                }
            })
        }
        val result = open(throwing).generate(3)
        assertEquals(IdentityFailureKind.UNKNOWN, result.problem?.kind)
        assertTrue(filesUnder(root).isEmpty())
    }

    @Test fun outputOutsideTheWorkspaceIsRefusedAndNotDeleted() {
        val outside = tmp.newFile("outside.png").apply { writeBytes(ByteArray(4)) }
        val fake = FakeIdentityEditEngine()
        val escaping = object : IdentityEditEngine by fake {
            override fun load(): EngineLoadResult {
                val warm = (fake.load() as EngineLoadResult.Loaded).engine
                return EngineLoadResult.Loaded(object : WarmIdentityEngine by warm {
                    override fun generate(request: IdentityEditRequest, workspace: File, cancellation: IdentityCancellation):
                        IdentityEngineResult {
                        val real = warm.generate(request, workspace, cancellation) as IdentityEngineResult.Success
                        return real.copy(file = outside)
                    }
                })
            }
        }
        val result = open(escaping).generate(3)
        assertEquals(IdentityRunStatus.FAILED, result.status)
        assertTrue(outside.exists())
        assertTrue(filesUnder(root).isEmpty())
    }

    @Test fun orphanSweepRemovesLeftoversFromADeadProcessOnly() {
        val stale = File(root, "session-${UUID.randomUUID()}/attempt-0").apply { mkdirs() }
        File(stale, "step-0.partial").writeBytes(ByteArray(16))
        val unrelated = File(root, "keep-me").apply { mkdirs() }
        File(unrelated, "note.txt").writeText("not a session")

        val first = open(FakeIdentityEditEngine())
        assertFalse(stale.parentFile!!.exists())
        assertTrue(File(unrelated, "note.txt").exists())
        first.generate(3)
        val firstFiles = filesUnder(root).filter { it.name == "candidate.png" }
        assertEquals(3, firstFiles.size)

        // A second live session in the same process does not sweep the first one.
        open(FakeIdentityEditEngine())
        assertTrue(firstFiles.all { it.exists() })
        assertEquals(0, IdentityCandidateSession.sweepOrphans(root))
    }

    @Test fun closeDeletesEverythingAndRefusesFurtherWork() {
        val engine = FakeIdentityEditEngine()
        val session = open(engine)
        session.generate(4)
        session.close()
        assertTrue(root.listFiles()!!.isEmpty())
        assertTrue(session.candidates.isEmpty())
        assertEquals(1, engine.releases.get())
        try { session.more(1); fail("closed session ran") } catch (expected: IllegalStateException) { }
    }

    // --- Route and network --------------------------------------------------------------------

    @Test fun engineOnAnotherRouteIsRefusedWithoutLoading() {
        val paid = FakeIdentityEditEngine(FakeIdentityEngineConfig(route = EngineRoute.PAID, makesNetworkCalls = true))
        val result = open(paid, route = EngineRoute.LOCAL).generate(3)
        assertEquals(IdentityFailureKind.ROUTE_NOT_APPROVED, result.problem?.kind)
        assertEquals(0, paid.loads.get())
        assertEquals(0, paid.calls.get())
    }

    @Test fun routeChangeMidSessionIsRefused() {
        val fake = FakeIdentityEditEngine()
        var route = EngineRoute.LOCAL
        val drifting = object : IdentityEditEngine by fake {
            override fun capabilities() = fake.capabilities().copy(
                route = route, makesNetworkCalls = route != EngineRoute.LOCAL
            )
        }
        val session = open(drifting)
        assertEquals(3, session.generate(3).newCandidates.size)
        route = EngineRoute.SELF_HOSTED
        val result = session.more(2)
        assertEquals(IdentityRunStatus.FAILED, result.status)
        assertEquals(IdentityFailureKind.ROUTE_NOT_APPROVED, result.problem?.kind)
        assertTrue(result.newCandidates.isEmpty())
        assertEquals(3, fake.calls.get())
    }

    @Test fun explicitRouteSwitchIsAllowedAndReleasesTheOldEngine() {
        val local = FakeIdentityEditEngine()
        val selfHosted = FakeIdentityEditEngine(FakeIdentityEngineConfig(route = EngineRoute.SELF_HOSTED, makesNetworkCalls = true))
        val session = open(local)
        session.generate(3)
        session.switchEngine(selfHosted, EngineRoute.SELF_HOSTED)
        assertEquals(1, local.releases.get())
        val more = session.more(1)
        assertEquals(1, more.newCandidates.size)
        assertEquals(EngineRoute.SELF_HOSTED, more.newCandidates.single().route)
    }

    @Test fun localCapabilityDeclaresNoNetworkAndNoCost() {
        val caps = FakeIdentityEditEngine().capabilities()
        assertEquals(EngineRoute.LOCAL, caps.route)
        assertFalse(caps.makesNetworkCalls)
        assertEquals(0L, caps.costPerCandidate.minorUnits)
        assertTrue(caps.reusesWarmState)
        assertEquals(setOf(IdentityEditMode.FACE, IdentityEditMode.HEAD), caps.supportedModes)
        try { caps.copy(makesNetworkCalls = true); fail("local engine declared network") }
        catch (expected: IllegalArgumentException) { }
        try { caps.copy(costPerCandidate = CostEstimate("EUR", 1)); fail("local engine declared a cost") }
        catch (expected: IllegalArgumentException) { }
    }

    @Test fun engineSourcesUseNoNetworkApis() {
        val dir = listOf(
            File("src/main/java/nl/atlas/gallery/featureidentity/engine"),
            File("app/src/main/java/nl/atlas/gallery/featureidentity/engine")
        ).firstOrNull { it.isDirectory }
        assertNotNull("engine sources not found from ${File(".").absolutePath}", dir)
        val forbidden = listOf("java.net", "javax.net", "okhttp", "android.net", "HttpURLConnection", "Socket(", "ktor", "retrofit")
        val sources = dir!!.listFiles { f -> f.name.endsWith(".kt") }!!.toList()
        assertTrue(sources.size >= 3)
        sources.forEach { file ->
            val text = file.readText()
            forbidden.forEach { assertFalse("${file.name} uses $it", text.contains(it)) }
        }
    }

    // --- Pre-checks and plain language --------------------------------------------------------

    @Test fun preChecksBecomePlainProblems() {
        fun kindFor(engine: FakeIdentityEditEngine, input: IdentitySessionInput) = open(engine, input).generate(3).problem?.kind

        assertEquals(IdentityFailureKind.ENGINE_NOT_INSTALLED,
            kindFor(FakeIdentityEditEngine(FakeIdentityEngineConfig(installed = false)), input()))
        assertEquals(IdentityFailureKind.FACE_TOO_SMALL,
            kindFor(FakeIdentityEditEngine(), input(box = FaceBox(20, 20, 30, 30),
                landmarks = List(5) { FacePoint(25f, 25f) })))
        assertEquals(IdentityFailureKind.NO_FACE_FOUND,
            kindFor(FakeIdentityEditEngine(), input(landmarks = List(5) { FacePoint(2f, 2f) })))
        assertEquals(IdentityFailureKind.NO_FACE_FOUND,
            kindFor(FakeIdentityEditEngine(), input(box = FaceBox(40, 40, 80, 80), landmarks = List(5) { FacePoint(50f, 50f) })))
        assertEquals(IdentityFailureKind.MODE_UNAVAILABLE,
            kindFor(FakeIdentityEditEngine(FakeIdentityEngineConfig(supportedModes = setOf(IdentityEditMode.FACE))),
                input(mode = IdentityEditMode.HEAD)))
        assertEquals(IdentityFailureKind.NO_REFERENCES, kindFor(FakeIdentityEditEngine(), input(refs = 0)))
        assertEquals(IdentityFailureKind.PHOTO_TOO_LARGE,
            kindFor(FakeIdentityEditEngine(FakeIdentityEngineConfig(maxSourcePixels = 1024)), input()))
    }

    @Test fun everyProblemHasPlainWordsAndANextAction() {
        val technical = Regex("(?i)\\b(model|engine|onnx|gpu|npu|ram|memory|error|exception|null|route|api|network|tensor|inference)\\b")
        IdentityFailureKind.values().forEach { kind ->
            IdentityEditMode.values().forEach { mode ->
                val p = IdentityProblems.of(kind, mode)
                assertEquals(kind, p.kind)
                assertTrue(p.message.isNotBlank() && p.nextAction.isNotBlank())
                assertNull("$kind: ${p.message}", technical.find(p.message))
                assertNull("$kind: ${p.nextAction}", technical.find(p.nextAction))
            }
        }
    }

    // --- Fake engine behaviour that later units rely on ---------------------------------------

    @Test fun fakeIsDeterministicAndLeavesPixelsOutsideTheRegionUntouched() {
        val src = source()
        fun runOnce(mode: IdentityEditMode): List<IdentityCandidate> {
            val dir = tmp.newFolder()
            val s = IdentityCandidateSession.open(FakeIdentityEditEngine(), input(mode = mode), dir, sessionSeed = 9L)
            try { return s.generate(3).newCandidates.map { it.copy(file = null) } } finally { s.close() }
        }
        val a = runOnce(IdentityEditMode.FACE)
        val b = runOnce(IdentityEditMode.FACE)
        assertEquals(a.map { it.image.pixels }, b.map { it.image.pixels })

        val head = runOnce(IdentityEditMode.HEAD)
        listOf(IdentityEditMode.FACE to a, IdentityEditMode.HEAD to head).forEach { (mode, candidates) ->
            val region = if (mode == IdentityEditMode.HEAD) FaceBox(16, 16, 48, 48).expanded(1.6, 64, 64) else FaceBox(16, 16, 48, 48)
            candidates.forEach { c ->
                assertEquals(src.width, c.image.width)
                assertEquals(src.height, c.image.height)
                var changedInside = 0
                for (y in 0 until 64) for (x in 0 until 64) {
                    val i = y * 64 + x
                    val inside = x >= region.left && x < region.right && y >= region.top && y < region.bottom
                    if (!inside) assertEquals("pixel $x,$y outside region changed", src.pixels[i], c.image.pixels[i])
                    else if (src.pixels[i] != c.image.pixels[i]) changedInside++
                }
                assertTrue(changedInside > 0)
            }
        }
        assertTrue(FaceBox(16, 16, 48, 48).expanded(1.6, 64, 64).width > 32)
    }

    private fun assertNotNullFile(file: File?): File { assertNotNull(file); return file!! }
    private fun assertNotNullProblem(p: IdentityProblem?): IdentityProblem { assertNotNull(p); return p!! }
}
