package nl.atlas.gallery

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import nl.atlas.gallery.featureaiedit.settings.KeyCheck
import nl.atlas.gallery.featureaiedit.settings.KeyCheckOutcome
import nl.atlas.gallery.featureaiedit.settings.KeyCheckRule
import nl.atlas.gallery.featureaiedit.settings.LocalKeyCheck
import nl.atlas.gallery.featureaiedit.settings.LocalProviderRegistry
import nl.atlas.gallery.featureaiedit.settings.ProviderAuthType
import nl.atlas.gallery.featureaiedit.settings.ProviderCredentialVault
import nl.atlas.gallery.featureaiedit.settings.ProviderKind
import nl.atlas.gallery.featureaiedit.settings.ProviderProfile
import nl.atlas.gallery.featureaiedit.settings.ProviderRegistry
import nl.atlas.gallery.featureaiedit.settings.ProviderRuntime
import nl.atlas.gallery.featureaiedit.settings.ProviderSettingsStore
import nl.atlas.gallery.featuredesign.AtlasGalleryTheme
import nl.atlas.gallery.featuredesign.AtlasSystemBarIcons
import nl.atlas.gallery.testkit.KeyboardRule
import nl.atlas.gallery.testkit.TestKeyboard
import nl.atlas.gallery.testkit.awaitThat
import nl.atlas.gallery.testkit.exists
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * AIE-19 (#430) brief rev 2 states, light and dark, at 1080x2340: the real Settings row and
 * AI Edit sub-screens, on isolated synthetic preferences and an isolated vault directory.
 * The key check is a fake: no test reaches a network service, and every key is synthetic.
 * "add-main" shows what this build offers (Custom only until #192/#429 merge); the other
 * shots use a registry with every preset so the hosted flow is visible.
 */
@RunWith(AndroidJUnit4::class)
class AiEditSettingsEvidenceTest {
    private val compose = createComposeRule()
    // A deterministic IME is installed before Compose starts: cold LatinIME startup must not
    // race with the fake key check or the screenshots of the Save button above the keyboard.
    @get:Rule val chain: RuleChain = RuleChain.outerRule(KeyboardRule(TestKeyboard.Fixed))
        .around(S24DisplayRule()).around(compose)

    private class FakeKeyCheck : KeyCheck {
        val queued = ArrayDeque<KeyCheckOutcome>()
        @Volatile var gate: CountDownLatch? = null
        val calls = AtomicInteger()
        override fun check(profile: ProviderProfile, secret: String?, rule: KeyCheckRule?): KeyCheckOutcome {
            if (rule == null) return KeyCheckOutcome.NO_CHECK
            calls.incrementAndGet()
            gate?.await(30, TimeUnit.SECONDS)
            return synchronized(queued) { queued.removeFirstOrNull() } ?: KeyCheckOutcome.WORKS
        }
    }

    private val everyKind = ProviderRegistry(ProviderKind.entries.associateWith {
        ProviderRuntime.AtlasJobRunner
    })

    @Test fun lightTheme() = journey(dark = false)
    @Test fun darkTheme() = journey(dark = true)

    private fun journey(dark: Boolean) {
        val t = if (dark) "dark" else "light"
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val prefsName = "aie19-preview-" + UUID.randomUUID()
        val vaultDir = File(app.cacheDir, "aie19-vault-" + UUID.randomUUID())
        val synthetic: Context = object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                app.getSharedPreferences(prefsName, mode)
            override fun getNoBackupFilesDir(): File = vaultDir.also { it.mkdirs() }
        }
        val store = ProviderSettingsStore(synthetic)
        val vault = ProviderCredentialVault(synthetic)
        val fake = FakeKeyCheck()
        var registry by mutableStateOf(ProviderRegistry.DEFAULT)
        try {
            compose.setContent {
                CompositionLocalProvider(LocalContext provides synthetic,
                    LocalProviderRegistry provides registry, LocalKeyCheck provides fake) {
                    AtlasSystemBarIcons(dark)
                    AtlasGalleryTheme(dark = dark) {
                        Surface(Modifier.fillMaxSize(), color = DeepBg,
                            contentColor = MaterialTheme.colorScheme.onBackground) {
                            AtlasSettingsScreen(
                                scanPhase = null, onPauseScan = null, onResumeScan = null,
                                visualIndexOn = null, showPhotoDetails = false, versionName = "synthetic",
                                onVisualIndex = {}, onPeopleNumbers = {}, onPhotoDetails = {},
                                onImportV4 = {}, onLinkV4 = {}, onDiagnostics = {}
                            )
                        }
                    }
                }
            }
            val row = compose.onNodeWithTag("atlas-settings-ai-edit-open").performScrollTo()
            row.assertTextContains("Not set up")
            shot("aie-19-settings-row-$t")

            // 1a: empty, first use. No consent dialog pops up; the mock row is gone.
            row.performClick()
            waitFor("aie-services-empty")
            compose.onNodeWithText("Synthetic mock").assertDoesNotExist()
            compose.onNodeWithTag("aie-keep-location").assertIsOff()
            shot("aie-19-empty-$t")

            // 2: what this build offers, then every preset in fixed order.
            compose.onNodeWithTag("aie-add-service").performClick()
            waitFor("aie-preset-ATLAS_PROTOCOL")
            compose.onNodeWithTag("aie-preset-GEMINI").assertDoesNotExist()
            shot("aie-19-add-main-$t")
            registry = everyKind
            waitFor("aie-preset-GEMINI")
            shot("aie-19-add-$t")

            // 3: key screen with the keyboard open; Save must sit above the keyboard.
            compose.onNodeWithTag("aie-preset-GEMINI").performClick()
            waitFor("aie-key-field")
            compose.onNode(hasContentDescription("Key for Google Gemini"), useUnmergedTree = true)
                .assertExists()
            compose.onNodeWithTag("aie-privacy-line")
                .assertTextContains("your instruction", substring = true)
            compose.onNodeWithTag("aie-key-field").performClick()
            compose.onNodeWithTag("aie-key-field").performTextInput("synthetic-wrong-key")
            assertSaveAboveKeyboard(t)
            shot("aie-19-key-ime-$t")

            // Rejected: message under the field, field keeps its content, nothing saved.
            synchronized(fake.queued) { fake.queued.addLast(KeyCheckOutcome.REJECTED) }
            compose.onNodeWithTag("aie-save").performClick()
            compose.awaitThat("rejected key message", 10_000) {
                compose.exists(hasText("This key was not accepted"), useUnmergedTree = true)
            }
            assertTrue(store.profiles().isEmpty())
            shot("aie-19-key-rejected-$t")

            // Checking: spinner on Save while the (fake) check runs; then "Key works".
            val gate = CountDownLatch(1)
            fake.gate = gate
            compose.onNodeWithTag("aie-key-field").performTextClearance()
            compose.onNodeWithTag("aie-key-field").performTextInput("synthetic-gemini-key")
            compose.onNodeWithTag("aie-save").performClick()
            waitFor("aie-checking")
            shot("aie-19-checking-$t")
            gate.countDown()
            fake.gate = null
            waitFor("aie-services")
            val gemini = store.profiles().single()
            assertEquals(ProviderKind.GEMINI, gemini.kind)
            assertEquals("synthetic-gemini-key", vault.get(gemini.id))
            assertEquals(gemini.id, store.defaultProviderId())

            // fal has no documented check: saved as "Checked on your first edit".
            // A service stored by a later build shows disabled, with Remove only.
            synthetic.getSharedPreferences("x", Context.MODE_PRIVATE).edit().putString("profiles",
                org.json.JSONArray(synthetic.getSharedPreferences("x", Context.MODE_PRIVATE)
                    .getString("profiles", "[]")).put(org.json.JSONObject()
                    .put("id", "later-1").put("name", "Later service")
                    .put("baseUrl", "https://later.example/").put("authType", "BEARER")
                    .put("kind", "SOME_LATER_KIND")).toString()).commit()
            compose.onNodeWithTag("aie-add-service").performScrollTo().performClick()
            compose.onNodeWithTag("aie-preset-FAL").performClick()
            compose.onNodeWithTag("aie-key-field").performTextInput("synthetic-fal-key")
            compose.onNodeWithTag("aie-save").performClick()
            waitFor("aie-services")
            waitFor("aie-unavailable-later-1")
            compose.onNodeWithText("Saved. Checked on your first edit.").assertExists()
            compose.onNodeWithText("Key works").assertExists()
            compose.onNodeWithTag("aie-default-badge", useUnmergedTree = true).assertExists()
            shot("aie-19-services-$t")

            // Saved but unverified: no connection at Save keeps the key, "Not checked yet".
            synchronized(fake.queued) { fake.queued.addLast(KeyCheckOutcome.NO_CONNECTION) }
            compose.onNodeWithTag("aie-add-service").performScrollTo().performClick()
            compose.onNodeWithTag("aie-preset-ATLAS_PROTOCOL").performClick()
            compose.onNodeWithTag("aie-custom-name").performTextInput("Home server")
            compose.onNodeWithTag("aie-custom-url").performTextInput("https://editor.example/v1/")
            compose.onNodeWithTag("aie-key-field").performTextInput("synthetic-home-key")
            compose.onNodeWithTag("aie-save").performClick() // pinned under the form, not in the scroll
            waitFor("aie-key-saved")
            compose.onNodeWithText("Couldn't check. No connection.").assertExists()
            compose.onNodeWithText("Key saved ••••").assertExists()
            compose.onNodeWithText("synthetic-home-key").assertDoesNotExist()
            shot("aie-19-unverified-$t")
            compose.onNodeWithTag("aie-back").performClick()
            waitFor("aie-services")
            compose.onNodeWithText("Not checked yet").assertExists()

            // Remove the default: confirm, key deleted, the next service becomes default.
            compose.onNodeWithTag("aie-service-row-" + gemini.id).performClick()
            compose.onNodeWithTag("aie-remove").performScrollTo().performClick()
            waitFor("aie-remove-confirm")
            shot("aie-19-remove-confirm-$t")
            compose.onNodeWithTag("aie-remove-confirm").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasText("Google Gemini removed. fal.ai is now the default."))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            assertNull(vault.get(gemini.id))
            assertEquals("fal.ai", store.defaultProvider()?.name)
            shot("aie-19-remove-default-$t")

            // Many services: the list scrolls, default first, Add service last.
            for (n in 1..7) store.save(ProviderProfile("many-$n", "Server $n",
                "https://server$n.example/v1/", ProviderAuthType.NONE))
            compose.onNodeWithTag("aie-back").performClick()
            row.performScrollTo().assertTextContains("fal.ai").performClick()
            waitFor("aie-service-row-many-7")
            compose.onNodeWithTag("aie-add-service").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("aie-title").assertIsDisplayed()
            shot("aie-19-many-$t")
        } finally {
            app.deleteSharedPreferences(prefsName)
            vaultDir.deleteRecursively()
        }
    }

    private fun waitFor(tag: String): SemanticsNodeInteraction {
        compose.awaitThat("UI node $tag", 10_000) {
            compose.exists(hasTestTag(tag), useUnmergedTree = true)
        }
        return compose.onNodeWithTag(tag)
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        saveUiEvidence("$name.png")
    }

    /**
     * Report, don't fail, when the CI image has no soft keyboard. When one is shown, Save
     * must be fully above it (wireframe 3: Save stays visible and tappable while typing).
     */
    private fun assertSaveAboveKeyboard(theme: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        var ime: Rect? = null
        val shown = runCatching {
            compose.waitUntil(5_000) {
                ime = automation.windows.firstOrNull {
                    it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
                }?.let { window -> Rect().also { window.getBoundsInScreen(it) } }
                ime?.let { it.height() > 0 } == true
            }
        }.isSuccess
        compose.waitForIdle()
        val save = compose.onNodeWithTag("aie-save").assertIsDisplayed()
            .fetchSemanticsNode().boundsInWindow
        Log.i("ATLAS_EVIDENCE", "aie-19 key screen ime visible=$shown ime=$ime " +
            "saveBottomPx=${save.bottom} theme=$theme")
        if (shown) assertTrue("Save (bottom ${save.bottom}) is under the keyboard (top ${ime!!.top})",
            save.bottom <= ime!!.top)
    }
}
