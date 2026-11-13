import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

// #18: verify source wiring in addition to the rendered Android journey.
// This intentionally fails against the old Media3 native-controller surface.
const source = readFileSync(new URL(
  "../../app/src/main/java/nl/atlas/gallery/GalleryViewer.kt", import.meta.url
), "utf8");

test("video uses gallery-owned controls, not Media3 controller chrome", () => {
  assert.match(source, /useController\s*=\s*false/);
  assert.doesNotMatch(source, /useController\s*=\s*true/);
  for (const tag of [
    "atlas-video-play-pause", "atlas-video-seek", "atlas-video-seek-preview"
  ]) assert.ok(source.includes(tag), `missing ${tag}`);
});

test("seek has a real video-frame preview and commits on release", () => {
  assert.match(source, /MediaMetadataRetriever/);
  assert.match(source, /getScaledFrameAtTime/);
  assert.match(source, /onValueChangeFinished\s*=/);
  assert.match(source, /player\.seekTo\(/);
});

test("video still mounts after settle, pauses in background, and releases", () => {
  assert.match(source, /delay\(180\)/);
  assert.match(source, /viewerVideoMustPause\(event\)/);
  assert.match(source, /player\.release\(\)/);
  assert.match(source, /latestLivePosition\(null\)/);
});
