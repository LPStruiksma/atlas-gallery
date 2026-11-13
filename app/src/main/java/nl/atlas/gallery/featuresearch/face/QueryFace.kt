package nl.atlas.gallery.featuresearch.face

import android.graphics.Bitmap
import nl.atlas.gallery.featureface.FaceMediaKey
import nl.atlas.gallery.featureface.UnknownFaceBox
import nl.atlas.gallery.featureface.onnx.SFaceModel

/**
 * One already-authorized gallery/viewer image. The host supplies a live,
 * grant-scoped FaceFrameReader for [media] and must invalidate [permissionEpoch]
 * when the source or photo grant changes. A raw path or persisted picker URI
 * is never an authorization token.
 */
data class QueryFaceSource(val media: FaceMediaKey, val permissionEpoch: String) {
    init { require(permissionEpoch.isNotBlank()) }
}

/** An ephemeral SFace query, not a person ID and never written to FaceIndexStore. */
class QueryFace(embedding: FloatArray, val modelVersion: String, val sourceBox: UnknownFaceBox) {
    init { require(modelVersion.isNotBlank()) }
    private val unit = SFaceModel.l2Normalize(embedding)
    val embedding: FloatArray get() = unit.copyOf()
}

/** Preview pixels are short-lived, app-private and bounded; never saved to disk. */
class QueryFaceChoice(val query: QueryFace, val preview: Bitmap?)

sealed interface QueryFaceCaptureState {
    data object Idle : QueryFaceCaptureState
    data class Loading(val source: QueryFaceSource) : QueryFaceCaptureState
    data class Empty(val source: QueryFaceSource) : QueryFaceCaptureState
    data class Ready(val source: QueryFaceSource, val faces: List<QueryFaceChoice>) : QueryFaceCaptureState
    data class Busy(val source: QueryFaceSource) : QueryFaceCaptureState
    data class Unavailable(val source: QueryFaceSource) : QueryFaceCaptureState
    data class Failure(val source: QueryFaceSource) : QueryFaceCaptureState
}
