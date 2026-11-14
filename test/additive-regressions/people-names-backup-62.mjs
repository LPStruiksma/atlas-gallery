import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const read = (file) => readFileSync(file, 'utf8');
const codec = read('app/src/main/java/nl/atlas/gallery/featurepersistence/NativePeopleNamesBackup.kt');
const io = read('app/src/main/java/nl/atlas/gallery/featurepersistence/PeopleSafBackupIO.kt');
const store = read('app/src/main/java/nl/atlas/gallery/featurepersistence/FaceIndexStore.kt');
const host = read('app/src/main/java/nl/atlas/gallery/PeopleDiscoveryHost.kt');
const settings = read('app/src/main/java/nl/atlas/gallery/AtlasSettingsScreen.kt');
const activity = read('app/src/main/java/nl/atlas/gallery/MainActivity.kt');

assert.match(codec, /object NativePeopleNamesBackupCodec/);
assert.match(codec, /SHA-256/);
assert.doesNotMatch(codec, /imagePath|mediaKey|canonicalMediaKey|photoUri|\.vector/);
assert.match(io, /exportNamesToSelectedDocument/);
assert.match(io, /previewNamesImport/);
assert.match(io, /importNamesFromSelectedDocument/);
assert.match(store, /fun exportNativeNamesSnapshot/);
assert.match(store, /fun importNativeNamesSnapshot/);
assert.match(host, /fun exportNamesBackup/);
assert.match(host, /fun importNamesBackup/);
assert.match(settings, /"Export names"/);
assert.match(settings, /"Import names"/);
assert.match(activity, /ActivityResultContracts.CreateDocument/);
assert.match(activity, /ActivityResultContracts.OpenDocument/);
assert.match(settings, /atlas-people-names-export/);
assert.match(settings, /atlas-people-names-import/);
