import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

// #33: duplicate MediaStore rows used to enter CurationReviewPlan.build,
// whose duplicate-URI invariant throws from a UI LaunchedEffect. An oversized
// provider thumbnail could trigger an unbounded hardware copy / pixel buffer.
const host = readFileSync("app/src/main/java/nl/atlas/gallery/PhotoDnaHost.kt", "utf8");

assert.match(host, /internal fun photoDnaDistinctEligible\(/);
assert.match(host, /photoDnaDistinctEligible\(source, budget, checkpoint\)/);
assert.match(host, /photoDnaDistinctEligible\(source, checkpoint = checkpoint\)/);
assert.match(host, /internal fun photoDnaThumbnailWithinBudget\(/);
const preview = host.slice(host.indexOf("internal fun visualHashAuthorizedImage("),
  host.indexOf("/** The family a Keep receipt"));
assert.ok(preview.indexOf("photoDnaThumbnailWithinBudget(bitmap.width, bitmap.height)") >
  preview.indexOf("resolver.loadThumbnail("));
assert.ok(preview.indexOf("photoDnaThumbnailWithinBudget(bitmap.width, bitmap.height)") <
  preview.indexOf("bitmap.copy("));

console.log("Photo DNA duplicate-URI and oversized-thumbnail guards are present");
