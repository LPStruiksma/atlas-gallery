package nl.atlas.gallery.featureeditor.filter

import java.io.InputStream
import java.security.MessageDigest

/** Stable, original, generated Atlas looks; preview strengths protect thumbnails. */
data class AtlasFilter(
    val id: String, val displayName: String, val previewStrength: Double,
    val sha256: String, val byteCount: Int
)

object AtlasFilterCatalogue {
    val looks = listOf(
        AtlasFilter("atlas-clear", "Clear Air", 0.68, "48f32565292e455b88afb3d65e14b330f0e897f33ec0e5a98230622ecbd30d43", 107821),
        AtlasFilter("atlas-hearth", "Hearth", 0.62, "66141ee57ffa4b2e837a6dca5273f4c54eacd4e4669d25e0a7e9185658aa000f", 107821),
        AtlasFilter("atlas-drift", "Blue Drift", 0.62, "1535d8687865ce4b402ca5a5b245eb8d9f6c89b3cec9323c2e8654fcdf1e1fa4", 107821),
        AtlasFilter("atlas-moss", "Moss Path", 0.64, "f2f827ef272ea0b4ed03413f8112c68564abee2dfa7ca93741b93141764614b4", 107821),
        AtlasFilter("atlas-amber", "Amber Hour", 0.48, "fd8c08e2cf642f709a1f0f9c7ccdaa2e2c77193cb78e9d59cbf76fa9506b0b15", 107821),
        AtlasFilter("atlas-slate", "Slate", 0.5, "4418652e80a5075ad40ab5dd8af89d470a96197e3c7908b22d8b5bb170d7159e", 107821),
        AtlasFilter("atlas-linen", "Linen", 0.66, "418b5dc070e2faf86b512cc893036dd3a949a6ccc2e5f98977ffa04ffcd50279", 107821),
        AtlasFilter("atlas-orchard", "Orchard", 0.58, "8b1f218ec9fa0c431120eff32fefc050793069990c57519dcea3ce5e12c4e05d", 107821),
        AtlasFilter("atlas-coast", "Quiet Coast", 0.64, "2a86f90bac520ee9e02d4d094b31829af43ebea560a37c98e31217c1c9661a98", 107821),
        AtlasFilter("atlas-copper", "Copper Leaf", 0.54, "136c59ba7ea07ba69da31d4c46d6cb7677251fee21915f9f08a6cfc0db12883e", 107821),
        AtlasFilter("atlas-dawn", "Dawn Glass", 0.68, "815f694660fa529d3d46b22f1342127ddfe53c12d8e0593d47aeaf37b290c561", 107821),
        AtlasFilter("atlas-pine", "Pine Shade", 0.62, "b09ffb758e2aa7748e68b21c12bc9af8b693d9a6a7deb98a3a353a5cfdb03baa", 107821),
        AtlasFilter("atlas-bloom", "Soft Bloom", 0.62, "cc8a5a6eabc07d220884f5a6b8d3048a2e2221966f8978ce8eac073f25272f46", 107821),
        AtlasFilter("atlas-ink", "Atlas Ink", 0.28, "9f49729043b5f3a6046c0bcd21553da01ef72f6c585b62bb63f902a4ec23e595", 107821)
    )
    private val byId = looks.associateBy(AtlasFilter::id)
    fun find(id: String): AtlasFilter? = byId[id]

    /**
     * Supply context.assets::open on Android or a file-backed opener in JVM tests.
     * Missing assets throw IOException; altered assets fail closed rather than rendering.
     */
    fun load(id: String, openAsset: (String) -> InputStream): AtlasLut? {
        val look = find(id) ?: return null
        val bytes = openAsset("editor/luts/" + look.id + ".lut").use { it.readBytes() }
        require(bytes.size == look.byteCount) { "LUT length mismatch" }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        require(hash == look.sha256) { "LUT content mismatch" }
        val lut = AtlasLut.fromBytes(bytes)
        require(lut.size == 33) { "LUT dimension mismatch" }
        return lut
    }
}
