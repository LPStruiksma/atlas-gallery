// USAGE-0 regression: a user-initiated, named JSON attachment, never implicit telemetry.
// This is a source contract; JVM/Android tests cover runtime parsing and chooser behavior.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const read = p => readFileSync(new URL('../../' + p, import.meta.url), 'utf8');
const manifest = read('app/src/main/AndroidManifest.xml');
const activity = read('app/src/main/java/nl/atlas/gallery/MainActivity.kt');
const exportFile = read('app/src/main/java/nl/atlas/gallery/UsageExportFile.kt');
const recorder = read('app/src/main/java/nl/atlas/gallery/UsageRecorder.kt');
const tests = read('app/src/test/java/nl/atlas/gallery/UsageAggregatesTest.kt');
assert.match(manifest, /android:allowBackup="false"/);
assert.match(manifest, /android:name="androidx\.core\.content\.FileProvider"/);
assert.match(manifest, /android:exported="false"/);
assert.match(manifest, /android:grantUriPermissions="true"/);
assert.doesNotMatch(recorder + exportFile, /https?:\/\/|uploadUsage|analyticsSdk/i);
assert.match(activity, /UsageExportFile\.shareIntent\(/);
assert.doesNotMatch(activity, /Intent\.EXTRA_TEXT, json/);
assert.match(exportFile, /Intent\.EXTRA_STREAM/);
assert.match(exportFile, /FLAG_GRANT_READ_URI_PERMISSION/);
assert.match(exportFile, /atlas-usage-/);
assert.match(recorder, /UsageExportFile\.clear/);
assert.match(tests, /everyExportStringIsEitherAClosedIdOrADay/);
console.log('USAGE-0 named export contract OK');
