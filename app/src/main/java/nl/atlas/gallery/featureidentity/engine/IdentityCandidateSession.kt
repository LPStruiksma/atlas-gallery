package nl.atlas.gallery.featureidentity.engine

import nl.atlas.gallery.featureaiedit.provider.EditImage
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** What the user picked: the photo, the face, the person and how to change it. */
data class IdentitySessionInput(
    val source: EditImage,
    val target: TargetFace,
    val references: IdentityReferenceSet,
    val mode: IdentityEditMode,
    val preferences: IdentityPreferences = IdentityPreferences()
)

/** A plain-language problem with the next thing the user can do. No technical words. */
data class IdentityProblem(val kind: IdentityFailureKind, val message: String, val nextAction: String)

object IdentityProblems {
    fun of(kind: IdentityFailureKind, mode: IdentityEditMode = IdentityEditMode.FACE): IdentityProblem = when (kind) {
        IdentityFailureKind.NO_FACE_FOUND -> IdentityProblem(kind,
            "We couldn't find a face there.", "Tap the face you want to change, or choose another photo.")
        IdentityFailureKind.FACE_TOO_SMALL -> IdentityProblem(kind,
            "That face is too small to change well.", "Choose a photo where the face is bigger.")
        IdentityFailureKind.ENGINE_NOT_INSTALLED -> IdentityProblem(kind,
            "Swap person isn't set up on this phone yet.", "Finish setting it up, then try again.")
        IdentityFailureKind.OUT_OF_MEMORY -> IdentityProblem(kind,
            "Your phone ran out of room to finish this.", "Close other apps and try again.")
        IdentityFailureKind.PHOTO_TOO_LARGE -> IdentityProblem(kind,
            "This photo is too large to change on this phone.", "Try a smaller copy of the photo.")
        IdentityFailureKind.MODE_UNAVAILABLE -> IdentityProblem(kind,
            if (mode == IdentityEditMode.HEAD) "Changing the whole head isn't available yet."
            else "Changing just the face isn't available yet.",
            if (mode == IdentityEditMode.HEAD) "Try changing just the face." else "Try changing the whole head.")
        IdentityFailureKind.NO_REFERENCES -> IdentityProblem(kind,
            "We need a few photos of this person first.", "Add photos of this person, then try again.")
        IdentityFailureKind.ROUTE_NOT_APPROVED -> IdentityProblem(kind,
            "This would use a different service than the one you chose.", "Check your Swap person settings.")
        IdentityFailureKind.UNKNOWN -> IdentityProblem(kind,
            "Something went wrong while making this.", "Try again.")
    }
}

/** A finished candidate with what the save unit needs for provenance. */
data class IdentityCandidate(
    val id: String,
    val number: Int,
    val image: EditImage,
    val file: File?,
    val variation: IdentityVariation,
    val fingerprint: PerceptualFingerprint,
    val engineId: String,
    val engineVersion: String,
    val route: EngineRoute,
    val mode: IdentityEditMode
)

enum class IdentityRunStatus { COMPLETE, CANCELLED, FAILED }

/**
 * Result of one [IdentityCandidateSession.generate] or [IdentityCandidateSession.more] call.
 * COMPLETE may hold fewer than [requested] candidates when the engine kept producing near
 * duplicates; the attempt budget is bounded so a run never loops forever.
 */
data class IdentityRunResult(
    val requested: Int,
    val newCandidates: List<IdentityCandidate>,
    val status: IdentityRunStatus,
    val problem: IdentityProblem? = null,
    val rejectedAsDuplicates: Int = 0
)

/**
 * Chooses how each attempt differs: a rotating reference subset, a fresh seed and a blend
 * strength from a fixed ladder. Attempt numbers are session-wide, so "More" never repeats a plan.
 */
class IdentityVariationPolicy(private val sessionSeed: Long) {
    fun planFor(attempt: Int, referenceCount: Int, maxReferences: Int): IdentityVariation {
        require(attempt >= 0 && referenceCount >= 1 && maxReferences >= 1)
        val size = minOf(referenceCount, maxReferences)
        // Attempt 0 uses the curator's best-first references; later attempts rotate the window.
        val indices = if (size == referenceCount) (0 until referenceCount).toList()
        else (0 until size).map { (attempt + it) % referenceCount }.sorted()
        return IdentityVariation(
            seed = mix(sessionSeed + attempt),
            referenceIndices = indices,
            blendStrength = BLEND_LADDER[attempt % BLEND_LADDER.size]
        )
    }

