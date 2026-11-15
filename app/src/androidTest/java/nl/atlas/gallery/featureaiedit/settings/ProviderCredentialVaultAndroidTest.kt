package nl.atlas.gallery.featureaiedit.settings

import android.content.pm.ApplicationInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProviderCredentialVaultAndroidTest {
    @Test fun keystoreCiphertextSurvivesNewInstanceAndNeverContainsPlaintext() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = "synthetic-" + UUID.randomUUID().toString()
        val secret = "synthetic-only-credential-" + UUID.randomUUID().toString()
        val vault = ProviderCredentialVault(context)
        try {
            vault.put(id, secret)
            assertEquals(secret, ProviderCredentialVault(context).get(id))
            val stored = File(context.noBackupFilesDir, "ai_edit_credentials")
                .listFiles().orEmpty().filter { it.extension == "enc" }
            assertTrue(stored.isNotEmpty())
            assertTrue(stored.all { !it.readBytes().toString(Charsets.ISO_8859_1).contains(secret) })
            assertTrue(stored.all { it.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath + File.separator) })
            assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        } finally {
            vault.delete(id)
        }
        assertNull(ProviderCredentialVault(context).get(id))
    }

    @Test fun noInvalidProviderPathCanEscapeVault() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        try {
            ProviderCredentialVault(context).put("../escape", "synthetic-secret")
            fail("Invalid provider ID must be refused")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
