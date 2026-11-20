package nl.atlas.gallery.featureeditor.engine

/** Synthetic recipes for engine tests. Hashes are made-up hex, not real content. */
internal object EngineFixtures {
    fun hash(seed: Char): String = seed.toString().repeat(64)

    /** One of every operation type, in a plausible editing order. */
    fun everyOperation(): EditRecipe = EditRecipe(
        sourceWidth = 4000,
        sourceHeight = 3000,
        operations = listOf(
            EditOperation.RasterPatch("op-1", PatchTool.ERASE, hash('a'), NormRect(0.1, 0.1, 0.3, 0.25), 800, 450, hash('b')),
            EditOperation.Adjust("op-2", AdjustParam.EXPOSURE, 0.25),
            EditOperation.Adjust("op-3", AdjustParam.CONTRAST, -0.1),
            EditOperation.Rotate90("op-4", 1),
            EditOperation.Flip("op-5", FlipAxis.HORIZONTAL),
            EditOperation.Straighten("op-6", 3.5),
            EditOperation.Perspective("op-7", Quad.fromKeystone(0.4, -0.2)),
            EditOperation.Crop("op-8", NormRect(0.1, 0.2, 0.9, 0.8), "free"),
            EditOperation.Filter("op-9", "atlas-warm", hash('c'), 0.6),
            EditOperation.OverlayAdd(
                "op-10",
                OverlayLayer("text-1", OverlayKind.TEXT, 0.5, 0.4, 1.5, -12.0, 0.9, null,
                    mapOf("text" to "Zomer \"24\"\n✓", "font" to "atlas-sans", "colour" to "#ffffff")),
            ),
            EditOperation.OverlayAdd("op-11", OverlayLayer("sticker-1", OverlayKind.STICKER, 0.2, 0.8, contentHash = hash('d'))),
            EditOperation.OverlayUpdate("op-12", OverlayLayer("sticker-1", OverlayKind.STICKER, 0.25, 0.75, 2.0, contentHash = hash('d'))),
            EditOperation.OverlayRemove("op-13", "sticker-1"),
            EditOperation.RasterPatch("op-14", PatchTool.AI_EDIT, hash('e'), NormRect.FULL, 2400, 1800, space = PatchSpace.OUTPUT),
            EditOperation.RasterPatch("op-15", PatchTool.FACE_SWAP, hash('f'), NormRect(0.4, 0.2, 0.6, 0.45), 512, 512, hash('1'), PatchSpace.OUTPUT),
            EditOperation.ResetAdjustments("op-16"),
            EditOperation.Adjust("op-17", AdjustParam.SHARPNESS, 0.3),
            EditOperation.ResetGeometry("op-18"),
            EditOperation.Rotate90("op-19", -1),
        ),
    )
}
