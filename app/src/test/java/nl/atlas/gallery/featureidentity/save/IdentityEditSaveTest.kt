package nl.atlas.gallery.featureidentity.save

import nl.atlas.gallery.featureaiedit.provider.EditImage
import nl.atlas.gallery.featureaiedit.provider.ImageFormat
import nl.atlas.gallery.featureidentity.engine.EngineRoute
import nl.atlas.gallery.featureidentity.engine.IdentityCandidate
import nl.atlas.gallery.featureidentity.engine.IdentityEditMode
import nl.atlas.gallery.featureidentity.engine.IdentityVariation
import nl.atlas.gallery.featureidentity.engine.PerceptualFingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IdentityEditSaveTest {
    private fun candidate() = IdentityCandidate(
        id = "candidate-3", number = 3,
        image = EditImage("synthetic", 8, 8, ImageFormat.PNG), file = null,
        variation = IdentityVariation(8675309L, listOf(0), 0.85),
        fingerprint = PerceptualFingerprint(List(64) { it }),
        engineId = "atlas-fake-identity", engineVersion = "1",
        route = EngineRoute.SELF_HOSTED, mode = IdentityEditMode.HEAD
    )

    @Test fun mapsOnlyDurablePrivateIdentityProvenance() {
        // Request holds android.net.Uri, which is a null-returning stub on the JVM; the
        // Request path is covered by IdentityEditSaveAndroidTest.
        val lineage = IdentityEditSave.provenance(
            candidate = candidate(),
            profileId = "0123456789abcdef0123456789abcdef", variantId = null,
            refinementParent = "candidate-2"
        )
        assertEquals(1, lineage.schemaVersion)
        assertEquals("head", lineage.kind)
        assertEquals("atlas-fake-identity", lineage.engineId)
        assertEquals("self_hosted", lineage.route)
        assertEquals(3, lineage.candidateIndex)
        assertEquals(8675309L, lineage.seed)
        assertNull(lineage.variantId)
        assertEquals("candidate-2", lineage.refinementParent)
    }
}
