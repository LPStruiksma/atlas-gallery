package nl.atlas.gallery

import android.content.Context
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Real Settings destination screenshots with synthetic UI only: no key, media or network. */
@RunWith(AndroidJUnit4::class)
class AiEditSettingsEvidenceTest {
    private val fixtures = SyntheticHeaderFixtures(sizes = listOf(120 to 90))
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(S24DisplayRule())
        .around(fixtures).around(compose)

    @Test fun providerListConsentAndByokFormRenderInSettings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Ensure deterministic first-use copy, not a carried real-device preference.
        context.getSharedPreferences("atlas_ai_edit_endpoints_v1", Context.MODE_PRIVATE)
            .edit().clear().commit()
        waitFor("atlas-header-more")
        compose.onNodeWithTag("atlas-header-more").performClick()
        waitFor("atlas-library-status")
        compose.onNodeWithTag("atlas-library-status").performClick()
        waitFor("atlas-settings-ai-edit-open")
        compose.onNodeWithTag("atlas-settings-ai-edit-open").performScrollTo().performClick()
        waitFor("aie-consent-acknowledge")
        saveUiEvidence("aie-5-first-use.png")
        compose.onNodeWithTag("aie-consent-acknowledge").performClick()
        waitFor("aie-provider-settings")
        compose.onNodeWithTag("aie-provider-settings").assertExists()
        saveUiEvidence("aie-5-provider-list.png")
        compose.onNodeWithTag("aie-provider-save").performScrollTo().assertExists()
        saveUiEvidence("aie-5-byok-form.png")
        compose.onNodeWithTag("aie-keep-location").performScrollTo().assertIsOff()
    }

    private fun waitFor(tag: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
