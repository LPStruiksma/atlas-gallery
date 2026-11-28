package nl.atlas.gallery.featureeditor.render

import nl.atlas.gallery.featureeditor.filter.AtlasFilterCatalogue
import nl.atlas.gallery.featureeditor.filter.AtlasLut
import nl.atlas.gallery.featureeditor.filter.CustomFilterStore
import java.io.IOException
import java.io.InputStream

/**
 * EditorAssets for the actual Atlas looks. The owning editor supplies its existing raster
 * assets, packaged asset opener and app-private custom filter store. No secondary store.
 *
 * The two LUT implementations share encoded sRGB and R-fastest lattice order. Convert only
 * verified reference samples; the renderer keeps its own wide-gamut residual handling.
 * Conflicting or missing IDs/hashes resolve to null, so strict rendering refuses the edit.
 * As with PlanRenderer, use one instance on the render thread.
 */
class FilterEditorAssets(
    private val openAsset: (String) -> InputStream,
    private val customStore: CustomFilterStore,
    rasterAssets: EditorAssets = EditorAssets.NONE,
) : EditorAssets by rasterAssets {
    private val presets = mutableMapOf<String, Lut3D>()
    private var lastCustom: Pair<String, Lut3D>? = null

    override fun lut(lutId: String, lutHash: String?): Lut3D? {
        return try {
            if (lutId.startsWith("custom-")) {
                val hash = lutHash ?: return null
                if (lutId != "custom-" + hash) return null
                // Recheck the file even when the decoded table was previously cached.
                val bytes = customStore.read(hash) ?: return null
                lastCustom?.takeIf { it.first == hash }?.second
                    ?: convert(AtlasLut.fromBytes(bytes)).also { lastCustom = hash to it }
            } else {
                val look = AtlasFilterCatalogue.find(lutId) ?: return null
                if (lutHash != null && lutHash != look.sha256) return null
                presets[lutId] ?: AtlasFilterCatalogue.load(lutId, openAsset)?.let(::convert)
                    ?.also { presets[lutId] = it }
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun convert(reference: AtlasLut): Lut3D =
        Lut3D.of(reference.size) { r, g, b, out ->
            val sample = reference.sample(r.toDouble(), g.toDouble(), b.toDouble())
            for (channel in 0..2) out[channel] = sample[channel].toFloat()
        }
}
