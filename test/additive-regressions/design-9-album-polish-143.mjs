import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const albums = readFileSync('app/src/main/java/nl/atlas/gallery/featurealbums/AtlasAlbumsScreen.kt', 'utf8');
const main = readFileSync('app/src/main/java/nl/atlas/gallery/MainActivity.kt', 'utf8');
const albumEvidence = readFileSync('app/src/androidTest/java/nl/atlas/gallery/AlbumsGridEvidenceTest.kt', 'utf8');
const parityEvidence = readFileSync('app/src/androidTest/java/nl/atlas/gallery/ParityAdditionalThemeJourneyTest.kt', 'utf8');
const theme = readFileSync('app/src/androidTest/java/nl/atlas/gallery/SystemThemeJourneyTest.kt', 'utf8');

// Visual changes only: retain authorized cover handling, pin order, sort and long press.
assert.match(albums, /coverRenderer\(album\.coverMedia, Modifier\.fillMaxSize\(\)\)/);
assert.match(albums, /albumPinRank\(album\.title\)/);
assert.match(albums, /onReorderAlbums\?\.invoke\(moveUnpinnedAlbum/);
assert.match(albums, /GridCells\.Adaptive\(minSize = 112\.dp\)/);
assert.match(albums, /horizontalArrangement = Arrangement\.spacedBy\(12\.dp\)/);
assert.match(albums, /val coverCorner = RoundedCornerShape\(AtlasSpace\.CoverCorner\)/);
assert.match(albums, /style = MaterialTheme\.typography\.titleSmall/);
assert.match(albums, /style = MaterialTheme\.typography\.labelMedium/);
assert.match(main, /val albumDetailOpen = photosControls && album != null && albumReturnToAlbums/);
assert.match(main, /subtitle = if \(albumDetailOpen\)/);
assert.match(main, /header = if \(headerScrollsWithGrid\)/);
assert.match(theme, /saveUiEvidence\("parity-24-albums-detail-\$mode\.png"\)/);
// Keep bounded waits and the one-step zoom that still asserts actual seven-column UI.
assert.match(albumEvidence, /compose\.waitUntil\(120_000L\)/);
assert.match(parityEvidence, /end0 = center - Offset\(100f, 0f\)/);
assert.match(parityEvidence, /end1 = center \+ Offset\(100f, 0f\)/);
assert.match(parityEvidence, /SemanticsMatcher\.expectValue\(SemanticsProperties\.StateDescription, "7 columns"\)/);
console.log('DESIGN-9 Albums polish source contract OK');
