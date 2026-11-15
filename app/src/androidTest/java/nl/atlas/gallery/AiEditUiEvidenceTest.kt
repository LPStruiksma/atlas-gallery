package nl.atlas.gallery

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.atlas.gallery.featureaiedit.ui.AiEditPhase
import nl.atlas.gallery.featureaiedit.ui.AiEditScreenContent
import nl.atlas.gallery.featureaiedit.ui.AiEditUiState
import nl.atlas.gallery.featureaiedit.ui.AiEditWorkingCandidate
import nl.atlas.gallery.featuredesign.AtlasGalleryTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File

/**
 * All nine states rendered with a nonblank, fully synthetic image, in both phone themes.
 * Produces emulator-only UI evidence for AIE-3. Never reads personal photos or calls a provider.
 */
@RunWith(AndroidJUnit4::class)
class AiEditUiEvidenceTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules = RuleChain.outerRule(S24DisplayRule()).around(compose)
    private val candidate = AiEditWorkingCandidate(
        id = "synthetic", fileName = "synthetic.png", parentId = null,
        prompt = "Brighten the sky", providerId = "atlas-synthetic-mock",
        model = "atlas-synthetic-tint", modelVersion = "1"
    )

    private fun image(name: String, tint: Int): Uri {
        val file = File(compose.activity.cacheDir, name)
        val pixels = IntArray(96 * 96) { i ->
            val x = i % 96; val y = i / 96
            0xff000000.toInt() or
                (((x * 2 + tint) and 255) shl 16) or
                (((y * 2 + 36) and 255) shl 8) or 0x50
        }
        val bitmap = Bitmap.createBitmap(pixels, 96, 96, Bitmap.Config.ARGB_8888)
        try { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
        return Uri.fromFile(file)
    }

    @Test fun everyStateHasVisualEvidenceInLightAndDark() {
        val original = image("aie-evidence-before.png", 10)
        val edited = image("aie-evidence-after.png", 55)
        var state by mutableStateOf(AiEditUiState(
            phase = AiEditPhase.Composing, prompt = "Brighten the sky"))
        var dark by mutableStateOf(false)
        var generates = 0
        var accepts = 0
        var cancels = 0
        compose.setContent {
            AtlasGalleryTheme(dark = dark) {
                Surface(Modifier.fillMaxSize()) {
                    AiEditScreenContent(
                        state = state,
                        originalUri = original,
                        selectedCandidateUri = if (state.candidates.isEmpty()) null else edited,
                        candidateUri = { edited },
                        onBack = {},
                        onPromptChanged = { state = state.copy(prompt = it) },
                        onGenerate = { generates++ },
                        onCancel = { cancels++ },
                        onAccept = { accepts++ },
                        onChangePrompt = { state = state.copy(phase = AiEditPhase.Composing) },
                        onSelectCandidate = { state = state.copy(selectedId = it) },
                        onBranchOriginal = { state = state.copy(branchParentId = null) },
                        onChooseProvider = {}
                    )
                }
            }
        }
        val cases = listOf(
            AiEditPhase.Idle, AiEditPhase.Composing, AiEditPhase.Generating,
            AiEditPhase.Result, AiEditPhase.Refused, AiEditPhase.Failed,
            AiEditPhase.Accepting, AiEditPhase.Saved, AiEditPhase.Cancelled
        )
        for (theme in listOf(false, true)) {
            for (phase in cases) {
                compose.runOnIdle {
                    dark = theme
                    state = AiEditUiState(
                        phase = phase, prompt = "Brighten the sky",
                        progress = .45f,
                        candidates = if (phase in listOf(AiEditPhase.Result,
                                AiEditPhase.Accepting, AiEditPhase.Saved)) listOf(candidate)
                            else emptyList(),
                        selectedId = if (phase in listOf(AiEditPhase.Result,
                                AiEditPhase.Accepting, AiEditPhase.Saved)) candidate.id else null,
                        refusalCode = "policy-declined",
                        error = "Could not reach the provider. Try again."
                    )
                }
                compose.waitForIdle()
                compose.onNodeWithTag("atlas-ai-edit-screen").assertExists()
                saveUiEvidence("aie-3-${if (theme) "dark" else "light"}-${phase.name.lowercase()}.png")
            }
        }
        compose.runOnIdle { dark = false; state = AiEditUiState(
            phase = AiEditPhase.Result, prompt = "Brighten the sky",
            candidates = listOf(candidate), selectedId = candidate.id) }
        compose.onNodeWithTag("atlas-ai-edit-accept").performClick()
        assertEquals(1, accepts)
        compose.runOnIdle { state = AiEditUiState(
            phase = AiEditPhase.Composing, prompt = "Brighten the sky") }
        compose.onNodeWithTag("atlas-ai-edit-generate").performClick()
        assertEquals(1, generates)
        compose.runOnIdle { state = AiEditUiState(phase = AiEditPhase.Generating) }
        compose.onNodeWithTag("atlas-ai-edit-cancel").performClick()
        assertEquals(1, cancels)
        File(compose.activity.cacheDir, "aie-evidence-before.png").delete()
        File(compose.activity.cacheDir, "aie-evidence-after.png").delete()
    }
}
