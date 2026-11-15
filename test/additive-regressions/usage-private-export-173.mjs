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
// Every selected-item bulk button must count one closed A17 intent, never a URI.
// These are user taps, not assertions that Android's consent or transfer completed.
assert.match(activity, /onShare = \{\s*usageRecorder\.action\("A17"\)\s*shareBatch\(\)/);
assert.match(activity, /onTrash = if \(Build\.VERSION\.SDK_INT >= 30\) \(\{\s*usageRecorder\.action\("A17"\)\s*trashBatch\(\)/);
assert.match(activity, /onFavourite = if \(Build\.VERSION\.SDK_INT >= 30\) \(\{\s*usageRecorder\.action\("A17"\)\s*favouriteBatch\(\)/);
assert.match(activity, /SelectionMoreAction\("Copy to album", "atlas-selection-copy-to-album"\) \{\s*usageRecorder\.action\("A17"\)/);
assert.match(activity, /SelectionMoreAction\("Move to album", "atlas-selection-move-to-album"\) \{\s*usageRecorder\.action\("A17"\)/);
assert.match(recorder, /if \(!enabled\(\)\) return/);
console.log('USAGE-0 named export contract OK');
