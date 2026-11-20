package nl.atlas.gallery.featureaiedit.settings

import nl.atlas.gallery.featureaiedit.network.EditHttpAuth
import nl.atlas.gallery.featureaiedit.provider.hosted.fal.FalImageEditProvider
import nl.atlas.gallery.featureaiedit.provider.hosted.fal.TransportFalExchange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRegistryTest {
    private val everything = ProviderRegistry(ProviderKind.entries.associateWith {
        ProviderRuntime.AtlasJobRunner
    })

    @Test fun mainOffersOnlyKindsWithARuntime() {
        val offered = ProviderRegistry.DEFAULT.offeredPresets()
        assertEquals(listOf(ProviderKind.ATLAS_PROTOCOL), offered.map { it.kind })
        assertTrue(offered.single().advanced)
        assertFalse(ProviderRegistry.DEFAULT.supports(ProviderKind.GEMINI))
        assertFalse(ProviderRegistry.DEFAULT.supports(ProviderKind.FAL))
        assertFalse(ProviderRegistry.DEFAULT.supports(null))
    }

    @Test fun presetOrderIsGeminiFalThenCustomUnderAdvanced() {
        assertEquals(listOf("Google Gemini", "fal.ai", "Custom server"),
            everything.offeredPresets().map { it.title })
        assertEquals(listOf(false, false, true), everything.offeredPresets().map { it.advanced })
    }

    @Test fun presetProfilesValidateExactlyAsCustomOnes() {
        for (preset in ProviderRegistry.PRESETS.filter { it.baseUrl != null }) {
            val profile = ProviderProfile("p-" + preset.kind.name.lowercase(), preset.title,
                preset.baseUrl!!, preset.authType, preset.headerName, preset.kind)
            assertTrue(preset.title, ProviderSettingsStore.valid(profile))
            assertFalse(ProviderSettingsStore.valid(profile.copy(
                baseUrl = preset.baseUrl!!.replace("https://", "http://"))))
            assertFalse(ProviderSettingsStore.valid(profile.copy(baseUrl = preset.baseUrl + "?k=1")))
            assertFalse(ProviderSettingsStore.valid(profile.copy(baseUrl = preset.baseUrl!!.trimEnd('/'))))
        }
        val custom = ProviderProfile("c", "Home", "https://editor.example/v1/", ProviderAuthType.BEARER)
        assertTrue(ProviderSettingsStore.valid(custom))
        assertFalse(ProviderSettingsStore.valid(custom.copy(baseUrl = "https://user@editor.example/v1/")))
        assertFalse(ProviderSettingsStore.valid(custom.copy(
            authType = ProviderAuthType.CUSTOM_HEADER, headerName = "Authorization")))
        assertFalse(ProviderSettingsStore.valid(custom.copy(modelId = "bad model")))
        assertTrue(ProviderSettingsStore.valid(custom.copy(modelId = "family/model-1.5")))
    }

    @Test fun oldProfilesDefaultToAtlasProtocolAndNotChecked() {
        val old = ProviderProfile("old", "Home", "https://editor.example/v1/", ProviderAuthType.BEARER)
        assertEquals(ProviderKind.ATLAS_PROTOCOL, old.kind)
        assertEquals(KeyStatus.UNCHECKED, old.keyStatus)
        assertNull(old.modelId)
    }

    @Test fun authenticationFollowsTheKind() {
        val g = ProviderRegistry.GEMINI_PRESET
        val gemini = ProviderProfile("g", g.title, g.baseUrl!!, g.authType, g.headerName, ProviderKind.GEMINI)
        assertEquals(EditHttpAuth.CustomHeader("x-goog-api-key", "k"), gemini.authentication("k"))
        val f = ProviderRegistry.FAL_PRESET
        val fal = ProviderProfile("f", f.title, f.baseUrl!!, f.authType, f.headerName, ProviderKind.FAL)
        // #462: fal's documented scheme is `Authorization: Key …`, the same word its adapter sends.
        assertEquals(EditHttpAuth.Scheme("Key", "k"), fal.authentication("k"))
        assertNull(fal.authentication(null))
        // A fal profile stored with any other scheme fails closed instead of sending Bearer.
        for (other in listOf(ProviderAuthType.BEARER, ProviderAuthType.CUSTOM_HEADER, ProviderAuthType.NONE)) {
            assertNull(fal.copy(authType = other).authentication("k"))
        }
        val none = ProviderProfile("n", "Open", "https://editor.example/v1/", ProviderAuthType.NONE)
        assertEquals(EditHttpAuth.None, none.authentication(null))
        assertNull(none.copy(authType = ProviderAuthType.BEARER).authentication(null))
        assertFalse(gemini.toString().contains("k\""))
    }

    @Test fun falPresetMatchesItsQueueAdapter() {
        val f = ProviderRegistry.FAL_PRESET
        assertEquals(ProviderAuthType.KEY_SCHEME, f.authType)
        assertEquals("Authorization", f.headerName)
        assertEquals(TransportFalExchange.AUTH_SCHEME, ProviderAuthType.FAL_KEY_SCHEME)
        assertEquals(FalImageEditProvider.QUEUE_BASE, f.baseUrl)
        // The Key scheme belongs to the fal preset only: a custom server cannot pick it,
        // and a non-fal profile carrying it is not valid.
        assertFalse(ProviderAuthType.KEY_SCHEME in ProviderAuthType.CUSTOM_SERVER)
        assertFalse(ProviderSettingsStore.valid(ProviderProfile("x", "Home",
            "https://editor.example/v1/", ProviderAuthType.KEY_SCHEME)))
        assertTrue(ProviderSettingsStore.valid(ProviderProfile("f", f.title, f.baseUrl!!,
            f.authType, f.headerName, ProviderKind.FAL)))
    }

    @Test fun defaultServiceIsPinnedFirstOthersKeepTheirOrder() {
        fun p(id: String) = ProviderProfile(id, id, "https://e.example/v1/", ProviderAuthType.BEARER)
        val list = listOf(p("a"), p("b"), p("c"))
        assertEquals(listOf("b", "a", "c"), orderedServices(list, "b").map { it.id })
        assertEquals(listOf("a", "b", "c"), orderedServices(list, null).map { it.id })
    }

    @Test fun copyNeverPromisesCreditStatusAndNamesWhatIsSent() {
        val line = AiEditCopy.privacyLine("Google Gemini")
        assertTrue(line.contains("the photo you choose to edit"))
        assertTrue(line.contains("your instruction"))
        assertTrue(line.contains("mask or reference photo"))
        val all = KeyCheckOutcome.entries.map { AiEditCopy.outcome(it, "Google Gemini") } +
            KeyStatus.entries.map { AiEditCopy.status(ProviderProfile("x", "x",
                "https://e.example/v1/", ProviderAuthType.BEARER, keyStatus = it)) }
        assertTrue(all.none { it.contains("credit", ignoreCase = true) })
        assertEquals("Couldn't check. No connection.",
            AiEditCopy.outcome(KeyCheckOutcome.NO_CONNECTION, "fal.ai"))
        assertEquals("Couldn't check. fal.ai didn't respond.",
            AiEditCopy.outcome(KeyCheckOutcome.NOT_RESPONDING, "fal.ai"))
        assertEquals("Charged by fal.ai per edit.",
            AiEditCopy.costLine(ProviderRegistry.FAL_PRESET, "fal.ai"))
        assertEquals("Google Gemini removed. fal.ai is now the default.",
            AiEditCopy.removed("Google Gemini", "fal.ai"))
        for (word in listOf("BYOK", "endpoint", "bearer", "provider", "HTTPS", "protocol")) {
            assertFalse(word, (all + line).any { it.contains(word, ignoreCase = true) })
        }
    }
}
