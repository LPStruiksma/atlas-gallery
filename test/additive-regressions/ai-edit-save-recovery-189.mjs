// AIE-4 (#189): static guardrails for the process-death window between
// MediaStore insert and the durable recordInserted call. Runtime behavior is
// exercised by the synthetic Android instrumentation tests below.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const source = readFileSync(
  'app/src/main/java/nl/atlas/gallery/featureaiedit/save/AiEditSaver.kt', 'utf8');
const models = readFileSync(
  'app/src/main/java/nl/atlas/gallery/featureaiedit/save/AiEditModels.kt', 'utf8');
const android = readFileSync(
  'app/src/androidTest/java/nl/atlas/gallery/featureaiedit/save/AiEditSaverAndroidTest.kt', 'utf8');

test('unrecorded MediaStore row uses a persisted unique temporary identity', () => {
  assert.match(models, /fun pendingName\(token: String, format: AiEditImageFormat\)/);
  assert.match(source, /val temporaryName = AiEditNaming\.pendingName\(token, candidate\.format\)/);
  assert.match(source, /registerIntent\(token, source\.volume, source\.relativePath, temporaryName\)/);
  assert.match(source, /pendingValues\(source, temporaryName, candidate\.format\)/);
  assert.ok(source.indexOf('lineageStore.registerIntent(') <
    source.indexOf('inserted = resolver.insert('), 'intent must be durable before insert');
  assert.ok(source.indexOf('lineageStore.recordInserted(') <
    source.indexOf('put(MediaStore.Images.Media.DISPLAY_NAME, name)\n                put(MediaStore.Images.Media.IS_PENDING, 0)'),
    'recorded URI must predate final-name publication');
});

test('recovery fails closed for legacy name-only intents and validates exact URI', () => {
  assert.match(source, /if \(!AiEditNaming\.isTokenNamedPendingRow\(pending\.token, pending\.displayName\)\)/);
  assert.match(source, /Uri\.parse\(pending\.insertedUri\)/);
  assert.match(source, /MediaStore\.setIncludePending\(recorded\)/);
  assert.match(source, /MediaStore\.Images\.Media\.OWNER_PACKAGE_NAME/);
  assert.match(android, /fun interruptedSaveBeforeRecordInsertedReclaimsOnlyTokenNamedRow\(/);
  assert.match(android, /fun preInsertCrashCannotDeleteLegacySameNamePendingImage\(/);
  assert.match(android, /fun recoveryFindsRecordedUriAfterPublishRenameAndKeepsLineage\(/);
});
