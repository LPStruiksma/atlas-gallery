package nl.atlas.gallery.featurepersistence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** COVER-2: synthetic observations only; the selector has no Android dependencies. */
class BestShotSelectorTest {
    private fun shot(id: String, score: Float? = null, videoAt: Long? = null,
                     small: Boolean? = false, version: Int? = 2) =
        BestShotCandidate(id, score, version, small, videoAt)

    private fun chosen(vararg faces: BestShotCandidate, incumbent: String? = null): String? =
        BestShotSelector.choose(faces.toList(), incumbent)?.faceUid

    @Test fun bestEligiblePhotoWinsEvenOverHigherVideoScore() {
        assertEquals("photo", chosen(shot("video", .98f, 450), shot("photo", .68f)))
    }

    @Test fun onlyVideoUsesHighestQualityFrame() {
        val winner = BestShotSelector.choose(listOf(
            shot("v1", .65f, 1200), shot("v2", .83f, 2800)), null)
        assertEquals("v2", winner?.faceUid)
        assertEquals(2800L, winner?.frameMs)
    }

    @Test fun qualityFloorAndUnknownAreNotTreatedAsZero() {
        assertEquals("video", chosen(shot("tiny", .99f, small = true),
            shot("video", .51f, videoAt = 240)))
        assertEquals("known", chosen(shot("unknown"), shot("known", .20f, small = true),
            incumbent = "unknown"))
        assertEquals("a", chosen(shot("z"), shot("a")))
        assertEquals("legacy", chosen(shot("legacy", .91f, version = 1),
            shot("unknown")))
    }

    @Test fun betterPhotoMovesButMarginalImprovementDoesNot() {
        val initial = listOf(shot("a", .65f), shot("b", .69f))
        assertEquals("a", BestShotSelector.choose(initial, "a")?.faceUid)
        assertEquals("better", chosen(*initial.toTypedArray(), shot("better", .78f),
            incumbent = "a"))
        assertEquals("better", chosen(*initial.toTypedArray(), shot("better", .78f),
            incumbent = "better"))
    }

    @Test fun newEligiblePhotoReplacesVideoWithoutHysteresisAndRemovalFallsBack() {
        val old = shot("video", .91f, 700)
        val photo = shot("new", .42f)
        assertEquals("new", chosen(old, photo, incumbent = "video"))
        assertEquals("video", chosen(old, incumbent = "new"))
        assertNull(BestShotSelector.choose(emptyList(), "new"))
    }

    @Test fun tiesAndRestartIncumbentAreDeterministic() {
        val shots = listOf(shot("z", .72f), shot("a", .72f))
        assertEquals("a", BestShotSelector.choose(shots, null)?.faceUid)
        assertEquals("z", BestShotSelector.choose(shots.reversed(), "z")?.faceUid)
        assertEquals("a", BestShotSelector.choose(shots, "removed")?.faceUid)
    }

    @Test fun versionChangeMakesOldQualityIneligible() {
        assertEquals("current", chosen(shot("old", .99f, version = 1),
            shot("current", .40f, videoAt = 123), incumbent = "old"))
    }
}
