import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const albums = readFileSync('app/src/main/java/nl/atlas/gallery/featurealbums/AtlasAlbumsScreen.kt', 'utf8');
const main = readFileSync('app/src/main/java/nl/atlas/gallery/MainActivity.kt', 'utf8');
const albumEvidence = readFileSync('app/src/androidTest/java/nl/atlas/gallery/AlbumsGridEvidenceTest.kt', 'utf8');
const parityEvidence = readFileSync('app/src/androidTest/java/nl/atlas/gallery/ParityAdditionalThemeJourneyTest.kt', 'utf8');
const theme = readFileSync('app/src/androidTest/java/nl/atlas/gallery/SystemThemeJourneyTest.kt', 'utf8');
const runner = readFileSync('scripts/ci_android_upgrade_emulator.sh', 'utf8');
const declared = readFileSync('scripts/ui-evidence.d/515-albums.txt', 'utf8');

// Visual changes only: retain authorized cover handling, pin order, sort and long press.
assert.match(albums, /coverRenderer\(album\.coverMedia, Modifier\.fillMaxSize\(\)\)/);
assert.match(albums, /albumPinRank\(album\.title\)/);
assert.match(albums, /onReorderAlbums\?\.invoke\(moveUnpinnedAlbum/);
assert.match(albums, /GridCells\.Adaptive\(minSize = 96\.dp\)/);
assert.match(albums, /horizontalArrangement = Arrangement\.spacedBy\(12\.dp\)/);
assert.match(albums, /val coverCorner = RoundedCornerShape\(AtlasSpace\.CoverCorner\)/);
assert.match(albums, /style = MaterialTheme\.typography\.titleSmall/);
assert.match(albums, /style = MaterialTheme\.typography\.labelMedium/);
assert.match(main, /val albumDetailOpen = photosControls && album != null && albumReturnToAlbums/);
assert.match(main, /subtitle = if \(photosControls && !settingsOpen && !statusOpen\) \{/);
assert.match(main, /if \(albumDetailOpen && !sortPending && !albumSelectionPending\)/);
assert.match(main, /else photosHeaderStatus\(sortPending, albumSelectionPending, visible\.size\)/);
assert.match(main, /header = if \(headerScrollsWithGrid\)/);
assert.match(theme, /saveUiEvidence\("parity-24-albums-detail-\$mode\.png"\)/);
// Keep bounded, stage-identified waits, lazy-grid semantics and inventory diagnostics.
assert.match(albumEvidence, /compose\.waitUntil\(120_000L, condition\)/);
assert.match(albumEvidence, /waitForAlbumsStage\("Albums navigation"\)/);
assert.match(albumEvidence, /waitForAlbumsStage\("visible Albums grid cards \(at least six\)"\)/);
assert.match(albumEvidence, /\.fetchSemanticsNodes\(\)\.size >= 6/);
assert.match(albumEvidence, /fun inventoryDiagnostic\(\): String/);
assert.match(albumEvidence, /Three pinned covers must share top row at S24 size/);
assert.match(albumEvidence, /MediaStore\.Images\.Media\.RELATIVE_PATH/);
assert.match(parityEvidence, /end0 = center - Offset\(100f, 0f\)/);
assert.match(parityEvidence, /end1 = center \+ Offset\(100f, 0f\)/);
assert.match(parityEvidence, /SemanticsMatcher\.expectValue\(SemanticsProperties\.StateDescription, "7 columns"\)/);
// Require paired real emulator snapshots from baseline and candidate in each theme.
for (const revision of ['before', 'after']) {
    for (const mode of ['light', 'dark']) {
        assert.ok(declared.split(String.fromCharCode(10)).includes('albums-' + revision + '-' + mode));
    }
}
assert.ok(albumEvidence.includes('atlasAlbumsRevision'));
assert.ok(albumEvidence.includes('albums-$revision-$captureTheme.png'));
for (const revision of ['before', 'after']) {
    for (const mode of ['light', 'dark']) {
        assert.ok(runner.includes('capture_albums_grid "' + revision + '" "' + mode + '"'));
    }
}
console.log('DESIGN-9 Albums polish source contract OK');
