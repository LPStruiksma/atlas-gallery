import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";

// #667 TAGS-0: the committed tag matrix matches the vocabulary and is fenced to
// the shipped models; the report keeps its evidence; manifest holds IDs only.
// Fails (RED) before the unit lands: none of these files exist.
const out = JSON.parse(execFileSync("python3", ["-I", "tools/tag_vocab/tag_vocab.py", "verify"], { encoding: "utf8" }));
assert.equal(out.ok, true);
assert.equal(out.tags, 159);

const report = JSON.parse(readFileSync("docs/visual-search/results/tags0_calibration.json", "utf8"));
assert.equal(report.image_model, "siglip2_b16_224.int8:74da3ac2b46e");
assert.equal(report.text_model, "siglip2_b16_224.text.int8:6fd97706a619");
assert.equal(report.summary.default_on, 117);
for (const t of report.tags) {
  for (const k of ["threshold_cos", "hard_negative_fpr", "background_fpr", "calibrated", "default_on"]) assert.ok(k in t, `${t.id} lacks ${k}`);
  if (t.default_on) assert.ok(t.calibrated && t.precision >= 0.6 && t.recall >= 0.15, `${t.id} breaks the gate`);
}

const manifest = readFileSync("tools/tag_vocab/data/tags0_manifest.csv", "utf8").trim().split("\n");
assert.equal(manifest[0], "ImageID,Split,Tag,Label");
for (const line of manifest.slice(1)) assert.match(line, /^[0-9a-f]{16},(train|validation|test),[a-z0-9_]*,[01]?$/);

const doc = readFileSync("docs/visual-search/TAGS_0.md", "utf8");
for (const k of ["precision", "recall", "hard-negative", "Version fence", "150k", "TAGS-1", "TAGS-2"]) assert.ok(doc.includes(k), `TAGS_0.md lacks ${k}`);
assert.ok(readFileSync("docs/product/ATLAS_VISION.md", "utf8").includes("Things / automatic tags"));
