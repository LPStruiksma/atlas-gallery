import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

// IDS-17 (#516): the OD-1 models (HyperSwap 1a 256, InsightFace w600k_r50) come from
// their original hosts at run time, after the user taps setup. Guard the repository
// side of that rule: both manifest entries name OD-1, both URLs are original-host
// release downloads, and no weight file of either model is ever committed.
const root = fileURLToPath(new URL('../../', import.meta.url));
const STORE = join(root, 'app/src/main/java/nl/atlas/gallery/featureidentity/local/IdentityModelStore.kt');
const OD1 = 'https://github.com/LPStruiksma/atlas-gallery/issues/467#issuecomment-6990241396';

test('manifest carries the two OD-1 entries from their original hosts', () => {
  const src = readFileSync(STORE, 'utf8');
  assert.ok(src.includes(`const val OD1 = "${OD1}"`));
  assert.match(src, /url = "https:\/\/github\.com\/facefusion\/facefusion-assets\/releases\/download\/models-3\.3\.0\/hyperswap_1a_256\.onnx"/);
  assert.match(src, /url = "https:\/\/github\.com\/deepinsight\/insightface\/releases\/download\/v0\.7\/buffalo_l\.zip"/);
  assert.equal((src.match(/ownerDecision = OD1/g) || []).length, 2);
  assert.doesNotMatch(src, /huggingface\.co|hf-mirror|resolve\/main/, 'no mirror URL in the manifest');
});

function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    if (name === '.git' || name === 'node_modules' || name === 'build' || name === '.gradle') continue;
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, out); else out.push(p);
  }
  return out;
}

test('no OD-1 weight file is committed', () => {
  const banned = /(hyperswap_1[abc]_256\.onnx|w600k_r50\.onnx|arcface_w600k_r50\.onnx|buffalo_l\.zip)$/;
  const hits = walk(root).filter((p) => banned.test(p));
  assert.deepEqual(hits, []);
});
