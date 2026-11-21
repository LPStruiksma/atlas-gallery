# Atlas editor engine (EDIT-1)

Package `nl.atlas.gallery.featureeditor.engine`, pure Kotlin with no Android imports and no new dependencies. It is the core of the Atlas Editor goal ([#629](https://github.com/LPStruiksma/atlas-gallery/issues/629)), built in unit [#631](https://github.com/LPStruiksma/atlas-gallery/issues/631).

The engine describes edits. It never touches pixels. EDIT-2 renders a `RenderPlan` (the preview at screen size, the export at full size) and EDIT-3 saves it. Because both use the same plan, the preview and the saved file can't disagree.

## One recipe, one history, one save path

| Type | Role |
|---|---|
| `EditRecipe` | Source size plus the ordered `EditOperation`s. Versioned (`schemaVersion`, currently 1). |
| `EditOperation` | One user action with a stable `id`. Covers geometry (`Rotate90`, `Flip`, `Straighten`, `Perspective`, `Crop`, `ResetGeometry`), colour (`Adjust`, `ResetAdjustments`), `Filter` (LUT and strength), overlay layers (`OverlayAdd`, `OverlayUpdate`, `OverlayRemove`) and `RasterPatch`. |
| `EditHistory` | Immutable undo/redo over a recipe, with `preview`/`commit` for drags and `setComparing` for press-and-hold compare. |
| `RenderPlan` | The recipe folded into fixed-order, GPU-independent stages at an exact output size. |
| `RecipeJson` | Deterministic JSON for recipes and histories, with migration hooks. |
| `GeometryState`, `Matrix3` | Geometry maths: crop fitting, homographies, forward and inverse mapping. |

Every tool goes through the same `EditHistory`: adjust, crop, filters, decorations, and (through EDIT-5) AI Edit, Face Swap and Erase. An AI tool returns its pixels to a content-addressed store and adds **one** `RasterPatch` (tool, `space`, SHA-256 `contentHash`, optional `maskHash`, normalized `rect`, native pixel size). AI Edit and Face Swap receive the fully rendered working picture (`docs/editor/AI_TOOLS.md`), so they add `space = OUTPUT` patches. Tools that work on the unedited photo add `space = SOURCE` patches. That patch can be undone, redone and saved like any other step. `EditHistory.referencedContentHashes()` lists every hash that any undo or redo state may still need, so the patch store knows what to keep.

### History rules

- `apply(op)` is a committed step, used for taps such as rotate, flip or applying an AI result.
- `preview(op)` shows a live value and replaces any earlier preview. `commit()` turns it into **one** history entry, so a 10-second slider drag is one undo step.
- `undo()` during a drag cancels only the drag. A new commit clears redo.
- `setComparing(true)` makes `displayed` the untouched original. It never changes the history, and any edit ends the compare.
- When the editor reopens a saved Atlas edit, the history starts from that recipe, so its steps can be undone back to the original. `isDirty` compares against the starting recipe.
- `revertAll()` clears every step and any live drag. Redo brings the committed steps back one at a time.

## Coordinates and stage order

All rectangles and points are normalized to `0..1` (y grows downwards).

- **Source space:** the decoded photo. `SOURCE` raster patches, overlay layers and the perspective quad live here, so they stay attached to the picture when the user later rotates, flips, straightens or crops.
- **Display canvas:** the source after perspective, straighten and orientation. Its size is the source size, with the axes swapped by odd quarter turns. The crop rectangle lives here.

- **Output space:** the rendered result of every earlier step. `OUTPUT` raster patches live here.

`RenderPlan.build(recipe, maxLongEdge)` always emits stages in this order, whatever order the user worked in. Stages that change nothing are left out, so an empty recipe produces only `Decode`.

1. `Decode`: the source.
2. `RasterPatches`: `SOURCE` patches in history order.
3. `Geometry`: one projective `sourceToOutput` matrix (perspective, then straighten, orientation, crop and output scale) into an exact `width x height`. Renderers inverse-map each output pixel.
4. `ColourAdjust`: every non-zero control, in `AdjustParam` declaration order. The last value set wins, and `ResetAdjustments` clears them all.
5. `Lut`: the filter at its strength. A strength of 0 or no LUT leaves the stage out.
6. `Overlays`: layers in z-order (the order they were added; updates keep their place), placed through the same geometry matrix. They are not colour-adjusted.

`maxLongEdge` scales the output down for previews and never scales it up.

### Output-space patches (AI Edit, Face Swap)

A recipe with `OUTPUT` patches is split at the last one:

1. `Base`: every earlier step, rendered as its own full-resolution plan. It may itself contain an earlier split.
2. `OutputPatch`: the patch is composited over that render at its output-normalized `rect`, as `out = a * patch + (1 - a) * before` when it has a mask.
3. Stages 2 to 6 for the steps after the patch, with the composite as their source.

Earlier crop, colour, filter and overlay steps are therefore applied to the AI result exactly once, before the AI tool saw it, never again after it. A masked partial patch keeps the earlier steps underneath it. Undoing the patch gives back exactly the plan of the earlier steps, which is what AI_TOOLS.md §4 option (a) asks for. The base doesn't change while later steps do, so EDIT-2 can cache its texture. `plan.normalizedSourceToOutput` and `plan.outputToSource` compose every segment, from the original source to the final output. `plan.geometry` is the geometry of the last segment, which is what the crop tool edits.

## Geometry

- **Orientation** is one element of the rectangle's 8-element symmetry group, kept in the canonical form "mirror in source, then turn clockwise". Any sequence of user rotations and flips folds into it. Rotating or flipping moves the crop with the display.
- **Straighten** is stored as the angle the user sees (`-45..45`, clockwise positive). Flipping negates the visible angle while the source-space rotation stays the same, so the photo content doesn't turn.
- **Perspective** is a convex, clockwise quad in source space that the homography pulls to the picture corners. `Quad.isValid()` also rejects quads whose homography would push part of the picture through the vanishing line, and quads that cover less than 5 % of the picture (an off-picture quad would render blank). `Quad.fromKeystone(vertical, horizontal)` maps two sliders onto a quad (full strength moves a corner 25 %). When both sliders together would wrap the picture, it scales them down to the strongest valid value.
- **Crop:** `requestedCrop` is what the user chose. `effectiveCrop` shrinks it, keeping its aspect, until it fits inside `cropRegion` (the warped picture clipped to the canvas), so the output never shows empty corners. Because only the effective crop shrinks, moving straighten back to 0 restores the user's crop. `largestCrop(aspect)` gives the centred auto-crop that Samsung's straighten applies.
- **Taps:** `GeometryState.outputToSource` and `sourceToOutputNormalized` map points within one segment, and `RenderPlan.outputToSource` maps them across every segment. Property tests check that a round trip stays within 0.5 px.
- **Ultra HDR:** `RenderPlan.gainmap(w, h)` returns a `Geometry` stage for a gainmap at any resolution. It uses the same normalized transform across every segment, and the output size scales with the gainmap. Gainmaps therefore follow every geometry step. Colour stages apply to the SDR base, and EDIT-2 and EDIT-3 decide how to re-weight the gainmap.

## JSON

`RecipeJson.encode` always writes the same bytes for the same recipe: fixed key order, sorted overlay props, no whitespace, integral numbers without a fraction, and no `-0`. A recipe looks like this:

```json
{"schema":"atlas.edit-recipe","version":1,"source":{"width":4000,"height":3000},"operations":[{"id":"op-1","type":"rotate90","turns":1}]}
```

`decode` is strict. It accepts RFC 8259 only and rejects duplicate keys, nesting deeper than 32 levels and documents over 8 MB. Every recipe is validated after decoding. Error messages never echo the input, because captions and text layers are personal.

**Schema changes:** bump `EditRecipe.CURRENT_SCHEMA_VERSION` and add a `RecipeMigration(fromVersion)` to `RecipeJson.MIGRATIONS`. Decoding upgrades older files step by step and refuses newer ones ("Recipe from a newer Atlas"). It never guesses. `encodeHistory`/`decodeHistory` store the recipe and the redo stack so a session can be restored after process death. A drag that was in progress is not stored.

## Performance

Planning is plain arithmetic on a few doubles per operation. In a JVM test, a 500-operation recipe for a 12 MP photo plans in well under 50 ms, so the engine stays a small part of the 300 ms open and 2 s save budgets in #629. Pixel performance belongs to EDIT-2.

## Tests

`app/src/test/java/nl/atlas/gallery/featureeditor/engine/` contains:

- JSON round-trip, determinism and migration tests;
- undo, redo, drag, compare and AI-patch history tests;
- geometry property tests (3,000 random geometries: the crop stays inside the warped image, the largest crop keeps its aspect, inverse after forward is within 0.5 px, the gainmap matches the base);
- hand-checked recipe-to-plan golden tests, including an AI result that is not edited twice.

# Rendering (EDIT-2)

Package `nl.atlas.gallery.featureeditor.render`, built in unit [#632](https://github.com/LPStruiksma/atlas-gallery/issues/632). It turns a `RenderPlan` into pixels: a live preview on a `Surface` and a full-resolution export, tile by tile. No new dependencies: platform AGSL (`RuntimeShader`), `HardwareRenderer`, `ImageReader` and `BitmapRegionDecoder`.

## One program, two backends

`SegmentProgram.of(plan)` lowers the plan's last segment into what a pixel pass needs: the output-to-source pixel matrix, the twelve colour values, the LUT stage, source-space patches and overlay layers. Two backends execute it, statement for statement:

| Backend | Where | Role |
|---|---|---|
| `AgslPixelBackend` / `AgslProgram` | API 33+, the GPU | Preview and export. One AGSL shader; inputs are bound with `setInputBuffer`, so the GPU reads bytes raw and Android's colour management never touches them. |
| `CpuPixelBackend` / `CpuRenderer` | any API, pure Kotlin | The reference the GPU is tested against, JVM tests, and the backend on API 29 to 32. |

Per output pixel (centre `x + 0.5, y + 0.5`): inverse-map through the matrix; outside the source is transparent; bilinear sampling of the four texels in **linear light** (unpremultiply, decode, convert primaries, premultiply, blend); Definition and Sharpness detail taps; the colour controls in `AdjustParam` order; the LUT; encode to the output space and round to 8 bits.

## Colour

- Working space: **linear Display P3, extended range** (`ColourScience`). sRGB sources convert into it exactly, Display P3 sources keep their gamut, and values below 0 or above 1 survive every stage. Clamping happens once, at the final encode.
- Output space follows the export (`PixelSpace.SRGB` or `DISPLAY_P3`, normally the source's). The preview uses sRGB unless the window is wide-gamut.
- Controls (`ColourOps`, all identity at 0, none clamps): exposure ±2 stops; brightness as a gamma on encoded values; contrast around encoded mid-grey; highlights and shadows as luminance-weighted gains; temperature and tint as white-balance gains; saturation around P3 luminance; hue as a rotation about the grey axis; Definition (8 taps at 0.4 % of the output's long edge) and Sharpness (4 taps at 1 px) as encoded-luma detail measured on the warped picture before colour; vignette by distance from the centre.
- LUTs (`Lut3D`, `.cube` parser) are authored on encoded sRGB. The renderer looks up the clamped colour and adds back what was clamped away, so a LUT cannot clip P3 or above-white colour. On the GPU the LUT is an `RGBA_F16` strip.
- **Ultra HDR**: `PlanRenderer.renderGainmap` renders the gainmap through `RenderPlan.gainmap`'s geometry with raw values (no transfer, colour controls or LUT), so HDR follows every rotate, straighten, perspective and crop. Colour controls apply to the SDR base only; the gainmap is not re-weighted (the HDR rendition scales with the edited base). EDIT-3 attaches it to the saved file.

## Inputs and layers

- `SourceProvider` supplies the segment's input. `BitmapSource` is an in-memory picture or downsampled preview proxy (its region scale tells the shader). `RegionDecoderSource` decodes only the region a tile needs, subsampled by a power of two when the output is smaller.
- `SOURCE` raster patches are composited onto the input region through their masks before the pixel pass. After an `OUTPUT` patch, the input is the **base plan rendered region by region** with the patch composited on, so AI results are tiled too and never edited twice.
- Overlay layers come from `EditorAssets.overlay` (EDIT-9 supplies the text, stroke and shape rasteriser) and are drawn after the pixel pass through the same geometry matrix, uncoloured.
- Exports are strict: a missing LUT, patch or mask, or any overlay layer `EditorAssets.overlay` cannot draw (sticker art or a text, stroke or shape layer with no rasteriser yet), throws `MissingAssetException` instead of saving a different picture. The preview leaves it out on both backends.

## Tiling

`PlanRenderer.export` walks `TileGrid` tiles (1024 px). Each tile asks only for `SegmentProgram.sourceRegionFor(tile)`: the tile's outline, padded by the detail taps, mapped back to the source, plus 2 px for bilinear. Every pixel reads the same texels whichever tile it is in, so tiles are seamless and agree with the whole frame. A 50 MP export holds one tile, one source region (under 2 MP) and the `TileSink`; `BitmapTileSink` assembles the output bitmap EDIT-3 encodes.

## Preview

`EditorPreviewSurface(surface, width, height, assets)` owns a `HardwareRenderer` on the editor's `Surface`. Each `render(plan, proxySource)` records the AGSL pass and overlays into a `RenderNode`, fitted and centred, and syncs one frame; `onFrameCommitted` reports render-to-commit time. The plan is built at display size (`RenderPlan.build(recipe, maxLongEdge)`), so slider and gesture changes only rebind uniforms.

## Tests

- JVM (`app/src/test/.../featureeditor/render/`): colour maths and identities, P3 kept out of the sRGB gamut, LUT exactness, `.cube` parsing and refusals, exact quarter turns, tiled CPU render equal to the whole frame, 50 MP tile regions bounded, and the AGSL source declaring every bound uniform with `ColourOps` constants.
- Device (`app/src/androidTest/.../featureeditor/render/`): GPU against the CPU reference per control, geometry and LUT (at most 3/255); GPU tiles against the whole frame (at most 1/255); **preview surface against tiled export from a region decoder (at most 1/255)**; P3 bytes unchanged through an identity LUT; gainmap geometry; patches, output patches and stickers, tiled. `EditorRendererBudgetTest` prints `ATLAS_EDIT2` records (in the BUDGETS.md format, `device_kind=EMULATOR`) for a 12 MP export, a 50 MP procedural export without OOM, and a 10 s scripted slider drag; CI prints them in the "ATLAS_EDIT2" log group. Emulator figures are not phone figures; the S24+ canary decides the budgets.
