package nl.atlas.gallery.featureaiedit.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** AIE-19 (#430): stored-profile migration and removal, on isolated preferences and vault. */
@RunWith(AndroidJUnit4::class)
class ProviderSettingsStoreAndroidTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefsName = "aie19-store-" + UUID.randomUUID()
    private val vaultDir = File(app.cacheDir, "aie19-vault-" + UUID.randomUUID())
    private val context: Context = object : ContextWrapper(app) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            app.getSharedPreferences(prefsName, mode)
        override fun getNoBackupFilesDir(): File = vaultDir.also { it.mkdirs() }
    }
    private fun raw(json: String) = context.getSharedPreferences("x", Context.MODE_PRIVATE)
        .edit().putString("profiles", json).commit()

    @After fun cleanUp() {
        app.deleteSharedPreferences(prefsName)
        vaultDir.deleteRecursively()
    }

    @Test fun profilesStoredBeforeKindsStillLoadAsAtlasProtocol() {
        raw("""[{"id":"old-1","name":"Home server","baseUrl":"https://editor.example/v1/",
            "authType":"CUSTOM_HEADER","headerName":"X-Atlas-Token"}]""")
        val profile = ProviderSettingsStore(context).profiles().single()
        assertEquals("old-1", profile.id)
        assertEquals(ProviderKind.ATLAS_PROTOCOL, profile.kind)
        assertEquals(ProviderAuthType.CUSTOM_HEADER, profile.authType)
        assertEquals(KeyStatus.UNCHECKED, profile.keyStatus)
        assertNull(profile.modelId)
    }

    @Test fun unknownKindIsKeptShownAndNeverUsed() {
        raw("""[{"id":"future-1","name":"Future service","baseUrl":"https://future.example/",
            "authType":"BEARER","kind":"SOME_LATER_KIND"}]""")
        val store = ProviderSettingsStore(context)
        assertTrue(store.profiles().isEmpty())
        assertEquals(listOf(UnavailableProfile("future-1", "Future service")), store.unavailableProfiles())
        val added = ProviderProfile("new-1", "Home", "https://editor.example/v1/", ProviderAuthType.BEARER)
        store.save(added)
        store.setDefault("new-1")
        assertEquals(listOf("future-1"), store.unavailableProfiles().map { it.id })
        assertEquals(listOf("new-1"), store.profiles().map { it.id })
        store.save(added.copy(keyStatus = KeyStatus.WORKS))
        assertEquals(listOf("future-1"), store.unavailableProfiles().map { it.id })
        assertEquals(KeyStatus.WORKS, store.profiles().single().keyStatus)
        assertNull(store.delete("future-1"))
        assertTrue(store.unavailableProfiles().isEmpty())
        assertEquals("new-1", store.defaultProviderId())
    }

    @Test fun kindModelAndStatusRoundTrip() {
        val store = ProviderSettingsStore(context)
        val g = ProviderRegistry.GEMINI_PRESET
        store.save(ProviderProfile("g-1", g.title, g.baseUrl!!, g.authType, g.headerName,
            ProviderKind.GEMINI, modelId = "model-a"))
        store.setKeyStatus("g-1", KeyStatus.NO_CHECK)
        val loaded = ProviderSettingsStore(context).profiles().single()
        assertEquals(ProviderKind.GEMINI, loaded.kind)
        assertEquals("model-a", loaded.modelId)
        assertEquals(KeyStatus.NO_CHECK, loaded.keyStatus)
        assertEquals("x-goog-api-key", loaded.headerName)
    }

    @Test fun removingTheDefaultDeletesItsKeyAndPromotesTheNextEligible() {
        val store = ProviderSettingsStore(context)
        val vault = ProviderCredentialVault(context)
        fun p(id: String, kind: ProviderKind = ProviderKind.ATLAS_PROTOCOL) =
            ProviderProfile(id, id, "https://$id.example/v1/", ProviderAuthType.BEARER, kind = kind)
        store.save(p("a")); store.save(p("b", ProviderKind.FAL)); store.save(p("c"))
        for (id in listOf("a", "b", "c")) vault.put(id, "synthetic-$id")
        store.setDefault("a")
        val next = store.delete("a") { it.kind == ProviderKind.ATLAS_PROTOCOL }
        assertEquals("c", next?.id)
        assertEquals("c", store.defaultProviderId())
        assertNull(vault.get("a"))
        assertEquals("synthetic-b", vault.get("b"))
        assertNull(store.delete("b"))
        assertEquals("c", store.defaultProviderId())
        assertNull(vault.get("b"))
        assertNull(store.delete("c"))
        assertNull(store.defaultProviderId())
        assertTrue(store.profiles().isEmpty())
    }
}
