package nl.atlas.gallery.featureeditor.render

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import androidx.core.graphics.ColorUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.atlas.gallery.featureeditor.engine.EditOperation
import nl.atlas.gallery.featureeditor.engine.EditRecipe
import nl.atlas.gallery.featureeditor.engine.RenderPlan
import nl.atlas.gallery.featureeditor.filter.AtlasFilterCatalogue
import nl.atlas.gallery.featureeditor.filter.AtlasLut
import nl.atlas.gallery.featureeditor.filter.CustomFilterStore
import nl.atlas.gallery.featureeditor.filter.FilterFromPhoto
import nl.atlas.gallery.featureeditor.filter.LabColourTransfer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class FilterRendererTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val swatches = intArrayOf(
        Color.rgb(0, 0, 0), Color.rgb(48, 48, 48), Color.rgb(128, 128, 128),
        Color.rgb(220, 220, 220), Color.rgb(255, 255, 255),
        Color.rgb(92, 54, 39), Color.rgb(166, 112, 82), Color.rgb(230, 177, 143),
        Color.rgb(250, 50, 25), Color.rgb(25, 240, 40), Color.rgb(30, 50, 245),
        Color.rgb(0, 255, 255), Color.rgb(255, 0, 255), Color.rgb(255, 255, 0),
    )
    private val width = swatches.size * 8
    private val height = 8

    private fun source(): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        for (x in 0 until width) for (y in 0 until height) setPixel(x, y, swatches[x / 8])
    }

    private fun plan(id: String, hash: String?, strength: Double) =
        RenderPlan.build(EditRecipe(width, height, listOf(EditOperation.Filter("f", id, hash, strength))))

    private fun whole(renderer: PlanRenderer, plan: RenderPlan, input: Bitmap) =
        renderer.renderTile(plan, BitmapSource(input), IntRect.size(width, height), PixelSpace.SRGB)

    private fun rgb(c: Int) = doubleArrayOf(Color.red(c) / 255.0, Color.green(c) / 255.0, Color.blue(c) / 255.0)
    private fun deltaE(a: Int, b: Int): Double {
        val x = DoubleArray(3); val y = DoubleArray(3)
        ColorUtils.colorToLAB(a, x); ColorUtils.colorToLAB(b, y)
        return sqrt(x.indices.sumOf { (x[it] - y[it]) * (x[it] - y[it]) })
    }

    private fun maxDifference(a: Bitmap, b: Bitmap): Double {
        var max = 0.0
        for (x in 0 until width) for (y in 0 until height) {
            max = maxOf(max, deltaE(a.getPixel(x, y), b.getPixel(x, y)))
        }
        return max
    }

    private fun customBytes(): ByteArray {
        val source = LabColourTransfer.statistics(swatches.map(::rgb))
        val reference = LabColourTransfer.statistics(swatches.map {
            rgb(Color.rgb((Color.red(it) * 0.9 + 12).toInt(), (Color.green(it) * 0.85 + 9).toInt(), (Color.blue(it) * 0.8 + 8).toInt()))
        })
        return FilterFromPhoto.derive(source, reference)
    }

    @Test fun packagedAndPrivateLooksAgreeWithReferenceAndGpu() {
        check(Build.VERSION.SDK_INT >= 33) { "EDIT-7 proof requires AGSL (API 33+)" }
        val dir = java.nio.file.Files.createTempDirectory(context.filesDir.toPath(), "edit7-").toFile()
        val input = source()
        try {
            val store = CustomFilterStore(dir)
            val bytes = customBytes()
            val custom = store.save(bytes, "Synthetic")
            val assets = FilterEditorAssets({ context.assets.open(it) }, store)
            val looks = AtlasFilterCatalogue.looks.map {
                Triple(it.id, it.sha256, it.previewStrength)
            } + Triple(custom.id, custom.sha256, 0.5)
            val failures = mutableListOf<String>()
            PlanRenderer(assets, CpuPixelBackend()).use { cpu ->
                PlanRenderer(assets, AgslPixelBackend()).use { gpu ->
                    for ((id, hash, previewStrength) in looks) {
                        val reference = if (id == custom.id) AtlasLut.fromBytes(bytes)
                            else requireNotNull(AtlasFilterCatalogue.load(id) { context.assets.open(it) })
                        for (strength in listOf(0.0, previewStrength, 0.5, 1.0).distinct()) {
                            val plan = plan(id, hash, strength)
                            val expected = whole(cpu, plan, input)
                            val actual = whole(gpu, plan, input)
                            try {
                                val maxDeltaE = maxDifference(expected, actual)
                                var referenceError = 0.0
                                for ((index, color) in swatches.withIndex()) {
                                    val sourceRgb = rgb(color)
                                    val wanted = reference.sample(sourceRgb[0], sourceRgb[1], sourceRgb[2], strength)
                                    val got = rgb(expected.getPixel(index * 8 + 4, 4))
                                    for (c in 0..2) referenceError = maxOf(referenceError, abs(got[c] - wanted[c]) * 255)
                                }
                                RenderTestKit.log("EDIT7 id=$id hash=$hash strength=$strength maxDeltaE76=$maxDeltaE referenceError8bit=$referenceError")
                                if (!(maxDeltaE <= 1.0 && referenceError <= 1.0))
                                    failures += "$id strength=$strength maxDeltaE76=$maxDeltaE referenceError8bit=$referenceError"
                                if (strength == 0.0 && RenderTestKit.diff(input, actual).first > 1)
                                    failures += "$id strength zero changed pixels"
                                if (strength == previewStrength) {
                                    val reader = RenderTestKit.reader(width, height)
                                    try {
                                        EditorPreviewSurface(reader.surface, width, height, assets).use { preview ->
                                            preview.render(plan, BitmapSource(input))
                                            val shown = RenderTestKit.readLatest(reader, PixelSpace.SRGB)
                                            try {
                                                val previewDelta = maxDifference(expected, shown)
                                                val pixels = swatches.indices.joinToString(",") { "%06x".format(shown.getPixel(it * 8 + 4, 4) and 0xffffff) }
                                                RenderTestKit.log("EDIT7 preview id=$id hash=$hash strength=$strength maxDeltaE76=$previewDelta swatches=$pixels")
                                                if (previewDelta > 1.0) failures += "$id preview maxDeltaE76=$previewDelta"
                                            } finally { shown.recycle() }
                                        }
                                    } finally { reader.close() }
                                }
                            } finally { expected.recycle(); actual.recycle() }
                        }
                    }
                }
            }
            assertTrue(failures.joinToString("\n"), failures.isEmpty())
        } finally { input.recycle(); dir.deleteRecursively() }
    }

    @Test fun missingOrCorruptHashesNeverFallBackToAnotherLook() {
        val dir = java.nio.file.Files.createTempDirectory(context.filesDir.toPath(), "edit7-invalid-").toFile()
        val input = source()
        try {
            val store = CustomFilterStore(dir)
            val custom = store.save(customBytes())
            val assets = FilterEditorAssets({ context.assets.open(it) }, store)
            val preset = AtlasFilterCatalogue.looks.first()
            assertNotNull(assets.lut(preset.id, null))
            assertNotNull(assets.lut(custom.id, custom.sha256))
            assertNull(assets.lut(custom.id, null))
            assertNull(assets.lut(custom.id, "0".repeat(64)))
            assertNull(assets.lut(preset.id, custom.sha256))
            assertNull(assets.lut("unknown", null))
            val corrupt = FilterEditorAssets({ byteArrayOf(0).inputStream() }, store)
            assertNull(corrupt.lut(preset.id, null))
            custom.file.writeBytes(byteArrayOf(0))
            assertNull(assets.lut(custom.id, custom.sha256))
            PlanRenderer(assets, CpuPixelBackend()).use { renderer ->
                assertThrows(MissingAssetException::class.java) {
                    whole(renderer, plan(custom.id, custom.sha256, 1.0), input)
                }
                custom.file.delete()
                assertThrows(MissingAssetException::class.java) {
                    whole(renderer, plan(custom.id, custom.sha256, 1.0), input)
                }
            }
        } finally { input.recycle(); dir.deleteRecursively() }
    }
}
