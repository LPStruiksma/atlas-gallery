package nl.atlas.gallery.featureaiedit.engine

import androidx.annotation.VisibleForTesting
import nl.atlas.gallery.featureaiedit.provider.ImageEditProvider
import nl.atlas.gallery.featureaiedit.provider.hosted.fal.FalModel
import nl.atlas.gallery.featureaiedit.provider.hosted.gemini.GeminiModel
import nl.atlas.gallery.featureaiedit.settings.HostedInputs
import nl.atlas.gallery.featureaiedit.settings.ProviderKind
import nl.atlas.gallery.featureaiedit.settings.ProviderProfile
import nl.atlas.gallery.featureaiedit.settings.ProviderRegistry
import nl.atlas.gallery.featureaiedit.settings.ProviderRuntime

/**
 * Which configured services the editor can use, and how it builds one (#431). Only profiles whose
 * kind has a hosted adapter in the registry appear; the custom ATLAS server keeps its own
 * EditJobRunner path and is not offered in the editor.
 */
object AiEditServices {
    /**
     * Instrumented tests only: build the provider with a loopback transport so the real adapter
     * code runs without the network. Never set in production code.
     */
    @VisibleForTesting
    @Volatile
    var providerOverride: ((ProviderProfile, HostedInputs) -> ImageEditProvider?)? = null

    /** Instrumented tests only: answers the pre-flight connectivity check. Never set in production code. */
    @VisibleForTesting
    @Volatile
    var onlineOverride: (() -> Boolean)? = null

    fun usable(profiles: List<ProviderProfile>, registry: ProviderRegistry): List<ProviderProfile> =
        profiles.filter { registry.runtimeFor(it) is ProviderRuntime.Adapter }

    fun create(profile: ProviderProfile, registry: ProviderRegistry, inputs: HostedInputs): ImageEditProvider? {
        providerOverride?.let { return it(profile, inputs) }
        val runtime = registry.runtimeFor(profile) as? ProviderRuntime.Adapter ?: return null
        return runtime.create(profile, inputs)
    }

    fun serviceName(profile: ProviderProfile): String = when (profile.kind) {
        ProviderKind.GEMINI -> "Google Gemini"
        ProviderKind.FAL -> "fal.ai"
        ProviderKind.ATLAS_PROTOCOL -> profile.name
    }

    fun modelName(profile: ProviderProfile): String? = when (profile.kind) {
        ProviderKind.GEMINI -> (GeminiModel.CATALOG.firstOrNull { it.id == profile.modelId } ?: GeminiModel.DEFAULT).displayName
        ProviderKind.FAL -> (FalModel.CATALOG.firstOrNull { it.endpoint == profile.modelId } ?: FalModel.DEFAULT).displayName
        ProviderKind.ATLAS_PROTOCOL -> null
    }

    /** The service's public pricing page. Opening it sends nothing to the service from ATLAS. */
    fun pricingUrl(kind: ProviderKind): String? = when (kind) {
        ProviderKind.GEMINI -> "https://ai.google.dev/gemini-api/docs/pricing"
        ProviderKind.FAL -> "https://fal.ai/pricing"
        ProviderKind.ATLAS_PROTOCOL -> null
    }
}
