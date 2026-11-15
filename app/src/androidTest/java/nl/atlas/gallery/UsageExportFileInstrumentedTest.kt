package nl.atlas.gallery

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class UsageExportFileInstrumentedTest {
    @Test fun namedJsonFileUsesReadOnlyContentGrant() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val json = "{\"schema_version\":1,\"days\":[]}"
        val intent = UsageExportFile.shareIntent(context, json, LocalDate.of(2026, 11, 15))
        try {
            assertEquals(Intent.ACTION_SEND, intent.action)
            assertEquals("application/json", intent.type)
            assertTrue((intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
            @Suppress("DEPRECATION")
            val uri: Uri = requireNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM))
            assertEquals("content", uri.scheme)
            assertEquals("atlas-usage-20261115.json", uri.lastPathSegment)
            assertNotNull(intent.clipData)
            val contents = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use {
                it.readText()
            }
            assertEquals(json, contents)
        } finally {
            UsageExportFile.clear(context)
        }
    }
}
