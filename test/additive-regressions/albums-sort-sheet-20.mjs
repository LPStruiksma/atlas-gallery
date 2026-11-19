import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const source = readFileSync(
  new URL("../../app/src/main/java/nl/atlas/gallery/featurealbums/AtlasAlbumsScreen.kt", import.meta.url),
  "utf8",
);
const start = source.indexOf("private fun AtlasAlbumSortMenu(");
const end = source.indexOf("private fun albumSortLabel(", start);
const sortMenu = source.slice(start, end);

test("Albums sort opens a compact themed bottom sheet rather than an anchored dropdown", () => {
  assert.ok(start >= 0 && end > start, "Albums sort action still exists");
  assert.match(sortMenu, /ModalBottomSheet\s*\(/);
  assert.match(sortMenu, /rememberModalBottomSheetState\(skipPartiallyExpanded = true\)/);
  assert.match(sortMenu, /atlas-album-sort-sheet/);
  assert.doesNotMatch(sortMenu, /\bDropdownMenu\s*\(/);
});

test("all existing album sorts are accessible radio choices without new catalog behavior", () => {
  assert.match(sortMenu, /AlbumSort\.entries\.forEach/);
  assert.match(sortMenu, /\.selectable\s*\(/);
  assert.match(sortMenu, /Role\.RadioButton/);
  assert.match(sortMenu, /RadioButton\(selected = choice == sort/);
  assert.match(sortMenu, /atlas-album-sort-/);
  assert.match(sortMenu, /onSort\(choice\)/);
  for (const choice of ["RECENT", "NAME", "COUNT", "CUSTOM"]) {
    assert.match(source, new RegExp("AlbumSort\\." + choice + "\\s*->"));
  }
});

test("Albums sort sheet follows Quiet Frame: surface colour and 28 dp top radius from tokens", () => {
  const tokens = readFileSync(
    new URL("../../app/src/main/java/nl/atlas/gallery/featuredesign/AtlasTokens.kt", import.meta.url),
    "utf8",
  );
  assert.match(tokens, /val Sheet = 28\.dp/);
  assert.match(sortMenu, /containerColor = MaterialTheme\.colorScheme\.surface,/);
  assert.match(sortMenu, /topStart = AtlasTokens\.Radii\.Sheet, topEnd = AtlasTokens\.Radii\.Sheet/);
  assert.doesNotMatch(sortMenu, /surfaceContainerLow/);
});
