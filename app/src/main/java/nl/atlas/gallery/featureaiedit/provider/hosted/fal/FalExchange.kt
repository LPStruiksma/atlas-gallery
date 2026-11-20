package nl.atlas.gallery.featureaiedit.provider.hosted.fal

import nl.atlas.gallery.featureaiedit.network.EditHttpAuth
import nl.atlas.gallery.featureaiedit.network.EditHttpBody
import nl.atlas.gallery.featureaiedit.network.EditHttpConfig
import nl.atlas.gallery.featureaiedit.network.EditHttpMethod
import nl.atlas.gallery.featureaiedit.network.EditHttpRequest
import nl.atlas.gallery.featureaiedit.network.EditHttpResult
import nl.atlas.gallery.featureaiedit.network.EditHttpTransport
import nl.atlas.gallery.featureaiedit.network.EditRequestCancellation
import java.io.File

enum class FalMethod { GET, POST, PUT }

/**
 * One HTTP exchange the fal adapter needs. [config] already pins the single allowed host;
 * [withKey] asks for `Authorization: Key <key>` (fal's documented scheme); [downloadTo] streams
 * a bounded body into an app-private file. The key is passed separately and never stored here.
 */
class FalCall(
    val method: FalMethod,
    val config: EditHttpConfig,
    val path: String,
    val json: ByteArray? = null,
    val withKey: Boolean,
    val downloadTo: File? = null,
    /** Non-secret fal request headers, such as the object-lifecycle preference (#464). */
    val headers: Map<String, String> = emptyMap()
) {
    override fun toString(): String = "FalCall(${method.name}, ${config.allowedHosts}, withKey=$withKey)"
}

/** Seam between the fal adapter and HTTP; tests run the real transport against a loopback fake. */
fun interface FalExchange {
    fun execute(call: FalCall, key: String, cancellation: EditRequestCancellation): EditHttpResult
}

/**
 * Production exchange over the shared [EditHttpTransport]: keyed calls send
 * `Authorization: Key <key>` through [EditHttpAuth.Scheme]; cancel is a PUT that is not replayed;
 * result downloads carry no key and are bounded by the transport's file cap.
 */
class TransportFalExchange(private val transport: EditHttpTransport = EditHttpTransport()) : FalExchange {
    override fun execute(call: FalCall, key: String, cancellation: EditRequestCancellation): EditHttpResult {
        val method = when (call.method) {
            FalMethod.GET -> EditHttpMethod.GET
            FalMethod.POST -> EditHttpMethod.POST
            FalMethod.PUT -> EditHttpMethod.PUT
        }
        return transport.execute(
            call.config,
            EditHttpRequest(method, call.path, call.json?.let { EditHttpBody.Json(it) }, call.headers),
            if (call.withKey) EditHttpAuth.Scheme(AUTH_SCHEME, key) else EditHttpAuth.None,
            cancellation,
            call.downloadTo
        )
    }

    companion object {
        /** fal's documented scheme word (`@fal-ai/client` 1.11.0, `src/request.js`). */
        const val AUTH_SCHEME = "Key"
    }
}
