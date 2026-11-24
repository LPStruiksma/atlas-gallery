package nl.atlas.gallery.featurecuration.quality

import org.junit.Assert.*
import org.junit.Test

class ConservativeKeeperTest {
    private fun candidate(id: String, variance: Double, preferred: Boolean = false, hard: Boolean = false) =
        KeeperCandidate(id, WholePhotoQuality(BlurSignal(variance), ExposureSignal(0.0, 0.0, .1, .9), 10, 10, 100),
            isPreferredEditOrRaw = preferred, hardNegativeOrDistinct = hard)

    @Test fun preferredEditPrecedesSignals() {
        assertEquals(KeeperDecision.Suggest("edit"), ConservativeKeeper.choose(listOf(candidate("edit", 0.0, true), candidate("sharp", 1.0))))
    }

    @Test fun clearBlurDifferenceCanSuggest() {
        assertEquals(KeeperDecision.Suggest("sharp"), ConservativeKeeper.choose(listOf(candidate("soft", .001), candidate("sharp", .01))))
    }

    @Test fun tiesMissingSignalsAndHardNegativesAbstain() {
        assertTrue(ConservativeKeeper.choose(listOf(candidate("b", .01), candidate("a", .01))) is KeeperDecision.Abstain)
        val unknown = KeeperCandidate("unknown", null)
        assertTrue(ConservativeKeeper.choose(listOf(candidate("known", .1), unknown)) is KeeperDecision.Abstain)
        assertTrue(ConservativeKeeper.choose(listOf(candidate("one", .1), candidate("night", .01, hard = true))) is KeeperDecision.Abstain)
    }

    @Test fun clippingAndLowKeyAbstainRatherThanPenalise() {
        val clipped = KeeperCandidate("clipped", WholePhotoQuality(BlurSignal(.001), ExposureSignal(.5, 0.0, 0.0, .2), 10, 10, 100))
        assertTrue(ConservativeKeeper.choose(listOf(candidate("normal", .01), clipped)) is KeeperDecision.Abstain)
    }
}
