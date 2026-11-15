package nl.atlas.gallery.featureaiedit.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * BYOK values are never in preferences, WorkManager Data, logcat, diagnostics or an exception
 * string. Each encrypted record lives in noBackupFilesDir (excluded from auto-backup/transfer),
 * and its non-exportable AES-256-GCM key lives in Android Keystore.
 *
 * The manifest already has allowBackup=false. If future migrations add backup/transfer rules,
 * they must also explicitly exclude ai_edit_credentials/ (owner-gated manifest work in AIE-6).
 */
class ProviderCredentialVault(context: Context) {
    private val root = File(context.noBackupFilesDir, "ai_edit_credentials")
    private val alias = "atlas-aie5-provider-credentials-v1"

    private fun key(create: Boolean): SecretKey? {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }

    private fun record(providerId: String): File {
        require(Regex("[a-zA-Z0-9_-]{1,80}").matches(providerId))
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(providerId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return File(root, digest + ".enc")
    }

    @Synchronized fun put(providerId: String, secret: String) {
        require(secret.isNotBlank() && secret.length <= 8192)
        val target = record(providerId)
        check(root.isDirectory || root.mkdirs())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, requireNotNull(key(create = true)))
        val encrypted = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        require(iv.size == 12)
        val temp = File.createTempFile(".cred-", ".tmp", root)
        try {
            FileOutputStream(temp).use {
                it.write(byteArrayOf(1, iv.size.toByte()))
                it.write(iv)
                it.write(encrypted)
                it.fd.sync()
            }
            check(temp.renameTo(target)) { "Credential store unavailable" }
        } finally {
            temp.delete()
        }
    }

    @Synchronized fun get(providerId: String): String? {
        val source = record(providerId)
        if (!source.isFile) return null
        val secretKey = key(create = false) ?: return null
        // An unusable key or malformed record fails closed. Do not echo decrypt errors.
        return try {
            val blob = source.readBytes()
            if (blob.size !in 30..12_000 || blob[0] != 1.toByte() || blob[1] != 12.toByte())
                return null
            val iv = blob.copyOfRange(2, 14)
            val ciphertext = blob.copyOfRange(14, blob.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Exception) { null }
    }

    @Synchronized fun delete(providerId: String) {
        val target = record(providerId)
        if (target.exists()) check(target.delete())
    }

    /** For device-reset or test cleanup only; never used by an ordinary settings action. */
    fun isBackedUp(): Boolean = false
}
