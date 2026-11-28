import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import assert from 'node:assert/strict';

const root = new URL('../../', import.meta.url);
const read = path => readFileSync(new URL(path, root), 'utf8');
test('EDIT-7 uses verified preset and private custom loaders in the renderer asset adapter', () => {
    const source = read('app/src/main/java/nl/atlas/gallery/featureeditor/render/FilterEditorAssets.kt');
    assert.match(source, /EditorAssets by/);
    assert.match(source, /AtlasFilterCatalogue\.load/);
    assert.match(source, /customStore\.read\(/);
    assert.match(source, /AtlasLut\.fromBytes/);
    assert.match(source, /Lut3D\.of/);
});
test('EDIT-7 device test uses packaged LUTs and a measured perceptual limit', () => {
    const source = read('app/src/androidTest/java/nl/atlas/gallery/featureeditor/render/FilterRendererTest.kt');
    assert.match(source, /AgslPixelBackend\(/);
    assert.match(source, /CpuPixelBackend\(/);
    assert.match(source, /EditorPreviewSurface\(/);
    assert.match(source, /maxDeltaE <= 1\.0/);
    assert.match(source, /MissingAssetException/);
});

test('EDIT-7 metric and preview evidence uses the retained emulator log tag', () => {
    const source = read('app/src/androidTest/java/nl/atlas/gallery/featureeditor/render/FilterRendererTest.kt');
    const kit = read('app/src/androidTest/java/nl/atlas/gallery/featureeditor/render/RenderTestKit.kt');
    const collector = read('scripts/ci_android_upgrade_emulator.sh');
    assert.match(source, /RenderTestKit\.log\("EDIT7 id=.*maxDeltaE76=.*referenceError8bit=/);
    assert.match(source, /RenderTestKit\.log\("EDIT7 preview id=.*maxDeltaE76=.*swatches=/);
    assert.match(kit, /const val TAG = "ATLAS_EDIT2"/);
    assert.match(collector, /adb logcat -d -s ATLAS_EDIT2:I/);
});
