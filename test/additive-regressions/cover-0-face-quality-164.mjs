import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const read = (path) => readFileSync(resolve(root, path), "utf8");
const base = "app/src/main/java/nl/atlas/gallery/";
const quality = read(base + "featureface/FaceQuality.kt");
const pipeline = read(base + "featureface/embed/FaceEmbeddingPipeline.kt");
const engine = read(base + "featureface/FaceScanEngine.kt");
const store = read(base + "featurepersistence/FaceIndexStore.kt");

assert.match(quality, /object FaceQualityScorer/);
assert.match(quality, /LAPLACIAN|laplacian/i);
assert.match(quality, /MIN_FACE_SHORT_EDGE_PX/);
assert.match(quality, /qualityVersion|VERSION/);
assert.match(pipeline, /embedWithQuality/);
assert.match(engine, /found\.quality/);
assert.match(store, /quality_score REAL/);
assert.match(store, /quality_version INTEGER/);
assert.match(store, /ensureQualityColumns\(db\)/);
assert.match(store, /putQuality\(/);
assert.match(store, /qualityScores: List<FaceQuality\?>/);
assert.match(store, /fun .*quality/i);
console.log("COVER-0 face quality source contract");
