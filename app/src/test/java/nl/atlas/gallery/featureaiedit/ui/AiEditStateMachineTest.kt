package nl.atlas.gallery.featureaiedit.ui

import org.junit.Assert.*
import org.junit.Test

class AiEditStateMachineTest {
    private fun candidate(id: String = "c1", parent: String? = null) = AiEditWorkingCandidate(
        id, "$id.png", parent, "Brighten the sky", "atlas-synthetic-mock", "mock", "1"
    )
    private fun generating(): Pair<AiEditStateMachine, Long> {
        val machine = AiEditStateMachine()
        assertTrue(machine.compose("Brighten the sky"))
        return machine to requireNotNull(machine.generate())
    }

    @Test fun fullTransitionChainAndSavingIsExplicit() {
        val machine = AiEditStateMachine()
        assertEquals(AiEditPhase.Idle, machine.state.phase)
        assertNull(machine.generate())
        assertTrue(machine.compose("Brighten the sky"))
        assertEquals(AiEditPhase.Composing, machine.state.phase)
        val token = requireNotNull(machine.generate())
        assertEquals(AiEditPhase.Generating, machine.state.phase)
        assertFalse(machine.compose("unwanted concurrent edit"))
        assertTrue(machine.onProgress(token, .5f))
        assertEquals(.5f, machine.state.progress!!, 0f)
        assertTrue(machine.onResult(token, listOf(candidate())))
        assertEquals(AiEditPhase.Result, machine.state.phase)
        assertTrue(machine.state.hasUnsavedCandidates)
        assertNotNull(machine.beginAccept())
        assertEquals(AiEditPhase.Accepting, machine.state.phase)
        assertNull(machine.generate())
        assertTrue(machine.saved())
        assertEquals(AiEditPhase.Saved, machine.state.phase)
        assertFalse(machine.state.hasUnsavedCandidates)
        assertFalse(machine.compose("no mutation after save"))
    }

    @Test fun cancelFencesLateResultsAndAllowsManualRetry() {
        val (machine, token) = generating()
        assertTrue(machine.cancel())
        assertEquals(AiEditPhase.Cancelled, machine.state.phase)
        assertFalse(machine.cancel())
        assertFalse(machine.onResult(token, listOf(candidate())))
        assertFalse(machine.onRefused(token, "policy"))
        assertFalse(machine.onFailed(token, "network"))
        assertTrue(machine.compose())
        val next = requireNotNull(machine.generate())
        assertTrue(next > token)
        assertFalse(machine.onProgress(token, 1f))
        assertTrue(machine.onResult(next, listOf(candidate())))
    }

    @Test fun refusalAndFailureAreDistinctAndNeverSwitchProviderAutomatically() {
        val (refused, first) = generating()
        assertTrue(refused.onRefused(first, "POLICY CODE!"))
        assertEquals(AiEditPhase.Refused, refused.state.phase)
        assertEquals("POLICYCODE", refused.state.refusalCode)
        assertNull(refused.state.error)
        assertEquals("atlas-synthetic-mock", refused.state.providerId)
        assertFalse(refused.chooseProvider("remote-unconfigured"))
        val retry = requireNotNull(refused.generate())
        assertTrue(refused.onFailed(retry, "Service unavailable"))
        assertEquals(AiEditPhase.Failed, refused.state.phase)
        assertEquals("Service unavailable", refused.state.error)
        assertNull(refused.state.refusalCode)
        assertTrue(refused.compose("New wording"))
        assertEquals(AiEditPhase.Composing, refused.state.phase)
    }

    @Test fun branchFromOriginalOrAnyCandidateWhileRetainingHistory() {
        val (machine, token) = generating()
        assertTrue(machine.onResult(token, listOf(candidate("c1"), candidate("c2"))))
        assertEquals(2, machine.state.candidates.size)
        assertTrue(machine.selectCandidate("c2"))
        assertTrue(machine.selectBranch("c1"))
        assertEquals("c1", machine.state.branchParentId)
        assertTrue(machine.selectBranch(null))
        assertNull(machine.state.branchParentId)
        assertFalse(machine.selectBranch("missing"))
        val next = requireNotNull(machine.generate())
        assertTrue(machine.onResult(next, listOf(candidate("c3"))))
        assertEquals(3, machine.state.candidates.size)
        assertEquals("c3", machine.state.selectedId)
        assertTrue(machine.selectCandidate("c1"))
        assertEquals("c1", machine.state.selectedId)
    }

    @Test fun invalidAndStaleTransitionsCannotInventResultsOrSave() {
        val (machine, token) = generating()
        assertFalse(machine.onResult(token + 1, listOf(candidate())))
        assertFalse(machine.onResult(token, emptyList()))
        assertFalse(machine.onResult(token, listOf(candidate(), candidate())))
        assertNull(machine.beginAccept())
        assertTrue(machine.onFailed(token, "not available"))
        assertFalse(machine.saved())
        assertFalse(machine.saveFailed())
        assertNull(machine.beginAccept())
        assertTrue(machine.compose())
        assertNull(machine.generate().takeIf { machine.state.prompt.isBlank() })
    }

    @Test fun restoreRecoversCacheButNeverResubmitsInterruptedGeneration() {
        val (machine, token) = generating()
        val interrupted = AiEditStateMachine.restore(machine.state, emptySet())
        assertEquals(AiEditPhase.Failed, interrupted.state.phase)
        assertTrue(interrupted.state.error!!.contains("interrupted"))
        assertEquals(token, interrupted.state.generation)
        assertTrue(interrupted.compose())
        val second = requireNotNull(interrupted.generate())
        assertTrue(interrupted.onResult(second, listOf(candidate())))
        assertNotNull(interrupted.beginAccept())
        val restored = AiEditStateMachine.restore(interrupted.state, setOf("c1.png"))
        assertEquals(AiEditPhase.Result, restored.state.phase)
        assertEquals("c1", restored.state.selectedId)
        assertNotNull(restored.beginAccept())
        val missing = AiEditStateMachine.restore(interrupted.state, emptySet())
        assertEquals(AiEditPhase.Failed, missing.state.phase)
        assertTrue(missing.state.candidates.isEmpty())
    }

    @Test fun discardMissingResultReturnsToComposingInsteadOfBrokenPreview() {
        val (machine, token) = generating()
        assertTrue(machine.onResult(token, listOf(candidate())))
        val restored = AiEditStateMachine.restore(machine.state, emptySet())
        assertEquals(AiEditPhase.Composing, restored.state.phase)
        assertTrue(restored.state.candidates.isEmpty())
        assertNull(restored.state.selectedCandidate)
    }
}
