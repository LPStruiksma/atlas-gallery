package nl.atlas.gallery

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Named file transfer occurs only after an explicit user export action. */
internal object UsageExportFile {
    private const val FOLDER = "usage-export"

    fun shareIntent(context: Context, json: String, today: LocalDate = LocalDate.now()): Intent {
        clear(context)
        val folder = File(context.cacheDir, FOLDER)
        check(folder.isDirectory || folder.mkdirs()) { "Cannot prepare usage export" }
        val name = "atlas-usage-" + today.format(DateTimeFormatter.BASIC_ISO_DATE) + ".json"
        val file = File(folder, name)
        file.writeText(json, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".usagefiles", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, "ATLAS usage", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun clear(context: Context) {
        File(context.cacheDir, FOLDER).listFiles()?.forEach {
            if (it.isFile && it.name.startsWith("atlas-usage-") && it.name.endsWith(".json")) {
                it.delete()
            }
        }
    }
}
