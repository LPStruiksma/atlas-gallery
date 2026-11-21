import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

// EDIT-9 source-contract regression. The JVM/instrumented tests exercise behaviour.
const read = (path) => readFileSync(new URL('../../' + path, import.meta.url), 'utf8');
const root = 'app/src/main/java/nl/atlas/gallery/featureeditor/overlay/';

test('EDIT-9 keeps every decoration editable through typed overlay properties', () => {
  const models = read(root + 'DecorationLayers.kt');
  for (const type of ['StrokeLayer', 'ShapeLayer', 'TextLayer', 'EmojiLayer', 'StickerLayer']) {
    assert.match(models, new RegExp('data class ' + type + '\\b'));
  }
  assert.match(models, /object DecorationCodec/);
  assert.match(models, /OverlayLayer/);
  assert.match(models, /contentHash/);
  assert.match(models, /fun decode\(/);
  assert.match(models, /fun encode\(/);
});

test('EDIT-9 draws on an isolated overlay so erasing does not destroy source pixels', () => {
  const raster = read(root + 'DecorationRasterizer.kt');
  assert.match(raster, /saveLayer\(/);
  assert.match(raster, /PorterDuff\.Mode\.DST_OUT/);
  assert.match(raster, /sourceToOutput/);
  assert.match(raster, /Bitmap/);
  assert.match(raster, /Canvas/);
});

test('EDIT-9 has source-space hit tests, smoothing, and save/re-edit evidence', () => {
  const math = read(root + 'DecorationGeometry.kt');
  const unit = read('app/src/test/java/nl/atlas/gallery/featureeditor/overlay/DecorationLayersTest.kt');
  const pixels = read('app/src/androidTest/java/nl/atlas/gallery/featureeditor/overlay/DecorationRasterAndroidTest.kt');
  assert.match(math, /fun hitTest/);
  assert.match(math, /fun outputToLocal/);
  assert.match(math, /fun smooth/);
  assert.match(unit, /RecipeJson\.decode/);
  assert.match(unit, /OverlayUpdate/);
  assert.match(pixels, /getPixel\(/);
  assert.match(read('docs/editor/DECORATIONS.md'), /source.space/i);
});
