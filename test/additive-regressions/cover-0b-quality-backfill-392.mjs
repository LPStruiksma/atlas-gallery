import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

// COVER-0b (#392): source-size provenance, NULL-only maintenance and paired throughput proof.
const root = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const read = (file) => readFileSync(resolve(root, file), "utf8");
const src = "app/src/main/java/nl/atlas/gallery/";
const reader = read(src + "featureface/AndroidMediaFaceFrameReader.kt");
const pipeline = read(src + "featureface/embed/FaceEmbeddingPipeline.kt");
const embedder = read(src + "featureface/onnx/OnnxFaceEmbedder.kt");
const store = read(src + "featurepersistence/FaceIndexStore.kt");
const runtime = read(src + "featureface/work/PeopleScanWorkRuntime.kt");
const worker = read(src + "featureface/work/PeopleScanWorker.kt");
const host = read(src + "PeopleDiscoveryHost.kt");
const bench = read("app/src/androidTest/java/nl/atlas/gallery/benchmark/FaceScanThroughputBenchmarkTest.kt");
const evidence = read("docs/people/FACE_QUALITY_V2.md");

assert.match(reader, /sourceScale/);
assert.match(pipeline, /embedWithQuality\(bitmap, face,\s*\(frame as AndroidBitmapFaceFrame\)\.sourceScale/);
assert.match(embedder, /scoreWithoutEmbedding/);
assert.match(store, /fun pendingQualitySource\(/);
assert.match(store, /fun backfillQualityIfNull\(/);
assert.match(store, /quality_score IS NULL/);
assert.match(runtime, /qualityBackfill/);
assert.match(worker, /qualityBackfill/);
assert.match(worker, /!manualForeground/);
assert.match(host, /pendingQualitySource\(/);
assert.match(bench, /quality_off/);
assert.match(bench, /quality_on/);
assert.match(evidence, /source-pixel/);
console.log("COVER-0b source-scale, NULL-backfill, charging and throughput contracts");
