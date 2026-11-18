package nl.atlas.gallery.featureaiedit.provider.local

import android.content.Context
import java.io.File

/**
 * The device wiring for AIE-22: LaMa from the bundled assets, app-private file IO. The
 * AI Edit screen (after #431) picks this as the default engine for Erase.
 */
fun localEraseProvider(context: Context, outputDir: File): LocalEraseProvider {
    val app = context.applicationContext
    return LocalEraseProvider(
        inpainterFactory = { OnnxLamaInpainter.open(app) },
        io = AndroidEraseImageIo(outputDir),
    )
}
