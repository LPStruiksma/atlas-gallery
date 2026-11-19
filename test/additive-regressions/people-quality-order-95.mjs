import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

// Synthetic scalar observations only: no photos, identities, or people.
const root = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const read = (path) => readFileSync(resolve(root, path), "utf8");
const source = read("app/src/main/java/nl/atlas/gallery/featureface/FaceQuality.kt");
const specification = read("docs/people/FACE_QUALITY_V2.md");

function sourceNumber(pattern, label) {
  const match = source.match(pattern);
  assert.ok(match, `COVER-0 contract drift: cannot derive ${label} from FaceQuality.kt`);
  const value = Number(match[1]);
  assert.ok(Number.isFinite(value), `COVER-0 contract drift: ${label} is not finite`);
  return value;
}

function documentedWeight(feature) {
  const escaped = feature.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const match = specification.match(new RegExp(`\\|\\s*${escaped}\\s*\\|\\s*([0-9.]+)\\s*\\|`));
  assert.ok(match, `COVER-0 contract drift: FACE_QUALITY_V2.md omits ${feature}`);
  return Number(match[1]);
}

const constants = {
  version: sourceNumber(/const val VERSION\s*=\s*(\d+)/, "quality version"),
  sizeFloor: sourceNumber(/const val MIN_FACE_SHORT_EDGE_PX\s*=\s*(\d+)/, "face-size floor"),
  exposureMidpoint: sourceNumber(/abs\(mean\s*-\s*([0-9.]+)\)\s*\/\s*127\.5/, "exposure midpoint"),
  contrastScale: sourceNumber(/contrast\s*\/\s*([0-9.]+)\)\.coerceIn/, "contrast normalisation"),
  sharpnessScale: sourceNumber(/laplacianVariance\s*\/\s*\(laplacianVariance\s*\+\s*([0-9.]+)\)/, "sharpness normalisation"),
  clipLow: sourceNumber(/v\s*<=\s*([0-9.]+)\s*\|\|\s*v\s*>=\s*247\.0/, "lower clipping bound"),
  clipHigh: sourceNumber(/v\s*<=\s*8\.0\s*\|\|\s*v\s*>=\s*([0-9.]+)/, "upper clipping bound"),
};

const weightNames = [
  ["detectionConfidence", "YuNet confidence"],
  ["size", "Face size"],
  ["sharp", "Sharpness"],
  ["tonal", "Exposure and contrast"],
  ["pose", "Frontalness and roll"],
  ["intact", "Truncation"],
];
const expression = source.match(/val result\s*=([\s\S]*?)\n\s*return FaceQuality/);
assert.ok(expression, "COVER-0 contract drift: weighted result expression is missing");
const weights = Object.fromEntries(weightNames.map(([factor, feature]) => {
  const match = expression[1].match(new RegExp(`([0-9.]+)\\s*\\*\\s*${factor}`));
  assert.ok(match, `COVER-0 contract drift: source weight for ${factor} is missing`);
  const sourceWeight = Number(match[1]);
  assert.equal(sourceWeight, documentedWeight(feature),
    `COVER-0 contract drift: ${feature} weight disagrees between source and documentation`);
  return [factor, sourceWeight];
}));

test("COVER-0 constants remain source-bound and documented", () => {
  assert.match(specification, new RegExp(`quality_version=${constants.version}\\b`),
    "COVER-0 contract drift: documented quality version changed");
  assert.match(specification, new RegExp(`shorter face-box side / ${constants.sizeFloor}\\b`),
    "COVER-0 contract drift: documented face-size floor changed");
  assert.match(specification, new RegExp(`variance / \\(variance \\+ ${constants.sharpnessScale}\\)`),
    "COVER-0 contract drift: documented sharpness normalisation changed");
  assert.match(specification, new RegExp(`standard deviation / ${constants.contrastScale}\\b`),
    "COVER-0 contract drift: documented contrast normalisation changed");
  assert.equal(Object.values(weights).reduce((sum, weight) => sum + weight, 0), 1,
    "COVER-0 contract drift: source weights must sum to one");
});

const cropSize = constants.sizeFloor;
const pixelCount = cropSize * cropSize;

function crop({ exposure = constants.exposureMidpoint, contrast = 70, blurred = false } = {}) {
  return Float64Array.from({ length: pixelCount }, (_, index) => {
    const x = index % cropSize;
    const y = Math.floor(index / cropSize);
    const period = blurred ? 28 : 1;
    const sign = (Math.floor(x / period) + Math.floor(y / period)) % 2 === 0 ? 1 : -1;
    return Math.max(0, Math.min(255, exposure + sign * contrast));
  });
}

function score({ pixels = crop(), shortEdge = 160 } = {}) {
  const mean = pixels.reduce((sum, value) => sum + value, 0) / pixelCount;
  const variance = pixels.reduce((sum, value) => sum + (value - mean) ** 2, 0) / pixelCount;
  const clipped = pixels.filter((value) => value <= constants.clipLow || value >= constants.clipHigh).length;
  const exposure = Math.max(0, Math.min(1, 1 - Math.abs(mean - constants.exposureMidpoint) / constants.exposureMidpoint));
  const tonal = exposure * (1 - clipped / pixelCount) *
    Math.max(0, Math.min(1, Math.sqrt(variance) / constants.contrastScale));

  const laplacians = [];
  for (let y = 1; y < cropSize - 1; y += 1) {
    for (let x = 1; x < cropSize - 1; x += 1) {
      const i = y * cropSize + x;
      laplacians.push(4 * pixels[i] - pixels[i - 1] - pixels[i + 1] -
        pixels[i - cropSize] - pixels[i + cropSize]);
    }
  }
  const lapMean = laplacians.reduce((sum, value) => sum + value, 0) / laplacians.length;
  const lapVariance = Math.max(0,
    laplacians.reduce((sum, value) => sum + value ** 2, 0) / laplacians.length - lapMean ** 2);
  const sharp = lapVariance / (lapVariance + constants.sharpnessScale);
  const size = Math.max(0, Math.min(1, shortEdge / constants.sizeFloor));

  // Confidence, pose, and border integrity are identical idealized scalars for every variant.
  const result = weights.detectionConfidence * 0.99 + weights.size * size + weights.sharp * sharp +
    weights.tonal * tonal + weights.pose * 1 + weights.intact * 1;
  return Math.max(0, Math.min(1, result));
}

test("COVER-0 orders deterministic same-observation degradations", () => {
  const scores = {
    clean: score(),
    blurred: score({ pixels: crop({ blurred: true }) }),
    exposureClipped: score({ pixels: crop({ exposure: 255 }) }),
    undersized: score({ shortEdge: constants.sizeFloor / 2 }),
  };

  for (const [variant, value] of Object.entries(scores)) {
    assert.ok(Number.isFinite(value), `${variant} synthetic quality score must be finite; got ${value}`);
    assert.ok(value >= 0 && value <= 1, `${variant} synthetic quality score must be in [0,1]; got ${value}`);
  }
  for (const degraded of ["blurred", "exposureClipped", "undersized"]) {
    assert.ok(scores.clean > scores[degraded],
      `clean synthetic observation (${scores.clean}) must outrank ${degraded} variant (${scores[degraded]})`);
  }
});
