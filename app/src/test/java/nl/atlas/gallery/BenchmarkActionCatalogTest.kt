package nl.atlas.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BenchmarkActionCatalogTest {
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected catalog validation failure") }
        catch (_: IllegalArgumentException) { /* expected */ }
    }

    @Test fun sourceAndCatalogAreBidirectionallyComplete() {
        BenchmarkActionCatalog.validate()
        assertEquals(UsageAction.entries.toSet(), BenchmarkActionCatalog.steps.map { it.action }.toSet())
        assertEquals(22, BenchmarkActionCatalog.steps.size)
        assertEquals(1, BenchmarkActionCatalog.VERSION)
    }

    @Test fun missingAndStaleRowsFail() {
        val rows = BenchmarkActionCatalog.steps
        rejected { BenchmarkActionCatalog.validate(rows.dropLast(1)) }
        rejected { BenchmarkActionCatalog.validate(rows, UsageAction.entries.dropLast(1).toSet()) }
    }

    @Test fun duplicateIdsAndStepKeysFail() {
        val rows = BenchmarkActionCatalog.steps
        rejected { BenchmarkActionCatalog.validate(rows + rows.first()) }
        val ready = rows.first()
        val other = rows.first { it.action != ready.action && it.stepKey != null }
        rejected { BenchmarkActionCatalog.validate(rows.map { if (it.action == other.action) it.copy(stepKey = ready.stepKey) else it }) }
    }

    @Test fun unavailableActionsNeverClaimRunnableScripts() {
        val unavailable = BenchmarkActionCatalog.steps.filter { it.availability == BenchmarkAvailability.UNAVAILABLE }
        assertTrue(unavailable.isNotEmpty())
        assertTrue(unavailable.all { it.stepKey == null })
        assertTrue(BenchmarkActionCatalog.steps.filter { it.availability == BenchmarkAvailability.CONTRACT_READY }
            .all { it.stepKey != null && it.controlAssertion.isNotBlank() && it.precondition.isNotBlank() })
    }

    @Test fun changedControlAssertionStopsBeforeTiming() {
        var executed = false
        val port = object : BenchmarkStepPort {
            override fun assertPrecondition(step: BenchmarkStep) = false
            override fun executeSynthetic(step: BenchmarkStep): Long { executed = true; return 1 }
        }
        try {
            BenchmarkActionCatalog.runOne(UsageAction.A1, port)
            fail("Expected failed control assertion")
        } catch (_: IllegalStateException) { /* expected */ }
        assertFalse(executed)
    }

    @Test fun unavailableStepCannotRun() {
        val port = object : BenchmarkStepPort {
            override fun assertPrecondition(step: BenchmarkStep) = true
            override fun executeSynthetic(step: BenchmarkStep): Long = 1
        }
        rejected { BenchmarkActionCatalog.runOne(UsageAction.A5, port) }
        assertEquals(17L, BenchmarkActionCatalog.runOne(UsageAction.A1, object : BenchmarkStepPort {
            override fun assertPrecondition(step: BenchmarkStep) = true
            override fun executeSynthetic(step: BenchmarkStep) = 17L
        }))
    }
}
