package nl.atlas.gallery.featureaiedit.save

import org.junit.Assert.*
import org.junit.Test

class AiEditNamingAndLineageTest {
    @Test fun namesAreUniqueInTheSourceFolderAndRespectFormat() {
        assertEquals("travel.photo_AI_1.jpg",
            AiEditNaming.next("travel.photo.jpeg", AiEditImageFormat.JPEG, emptySet()))
        assertEquals("travel.photo_AI_3.png",
            AiEditNaming.next("travel.photo.jpeg", AiEditImageFormat.PNG,
                setOf("travel.photo_AI_1.png", "travel.photo_AI_2.png",
                    "other_AI_3.png")))
        assertEquals("Photo_AI_1.jpg",
            AiEditNaming.next("../", AiEditImageFormat.JPEG, emptySet()))
        assertEquals("portrait_AI_1.webp",
            AiEditNaming.next("portrait.jpg", AiEditImageFormat.WEBP, emptySet()))
    }

    @Test fun preInsertNameIsTokenBoundAndNeverAnOrdinaryOutputName() {
        val token = "0c1268ba-25c0-46ac-a7da-8b3beec486da"
        val other = "75ef4493-50a2-4f8a-96e2-38160da6b976"
        val temporary = AiEditNaming.pendingName(token, AiEditImageFormat.JPEG)
        assertEquals("atlas-ai-pending-$token.jpg", temporary)
        assertTrue(AiEditNaming.isTokenNamedPendingRow(token, temporary))
        assertFalse(AiEditNaming.isTokenNamedPendingRow(other, temporary))
        assertFalse(AiEditNaming.isTokenNamedPendingRow(token, "source_AI_1.jpg"))
        assertNotEquals(temporary, AiEditNaming.pendingName(token, AiEditImageFormat.PNG))
    }

    @Test fun lineageKeepsEveryRevisionAndCandidateParentSeparately() {
        val record = AiEditLineage(
            editedUri = "content://media/external_primary/images/media/103",
            originalUri = "content://media/external_primary/images/media/101",
            originalRevision = "generation-7",
            provider = "test-provider", model = "synthetic-model", modelVersion = "v2",
            prompt = "Make the synthetic sky blue",
            settingsJson = "{\"strength\":0.7}", createdAtMillis = 123456L,
            candidateParentUri = "content://media/external_primary/images/media/102"
        )
        assertNotEquals(record.editedUri, record.originalUri)
        assertEquals("generation-7", record.originalRevision)
        assertEquals("content://media/external_primary/images/media/102", record.candidateParentUri)
        assertEquals("v2", record.modelVersion)
        assertEquals("{\"strength\":0.7}", record.settingsJson)
    }

    @Test(expected = IllegalArgumentException::class)
    fun namingCannotAcceptBlankSourceName() {
        AiEditNaming.next(" ", AiEditImageFormat.JPEG, emptySet())
    }
}
