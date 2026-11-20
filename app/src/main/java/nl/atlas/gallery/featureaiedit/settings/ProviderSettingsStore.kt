package nl.atlas.gallery.featureaiedit.settings

import android.content.Context
import nl.atlas.gallery.featureaiedit.network.EditHttpAuth
import nl.atlas.gallery.featureaiedit.network.EditHttpConfig
import nl.atlas.gallery.featureaiedit.provider.hosted.fal.TransportFalExchange
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale

/** Only service metadata is persisted here. Keys are stored separately in Keystore-backed vault. */
enum class ProviderAuthType {
    NONE, BEARER, CUSTOM_HEADER,

    /**
     * `Authorization: Key <key>`, fal.ai's documented scheme (#462). Only the fal preset uses it;
     * a custom server cannot pick it ([CUSTOM_SERVER]) and [ProviderSettingsStore.valid]
     * refuses it on any other kind.
     */
    KEY_SCHEME;

    companion object {
        /** What "Custom server" offers, in this order. */
        val CUSTOM_SERVER: List<ProviderAuthType> = listOf(NONE, BEARER, CUSTOM_HEADER)

        /** The scheme word [KEY_SCHEME] sends; the fal adapter sends the same word. */
        const val FAL_KEY_SCHEME = TransportFalExchange.AUTH_SCHEME
    }
}

/**
 * Result of the last user-initiated key check, shown as the service row's status.
 * [NO_CHECK]: the kind has no documented free, upload-free check (see [ProviderRegistry]).
 */
enum class KeyStatus { WORKS, UNCHECKED, REJECTED, NO_CHECK }

data class ProviderProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val authType: ProviderAuthType,
    val headerName: String = "X-Atlas-Token",
    /** AIE-19: profiles stored before #430 have no kind and read as ATLAS protocol. */
    val kind: ProviderKind = ProviderKind.ATLAS_PROTOCOL,
    /** Only for a kind that offers several models; null means the kind's default. */
    val modelId: String? = null,
    val keyStatus: KeyStatus = KeyStatus.UNCHECKED
) {
    fun transportConfig(): EditHttpConfig {
        val host = requireNotNull(URI(baseUrl).host).lowercase(Locale.ROOT)
        return EditHttpConfig(baseUrl, setOf(host))
    }

    fun authentication(vault: ProviderCredentialVault): EditHttpAuth? =
        authentication(vault.get(id))

    /**
     * fal only ever gets `Authorization: Key …` (#462), the scheme its adapter (#429) sends; a fal
     * profile stored with any other scheme fails closed rather than sending Bearer.
     */
    fun authentication(secret: String?): EditHttpAuth? = when {
        kind == ProviderKind.FAL && authType != ProviderAuthType.KEY_SCHEME -> null
        authType == ProviderAuthType.NONE -> EditHttpAuth.None
        secret == null -> null
        authType == ProviderAuthType.BEARER -> EditHttpAuth.Bearer(secret)
        authType == ProviderAuthType.KEY_SCHEME ->
            EditHttpAuth.Scheme(ProviderAuthType.FAL_KEY_SCHEME, secret)
        else -> EditHttpAuth.CustomHeader(headerName, secret)
    }

    override fun toString(): String = "ProviderProfile($id, $kind)"
}

/** A stored service of a kind this build does not know: kept and shown, never used. */
data class UnavailableProfile(val id: String, val name: String)

class ProviderSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences(
        "atlas_ai_edit_endpoints_v1", Context.MODE_PRIVATE)
    // The given context, so an isolated test context also isolates the vault directory.
    private val vault = ProviderCredentialVault(context)

    /** Every stored entry in storage order, including kinds this build does not know. */
    private fun entries(): List<JSONObject> = try {
        val data = JSONArray(preferences.getString("profiles", "[]") ?: "[]")
        buildList {
            for (n in 0 until data.length().coerceAtMost(MAX_PROFILES)) {
                data.optJSONObject(n)?.let { add(it) }
            }
        }
    } catch (_: Exception) { emptyList() }

    private fun decode(item: JSONObject): ProviderProfile? = try {
        val kindName = item.optString("kind", ProviderKind.ATLAS_PROTOCOL.name)
        val kind = ProviderKind.entries.firstOrNull { it.name == kindName }
        if (kind == null) null else ProviderProfile(
            item.getString("id"), item.getString("name"), item.getString("baseUrl"),
            ProviderAuthType.valueOf(item.getString("authType")),
            item.optString("headerName", "X-Atlas-Token"),
            kind,
            item.optString("modelId", "").ifBlank { null },
            KeyStatus.entries.firstOrNull { it.name == item.optString("keyStatus") }
                ?: KeyStatus.UNCHECKED
        ).takeIf { valid(it) }
    } catch (_: Exception) { null }

    private fun isUnknownKind(item: JSONObject): Boolean =
        item.has("kind") && ProviderKind.entries.none { it.name == item.optString("kind") }

    private fun encode(profile: ProviderProfile): JSONObject = JSONObject().apply {
        put("id", profile.id)
        put("name", profile.name)
        put("baseUrl", profile.baseUrl)
        put("authType", profile.authType.name)
        put("headerName", profile.headerName)
        put("kind", profile.kind.name)
        profile.modelId?.let { put("modelId", it) }
        put("keyStatus", profile.keyStatus.name)
    }

    fun profiles(): List<ProviderProfile> =
        entries().mapNotNull { decode(it) }.distinctBy { it.id }

    /** Entries whose kind is unknown to this build, so the user can see and remove them. */
    fun unavailableProfiles(): List<UnavailableProfile> = entries()
        .filter { isUnknownKind(it) }
        .mapNotNull { item ->
            val id = item.optString("id")
            val name = item.optString("name").trim()
            if (!id.matches(ID) || name.isBlank()) null
            else UnavailableProfile(id, name.take(80))
        }.distinctBy { it.id }

    fun defaultProvider(): ProviderProfile? {
        val selected = preferences.getString("defaultProviderId", null)
        return profiles().firstOrNull { it.id == selected }
    }

    fun defaultProviderId(): String? = defaultProvider()?.id

    /** Replaces a profile in place (keeping list order) or appends it. Unknown kinds survive. */
    fun save(profile: ProviderProfile) {
        require(valid(profile)) { "Invalid HTTPS provider configuration" }
        val current = entries()
        var replaced = false
        val json = JSONArray()
        for (item in current) {
            if (item.optString("id") == profile.id) {
                if (!replaced) json.put(encode(profile))
                replaced = true
            } else if (isUnknownKind(item) || decode(item) != null) {
                json.put(item)
            }
        }
        if (!replaced) json.put(encode(profile))
        require(json.length() <= MAX_PROFILES)
        preferences.edit().putString("profiles", json.toString()).apply()
    }

    fun setKeyStatus(id: String, status: KeyStatus) {
        profiles().firstOrNull { it.id == id }?.let { save(it.copy(keyStatus = status)) }
    }

    fun setDefault(id: String) {
        require(profiles().any { it.id == id })
        preferences.edit().putString("defaultProviderId", id).apply()
    }

    /**
     * Removes a service and deletes its key from the vault at once. When it was the default,
     * the next eligible service in list order becomes the default. Returns that new default,
     * or null when the default did not change or no eligible service is left.
     */
    fun delete(id: String, eligible: (ProviderProfile) -> Boolean = { true }): ProviderProfile? {
        val wasDefault = preferences.getString("defaultProviderId", null) == id
        val json = JSONArray()
        for (item in entries()) {
            if (item.optString("id") == id) continue
            if (isUnknownKind(item) || decode(item) != null) json.put(item)
        }
        val editor = preferences.edit().putString("profiles", json.toString())
        if (wasDefault) editor.remove("defaultProviderId")
        editor.apply()
        vault.delete(id)
        if (!wasDefault) return null
        val next = profiles().firstOrNull(eligible) ?: return null
        setDefault(next.id)
        return next
    }

    var keepLocationInUploads: Boolean
        get() = preferences.getBoolean("keepLocationInUploads", false)
        set(value) { preferences.edit().putBoolean("keepLocationInUploads", value).apply() }

    var firstUseAcknowledged: Boolean
        get() = preferences.getBoolean("firstUseAcknowledged", false)
        set(value) { preferences.edit().putBoolean("firstUseAcknowledged", value).apply() }

    companion object {
        const val MAX_PROFILES = 40
        private val ID = Regex("[A-Za-z0-9_-]{1,80}")

        /** Preset profiles validate host and scheme exactly as custom ones. */
        fun valid(profile: ProviderProfile): Boolean = try {
            val uri = URI(profile.baseUrl)
            val host = uri.host
            profile.id.matches(ID) &&
                profile.name.isNotBlank() && profile.name.length <= 80 &&
                profile.baseUrl.length <= 2048 && uri.scheme == "https" &&
                !host.isNullOrBlank() && uri.port in -1..65535 &&
                uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                uri.rawPath?.endsWith("/") == true && uri.normalize() == uri &&
                (profile.modelId == null || profile.modelId.matches(MODEL_ID)) &&
                (profile.authType != ProviderAuthType.KEY_SCHEME || profile.kind == ProviderKind.FAL) &&
                (profile.authType != ProviderAuthType.CUSTOM_HEADER ||
                    (profile.headerName.matches(Regex("[A-Za-z][A-Za-z0-9-]{0,63}")) &&
                        profile.headerName.lowercase(Locale.ROOT) !in
                            setOf("authorization", "host", "cookie", "content-length", "content-type",
                                "connection", "transfer-encoding", "proxy-authorization")))
        } catch (_: Exception) { false }

        private val MODEL_ID = Regex("[A-Za-z0-9._/-]{1,128}")
    }
}
