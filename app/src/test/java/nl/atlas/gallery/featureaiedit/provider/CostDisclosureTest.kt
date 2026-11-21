package nl.atlas.gallery.featureaiedit.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CostDisclosureTest {
    private val day = LocalDate.of(2026, 11, 15)
    private fun est(scope: CostScope?, source: String? = "Price page", date: LocalDate? = day, code: String = "USD", minor: Long = 7) =
        CostEstimate(code, minor, "free text that is never parsed", scope, source, date)

    @Test fun branchesOnlyOnTypedScope() {
        val total = CostDisclosure.of(est(CostScope.TOTAL)) as CostDisclosure.Total
        assertEquals("US$0.07", total.amount)
        assertEquals(day, total.observedOn)
        assertTrue(CostDisclosure.of(est(CostScope.OUTPUT_ONLY)) is CostDisclosure.Partial)
        assertTrue(CostDisclosure.of(est(CostScope.PARTIAL)) is CostDisclosure.Partial)
    }

    @Test fun failsClosedOnAnythingIncomplete() {
        assertEquals(CostDisclosure.Unknown, CostDisclosure.of(null))
        assertEquals(CostDisclosure.Unknown, CostDisclosure.of(est(null)))
        assertEquals(CostDisclosure.Unknown, CostDisclosure.of(est(CostScope.TOTAL, source = null)))
        assertEquals(CostDisclosure.Unknown, CostDisclosure.of(est(CostScope.TOTAL, source = "  ")))
        assertEquals(CostDisclosure.Unknown, CostDisclosure.of(est(CostScope.TOTAL, date = null)))
        assertEquals(CostDisclosure.Unknown, CostDisclosure.of(est(CostScope.TOTAL, code = "usd")))
        assertEquals(CostDisclosure.Unknown, CostDisclosure.of(est(CostScope.TOTAL, code = "ZZZ")))
        assertEquals(CostDisclosure.Unknown, CostDisclosure.of(est(CostScope.TOTAL, minor = -1)))
        // An untyped legacy estimate (note only) is unknown, whatever the note says.
        assertEquals(CostDisclosure.Unknown, CostDisclosure.of(CostEstimate("USD", 7, "total price US$0.07")))
    }

    @Test fun amountStaysInTheProviderCurrency() {
        assertEquals("€0.07", CostDisclosure.formatAmount("EUR", 7))
        assertEquals("US$1.50", CostDisclosure.formatAmount("USD", 150))
        assertEquals("JPY 12", CostDisclosure.formatAmount("JPY", 12))
        assertEquals("£0.00", CostDisclosure.formatAmount("GBP", 0))
        assertNull(CostDisclosure.formatAmount("EURO", 7))
    }
}
