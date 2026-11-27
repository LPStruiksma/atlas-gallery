package nl.atlas.gallery

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/**
 * BRAND-L4 (#657, Strategy decision 2026-11-27): the "short recording of fast
 * scroll" is an animated PNG built from frames taken DURING the real gesture,
 * at real timing, plus a still contact sheet of the same frames labelled with
 * their timestamps (some viewers show only an APNG's first frame). Both are
 * ordinary .png files, so the existing evidence publishing carries them.
 */
internal class FastScrollRecorder(private val scale: Float = 1f / 3f) {
    private val frames = mutableListOf<Bitmap>()
    private val times = mutableListOf<Long>()
    private var start = -1L

    /** Takes one screenshot now and stamps it with the real (uptime) clock. */
    fun capture() {
        val shot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation
            .takeScreenshot()) { "Screenshot acquisition failed during the recording" }
        val now = SystemClock.uptimeMillis()
        if (start < 0) start = now
        val small = Bitmap.createScaledBitmap(shot, (shot.width * scale).toInt(),
            (shot.height * scale).toInt(), true)
        if (small !== shot) shot.recycle()
        frames += small
        times += now - start
    }

    /** Milliseconds since the first frame, one per frame. */
    val timestamps: List<Long> get() = times.toList()

    /**
     * Writes `<name>.png` (the APNG; frame delays are the measured gaps, the
     * last frame holds 1 s) and `<name>-sheet.png` (the contact sheet).
     */
    fun save(name: String) {
        check(frames.size >= 2) { "a recording needs at least two frames, got ${frames.size}" }
        val pngs = frames.map { it.toPng() }
        val delays = times.zipWithNext { a, b -> (b - a).coerceIn(1L, 65_535L).toInt() } + 1_000
        val apng = encodeApng(pngs, delays,
            "ATLAS #657 fast-scroll recording; frame ms since first: ${times.joinToString(",")}")
        check(apngFrameCount(apng) == frames.size) { "$name: APNG frame count mismatch" }
        writeUiEvidenceFile(uiEvidenceDisplayName("$name.png"), apng)
        writeUiEvidenceFile(uiEvidenceDisplayName("$name-sheet.png"), contactSheet().toPng())
        android.util.Log.i("ATLAS_EVIDENCE",
            "$name: ${frames.size} frames at ms ${times.joinToString(",")}")
        frames.forEach(Bitmap::recycle)
        frames.clear()
    }

    private fun contactSheet(): Bitmap {
        val columns = 6
        val w = frames[0].width
        val h = frames[0].height
        val label = 36
        val pad = 6
        val rows = (frames.size + columns - 1) / columns
        val sheet = Bitmap.createBitmap(columns * (w + pad) + pad, rows * (h + label + pad) + pad,
            Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.rgb(24, 24, 28))
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 24f
            typeface = Typeface.MONOSPACE
        }
        frames.forEachIndexed { i, frame ->
            val x = pad + (i % columns) * (w + pad)
            val y = pad + (i / columns) * (h + label + pad)
            canvas.drawText("#${i + 1}  +${times[i]} ms", x.toFloat() + 4f, y + 26f, ink)
            canvas.drawBitmap(frame, x.toFloat(), (y + label).toFloat(), null)
        }
        return sheet
    }
}

private fun Bitmap.toPng(): ByteArray = ByteArrayOutputStream().also {
    check(compress(Bitmap.CompressFormat.PNG, 100, it)) { "PNG encoding failed" }
}.toByteArray()

private val PNG_SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

private class PngChunk(val type: String, val data: ByteArray)

private fun readChunks(png: ByteArray): List<PngChunk> {
    require(png.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)) { "not a PNG" }
    val chunks = mutableListOf<PngChunk>()
    var at = 8
    while (at + 8 <= png.size) {
        val length = readInt(png, at)
        val type = String(png, at + 4, 4, Charsets.US_ASCII)
        chunks += PngChunk(type, png.copyOfRange(at + 8, at + 8 + length))
        at += 12 + length
        if (type == "IEND") break
    }
    return chunks
}

private fun readInt(b: ByteArray, at: Int): Int =
    (b[at].toInt() and 0xff shl 24) or (b[at + 1].toInt() and 0xff shl 16) or
        (b[at + 2].toInt() and 0xff shl 8) or (b[at + 3].toInt() and 0xff)

private fun ByteArrayOutputStream.int(v: Int) {
    write(v ushr 24 and 0xff); write(v ushr 16 and 0xff); write(v ushr 8 and 0xff); write(v and 0xff)
}

private fun ByteArrayOutputStream.short(v: Int) {
    write(v ushr 8 and 0xff); write(v and 0xff)
}

private fun ByteArrayOutputStream.chunk(type: String, data: ByteArray) {
    int(data.size)
    val typeBytes = type.toByteArray(Charsets.US_ASCII)
    write(typeBytes)
    write(data)
    val crc = CRC32().apply { update(typeBytes); update(data) }
    int(crc.value.toInt())
}

/**
 * Packs same-size PNG frames into one APNG (W3C PNG 3rd edition): IHDR of the
 * first frame, acTL, then per frame an fcTL with its delay in ms; frame 1
 * keeps its IDAT (so plain PNG viewers show it), later frames carry the same
 * bytes as fdAT with a sequence number. [comment] goes in a tEXt chunk.
 */
internal fun encodeApng(frames: List<ByteArray>, delaysMs: List<Int>, comment: String): ByteArray {
    require(frames.isNotEmpty() && frames.size == delaysMs.size)
    val parsed = frames.map(::readChunks)
    val ihdr = parsed[0].first { it.type == "IHDR" }.data
    val width = readInt(ihdr, 0)
    val height = readInt(ihdr, 4)
    parsed.forEachIndexed { i, chunks ->
        val h = chunks.first { it.type == "IHDR" }.data
        require(h.contentEquals(ihdr)) { "frame ${i + 1} differs in size or format from frame 1" }
    }
    val out = ByteArrayOutputStream()
    out.write(PNG_SIGNATURE)
    out.chunk("IHDR", ihdr)
    out.chunk("acTL", ByteArrayOutputStream().apply { int(frames.size); int(0) }.toByteArray())
    out.chunk("tEXt", ("Comment\u0000" + comment).toByteArray(Charsets.ISO_8859_1))
    var sequence = 0
    parsed.forEachIndexed { i, chunks ->
        out.chunk("fcTL", ByteArrayOutputStream().apply {
            int(sequence++); int(width); int(height); int(0); int(0)
            short(delaysMs[i]); short(1000)
            write(0) // dispose: none
            write(0) // blend: source
        }.toByteArray())
        for (data in chunks.filter { it.type == "IDAT" }.map { it.data }) {
            if (i == 0) out.chunk("IDAT", data)
            else out.chunk("fdAT", ByteArrayOutputStream().apply { int(sequence++); write(data) }
                .toByteArray())
        }
    }
    out.chunk("IEND", ByteArray(0))
    return out.toByteArray()
}

/** Frame count in an APNG's acTL, or null for a still PNG. For the test's own check. */
internal fun apngFrameCount(apng: ByteArray): Int? =
    readChunks(apng).firstOrNull { it.type == "acTL" }?.let { readInt(it.data, 0) }
