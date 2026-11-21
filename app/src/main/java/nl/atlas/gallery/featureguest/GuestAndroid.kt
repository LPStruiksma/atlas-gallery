package nl.atlas.gallery.featureguest

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.security.SecureRandom

/**
 * GUEST-1 (#589): the Android side of the guard. Framework facilities only
 * (SharedPreferences.commit, AtomicFile, KeyguardManager); no new dependency
 * and no manifest change.
 */
object GuestAndroid {
    private const val PREFS = "atlas_guest_guard"
    private const val KEY_ACTIVE = "active"
    internal const val RECORD_FILE = "guest-session.v1"

    fun store(context: Context): GuestSessionStore {
        val app = context.applicationContext
        return GuestSessionStore(PrefsSentinel(app), AtomicRecord(File(app.noBackupFilesDir, RECORD_FILE)),
            ::newToken)
    }

    /** Guest view needs a secure lock: without one nothing could protect the exit. */
    fun deviceSecure(context: Context): Boolean {
        val keyguard = context.getSystemService(KeyguardManager::class.java) ?: return false
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN) &&
            keyguard.isDeviceSecure
    }

    /**
     * Brief v2 gap 3: the system's own device-credential confirmation (PIN,
     * pattern, password, or the biometric the OS offers there). API 29–36,
     * needs no permission. Null when the device has no secure lock.
     */
    @Suppress("DEPRECATION")
    fun confirmIntent(context: Context): Intent? =
        context.getSystemService(KeyguardManager::class.java)
            ?.createConfirmDeviceCredentialIntent(null, null)

    private val random = SecureRandom()

    /** 32 hex characters from SecureRandom: session ids and auth nonces. */
    fun newToken(): String {
        val bytes = ByteArray(16).also(random::nextBytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private class PrefsSentinel(context: Context) : GuestSentinelBackend {
        private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        override fun read(): Boolean? =
            if (!prefs.contains(KEY_ACTIVE)) null else prefs.getBoolean(KEY_ACTIVE, true)

        override fun commit(active: Boolean): Boolean = prefs.edit().putBoolean(KEY_ACTIVE, active).commit()
    }

    private class AtomicRecord(file: File) : GuestRecordBackend {
        private val atomic = AtomicFile(file)

        override fun read(): String? = try {
            String(atomic.readFully(), Charsets.UTF_8)
        } catch (_: FileNotFoundException) {
            null
        }

        override fun write(text: String): Boolean {
            val stream = try { atomic.startWrite() } catch (_: Exception) { return false }
            return try {
                stream.write(text.toByteArray(Charsets.UTF_8))
                atomic.finishWrite(stream) // fsyncs before the rename
                true
            } catch (_: Exception) {
                atomic.failWrite(stream)
                false
            }
        }
    }
}
