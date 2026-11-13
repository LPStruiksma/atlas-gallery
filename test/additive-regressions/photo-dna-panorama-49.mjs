import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

// #49: a 256px per-axis guard skips safe panoramic thumbnails. A provider
// may preserve aspect ratio when asked for a square; constrain pixel count
// before a HARDWARE copy and request a smaller edge for extreme ratios.
const host = readFileSync("app/src/main/java/nl/atlas/gallery/PhotoDnaHost.kt", "utf8");
const unit = readFileSync("app/src/test/java/nl/atlas/gallery/PhotoDnaCrashScaleTest.kt", "utf8");
const android = readFileSync("app/src/androidTest/java/nl/atlas/gallery/PhotoDnaPanoramaAndroidTest.kt", "utf8");

assert.match(host, /internal fun photoDnaThumbnailEdge\(/);
assert.match(host, /width\.toLong\(\) \* height <= PHOTO_DNA_THUMBNAIL_MAX_PIXELS/);
assert.match(host, /photoDnaThumbnailEdge\(record\.widthPixels, record\.heightPixels\)/);
assert.match(host, /photoDnaThumbnailWithinBudget\(bitmap\.width, bitmap\.height\)/);
const hash = host.slice(host.indexOf("internal fun visualHashAuthorizedImage("),
  host.indexOf("/** The family a Keep receipt"));
assert.ok(hash.indexOf("photoDnaThumbnailWithinBudget(bitmap.width, bitmap.height)") <
  hash.indexOf("bitmap.copy("));
assert.match(hash, /onOverBudget\(\)/);
assert.match(host, /oversizedThumbnailSkips=/);
assert.match(unit, /photoDnaThumbnailWithinBudget\(800, 80\)/);
assert.match(unit, /photoDnaThumbnailEdge\(8_000, 1_000\)/);
assert.match(android, /Bitmap\.createBitmap\(8_000, 1_000, Bitmap\.Config\.ARGB_8888\)/);
assert.match(android, /visualHashAuthorizedImage\(/);
assert.match(android, /assertTrue\(hash\.informative\)/);
console.log("Photo DNA panoramas use pixel-count budget, aspect-aware request, and aggregate skips");
