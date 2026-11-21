package nl.atlas.gallery.featureaiedit.settings

import nl.atlas.gallery.featureaiedit.network.FailureCode
import nl.atlas.gallery.featureaiedit.provider.ImageEditProvider
import nl.atlas.gallery.featureaiedit.provider.hosted.SourceImageLoader
import nl.atlas.gallery.featureaiedit.provider.hosted.fal.FalImageEditProvider
import nl.atlas.gallery.featureaiedit.provider.hosted.fal.FalModel
import nl.atlas.gallery.featureaiedit.provider.hosted.gemini.GeminiImageEditProvider
import nl.atlas.gallery.featureaiedit.provider.hosted.gemini.GeminiModel
import java.io.File

/**
 * AIE-19 (#430): the kinds of service a stored profile can point at. A profile stored by a
 * build that knew a kind this build does not is kept as an [UnavailableProfile], never used.
 */
enum class ProviderKind { ATLAS_PROTOCOL, GEMINI, FAL }

data class ProviderModel(val id: String, val label: String)

/**
 * The documented, user-initiated call that proves a key without uploading anything.
 * [path] is relative to the preset's (or the custom profile's) base URL. Results in
 * [rejectedOn] mean "This key was not accepted"; every other failure keeps the key and
 * reports "Couldn't check" (see [KeyCheckOutcome]). Sources: docs/ai-edit/ARCHITECTURE.md.
 */
data class KeyCheckRule(val path: String, val rejectedOn: Set<FailureCode>)

/** A dated cost estimate; without one the UI only says "Charged by {service} per edit." */
data class DatedCost(val text: String, val checkedMonthYear: String)

enum class PresetIcon { HOSTED, CUSTOM }

/**
 * What "Add service" offers for one kind. Fixed order (brief rev 2): Google Gemini first
 * (owner's first choice, #432, needs only a key), fal.ai, then Custom server under Advanced
 * because it needs a URL. Hosted presets carry their fixed base URL, so nobody types one.
 */
data class ProviderPreset(
    val kind: ProviderKind,
    val title: String,
    val summary: String,
    val icon: PresetIcon,
    val baseUrl: String?,
    val authType: ProviderAuthType,
    val headerName: String,
    val keyCheck: KeyCheckRule?,
    val getKeyUrl: String?,
    val cost: DatedCost? = null,
    val models: List<ProviderModel> = emptyList(),
    val advanced: Boolean = false
)

/** How a kind's edits run once AI Edit is wired to real services (#431). */
sealed interface ProviderRuntime {
    /** ATLAS protocol v1 jobs run through EditJobRunner with the profile's own URL and auth. */
    data object AtlasJobRunner : ProviderRuntime

    /**
     * A hosted adapter (#192 Gemini, #429 fal) builds an [ImageEditProvider] for a profile. The key
     * is read through [HostedInputs.apiKey] on the worker, never captured in the provider's state.
     */
    class Adapter(
        val create: (profile: ProviderProfile, inputs: HostedInputs) -> ImageEditProvider
    ) : ProviderRuntime
}

/** What a hosted adapter needs from the app: the key on demand, the one selected image, a private output folder. */
class HostedInputs(
    val apiKey: () -> String?,
    val images: SourceImageLoader,
    val outputDirectory: File
) {
    override fun toString(): String = "HostedInputs(${outputDirectory.name})"
}

/**
 * Maps a profile's kind to the runtime that serves it. A preset whose adapter is not in this
 * registry is not offered in "Add service", and a stored profile of such a kind is shown
 * disabled.
 */
class ProviderRegistry(private val runtimes: Map<ProviderKind, ProviderRuntime>) {

    fun supports(kind: ProviderKind?): Boolean = kind != null && kind in runtimes

    fun runtimeFor(profile: ProviderProfile): ProviderRuntime? = runtimes[profile.kind]

    /** Hosted presets in fixed order, then Advanced. Kinds without a runtime are hidden. */
    fun offeredPresets(): List<ProviderPreset> = PRESETS.filter { supports(it.kind) }

    fun preset(kind: ProviderKind): ProviderPreset = PRESETS.first { it.kind == kind }

