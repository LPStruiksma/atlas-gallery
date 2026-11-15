package nl.atlas.gallery

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Settings composable and UI screenshots, but isolated synthetic preferences.
 * Never modifies an installed user's saved providers or opens the real credential vault.
 */
@RunWith(AndroidJUnit4::class)
class AiEditSettingsEvidenceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun providerListConsentAndByokFormRenderInSettings() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val isolatedName = "aie5-preview-" + UUID.randomUUID()
        val synthetic = object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                app.getSharedPreferences(isolatedName, mode)
        }
        try {
            compose.setContent {
                CompositionLocalProvider(LocalContext provides synthetic) {
                    MaterialTheme {
                        AtlasSettingsScreen(
                            scanPhase = null, onPauseScan = null, onResumeScan = null,
                            visualIndexOn = null, showPhotoDetails = false, versionName = "synthetic",
                            onVisualIndex = {}, onPeopleNumbers = {}, onPhotoDetails = {},
                            onImportV4 = {}, onLinkV4 = {}, onDiagnostics = {}
                        )
                    }
                }
            }
            compose.onNodeWithTag("atlas-settings-ai-edit-open").performScrollTo().performClick()
            compose.onNodeWithTag("aie-consent-acknowledge").assertExists()
            saveUiEvidence("aie-5-first-use.png")
            compose.onNodeWithTag("aie-consent-acknowledge").performClick()
            compose.onNodeWithTag("aie-provider-settings").assertExists()
            saveUiEvidence("aie-5-provider-list.png")
            compose.onNodeWithTag("aie-provider-save").performScrollTo().assertExists()
            saveUiEvidence("aie-5-byok-form.png")
            compose.onNodeWithTag("aie-keep-location").performScrollTo().assertIsOff()
        } finally {
            app.deleteSharedPreferences(isolatedName)
        }
    }
}
