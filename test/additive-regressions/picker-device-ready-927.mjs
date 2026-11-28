// #927: the picker must not launch while the system is still applying the
// test's night-mode / S24 display overrides. That race recreates its Compose
// root between the tile-stillness check and performTouchInput.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const file = fileURLToPath(new URL(
  '../../app/src/androidTest/java/nl/atlas/gallery/featureintents/ExternalPickerJourneyTest.kt',
  import.meta.url,
));
const source = readFileSync(file, 'utf8');

test('picker waits for the intended system configuration before creating media or launching', () => {
  const before = source.match(/@Before fun setUp\(\) \{([\s\S]*?)\n    \}/)?.[1];
  assert.ok(before, 'keep an explicit JUnit setup boundary');
  assert.match(before, /compose\.awaitThat\(/,
    'wait for a configuration outcome rather than sleeping');
  assert.ok(before.indexOf('compose.awaitThat(') < before.indexOf('media.create()'),
    'configuration gate must precede picker test data and activity launch');
  assert.match(before, /Configuration\.UI_MODE_NIGHT_MASK/,
    'gate must observe the app-context night mode, not merely a shell command');
  assert.match(before, /densityDpi\s*==\s*450/,
    'gate must observe the S24 density override');
  assert.match(before, /1080\s*&&\s*displaySize\.y\s*==\s*2340/,
    'gate must observe the S24 size override');
});

test('picker retains touch delivery and both cancel/Back result assertions', () => {
  assert.match(source, /@Test fun cancelAndBackReturnNothing\(/);
  assert.match(source, /compose\.tapTile\(media\.first\)/);
  assert.match(source, /compose\.tap\(hasTestTag\(ExternalPickerTags\.CANCEL\)\)/);
  assert.match(source, /GLOBAL_ACTION_BACK/);
  assert.match(source, /assertEquals\(Activity\.RESULT_CANCELED, cancelled\.result\.resultCode\)/);
  assert.match(source, /assertEquals\(Activity\.RESULT_CANCELED, backed\.result\.resultCode\)/);
  assert.match(source, /awaitTileSteady\(tag\)/);
  assert.doesNotMatch(source, /Thread\.sleep\(|@Ignore|\bretry\s*\(/);
});
