package nl.atlas.gallery.featureeditor.overlay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import nl.atlas.gallery.featureeditor.engine.isContentHash
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Cut-outs and images become self-contained, alpha-preserving PNG assets in the caller's
 * content-addressed store. Only the SHA-256 is retained in StickerLayer/RecipeJson.
 * The store owns persistence, retrieval, eviction and permissions.
 */
data class EncodedSticker(val sha256: String, val pngBytes: ByteArray)

object StickerAssets {
    fun fromBitmap(bitmap: Bitmap): EncodedSticker {
        require(!bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) {
            "Invalid sticker image"
        }
        val bytes = ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                "Could not encode sticker"
            }
            output.toByteArray()
        }
        return EncodedSticker(sha256(bytes), bytes)
    }

    fun decodeVerified(pngBytes: ByteArray, expectedHash: String): Bitmap {
        require(isContentHash(expectedHash) && sha256(pngBytes) == expectedHash) {
            "Sticker asset checksum mismatch"
        }
        return BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size)
            ?: throw IllegalArgumentException("Invalid sticker PNG")
    }

    fun sha256(bytes: ByteArray): String {
        val hex = "0123456789abcdef"
        return buildString(64) {
            MessageDigest.getInstance("SHA-256").digest(bytes).forEach { byte ->
                val b = byte.toInt() and 255
                append(hex[b ushr 4])
                append(hex[b and 15])
            }
        }
    }
}
