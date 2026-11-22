package nl.atlas.gallery

/**
 * PERF-BENCH-1 (#962). A catalog is a contract, not evidence of a completed benchmark.
 * The future Android runner must implement [BenchmarkStepPort] with disposable synthetic data.
 */
enum class BenchmarkAvailability { CONTRACT_READY, UNAVAILABLE }

data class BenchmarkStep(
    val action: UsageAction,
    val label: String,
    val availability: BenchmarkAvailability,
    val precondition: String,
    val controlAssertion: String,
    val stepKey: String?
) {
    init {
        require(label.isNotBlank())
        require(precondition.isNotBlank())
        require(controlAssertion.isNotBlank())
        require((availability == BenchmarkAvailability.CONTRACT_READY) == (stepKey != null))
        if (stepKey != null) require(stepKey.isNotBlank())
    }
}

/** No implementation in this unit; never invoke a production repository or media URI. */
interface BenchmarkStepPort {
    /** Verify a visible control and synthetic fixture before timing starts. */
    fun assertPrecondition(step: BenchmarkStep): Boolean
    /** Return elapsed nanoseconds only after an asserted, completed synthetic action. */
    fun executeSynthetic(step: BenchmarkStep): Long
}

/** Bump only when the ID set, status, step key or assertion contract changes. */
object BenchmarkActionCatalog {
    const val VERSION = 1

    private fun ready(id: UsageAction, label: String, before: String, assertion: String, key: String) =
        BenchmarkStep(id, label, BenchmarkAvailability.CONTRACT_READY, before, assertion, key)

    private fun unavailable(id: UsageAction, label: String, reason: String) =
        BenchmarkStep(id, label, BenchmarkAvailability.UNAVAILABLE, reason,
            "No runnable control assertion: action is unavailable in benchmark v1", null)

    val steps: List<BenchmarkStep> = listOf(
        ready(UsageAction.A1, "Open app to cached grid", "Synthetic inventory seeded; app process stopped",
            "Pictures grid visible and first synthetic thumbnail displayed", "open-cached-grid"),
        ready(UsageAction.A2, "Scroll / fling Pictures grid", "Pictures grid visible with enough synthetic rows",
            "Grid scroll offset changes and expected synthetic item becomes visible", "fling-pictures"),
        ready(UsageAction.A3, "Open photo from grid", "Pictures grid visible; synthetic photo cell exists",
            "Viewer shows the selected synthetic photo identifier", "open-synthetic-photo"),
        ready(UsageAction.A4, "Swipe between viewer photos", "Viewer shows first of two synthetic photos",
            "Viewer shows second synthetic photo after swipe", "swipe-viewer"),
        unavailable(UsageAction.A5, "Background face and visual indexing", "Background work has no stable screen/control boundary"),
        unavailable(UsageAction.A6, "New photo appears after capture", "Camera and MediaStore mutation are excluded from synthetic runner"),
        ready(UsageAction.A7, "Return to grid position", "Viewer opened from a known synthetic grid position",
            "Grid returns with original synthetic anchor visible", "return-grid"),
        unavailable(UsageAction.A8, "Memory and stability", "Aggregate diagnostic, not a single UI action"),
        ready(UsageAction.A9, "Open People tab", "Synthetic People dataset seeded; bottom navigation visible",
            "People tab heading and synthetic people list visible", "open-people"),
        ready(UsageAction.A10, "Open person and scroll photos", "Synthetic person with multiple photos visible",
            "Person page opens and photo list advances to expected synthetic item", "scroll-person"),
        ready(UsageAction.A11, "Search and suggestions", "Synthetic searchable inventory and search control visible",
            "Synthetic query suggestion and matching result visible", "search-synthetic"),
        ready(UsageAction.A12, "Pinch grid zoom", "Pictures grid visible with synthetic media",
            "Grid column count changes after pinch gesture", "pinch-grid"),
        unavailable(UsageAction.A13, "Viewer zoom tiles", "Tile source and visible tile completion contract not established"),
        ready(UsageAction.A14, "Play video", "Synthetic licensed video fixture in viewer",
            "Playback state advances and rendered frame is visible", "play-synthetic-video"),
        ready(UsageAction.A15, "Switch tabs / browse albums", "Bottom navigation and synthetic album visible",
            "Albums tab selected and synthetic album contents visible", "browse-albums"),
        ready(UsageAction.A16, "Select photos", "Synthetic photo cells visible in grid",
            "Selection toolbar shows expected selected count", "select-photos"),
        unavailable(UsageAction.A17, "Bulk actions", "Destructive mutations and rollback contract not yet specified"),
        unavailable(UsageAction.A18, "People review actions", "People identity mutations excluded pending disposable fixture"),
        unavailable(UsageAction.A19, "Stories", "Story playback end condition not yet established"),
        ready(UsageAction.A20, "Details and favourite", "Synthetic photo open in viewer",
            "Details sheet visible and favourite control reflects selected state", "details-favourite"),
        unavailable(UsageAction.A21, "Photo DNA and trash review", "Trash mutation and review dataset not yet specified"),
        ready(UsageAction.A22, "Sort and Settings", "Synthetic gallery visible with overflow controls",
            "Settings heading visible after navigation", "open-settings")
    )

    /** Reject both missing and stale IDs, duplicate rows/keys and broken contracts. */
    fun validate(rows: List<BenchmarkStep> = steps, source: Set<UsageAction> = UsageAction.entries.toSet()) {
        require(VERSION > 0)
        require(rows.map { it.action }.size == rows.map { it.action }.toSet().size) { "Duplicate catalog action" }
        require(rows.map { it.action }.toSet() == source) { "Missing or stale action IDs" }
        val keys = rows.mapNotNull { it.stepKey }
        require(keys.size == keys.toSet().size) { "Duplicate step keys" }
        rows.forEach {
            require(it.precondition.isNotBlank() && it.controlAssertion.isNotBlank())
            require((it.availability == BenchmarkAvailability.CONTRACT_READY) == (it.stepKey != null))
        }
    }

    /** Never run a step if its current control assertion cannot be established. */
    fun runOne(action: UsageAction, port: BenchmarkStepPort): Long {
        validate()
        val step = steps.single { it.action == action }
        require(step.availability == BenchmarkAvailability.CONTRACT_READY) { "Unavailable benchmark action" }
        check(port.assertPrecondition(step)) { "Synthetic control assertion failed before timing" }
        val elapsed = port.executeSynthetic(step)
        check(elapsed >= 0) { "Invalid elapsed duration" }
        return elapsed
    }
}
