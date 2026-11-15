import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const albums = readFileSync('app/src/main/java/nl/atlas/gallery/featurealbums/AtlasAlbumsScreen.kt', 'utf8');
const main = readFileSync('app/src/main/java/nl/atlas/gallery/MainActivity.kt', 'utf8');
const albumEvidence = readFileSync('app/src/androidTest/java/nl/atlas/gallery/AlbumsGridEvidenceTest.kt', 'utf8');
const parityEvidence = readFileSync('app/src/androidTest/java/nl/atlas/gallery/ParityAdditionalThemeJourneyTest.kt', 'utf8');
const theme = readFileSync('app/src/androidTest/java/nl/atlas/gallery/SystemThemeJourneyTest.kt', 'utf8');
const killFixture = readFileSync('app/src/androidTest/java/nl/atlas/gallery/PeopleScanProcessKillTest.kt', 'utf8');
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
assert.match(main, /subtitle = if \(albumDetailOpen\)/);
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
// Proof is from actual baseline and candidate app builds, with identical synthetic media.
for (const revision of ['before', 'after']) {
    for (const mode of ['light', 'dark']) {
        assert.match(declared, new RegExp('^albums-' + revision + '-' + mode + '
, 'm'));
    }
}
assert.match(albumEvidence, /atlasAlbumsRevision/);
assert.match(albumEvidence, /albums-\\$revision-\\$captureTheme\\.png/);
assert.match(runner, /capture_albums_grid "before" "light"/);
assert.match(runner, /capture_albums_grid "before" "dark"/);
assert.match(runner, /capture_albums_grid "after" "light"/);
assert.match(runner, /capture_albums_grid "after" "dark"/);
// The instrumentation seed ends its own WorkManager chain before the independent,
// opted-in app launch re-arms a new immediate one. The periodic rearm is untouched.
assert.match(killFixture, /cancelUniqueWork\\(PeopleScanWorkScheduler\\.UNIQUE_NAME\\)/);
assert.match(killFixture, /\\.result\\.get\\(30, TimeUnit\\.SECONDS\\)/);
assert.match(runner, /adb shell dumpsys battery set ac 1/);
assert.match(runner, /kill_app/);
assert.match(runner, /wait_restore_in_new_pid/);
console.log('DESIGN-9 Albums polish source contract OK');
