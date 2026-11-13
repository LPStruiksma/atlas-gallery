import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";

const stories = readFileSync(
  "app/src/main/java/nl/atlas/gallery/featurestories/AtlasStoriesScreen.kt",
  "utf8"
);

// #21: a source-level safety net; the Android journey proves actual rendering,
// Media3 completion, and both-theme photo/video/photo navigation.
test("Stories video stays inline with playable controls and advances at EOS", () => {
  assert.match(stories, /StoryVideoStage\s*\(/);
  assert.match(stories, /Player\.STATE_ENDED/);
  assert.match(stories, /onPositionChange\(index \+ 1, true\)/);
  assert.doesNotMatch(stories, /enabled\s*=\s*!item\.isVideo/);
  assert.match(stories, /Resume video/);
});

test("Every Story video player is paused on background and released on removal", () => {
  assert.match(stories, /LifecycleEventObserver/);
  assert.match(stories, /Lifecycle\.Event\.ON_PAUSE/);
  assert.match(stories, /Lifecycle\.Event\.ON_STOP/);
  assert.match(stories, /player\.release\(\)/);
  assert.match(stories, /DisposableEffect\(player/);
  // TextureView composes with Story chrome for real emulator screenshots.
  assert.match(stories, /TextureView/);
  assert.match(stories, /setVideoTextureView\(/);
  assert.match(stories, /clearVideoTextureView\(/);
  assert.doesNotMatch(stories, /PlayerView\(/);
});
