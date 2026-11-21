package nl.atlas.gallery.featurevisual.tags.store

import android.content.Context
import android.os.SystemClock
import nl.atlas.gallery.featurevisual.VisualScanIndexPort
import nl.atlas.gallery.featurevisual.store.VisualIndexedVector
import nl.atlas.gallery.featurevisual.store.VisualVectorCursor
import nl.atlas.gallery.featurevisual.tags.TagMatrixCodec
import nl.atlas.gallery.featurevisual.tags.TagScorer

/**
 * TAGS-1 (#732): what the People/visual worker holds. Cheap to construct:
 * the tag matrix (125 KB asset) and the tag database open on first use, on
 * the worker thread, never at app start. Any failure to open disables tagging
 * for this process and never touches the visual index.
 */
class VisualTagHook(
    private val open: () -> VisualTagger,
    /** Deletes the tag data when it was never opened in this process. */
    private val deleteUnopened: () -> Unit = {},
) {
    private var tagger: VisualTagger? = null
    private var failed = false

    @Synchronized private fun tagger(): VisualTagger? {
        if (failed) return null
        tagger?.let { return it }
        return try {
            open().also { tagger = it }
        } catch (_: RuntimeException) {
            failed = true
            null
        } catch (_: java.io.IOException) {
            failed = true
            null
        }
    }

    /** The scan's index port, tagging every batch the index accepts. */
    fun wrap(port: VisualScanIndexPort): VisualScanIndexPort =
        TaggingIndexPort(port) { writes, videos -> tagger()?.tagPublished(writes, videos) }

    /** One bounded sweep step; null when tagging is unavailable or failed this time. */
    fun sweepStep(
        epoch: String, modelVersion: String,
        read: (after: VisualVectorCursor?, limit: Int) -> List<VisualIndexedVector>,
        canContinue: () -> Boolean,
    ): TagSweepResult? {
        val t = tagger() ?: return null
        return try {
            t.sweepStep(epoch, modelVersion, read, canContinue)
        } catch (_: RuntimeException) {
            null // SQLite error: retried on a later step
        }
    }

    /**
     * The user deleted the visual index: every tag row goes too. Best effort;
     * a failure leaves rows that the next sweep (or fence) clears.
     */
    @Synchronized fun deleteAll() {
        try {
            tagger?.deleteAll() ?: deleteUnopened()
        } catch (_: RuntimeException) {
        }
    }

    companion object {
        const val MATRIX_ASSET = "models/siglip2_tags/tags.bin"

        fun forApp(context: Context): VisualTagHook {
            val app = context.applicationContext
            return VisualTagHook(
                open = {
                    val matrix = app.assets.open(MATRIX_ASSET).use { TagMatrixCodec.decode(it.readBytes()) }
                    VisualTagger(TagScorer(matrix), VisualTagStore(app),
                        clockElapsedMs = { SystemClock.elapsedRealtime() },
                        wallClockMs = { System.currentTimeMillis() })
                },
                deleteUnopened = { app.deleteDatabase(VisualTagStore.DB_NAME) },
            )
        }
    }
}
