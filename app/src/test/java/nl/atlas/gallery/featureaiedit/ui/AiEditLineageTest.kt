package nl.atlas.gallery.featureaiedit.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiEditLineageTest {
    private fun c(id: String, parent: String?, prompt: String = "p-$id") =
        AiEditWorkingCandidate(id, "$id.png", parent, prompt, "atlas-synthetic-mock", "m", "1")

    @Test fun trailWalksFromOriginalToSelectedWithSessionLabels() {
        val all = listOf(c("a", null), c("b", "a"), c("x", null), c("d", "b"))
        val trail = AiEditLineage.trail(all, "d")
        assertEquals(listOf("Original", "Result 1", "Result 2", "Result 4"), trail.steps.map { it.label })
        assertEquals(listOf(null, "a", "b", "d"), trail.steps.map { it.candidateId })
        assertFalse(trail.broken)
        assertEquals("d", trail.current.candidateId)
    }

    @Test fun switchingCandidateShowsItsOwnLineageNotTheNewest() {
        val all = listOf(c("a", null), c("b", "a"), c("c", null))
        assertEquals(listOf(null, "a", "b"), AiEditLineage.trail(all, "b").steps.map { it.candidateId })
        assertEquals(listOf(null, "c"), AiEditLineage.trail(all, "c").steps.map { it.candidateId })
    }

    @Test fun nothingSelectedIsJustTheOriginal() {
        val trail = AiEditLineage.trail(listOf(c("a", null)), null)
        assertEquals(listOf(AiEditLineage.ORIGINAL), trail.steps)
    }

    @Test fun cycleAndMissingParentStopSafelyAndMarkBroken() {
        val cycle = listOf(c("a", "b"), c("b", "a"))
        val t1 = AiEditLineage.trail(cycle, "a")
        assertTrue(t1.broken)
        assertEquals(3, t1.steps.size) // Original + b + a, each once
        val missing = listOf(c("b", "gone"))
        val t2 = AiEditLineage.trail(missing, "b")
        assertTrue(t2.broken)
        assertEquals(listOf(null, "b"), t2.steps.map { it.candidateId })
        val self = listOf(c("s", "s"))
        assertTrue(AiEditLineage.trail(self, "s").broken)
    }

    @Test fun deepChainIsCutAtMaxDepthAndCompactShowsParentAndCurrent() {
        val chain = (0 until 50).map { i -> c("n$i", if (i == 0) null else "n${i - 1}") }
        val trail = AiEditLineage.trail(chain, "n49")
        assertTrue(trail.broken)
        assertEquals(AiEditLineage.MAX_DEPTH + 1, trail.steps.size)
        val compact = AiEditLineage.compact(trail)
        assertEquals(listOf("Original", "Result 49", "Result 50"), compact.steps.map { it.label })
        assertTrue(compact.needsHistory)
        assertEquals(trail.steps.size - 3, compact.hiddenSteps)
    }

    @Test fun shortTrailNeedsNoHistory() {
        val all = listOf(c("a", null), c("b", "a"))
        val compact = AiEditLineage.compact(AiEditLineage.trail(all, "b"))
        assertEquals(3, compact.steps.size)
        assertFalse(compact.needsHistory)
    }

    @Test fun labelsNeverExposeInternalIds() {
        val all = listOf(c("3f2a-uuid", null))
        val labels = AiEditLineage.trail(all, "3f2a-uuid").steps.map { it.label }
        assertTrue(labels.none { it.contains("uuid") })
        assertEquals("Earlier result", AiEditLineage.label(all, "unknown"))
    }

    @Test fun refineSourceIsTheChosenBranchParentOrTheOriginal() {
        val all = listOf(c("a", null), c("b", "a"))
        val base = AiEditUiState(candidates = all, selectedId = "b")
        assertEquals(AiEditRefineSource.Original, AiEditRefineSource.of(base.copy(branchParentId = null)))
        assertEquals(AiEditRefineSource.Candidate("a", "a.png"), AiEditRefineSource.of(base.copy(branchParentId = "a")))
        // A branch parent that no longer exists never silently becomes another result.
        assertEquals(AiEditRefineSource.Original, AiEditRefineSource.of(base.copy(branchParentId = "gone")))
    }

    @Test fun editThisResultThenGenerateRecordsTheChosenParent() {
        val machine = AiEditStateMachine()
        machine.compose("first")
        val t1 = requireNotNull(machine.generate())
        assertTrue(machine.onResult(t1, listOf(c("a", null, "first"), c("b", null, "first"))))
        // Choose result 2, then refine it.
        assertTrue(machine.selectCandidate("b"))
        assertTrue(machine.selectBranch("b"))
        assertTrue(machine.compose("second"))
        assertEquals(AiEditRefineSource.Candidate("b", "b.png"), AiEditRefineSource.of(machine.state))
        // Start over goes back to the original in one step, keeping all results.
        assertTrue(machine.selectBranch(null))
        assertEquals(AiEditRefineSource.Original, AiEditRefineSource.of(machine.state))
        assertEquals(2, machine.state.candidates.size)
    }
}
