package nl.atlas.gallery.featureaiedit.network

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import nl.atlas.gallery.featureidentity.local.FetchResponse
import nl.atlas.gallery.featureidentity.local.ModelFetchException
import nl.atlas.gallery.featureidentity.local.ModelFetchFailure
import nl.atlas.gallery.featureidentity.local.ModelFetcher
import nl.atlas.gallery.featureidentity.local.ModelHostPolicy

/*
 * IDS-19 (#619): the one HTTPS fetcher for Identity Studio models. It lives here, inside the
 * #191 network fence, because it runs only after the user taps setup (Strategy's choice on
 * #619; LOCAL_ENGINE.md). It never runs while loading or generating.
 *
 * - The first URL must pass [ModelHostPolicy] (the publisher's own GitHub release download).
 * - A redirect is followed only to GitHub's release-asset storage, by the exact host list
 *   [RELEASE_ASSET_HOSTS], never a pattern, over HTTPS on the default port, without user info.
 * - `Range: bytes=<offset>-` resumes a partial file. A 200 to a ranged request means the host
 *   ignored the range; [FetchResponse.resumed] is then false and the store restarts cleanly.
 * - The body is streamed to the caller (the store writes it to its partial file); nothing is
 *   held in memory beyond the caller's buffer.
 * - Failures carry a [ModelFetchFailure] code only: no URL (signed redirect URLs carry tokens),
 *   no header, no cookie and no exception text, so nothing private can reach a log or the UI.
 */
class HttpsModelFetcher(
    private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpsURLConnection },
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
    private val maxRedirects: Int = 4
) : ModelFetcher {

    companion object {
        /**
         * GitHub's release-asset storage, where a `releases/download/` URL redirects to
         * (checked 2026-11-19 for both OD-1 URLs: `release-assets.githubusercontent.com`;
         * `objects.githubusercontent.com` is the older storage host GitHub still uses).
         */
        val RELEASE_ASSET_HOSTS: Set<String> = setOf(
            "release-assets.githubusercontent.com",
            "objects.githubusercontent.com"
        )

        internal fun redirectAllowed(location: String): Boolean {
            val uri = try { URI(location) } catch (_: Exception) { return false }
            if (!uri.isAbsolute || uri.scheme != "https") return false
            if (uri.rawUserInfo != null || (uri.port != -1 && uri.port != 443)) return false
            val host = uri.host ?: return false
            return host in RELEASE_ASSET_HOSTS
        }
    }

    init {
        require(connectTimeoutMs in 1..120_000 && readTimeoutMs in 1..300_000 && maxRedirects in 0..8)
    }

    override fun open(url: String, offset: Long): FetchResponse {
        require(offset >= 0)
        if (!ModelHostPolicy.allowed(url)) throw ModelFetchException(ModelFetchFailure.HOST_REFUSED)
        var current = try { URL(url) } catch (_: Exception) { throw ModelFetchException(ModelFetchFailure.HOST_REFUSED) }
        var hops = 0
        while (true) {
            val connection = try { connectionFactory(current) } catch (_: IOException) {
                throw ModelFetchException(ModelFetchFailure.NETWORK)
            }
            var handedOff = false
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = connectTimeoutMs
                connection.readTimeout = readTimeoutMs
                connection.useCaches = false
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("User-Agent", "ATLAS-Gallery")
                if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
                val status = connection.responseCode
                when {
                    status in REDIRECTS -> {
                        val location = connection.getHeaderField("Location")
                        if (location == null || !redirectAllowed(location)) {
                            throw ModelFetchException(ModelFetchFailure.HOST_REFUSED)
                        }
                        if (++hops > maxRedirects) throw ModelFetchException(ModelFetchFailure.HOST_REFUSED)
                        current = try { URL(location) } catch (_: Exception) {
                            throw ModelFetchException(ModelFetchFailure.HOST_REFUSED)
                        }
                    }
                    status == 206 -> {
                        if (offset == 0L || !contentRangeStartsAt(connection.getHeaderField("Content-Range"), offset)) {
                            // A partial reply we did not ask for, or for other bytes: never splice it in.
                            throw ModelFetchException(ModelFetchFailure.NETWORK)
                        }
                        val body = ClosingStream(connection)
                        handedOff = true
                        return FetchResponse(body, resumed = true)
                    }
                    status == 200 -> {
                        val body = ClosingStream(connection)
                        handedOff = true
                        // Without a Range header byte 0 is exactly what was asked for.
                        return FetchResponse(body, resumed = offset == 0L)
                    }
                    status == 416 -> throw ModelFetchException(ModelFetchFailure.NETWORK)
                    status == 408 || status == 429 || status >= 500 -> throw ModelFetchException(ModelFetchFailure.NETWORK)
                    else -> throw ModelFetchException(ModelFetchFailure.HOST_REFUSED)
                }
            } catch (e: ModelFetchException) {
                throw e
            } catch (_: IOException) {
                throw ModelFetchException(ModelFetchFailure.NETWORK)
            } finally {
                if (!handedOff) connection.disconnect()
            }
        }
    }

    private fun contentRangeStartsAt(header: String?, offset: Long): Boolean {
        val match = CONTENT_RANGE.matchEntire(header?.trim() ?: return false) ?: return false
        return match.groupValues[1].toLongOrNull() == offset
    }

    /** Disconnects when the store closes the body; read errors become a plain network failure. */
    private class ClosingStream(private val connection: HttpURLConnection) :
        FilterInputStream(connection.inputStream) {
        override fun read(): Int = guard { super.read() }
        override fun read(b: ByteArray, off: Int, len: Int): Int = guard { super.read(b, off, len) }
        override fun close() {
            try { super.close() } catch (_: IOException) { } finally { connection.disconnect() }
        }
        private inline fun guard(block: () -> Int): Int = try { block() } catch (_: IOException) {
            throw ModelFetchException(ModelFetchFailure.NETWORK)
        }
    }
}

private val REDIRECTS = setOf(301, 302, 303, 307, 308)
private val CONTENT_RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)")
