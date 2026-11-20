# Atlas editor engine (EDIT-1)

Package `nl.atlas.gallery.featureeditor.engine`, pure Kotlin with no Android imports and no new dependencies. It is the core of the Atlas Editor goal ([#629](https://github.com/LPStruiksma/atlas-gallery/issues/629)), built in unit [#631](https://github.com/LPStruiksma/atlas-gallery/issues/631).

The engine describes edits. It never touches pixels. EDIT-2 renders a `RenderPlan` (the preview at screen size, the export at full size) and EDIT-3 saves it. Because both use the same plan, the preview and the saved file can't disagree.

## One recipe, one history, one save path

| Type | Role |
|---|---|
| `EditRecipe` | Source size plus the ordered `EditOperation`s. Versioned (`schemaVersion`, currently 2; v1 decodes through a lossless migration). |
| `EditOperation` | One user action with a stable `id`. Covers geometry (`Rotate90`, `Flip`, `Straighten`, `Perspective`, `Crop`, `ResetGeometry`), colour (`Adjust`, `ResetAdjustments`), `Filter` (LUT and strength), overlay layers (`OverlayAdd`, `OverlayUpdate`, `OverlayRemove`) and `RasterPatch`, plus v2 `Curves`, `ColorMix`, `SpotColor`, `ShapeCrop`, `Resize`. |
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
4. `ShapeCrop`: one display-canvas lasso/preset mask with a normalized canvas-to-output matrix; the outside is transparent or the specified solid RGB fill.
5. `Resize`: metadata for the output target already sized by `Geometry`, not an extra scaling pass.
6. `ColourAdjust`: every non-zero basic control in `AdjustParam` order.
7. `Curves`: RGB composite and per-channel monotone knot lists.
8. `ColorMix`: preset and sampled colour HSL adjustments.
9. `SpotColor`: kept/removed sampled hues, tolerance, optional brush-erase mask.
10. `Lut`: the filter at its strength (omitted for zero strength or null LUT).
11. `Overlays`: layers in z-order, placed through the same geometry matrix; not colour-adjusted.

The fixed order does not depend on operation history order. Each absolute v2 tool uses its latest operation; `ResetAdjustments` clears curves, mix and spot colour as well as basic adjustments. `ResetGeometry` clears the shape mask and requested resize. Neutral stages are omitted. EDIT-2 (or any renderer that predates these stages) must **skip unknown stages**, not reject or silently reinterpret old stages; pixel implementations are deliberately out of scope for EDIT-8.

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

`RecipeJson.encode` always writes the same bytes for the same recipe: fixed key order, sorted overlay props, no whitespace, integral numbers without a fraction, and no `-0`. A v2 recipe looks like this:

```json
{"schema":"atlas.edit-recipe","version":2,"source":{"width":4000,"height":3000},"operations":[{"id":"op-1","type":"rotate90","turns":1}]}
```

`decode` is strict. It accepts RFC 8259 only and rejects duplicate keys, nesting deeper than 32 levels and documents over 8 MB. Every recipe is validated after decoding. Error messages never echo the input, because captions and text layers are personal.

**Schema changes:** `EditRecipe.CURRENT_SCHEMA_VERSION = 2`. `RecipeJson.MIGRATIONS` contains `RecipeMigration(1)`, which copies every v1 field and operation unchanged and advances only `version: 1` to `2`. Decoding upgrades older files step by step and refuses newer ones ("Recipe from a newer Atlas"). A v1 fixture still produces the identical render plan as before. The encoder always writes v2; it does not rewrite a v1 file byte-identically, but v2 encode/decode round-trips byte-identically. `encodeHistory`/`decodeHistory` preserve recipe and redo stack across process death. A drag in progress is not stored.

### V2 operations and validation

| Operation | Data and bounds | Render target |
|---|---|---|
| `Curves` | Four lists (`rgb`, `red`, `green`, `blue`) of 2–32 knots `[x,y]` in 0..1, with x strictly increasing from 0 to 1 and y nondecreasing | `Curves`, after basic light/colour adjustments |
| `ColorMix` | Up to 8 unique preset hues (red, orange, yellow, green, cyan, blue, purple, magenta), plus at most 3 unique eyedropper `#rrggbb` colours; each has hue/saturation/luminance offsets in -1..1 | `ColorMix` |
| `SpotColor` | Lists of unique `keep` and `remove` samples (canonical `#rrggbb`), tolerance in 0..1 and optional SHA-256 `brushMaskHash` for erase; the mask needs at least one sample | `SpotColor` (desaturate except kept hues, and suppress removed hues) |
| `ShapeCrop` | Exactly one lasso polygon (3–256 finite, non-self-intersecting normalized display-canvas points) or named Atlas preset; outside `transparent` or `solid` with `#rrggbb` fill | `ShapeCrop`, with normalized canvas-to-output transform |
| `Resize` | Exactly one output `longEdge` (1–32768 px) or `percentage` (1–400%). Aspect ratio preserved; `maxLongEdge` preview cap is applied afterward | `Geometry` target dimensions plus a metadata-only `Resize` stage |

Both spot-colour brush hashes and raster/filter/overlay hashes are included in `referencedContentHashes()` for undo/redo retention. V2 modes, bounds and determinism are exercised by pure-JVM synthetic tests. No actual LUT, colour math, masks or resize pixel kernel is shipped by this unit. Samsung control ranges, preset-shape catalogue and pixel output require their later canary/render work; this schema is an Atlas representation, not a claim of S24+ UI parity.

## Performance

Planning is plain arithmetic on a few doubles per operation. In a JVM test, a 500-operation recipe for a 12 MP photo plans in well under 50 ms, so the engine stays a small part of the 300 ms open and 2 s save budgets in #629. Pixel performance belongs to EDIT-2.

## Tests

`app/src/test/java/nl/atlas/gallery/featureeditor/engine/` contains:

- JSON round-trip, determinism and migration tests;
- undo, redo, drag, compare and AI-patch history tests;
- geometry property tests (3,000 random geometries: the crop stays inside the warped image, the largest crop keeps its aspect, inverse after forward is within 0.5 px, the gainmap matches the base);
- hand-checked recipe-to-plan golden tests, including an AI result that is not edited twice.
