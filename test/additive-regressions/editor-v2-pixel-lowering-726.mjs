import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

test('EDIT-8 pixel-program lowering explicitly ignores all deferred v2 stages', () => {
  const renderer = readFileSync('app/src/main/java/nl/atlas/gallery/featureeditor/render/SegmentProgram.kt', 'utf8');
  const skipBranch = renderer.match(
    /is RenderStage\.ShapeCrop,\s*is RenderStage\.Resize,\s*is RenderStage\.Curves,\s*is RenderStage\.ColorMix,\s*is RenderStage\.SpotColor\s*->\s*Unit/
  );
  assert.ok(skipBranch, 'explicitly skip v2 metadata-only stages in SegmentProgram until pixel implementations exist');
});
