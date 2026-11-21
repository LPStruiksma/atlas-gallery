import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

// HIDE-1 (#585) source contract: one shared filter at the single catalogue source,
// no file operation anywhere in the feature, discovery sees the unfiltered list.
const root = new URL("../../app/src/main/java/nl/atlas/gallery/", import.meta.url);
const read = (path) => readFileSync(new URL(path, root), "utf8");
const main = read("MainActivity.kt");
const store = read("featurepersistence/HiddenMedia.kt");
const screen = read("featurealbums/HiddenMediaScreen.kt");
const viewer = read("GalleryViewer.kt");

test("the shared filter is applied once, at the catalogue every surface derives from", () => {
  assert.match(main, /val allMedia = publishedGallery\.displayItems\(grants\)/);
  assert.match(main, /HiddenMedia\.visible\(allMedia, hidden\)/);
  assert.equal(main.match(/HiddenMedia\.visible\(/g).length, 1);
  // Unknown or unreadable means nothing is shown, never "no hidden items".
  assert.match(main, /if \(hidden == null\) emptyList\(\)/);
});

test("the face index sees the unfiltered catalogue so hiding never prunes face data", () => {
  assert.match(main, /rememberPeopleDiscovery\(discoveryHost, searchGrant, allMedia\)/);
});

test("Hide is a stored key set only: no MediaStore, file or consent operation", () => {
  const code = (store + screen).replace(/\/\/.*|\/\*[\s\S]*?\*\//g, "");
  assert.doesNotMatch(code, /\bMediaStore\b|ContentResolver|createTrashRequest|File\(|renameTo|\.nomedia|Biometric|Cipher/);
  assert.match(store, /CREATE TABLE hidden_media\(media_key TEXT PRIMARY KEY NOT NULL\)/);
  assert.match(store, /beginTransaction\(\)/);
  assert.match(store, /catch \(_: SQLiteException\) \{ null \}/);
});

test("Hide and Unhide are reachable from selection More, viewer More and the Albums menu", () => {
  assert.match(main, /SelectionMoreAction\("Hide", "atlas-selection-hide"\)/);
  assert.match(main, /onHideToggle = \{ photo ->/);
  assert.match(main, /onOpenHidden = \{ hiddenOpen = true \}/);
  assert.match(viewer, /atlas-viewer-unhide/);
  assert.match(viewer, /atlas-viewer-hide/);
  assert.match(read("featurealbums/AtlasAlbumsScreen.kt"), /atlas-albums-hidden-row/);
});

test("the Hidden screen has the specified empty copy, direct Unhide and no auth or warning", () => {
  assert.match(screen, /No hidden photos or videos\./);
  assert.match(screen, /atlas-hidden-unhide/);
  assert.doesNotMatch(screen + main.slice(main.indexOf("hiddenOpen ->")), /BiometricPrompt|KeyguardManager/);
});

test("Stories, Photo DNA, search, People covers, albums and Declutter all read the filtered catalogue", () => {
  for (const call of [
    /rememberStoryDeck\(media,/,
    /rememberPhotoDna\(media,/,
    /rememberAsyncSortedCatalog\(media,/,
    /rememberAsyncAlbumCards\(media,/,
    /declutterSource = /,
  ]) assert.match(main, call);
  // Nothing derives a user-visible list from the unfiltered catalogue except the
  // face-index host and the Hidden collection itself.
  const uses = [...main.matchAll(/\ballMedia\b/g)].length;
  assert.ok(uses <= 12, `allMedia leaked into ordinary surfaces (${uses} uses)`);
});

test("People home counts and covers skip hidden media while the face index stays unfiltered", () => {
  const host = read("PeopleDiscoveryHost.kt");
  const faceStore = read("featurepersistence/FaceIndexStore.kt");
  assert.match(main, /rememberPeopleDiscovery\(discoveryHost, searchGrant, allMedia\)/);
  assert.match(main, /discoveryHost\.setHiddenMedia\(/);
  assert.match(host, /store\.peopleHome\(epoch, hidden\)/);
  assert.match(faceStore, /hiddenMediaKeys: Set<String> = emptySet\(\)/);
  assert.match(read("featurepersistence/PeopleHomeHidden.kt"), /object PeopleHomeHidden/);
});

test("Hidden is a row on Albums and the viewer shows the Hide result with Undo", () => {
  const albums = read("featurealbums/AtlasAlbumsScreen.kt");
  assert.match(albums, /HiddenUtilityRow\(/);
  assert.doesNotMatch(albums, /atlas-albums-menu-hidden/);
  const viewerBranch = main.slice(main.indexOf("if (contextItems != null && currentUri != null)"));
  assert.match(viewerBranch.slice(0, viewerBranch.indexOf("return\n")), /HiddenUndoSnackbarHost\(hiddenResults/);
});