    companion object {
        val BLEND_LADDER = listOf(0.85, 0.7, 1.0, 0.6, 0.95, 0.75)

        /** SplitMix64 finaliser: deterministic, well spread seeds from consecutive inputs. */
        fun mix(value: Long): Long {
            var z = value + -0x61c8864680b583ebL
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }
    }
}

/**
 * Runs 3–4 candidates plus "More" for one photo, face and person.
 *
 * - Warm: the engine is loaded once and reused for every candidate and every "More", then
 *   released by [releaseIfIdle] after [idleTimeoutMs] without use (the host calls it on a timer).
 * - Variation: [IdentityVariationPolicy] plans each attempt; a result closer than
 *   [duplicateDistance] to any kept candidate is discarded and another plan is tried.
 * - Cancel: [cancel] stops the running candidate at the engine's next check and deletes its
 *   work; finished candidates stay until [close], which deletes everything.
 * - Cleanup: every attempt gets its own folder under the session folder. Failure or cancel
 *   deletes that folder. [open] first sweeps session folders left by a dead process.
 * - Route: the session only runs an engine whose route equals the approved route. It never
 *   falls back to another engine; [switchEngine] needs the new route stated explicitly.
 */
class IdentityCandidateSession private constructor(
    engine: IdentityEditEngine,
    approvedRoute: EngineRoute,
    val input: IdentitySessionInput,
    private val sessionDir: File,
    private val clock: () -> Long,
    private val idleTimeoutMs: Long,
    private val duplicateDistance: Double,
    sessionSeed: Long
) : AutoCloseable {

    val id: String = sessionDir.name
    private val policy = IdentityVariationPolicy(sessionSeed)
    private val runLock = ReentrantLock()
    private val kept = CopyOnWriteArrayList<IdentityCandidate>()

    @Volatile private var engine: IdentityEditEngine = engine
    @Volatile private var approvedRoute: EngineRoute = approvedRoute
    @Volatile private var warm: WarmIdentityEngine? = null
    @Volatile private var lastUsedAt: Long = clock()
    @Volatile private var activeRun: RunToken? = null
    @Volatile private var closed = false
    private var nextAttempt = 0

    val candidates: List<IdentityCandidate> get() = kept.toList()
    val isEngineWarm: Boolean get() = warm != null

    /** First batch: 3 or 4 candidates. */
    fun generate(count: Int = 4, onCandidate: (IdentityCandidate) -> Unit = {}): IdentityRunResult {
        require(count in FIRST_BATCH) { "The first batch is 3 or 4 candidates" }
        return run(count, onCandidate)
    }

    /** More candidates for the same photo, face and person, reusing the warm engine. */
    fun more(count: Int = 2, onCandidate: (IdentityCandidate) -> Unit = {}): IdentityRunResult {
        require(count in 1..4) { "More adds 1 to 4 candidates" }
        return run(count, onCandidate)
    }

    /** Stops the running candidate. Safe from any thread; a no-op when nothing runs. */
    fun cancel() { activeRun?.cancelled = true }

    /**
     * Explicit route change, for example after the user chose a self-hosted service. The new
     * engine's route must equal [newApprovedRoute]; the old warm engine is released.
     */
    fun switchEngine(newEngine: IdentityEditEngine, newApprovedRoute: EngineRoute) {
        check(!closed) { "Session is closed" }
        cancel()
        runLock.withLock {
            releaseWarm()
            engine = newEngine
            approvedRoute = newApprovedRoute
        }
    }

    /** Releases the engine when unused for the idle timeout. Returns true when it released. */
    fun releaseIfIdle(): Boolean {
        if (!runLock.tryLock()) return false
        try {
            if (warm == null || clock() - lastUsedAt < idleTimeoutMs) return false
            releaseWarm()
            return true
        } finally { runLock.unlock() }
    }

    /** Cancels, releases the engine and deletes every file this session made. */
    override fun close() {
        if (closed) return
        closed = true
        cancel()
        runLock.withLock {
            releaseWarm()
            kept.clear()
            deleteTree(sessionDir)
            unregister(sessionDir)
        }
    }

    private class RunToken : IdentityCancellation {
        @Volatile var cancelled = false
        override fun isCancelled() = cancelled
    }

    private fun run(count: Int, onCandidate: (IdentityCandidate) -> Unit): IdentityRunResult = runLock.withLock {
        check(!closed) { "Session is closed" }
        check(kept.size + count <= MAX_CANDIDATES) { "This session already has enough candidates" }
        val token = RunToken()
        activeRun = token
        try {
            val made = ArrayList<IdentityCandidate>()
            var duplicates = 0
            fun result(status: IdentityRunStatus, kind: IdentityFailureKind? = null) = IdentityRunResult(
                count, made.toList(), status, kind?.let { IdentityProblems.of(it, input.mode) }, duplicates
            )
            val currentEngine = engine
            val caps = currentEngine.capabilities()
            precheck(caps)?.let { return result(IdentityRunStatus.FAILED, it) }

            var attemptsLeft = count * ATTEMPTS_PER_CANDIDATE
            while (made.size < count && attemptsLeft-- > 0) {
                if (token.cancelled) return result(IdentityRunStatus.CANCELLED)
                // A capability change mid-session is a route change nobody asked for.
                if (currentEngine.capabilities().route != approvedRoute)
                    return result(IdentityRunStatus.FAILED, IdentityFailureKind.ROUTE_NOT_APPROVED)
                val loaded = warmEngine(currentEngine)
                if (loaded is EngineLoadResult.Unavailable) return result(IdentityRunStatus.FAILED, loaded.kind)
                val model = (loaded as EngineLoadResult.Loaded).engine

                val attempt = nextAttempt++
                val variation = policy.planFor(attempt, input.references.references.size, caps.maxReferences)
                val request = IdentityEditRequest(
                    source = input.source,
                    target = input.target,
                    references = input.references.copy(
                        references = variation.referenceIndices.map { input.references.references[it] }
                    ),
                    mode = input.mode,
                    preferences = input.preferences,
                    variation = variation
                )
                val workspace = File(sessionDir, "attempt-$attempt")
                check(workspace.mkdirs()) { "Cannot create a private working folder" }
                val outcome: IdentityEngineResult = try {
                    model.generate(request, workspace, token)
                } catch (e: IdentityCancelledException) {
                    deleteTree(workspace)
                    return result(IdentityRunStatus.CANCELLED)
                } catch (e: OutOfMemoryError) {
                    deleteTree(workspace)
                    releaseWarm()
                    return result(IdentityRunStatus.FAILED, IdentityFailureKind.OUT_OF_MEMORY)
                } catch (e: Exception) {
                    deleteTree(workspace)
                    return result(IdentityRunStatus.FAILED, IdentityFailureKind.UNKNOWN)
                } finally {
                    lastUsedAt = clock()
                    if (!caps.reusesWarmState) releaseWarm()
                }
                when (outcome) {
                    is IdentityEngineResult.Failure -> {
                        deleteTree(workspace)
                        return result(IdentityRunStatus.FAILED, outcome.kind)
                    }
                    is IdentityEngineResult.Success -> {
                        if (token.cancelled) {
                            deleteTree(workspace)
                            return result(IdentityRunStatus.CANCELLED)
                        }
                        if (outcome.file != null && !isInside(outcome.file, workspace)) {
                            deleteTree(workspace)
                            return result(IdentityRunStatus.FAILED, IdentityFailureKind.UNKNOWN)
                        }
                        if (kept.any { it.fingerprint.distance(outcome.fingerprint) < duplicateDistance }) {
                            duplicates++
                            deleteTree(workspace)
                            continue
                        }
                        val candidate = IdentityCandidate(
                            id = "$id-${kept.size + 1}",
                            number = kept.size + 1,
                            image = outcome.image,
                            file = outcome.file,
                            variation = variation,
                            fingerprint = outcome.fingerprint,
                            engineId = currentEngine.id,
                            engineVersion = currentEngine.version,
                            route = caps.route,
                            mode = input.mode
                        )
                        kept += candidate
                        made += candidate
                        onCandidate(candidate)
                    }
                }
            }
            result(IdentityRunStatus.COMPLETE)
        } finally {
            activeRun = null
        }
    }

    private fun precheck(caps: IdentityEngineCapabilities): IdentityFailureKind? {
        val source = input.source
        val box = input.target.box
        return when {
            caps.route != approvedRoute -> IdentityFailureKind.ROUTE_NOT_APPROVED
            input.mode !in caps.supportedModes -> IdentityFailureKind.MODE_UNAVAILABLE
            input.references.references.isEmpty() -> IdentityFailureKind.NO_REFERENCES
            source.width.toLong() * source.height > caps.maxSourcePixels -> IdentityFailureKind.PHOTO_TOO_LARGE
            box.left < 0 || box.top < 0 || box.right > source.width || box.bottom > source.height ||
                input.target.landmarks.any { !box.contains(it) } -> IdentityFailureKind.NO_FACE_FOUND
            else -> null
        }
    }

    private fun warmEngine(current: IdentityEditEngine): EngineLoadResult {
        warm?.let { return EngineLoadResult.Loaded(it) }
        val loaded = try { current.load() } catch (e: OutOfMemoryError) {
            EngineLoadResult.Unavailable(IdentityFailureKind.OUT_OF_MEMORY)
        } catch (e: Exception) {
            EngineLoadResult.Unavailable(IdentityFailureKind.ENGINE_NOT_INSTALLED)
        }
        if (loaded is EngineLoadResult.Loaded) warm = loaded.engine
        return loaded
    }

    private fun releaseWarm() {
        val current = warm ?: return
        warm = null
        try { current.close() } catch (e: Exception) { }
    }

    companion object {
        val FIRST_BATCH = 3..4
        const val MAX_CANDIDATES = 16
        const val ATTEMPTS_PER_CANDIDATE = 3
        const val DEFAULT_IDLE_TIMEOUT_MS = 90_000L
        const val DEFAULT_DUPLICATE_DISTANCE = 0.03
        private val SESSION_NAME = Regex("session-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val live = HashSet<String>()

        /**
         * Sweeps orphans under [workspaceRoot], then starts a session. [workspaceRoot] is an
         * app-private folder used only for identity sessions (for example cacheDir/atlas-identity).
         */
        fun open(
            engine: IdentityEditEngine,
            input: IdentitySessionInput,
            workspaceRoot: File,
            approvedRoute: EngineRoute = EngineRoute.LOCAL,
            clock: () -> Long = System::currentTimeMillis,
            idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
            duplicateDistance: Double = DEFAULT_DUPLICATE_DISTANCE,
            sessionSeed: Long = UUID.randomUUID().mostSignificantBits
        ): IdentityCandidateSession {
            require(idleTimeoutMs > 0) { "Idle timeout must be positive" }
            require(duplicateDistance in 0.0..1.0) { "Duplicate distance is a 0..1 fraction" }
            check(workspaceRoot.isDirectory || workspaceRoot.mkdirs()) { "Cannot create the identity workspace" }
            sweepOrphans(workspaceRoot)
            val dir = File(workspaceRoot.canonicalFile, "session-${UUID.randomUUID()}")
            check(dir.mkdirs()) { "Cannot create a session folder" }
            synchronized(live) { live += dir.path }
            return IdentityCandidateSession(
                engine, approvedRoute, input, dir, clock, idleTimeoutMs, duplicateDistance, sessionSeed
            )
        }

        /**
         * Deletes session folders under [workspaceRoot] that no live session in this process owns:
         * leftovers from a crash, kill or restart. Only session-shaped names are touched.
         */
        fun sweepOrphans(workspaceRoot: File): Int {
            val root = workspaceRoot.canonicalFile
            val alive = synchronized(live) { live.toSet() }
            var swept = 0
            root.listFiles().orEmpty()
                .filter { it.name.matches(SESSION_NAME) && File(root, it.name).path !in alive }
                .forEach { deleteTree(it); swept++ }
            return swept
        }

        private fun unregister(dir: File) { synchronized(live) { live -= dir.path } }

        private fun isInside(file: File, dir: File): Boolean {
            val base = dir.canonicalFile.toPath()
            return file.canonicalFile.toPath().startsWith(base)
        }

        /** Deletes a tree without following symbolic links out of it. */
        internal fun deleteTree(target: File) {
            val path = target.toPath()
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
            try {
                Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        Files.deleteIfExists(file); return FileVisitResult.CONTINUE
                    }
                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                        Files.deleteIfExists(file); return FileVisitResult.CONTINUE
                    }
                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        Files.deleteIfExists(dir); return FileVisitResult.CONTINUE
                    }
                })
            } catch (e: IOException) { }
        }
    }
}
