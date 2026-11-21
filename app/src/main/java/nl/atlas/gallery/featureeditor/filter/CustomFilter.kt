package nl.atlas.gallery.featureeditor.filter

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.roundToInt

/** Synthetic or on-device three-channel CIE Lab summary; reference pixels aren't retained. */
data class LabStatistics(val mean: DoubleArray, val deviation: DoubleArray) {
    init {
        require(mean.size == 3 && deviation.size == 3)
        require((mean + deviation).all { it.isFinite() } && deviation.all { it > 0.0 })
    }
}

object LabColourTransfer {
    fun statistics(rgb: List<DoubleArray>): LabStatistics {
        require(rgb.isNotEmpty() && rgb.all { it.size == 3 && it.all(Double::isFinite) })
        val labs = rgb.map(::rgbToLab)
        val mean = DoubleArray(3) { c -> labs.sumOf { it[c] } / labs.size }
        val sd = DoubleArray(3) { c ->
            kotlin.math.sqrt(labs.sumOf { (it[c] - mean[c]).pow(2) } / labs.size).coerceAtLeast(1e-12)
        }
        return LabStatistics(mean, sd)
    }

    fun transfer(rgb: DoubleArray, source: LabStatistics, reference: LabStatistics): DoubleArray {
        val lab = rgbToLab(rgb)
        val moved = DoubleArray(3) { c ->
            reference.mean[c] + (lab[c] - source.mean[c]) * reference.deviation[c] / source.deviation[c]
        }
        return labToRgb(moved)
    }

    private fun rgbToLab(rgb: DoubleArray): DoubleArray {
        require(rgb.size == 3 && rgb.all(Double::isFinite))
        fun linear(v: Double) = if (v <= .04045) v / 12.92 else ((v + .055) / 1.055).pow(2.4)
        val r = linear(rgb[0].coerceIn(0.0, 1.0))
        val g = linear(rgb[1].coerceIn(0.0, 1.0))
        val b = linear(rgb[2].coerceIn(0.0, 1.0))
        fun f(v: Double) = if (v > .008856) cbrt(v) else 7.787 * v + 16.0 / 116.0
        val x = f((.4124564 * r + .3575761 * g + .1804375 * b) / .95047)
        val y = f(.2126729 * r + .7151522 * g + .0721750 * b)
        val z = f((.0193339 * r + .1191920 * g + .9503041 * b) / 1.08883)
        return doubleArrayOf(116 * y - 16, 500 * (x - y), 200 * (y - z))
    }

    private fun labToRgb(lab: DoubleArray): DoubleArray {
        val fy = (lab[0] + 16) / 116
        val fx = fy + lab[1] / 500
        val fz = fy - lab[2] / 200
        fun inv(v: Double) = if (v.pow(3) > .008856) v.pow(3) else (v - 16.0 / 116) / 7.787
        val x = .95047 * inv(fx)
        val y = inv(fy)
        val z = 1.08883 * inv(fz)
        fun gamma(v: Double): Double {
            val c = if (v <= .0031308) 12.92 * v else 1.055 * v.pow(1 / 2.4) - .055
            return c.coerceIn(0.0, 1.0)
        }
        return doubleArrayOf(
            gamma(3.2404542 * x - 1.5371385 * y - .4985314 * z),
            gamma(-.969266 * x + 1.8760108 * y + .041556 * z),
            gamma(.0556434 * x - .2040259 * y + 1.0572252 * z)
        )
    }
}

/** Derive a content-addressable custom 3D LUT from on-device source/reference statistics. */
object FilterFromPhoto {
    fun derive(source: LabStatistics, reference: LabStatistics, size: Int = 33): ByteArray {
        require(size in 2..65)
        val bytes = ByteArray(10 + size * size * size * 3)
        "ATLASLUT1".toByteArray(Charsets.US_ASCII).copyInto(bytes)
        bytes[9] = size.toByte()
        var index = 10
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            val result = LabColourTransfer.transfer(
                doubleArrayOf(r.toDouble() / (size - 1), g.toDouble() / (size - 1), b.toDouble() / (size - 1)),
                source, reference
            )
            for (channel in 0..2) {
                bytes[index++] = (result[channel] * 255.0).roundToInt().coerceIn(0, 255).toByte()
            }
        }
        return bytes
    }
}

/** App-private, content-addressed storage; callers must pass a directory under filesDir. */
class CustomFilterStore(private val directory: File) {
    data class Stored(val id: String, val sha256: String, val file: File, val name: String? = null)

    fun save(bytes: ByteArray, name: String? = null): Stored {
        require(bytes.isNotEmpty() && bytes.size <= 2_000_000)
        val displayName = name?.trim()
        require(displayName == null || (displayName.isNotEmpty() && displayName.length <= 64 &&
            displayName.none { it.isISOControl() })) { "invalid custom filter name" }
        require(directory.isDirectory || directory.mkdirs()) { "cannot create private storage" }
        val hash = sha256(bytes)
        val target = File(directory, hash + ".lut")
        if (!target.exists()) writeAtomically(target, bytes)
        else require(target.isFile && target.readBytes().contentEquals(bytes)) { "existing filter is corrupt" }
        if (displayName != null) writeAtomically(File(directory, hash + ".name"),
            displayName.toByteArray(Charsets.UTF_8))
        return Stored("custom-" + hash, hash, target, displayName)
    }

    fun read(hash: String): ByteArray? {
        if (!validHash(hash)) return null
        val file = File(directory, hash + ".lut")
        if (!file.isFile) return null
        return file.readBytes().takeIf { sha256(it) == hash }
    }

    fun readName(hash: String): String? {
        if (!validHash(hash)) return null
        val file = File(directory, hash + ".name")
        return if (file.isFile) file.readText(Charsets.UTF_8).takeIf {
            it.isNotBlank() && it.length <= 64 && it.none(Char::isISOControl)
        } else null
    }

    private fun validHash(hash: String) = hash.matches(Regex("[0-9a-f]{64}"))

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val temp = File.createTempFile("atlas-filter-", ".tmp", directory)
        try {
            temp.writeBytes(bytes)
            try {
                Files.move(temp.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
