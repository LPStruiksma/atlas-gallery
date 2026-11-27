package nl.atlas.gallery.featurepeopleui

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest

/**
 * HUD-1C: Skip is a private preference, never a People review fact. Each grant
 * has a separate digest-named set; entries are opaque membership fingerprints.
 * No face/person IDs or grant tokens are persisted in plaintext.
 */
internal class SamePersonSkipPreferences(private val prefs: SharedPreferences) {
    companion object {
        fun forApp(context: Context): SamePersonSkipPreferences =
            SamePersonSkipPreferences(context.applicationContext.getSharedPreferences(
                "atlas_same_person_skips_v1", Context.MODE_PRIVATE))
    }

    private fun scope(epoch: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(("skip-scope-v1|" + epoch).toByteArray(Charsets.UTF_8))
        return "scope_" + bytes.joinToString("") { "%02x".format(it) }
    }

    fun read(epoch: String): Set<String> = try {
        prefs.getStringSet(scope(epoch), null)?.toSet().orEmpty()
    } catch (_: ClassCastException) {
        emptySet() // Corrupt or old preference: do not suppress a question.
    }

    fun add(epoch: String, token: String): Set<String> {
        val updated = read(epoch) + token
        prefs.edit().putStringSet(scope(epoch), updated).apply()
        return updated
    }

    fun clear(epoch: String) {
        prefs.edit().remove(scope(epoch)).apply()
    }
}