    companion object {
        /** No query strings: EditHttpTransport refuses them, and none is needed. */
        val GEMINI_PRESET = ProviderPreset(
            kind = ProviderKind.GEMINI,
            title = "Google Gemini",
            summary = "Best at keeping faces unchanged. Needs a key.",
            icon = PresetIcon.HOSTED,
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/",
            authType = ProviderAuthType.CUSTOM_HEADER,
            headerName = "x-goog-api-key",
            // models.list is free and uploads nothing. A wrong key answers 400
            // INVALID_ARGUMENT/API_KEY_INVALID (observed 2026-11-18); a key without access
            // to the API answers 403 PERMISSION_DENIED. Both mean "not accepted".
            keyCheck = KeyCheckRule("models",
                setOf(FailureCode.AUTH, FailureCode.INVALID_REQUEST)),
            getKeyUrl = "https://aistudio.google.com/app/apikey"
        )

        /**
         * fal's only key-proving GET (`/v1/models/pricing`) documents its header as an
         * *admin* key while its prose says any valid key; an ordinary key could be refused
         * there. `/v1/models` accepts no key at all, so it proves nothing. Until #429/#432
         * verify one with a real key, fal has no check: "Saved. Checked on your first edit."
         * Auth is fal's documented `Authorization: Key …` and the base is the queue host, the
         * same scheme and host its adapter (#429) uses (#462).
         */
        val FAL_PRESET = ProviderPreset(
            kind = ProviderKind.FAL,
            title = "fal.ai",
            summary = "Several models, including open ones. Needs a key.",
            icon = PresetIcon.HOSTED,
            baseUrl = FalImageEditProvider.QUEUE_BASE,
            authType = ProviderAuthType.KEY_SCHEME,
            headerName = "Authorization",
            keyCheck = null,
            getKeyUrl = "https://fal.ai/dashboard/keys"
        )

        /** ATLAS protocol v1: `GET capabilities` is authenticated (docs/ai-edit/PROTOCOL.md). */
        val CUSTOM_PRESET = ProviderPreset(
            kind = ProviderKind.ATLAS_PROTOCOL,
            title = "Custom server",
            summary = "Your own ATLAS-compatible server.",
            icon = PresetIcon.CUSTOM,
            baseUrl = null,
            authType = ProviderAuthType.BEARER,
            headerName = "X-Atlas-Token",
            keyCheck = KeyCheckRule("capabilities", setOf(FailureCode.AUTH)),
            getKeyUrl = null,
            advanced = true
        )

        val PRESETS: List<ProviderPreset> = listOf(GEMINI_PRESET, FAL_PRESET, CUSTOM_PRESET)

        /** Gemini with the profile's model, or Nano Banana 2 when none (or an unknown one) is stored. */
        val GEMINI_RUNTIME = ProviderRuntime.Adapter { profile, inputs ->
            GeminiImageEditProvider(
                model = GeminiModel.CATALOG.firstOrNull { it.id == profile.modelId } ?: GeminiModel.DEFAULT,
                apiKey = inputs.apiKey, images = inputs.images, outputDirectory = inputs.outputDirectory
            )
        }

        /** fal with the profile's endpoint, or Qwen Image Edit when none (or an unknown one) is stored. */
        val FAL_RUNTIME = ProviderRuntime.Adapter { profile, inputs ->
            FalImageEditProvider(
                model = FalModel.CATALOG.firstOrNull { it.endpoint == profile.modelId } ?: FalModel.DEFAULT,
                apiKey = inputs.apiKey, images = inputs.images, outputDirectory = inputs.outputDirectory
            )
        }

        /**
         * What this build ships (#431): both hosted adapters run in the AI Edit editor; the custom
         * ATLAS server keeps its EditJobRunner runtime.
         */
        val DEFAULT = ProviderRegistry(mapOf(
            ProviderKind.GEMINI to GEMINI_RUNTIME,
            ProviderKind.FAL to FAL_RUNTIME,
            ProviderKind.ATLAS_PROTOCOL to ProviderRuntime.AtlasJobRunner
        ))
    }
}
