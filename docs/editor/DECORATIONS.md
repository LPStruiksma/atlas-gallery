# EDIT-9: typed decorations (goal #629, unit #727)

This is the non-UI decorations backend. It lives in
`nl.atlas.gallery.featureeditor.overlay` and depends on the existing
EDIT-1 `EditRecipe`, `OverlayLayer`, `RenderStage.Overlays`, and `Matrix3`.
No `featureeditor/engine/**` files or Gradle dependencies change.
The EDIT-10 panel will create typed layers and append `OverlayAdd`,
`OverlayUpdate`, or `OverlayRemove` operations.

## Source space, transformations, and persistence

All layer pivots (`centerX`, `centerY`) are normalized original **source space**.
Local points, stroke widths, text sizes and sticker extents are fractions of the
**shorter source image edge** (not display/crop size); local zero is the pivot.
`DecorationPlacement` supplies scale, clockwise rotation and opacity.
`DecorationSpace` composes the EDIT-1 source-pixel `sourceToOutput` matrix
with source-pixel pivot translation, isotropic local scale and rotation.
`localToOutput`/`outputToLocal` use the same matrix in both directions;
`DecorationGeometry.hitTest` and `hitTopmost` use it for selection. Therefore
crop, flip, straighten and rotate after drawing do not detach a decoration from
the source pixels. When a projective warp creates nonuniform scale the strokes
warp with the underlying picture.

`DecorationCodec.encode` projects each typed layer to an existing
`OverlayLayer`, with the full editable model in sorted, versioned string props
(`d.version=1`). `DecorationCodec.decode` checks types/ranges and refuses
unsupported or malformed properties. The existing `RecipeJson` serializes the
props and layer transforms losslessly; save/reopen is a JSON decode followed
by `DecorationCodec.decode`, **not** a bitmap flatten. The asset store keeps
SHA-256 sticker hashes via `EditRecipe.referencedContentHashes()`.
The supplied `StickerAssets.fromBitmap(cutoutOrUserBitmap)` produces a deterministic PNG
payload plus lowercase SHA-256; persist the bytes in the app's existing asset
store under the hash and set `StickerLayer.contentHash` to that hash.
`StickerAssets.decodeVerified` checks the bytes before decoding, so
corrupted or replaced assets cannot silently render.
`DecorationBitmapResolver` resolves content hashes; a missing asset fails export
explicitly instead of silently dropping stickers.

### Typed layers

| Type | Editable attributes |
|---|---|
| `StrokeLayer` | pen, highlighter or stroke eraser, ARGB, width, points and optional pressure |
| `ShapeLayer` | line, arrow, rectangle, ellipse; endpoints, ARGB, width, fill and snapping state |
| `TextLayer` | UTF-8 caption, SANS/SERIF/MONO/SYSTEM face, size, ARGB, LEFT/CENTER/RIGHT, textbox width, NONE/SOLID/ROUNDED background and ARGB |
| `EmojiLayer` | system-rendered emoji glyph and size |
| `StickerLayer` | content-hashed user/cut-out bitmap, width and height |

`DecorationGeometry.smooth` samples a uniform Catmull-Rom spline between
captured pen points (8 segments per pair by default, end-point duplication).
Point pressure, if present, is interpolated and controls per-segment brush
width. `snappedEnd` constrains lines/arrows to 45-degree increments and
rectangles/ellipses to equal sides. The persisted endpoint is the snapped
point; the snapping flag is UI state, not a second correction during export.

`OverlayAdd` appends to z-order; `OverlayUpdate` preserves position.
For bring-to-front use `DecorationGeometry.bringToFront(layer, removeId, addId)`
which generates a remove and an add with distinct operation IDs. Those are
two history operations until the editor has grouped multi-operation gestures;
the panel must not present them as one undo step. For an arbitrary reorder,
remove/re-add the affected layers in the intended order.

## CPU reference raster path

After the existing geometry/colour/filter stages have filled a bitmap:

```kotlin
val stage: RenderStage.Overlays = /* from RenderPlan.build(recipe).stages */
DecorationRasterizer.draw(
    canvas = Canvas(outputBitmap),
    stage = stage,
    sourceWidth = segmentSourceWidth,
    sourceHeight = segmentSourceHeight,
    outputWidth = outputBitmap.width,
    outputHeight = outputBitmap.height,
    bitmaps = DecorationBitmapResolver { sha256 -> assetStore.findBitmap(sha256) },
)
```

Use the source dimensions **of the stage's render-plan segment**, not the
original photo if an OUTPUT-space AI patch split the plan. The stage's
`sourceToOutput` is in source pixels and maps directly to the target bitmap;
the rasterizer must not apply orientation/crop a second time.

`DecorationRasterizer` renders all layers into one transparent Canvas
`saveLayer`. Ordinary layers use nested alpha layers for stable per-layer
opacity; a stroke with `ERASER` uses CLEAR on the shared decorations surface.
Thus it erases preceding strokes/shapes/text/stickers but never photo pixels
or decorations drawn later. It is an **overlay eraser**, not the AI/content-aware
object remover. The source photo stays unchanged. Highlights use alpha
compositing, rather than destructive changes to the photo. Sticker bitmap
resolution stays the asset store's responsibility. This is a correctness
reference; EDIT-2 may reproduce the same layer semantics on GPU for speed.

Bounded inputs: 10,000 points per stroke, at most 4,096 caption code units,
finite transforms/sizes, constrained scales and SHA-256 lowercase hex asset
hashes. No bundled Samsung fonts, packs, or proprietary assets are used.
Atlas's own sticker catalogue can feed the hash-based bitmap resolver.

## Verification

- `test/additive-regressions/edit-9-decorations-727.mjs` makes the
  source contract fail before the implementation exists.
- `app/src/test/java/nl/atlas/gallery/featureeditor/overlay/DecorationLayersTest.kt`
  exercises all five editable types through `RecipeJson`, reopened layer
  updates, smoothing, snapping, z-order, source-space geometry through
  rotate+flip+crop and input validation.
- `app/src/androidTest/java/nl/atlas/gallery/featureeditor/overlay/DecorationRasterAndroidTest.kt`
  checks synthetic Canvas pixels: photo-preserving stroke eraser, translucent
  highlighter, shape fill, text background, content-hashed sticker, and
  explicit missing-sticker error, PNG encoding and tamper rejection.

No editor panel or export integration is claimed here; EDIT-2/EDIT-10 invoke
these APIs in their own units. Instrumented pixel tests require an emulator;
JVM tests do not exercise Android Canvas.
