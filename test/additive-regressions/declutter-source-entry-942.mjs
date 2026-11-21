import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const root = 'app/src/main/java/nl/atlas/gallery/';
const host = readFileSync(root + 'MainActivity.kt', 'utf8');
const timeline = readFileSync(root + 'featuregrid/AtlasTimelineGallery.kt', 'utf8');
const photo = readFileSync(root + 'featurecuration/PhotoDnaReviewScreen.kt', 'utf8');
const dnaHost = readFileSync(root + 'PhotoDnaHost.kt', 'utf8');
const progress = readFileSync(root + 'DeclutterProgress.kt', 'utf8');
const screen = readFileSync(root + 'DeclutterScreen.kt', 'utf8');

assert.match(host, /openDeclutter\([\s\S]*?source: List<MediaRecord>[\s\S]*?sourceSort: String/,
  'one host entry accepts frozen source records and the current source sort');
assert.match(host, /kind = "album"[\s\S]*?sourceSort = "\$\{sort\.key\.name\}:\$\{sort\.direction\.name\}"/,
  'album entry keeps the active album sort');
assert.match(host, /onDeclutterMonth = if \(albumRoute == null\)[\s\S]*?kind = "month"/,
  'Pictures month entry is supplied from the timeline header range');
assert.match(timeline, /slot\.stableKey\.startsWith\("header:MONTH:"\)/,
  'only a displayed month header exposes the month action');
assert.match(host, /source = visible[\s\S]*?atlas-search-declutter/,
  'Search launches the exact visible results and retains its query on Back');
assert.match(photo, /group\.members\.map \{ it\.uri \}[\s\S]*?photo-dna-declutter-group/,
  'Photo DNA exact family exposes its ordered members');
assert.match(photo, /pair\.left\.uri[\s\S]*?pair\.right\.uri[\s\S]*?photo-dna-declutter-pair/,
  'Photo DNA visual pair exposes the exact pair');
assert.match(dnaHost, /shown\.records\[it\][\s\S]*?shown\.plan\.scopeEpoch/,
  'Photo DNA source is fenced to the current grant and record map');
assert.match(progress, /fields = if \(snapshot\.isBlank\(\)\)[\s\S]*?fun declutterSourceFingerprint[\s\S]*?modifiedAtSeconds/,
  'snapshot identity changes with order, membership and media revision');
assert.match(screen, /ModalBottomSheet[\s\S]*?atlas-declutter-resume[\s\S]*?atlas-declutter-start-again[\s\S]*?atlas-declutter-not-now/,
  'resume / reset / exit actions share the approved bottom sheet');
assert.match(host, /DeclutterTrashPreflight\.unchanged\(\s*context\.contentResolver, selected\)/,
  'the existing MediaStore preflight remains in place');
console.log('TIDY-1B source entry contract ok');
