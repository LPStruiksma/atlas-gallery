import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

// Deliberately red, narrowly scoped scaffold for the Codex patch of #305.
// Do not treat a source-presence check as evidence of quality-score ordering.
const root = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const quality = readFileSync(
  resolve(root, "app/src/main/java/nl/atlas/gallery/featureface/FaceQuality.kt"),
  "utf8",
);
assert.match(quality, /object FaceQualityScorer/, "COVER-0 scorer must be merged on main");
assert.fail(
  "PENDING_CODEX_ORACLE: replace this scaffold with deterministic synthetic checks " +
    "for finite, bounded COVER-0 scoring and monotonic blur/exposure/size penalties, " +
    "with coefficients and thresholds verified against FaceQuality.kt and FACE_QUALITY_V2.md.",
);
