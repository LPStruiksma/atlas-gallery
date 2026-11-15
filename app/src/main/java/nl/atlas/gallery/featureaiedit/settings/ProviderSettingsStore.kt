package nl.atlas.gallery.featureaiedit.settings

import android.content.Context
import nl.atlas.gallery.featureaiedit.network.EditHttpAuth
import nl.atlas.gallery.featureaiedit.network.EditHttpConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale

/** Only endpoint metadata is persisted here. Keys are stored separately in Keystore-backed vault. */
enum class ProviderAuthType { NONE, BEARER, CUSTOM_HEADER }

data class ProviderProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val authType: ProviderAuthType,
    val headerName: String = "X-Atlas-Token"
) {
    fun transportConfig(): EditHttpConfig {
        val host = requireNotNull(URI(baseUrl).host).lowercase(Locale.ROOT)
        return EditHttpConfig(baseUrl, setOf(host))
    }

    fun authentication(vault: ProviderCredentialVault): EditHttpAuth? = when (authType) {
        ProviderAuthType.NONE -> EditHttpAuth.None
        ProviderAuthType.BEARER -> vault.get(id)?.let { EditHttpAuth.Bearer(it) }
        ProviderAuthType.CUSTOM_HEADER -> vault.get(id)?.let {
            EditHttpAuth.CustomHeader(headerName, it)
        }
    }
}

class ProviderSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "atlas_ai_edit_endpoints_v1", Context.MODE_PRIVATE)
    private val vault = ProviderCredentialVault(context.applicationContext)

    fun profiles(): List<ProviderProfile> = try {
        val data = JSONArray(preferences.getString("profiles", "[]") ?: "[]")
        buildList {
            for (n in 0 until data.length().coerceAtMost(40)) {
                val item = data.getJSONObject(n)
                val profile = ProviderProfile(
                    item.getString("id"), item.getString("name"), item.getString("baseUrl"),
                    ProviderAuthType.valueOf(item.getString("authType")),
                    item.optString("headerName", "X-Atlas-Token")
                )
                if (valid(profile)) add(profile)
            }
        }.distinctBy { it.id }
    } catch (_: Exception) { emptyList() }

    fun defaultProvider(): ProviderProfile? {
        val selected = preferences.getString("defaultProviderId", null)
        return profiles().firstOrNull { it.id == selected }
    }

    fun save(profile: ProviderProfile) {
        require(valid(profile)) { "Invalid HTTPS provider configuration" }
        val list = profiles().filterNot { it.id == profile.id } + profile
        require(list.size <= 40)
        val json = JSONArray()
        for (item in list) json.put(JSONObject().apply {
            put("id", item.id)
            put("name", item.name)
            put("baseUrl", item.baseUrl)
            put("authType", item.authType.name)
            put("headerName", item.headerName)
        })
        preferences.edit().putString("profiles", json.toString()).apply()
    }

    fun setDefault(id: String) {
        require(profiles().any { it.id == id })
        preferences.edit().putString("defaultProviderId", id).apply()
    }

    fun delete(id: String) {
        val list = profiles().filterNot { it.id == id }
        val json = JSONArray()
        for (item in list) json.put(JSONObject().apply {
            put("id", item.id); put("name", item.name); put("baseUrl", item.baseUrl)
            put("authType", item.authType.name); put("headerName", item.headerName)
        })
        preferences.edit().putString("profiles", json.toString())
            .apply {
                if (preferences.getString("defaultProviderId", null) == id)
                    remove("defaultProviderId")
            }.apply()
        vault.delete(id)
    }

    var keepLocationInUploads: Boolean
        get() = preferences.getBoolean("keepLocationInUploads", false)
        set(value) { preferences.edit().putBoolean("keepLocationInUploads", value).apply() }

    var firstUseAcknowledged: Boolean
        get() = preferences.getBoolean("firstUseAcknowledged", false)
        set(value) { preferences.edit().putBoolean("firstUseAcknowledged", value).apply() }

    private fun valid(profile: ProviderProfile): Boolean = try {
        val uri = URI(profile.baseUrl)
        val host = uri.host
        profile.id.matches(Regex("[A-Za-z0-9_-]{1,80}")) &&
            profile.name.isNotBlank() && profile.name.length <= 80 &&
            profile.baseUrl.length <= 2048 && uri.scheme == "https" &&
            !host.isNullOrBlank() && uri.port in -1..65535 &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.rawPath?.endsWith("/") == true && uri.normalize() == uri &&
            (profile.authType != ProviderAuthType.CUSTOM_HEADER ||
                (profile.headerName.matches(Regex("[A-Za-z][A-Za-z0-9-]{0,63}")) &&
                    profile.headerName.lowercase(Locale.ROOT) !in
                        setOf("authorization", "host", "cookie", "content-length", "content-type",
                            "connection", "transfer-encoding", "proxy-authorization")))
    } catch (_: Exception) { false }
}
