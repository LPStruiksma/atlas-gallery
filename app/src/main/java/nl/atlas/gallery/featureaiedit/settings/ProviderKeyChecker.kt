package nl.atlas.gallery.featureaiedit.settings

import nl.atlas.gallery.featureaiedit.network.EditHttpConfig
import nl.atlas.gallery.featureaiedit.network.EditHttpMethod
import nl.atlas.gallery.featureaiedit.network.EditHttpRequest
import nl.atlas.gallery.featureaiedit.network.EditHttpResult
import nl.atlas.gallery.featureaiedit.network.EditHttpTransport
import nl.atlas.gallery.featureaiedit.network.FailureCode
import java.net.URI
import java.util.Locale

/** What the user sees after Save or "Check key". There is deliberately no "out of credit". */
enum class KeyCheckOutcome { WORKS, REJECTED, NO_CONNECTION, NOT_RESPONDING, NO_CHECK }

fun KeyCheckOutcome.toStatus(): KeyStatus = when (this) {
    KeyCheckOutcome.WORKS -> KeyStatus.WORKS
    KeyCheckOutcome.REJECTED -> KeyStatus.REJECTED
    KeyCheckOutcome.NO_CONNECTION, KeyCheckOutcome.NOT_RESPONDING -> KeyStatus.UNCHECKED
    KeyCheckOutcome.NO_CHECK -> KeyStatus.NO_CHECK
}

/** Seam for the UI so evidence tests never touch the network. Blocking; call off main. */
fun interface KeyCheck {
    fun check(profile: ProviderProfile, secret: String?, rule: KeyCheckRule?): KeyCheckOutcome
}

/**
 * AIE-19 key check: one authenticated GET to the kind's documented, free, upload-free call,
 * through the same host-pinned HTTPS transport as edits. User-initiated only (Save or
 * "Check key"); never an image, prompt or body. The key is never logged or echoed.
 */
class ProviderKeyChecker(
    private val transport: EditHttpTransport = EditHttpTransport()
) : KeyCheck {

    override fun check(profile: ProviderProfile, secret: String?, rule: KeyCheckRule?): KeyCheckOutcome {
        if (rule == null) return KeyCheckOutcome.NO_CHECK
        val auth = profile.authentication(secret) ?: return KeyCheckOutcome.REJECTED
        val host = try { URI(profile.baseUrl).host?.lowercase(Locale.ROOT) } catch (_: Exception) { null }
            ?: return KeyCheckOutcome.NOT_RESPONDING
        val config = EditHttpConfig(profile.baseUrl, setOf(host),
            connectTimeoutMs = 5_000, readTimeoutMs = 10_000,
            overallTimeoutMs = 15_000L, maxAttempts = 2)
        return when (val result = transport.execute(config,
            EditHttpRequest(EditHttpMethod.GET, rule.path), auth)) {
            is EditHttpResult.Success -> KeyCheckOutcome.WORKS
            is EditHttpResult.Failed -> when {
                result.code in rule.rejectedOn -> KeyCheckOutcome.REJECTED
                result.code == FailureCode.NETWORK -> KeyCheckOutcome.NO_CONNECTION
                else -> KeyCheckOutcome.NOT_RESPONDING
            }
            EditHttpResult.Cancelled -> KeyCheckOutcome.NOT_RESPONDING
        }
    }

    companion object {
        /**
         * Pasted keys often carry a trailing newline or spaces: trim them. A key with inner
         * whitespace or control characters is refused locally and never sent.
         */
        fun normalizeKey(raw: String): String? {
            val key = raw.trim()
            if (key.isEmpty() || key.length > 8192) return null
            if (key.any { it.isWhitespace() || it.isISOControl() }) return null
            return key
        }
    }
}
