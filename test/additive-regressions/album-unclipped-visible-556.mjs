import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

// #556: the list may clip almost all of a tile at its top boundary.
// A clipped boundsInRoot rectangle cannot prove the *original* tile is visible.
const album = readFileSync(
  'app/src/androidTest/java/nl/atlas/gallery/featuregrid/OriginalProportionsAlbumJourneyTest.kt',
  'utf8',
);
const selection = album.match(/val visible = nodes\.filter \{([^}]+)\}/)?.[1];
assert.ok(selection, 'visible fixture selection must exist');
assert.match(selection, /positionInRoot/, 'use the original node position');
assert.match(selection, /\.size/, 'use the original node size, not clipped bounds');
assert.doesNotMatch(selection, /boundsInRoot/, 'clipped geometry must not decide full visibility');
// This changes only the candidate selection, not the pixel guarantee or its wait.
assert.match(album, /compose\.waitUntil\(30_000L\)/);
assert.match(album, /tileIsOneColour\(pixels\)/);
assert.match(album, /assertEquals\("every fixture shape must have been checked"/);
console.log('Album original-size visibility contract (#556) OK');
