package nl.atlas.gallery.featureidentity.save

import android.content.Context
import android.net.Uri
import nl.atlas.gallery.featureaiedit.save.AiEditCandidate
import nl.atlas.gallery.featureaiedit.save.AiEditImageFormat
import nl.atlas.gallery.featureaiedit.save.AiEditSaveResult
import nl.atlas.gallery.featureaiedit.save.AiEditSaver
import nl.atlas.gallery.featureaiedit.save.IdentityEditLineage
import nl.atlas.gallery.featureidentity.engine.EngineRoute
import nl.atlas.gallery.featureidentity.engine.IdentityCandidate
import nl.atlas.gallery.featureidentity.engine.IdentityEditMode

/** Identity Studio adapter for the single journaled [AiEditSaver] transaction. */
internal class IdentityEditSave(
    context: Context,
    private val saver: AiEditSaver = AiEditSaver(context)
) {
    data class Request(
        val originalUri: Uri,
        val candidateUri: Uri,
        val originalRevision: String,
        val candidate: IdentityCandidate,
        val profileId: String,
        val variantId: String?,
        val refinementParent: String? = null,
        val format: AiEditImageFormat = AiEditImageFormat.PNG,
        val createdAtMillis: Long = System.currentTimeMillis()
    )

    suspend fun save(
        request: Request,
        onRefresh: () -> Unit = {},
        onFocus: (Uri) -> Unit = {}
    ): AiEditSaveResult = saver.save(
        AiEditCandidate(
            originalUri = request.originalUri,
            candidateUri = request.candidateUri,
            originalRevision = request.originalRevision,
            provider = request.candidate.engineId,
            model = "identity-${request.candidate.mode.name.lowercase()}",
            modelVersion = request.candidate.engineVersion,
            prompt = "",
            settingsJson = "{}",
            format = request.format,
            createdAtMillis = request.createdAtMillis,
            identity = provenance(request)
        ), onRefresh, onFocus
    )

    companion object {
        internal fun provenance(request: Request) = IdentityEditLineage(
            kind = request.candidate.mode.name.lowercase(),
            engineId = request.candidate.engineId,
            engineVersion = request.candidate.engineVersion,
            route = request.candidate.route.name.lowercase(),
            profileId = request.profileId,
            variantId = request.variantId,
            candidateIndex = request.candidate.number,
            seed = request.candidate.variation.seed,
            refinementParent = request.refinementParent
        )
    }
}
