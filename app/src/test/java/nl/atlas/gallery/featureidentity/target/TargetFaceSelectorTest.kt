package nl.atlas.gallery.featureidentity.target

import nl.atlas.gallery.featureidentity.engine.FaceBox
import nl.atlas.gallery.featureidentity.engine.FacePoint
import nl.atlas.gallery.featureidentity.engine.TargetFace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetFaceSelectorTest {
    @Test fun noFace() {
        assertSame(TargetFaceSelection.NoFace, TargetFaceSelector.select(emptyList(), 1000, 800, 64))
    }

    @Test fun oneUsableFaceIsAutomatic() {
        val face = candidate(350, 250, 650, 600)
        assertEquals(TargetFaceSelection.Auto(face.face),
            TargetFaceSelector.select(listOf(face), 1000, 800, 64))
    }

    @Test fun twoSimilarlyStrongFacesAskInRankOrder() {
        val left = candidate(170, 250, 370, 500)
        val right = candidate(630, 250, 830, 500)
        val result = TargetFaceSelector.select(listOf(right, left), 1000, 800, 64)
        assertTrue(result is TargetFaceSelection.Ask)
        assertEquals(setOf(left.face, right.face), (result as TargetFaceSelection.Ask).faces.toSet())
    }

    @Test fun dominantSubjectInCrowdIsAutomatic() {
        val dominant = candidate(250, 120, 750, 720, sharpness = 1.0, confidence = .99)
        val crowd = listOf(
            candidate(10, 20, 90, 110, .6, .7), candidate(850, 30, 930, 120, .6, .7),
            candidate(30, 650, 110, 740, .6, .7), candidate(880, 650, 960, 740, .6, .7)
        )
        assertEquals(TargetFaceSelection.Auto(dominant.face),
            TargetFaceSelector.select(crowd + dominant, 1000, 800, 64))
    }

    @Test fun reportsBestFaceWhenAllAreTooSmall() {
        val tiny = candidate(480, 380, 520, 420)
        assertEquals(TargetFaceSelection.TooSmall(tiny.face),
            TargetFaceSelector.select(listOf(tiny), 1000, 800, 64))
    }

    @Test fun rotatedAspectFitTapMapsToRawFaceAndHonoursTolerance() {
        // Raw 400x200 rotated clockwise is upright 200x400, fitted into 400x400 with x=100 letterbox.
        val face = candidate(40, 120, 100, 180).face
        assertSame(face, TargetFaceSelector.faceAtTap(listOf(face), 400, 200, 90,
            400f, 400f, FacePoint(150f, 70f), 48f))
        assertNull(TargetFaceSelector.faceAtTap(listOf(face), 400, 200, 90,
            400f, 400f, FacePoint(20f, 70f), 48f))
    }

    @Test fun sidewaysPhotoDetectionAndTapShareRawSpace() {
        // Raw 400x200 shown rotated 90° clockwise: upright 200x400. A face detected upright at
        // x 20..80, y 140..200 is raw x 140..200, y 120..180 (raw = (y, rawHeight - x)).
        val upright = candidate(20, 140, 80, 200).face
        val raw = TargetFaceSelector.uprightToRaw(upright, 400, 200, 90)
        assertEquals(FaceBox(140, 120, 200, 180), raw.box)
        assertEquals(FacePoint(140f, 180f), raw.landmarks[0])
        // The same upright point the user taps (centre 50,170; view 400x400, letterbox x=100)
        // hits the raw-space face through faceAtTap with the same rotation.
        assertSame(raw, TargetFaceSelector.faceAtTap(listOf(raw), 400, 200, 90,
            400f, 400f, FacePoint(150f, 170f), 0f))
        // The unmapped upright box would be missed: the coordinate spaces really differ.
        assertNull(TargetFaceSelector.faceAtTap(listOf(upright), 400, 200, 90,
            400f, 400f, FacePoint(150f, 170f), 0f))
    }

    @Test fun uprightToRawRoundTripsEveryRotation() {
        val p = FacePoint(30f, 50f)
        assertEquals(p, TargetFaceSelector.uprightToRaw(p, 400, 200, 0))
        assertEquals(FacePoint(50f, 170f), TargetFaceSelector.uprightToRaw(p, 400, 200, 90))
        assertEquals(FacePoint(370f, 150f), TargetFaceSelector.uprightToRaw(p, 400, 200, 180))
        assertEquals(FacePoint(350f, 30f), TargetFaceSelector.uprightToRaw(p, 400, 200, 270))
    }

    @Test fun poorQualityFaceRemainsTappableButIsNotAutoSelected() {
        val blurry = candidate(300, 200, 700, 650, sharpness = .01, confidence = .9)
        assertEquals(TargetFaceSelection.Ask(listOf(blurry.face)),
            TargetFaceSelector.select(listOf(blurry), 1000, 800, 64))
    }

    private fun candidate(
        left: Int, top: Int, right: Int, bottom: Int,
        sharpness: Double = .8, confidence: Double = .9
    ): TargetFaceCandidate {
        val box = FaceBox(left, top, right, bottom)
        val points = listOf(
            FacePoint(left.toFloat(), top.toFloat()), FacePoint(right.toFloat(), top.toFloat()),
            FacePoint((left + right) / 2f, (top + bottom) / 2f),
            FacePoint(left.toFloat(), bottom.toFloat()), FacePoint(right.toFloat(), bottom.toFloat())
        )
        return TargetFaceCandidate(TargetFace(box, points), confidence, sharpness)
    }
}
