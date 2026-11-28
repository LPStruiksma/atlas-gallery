// #1039: fal.ai Save follows text input while the fixed IME can still resize
// the dialog. Require the existing keyboard/checked-pointer contracts and
// observable fake/store milestones; the emulator remains the runtime proof.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const source = readFileSync(new URL(
  '../../app/src/androidTest/java/nl/atlas/gallery/AiEditSettingsEvidenceTest.kt',
  import.meta.url,
), 'utf8');

test('fal Save waits for settled IME, then sends one checked pointer with an effect', () => {
  const fal = source.split('// fal has no documented check:')[1]
    ?.split('// Saved but unverified:')[0];
  assert.ok(fal, 'retain the fal and unavailable-service journey');
  const typed = fal.indexOf('compose.typeText(hasTestTag("aie-key-field"), "synthetic-fal-key", KeyboardAfter.Shown)');
  const geometry = fal.indexOf('compose.assertAboveKeyboard(hasTestTag("aie-save"))');
  const tap = fal.indexOf('compose.tap(hasTestTag("aie-save"))');
  assert.ok(typed >= 0 && geometry > typed && tap > geometry);
  assert.match(fal, /compose\.exists\(hasTestTag\("aie-services"\)/);
  assert.doesNotMatch(fal, /performTextInput\(|onNodeWithTag\("aie-save"\)\.performClick\(|Thread\.sleep\(|\bretry\s*\(/);
  assert.match(fal, /fake\.falNoCheckCalls\.get\(\)/);
  assert.match(fal, /store\.profiles\(\).*ProviderKind\.FAL/);
  assert.match(fal, /assertEquals\("synthetic-fal-key", vault\.get\(falProfile\.id\)\)/);
  assert.match(fal, /waitFor\("aie-unavailable-later-1"\)/);
  assert.match(fal, /Saved\. Checked on your first edit\./);
  assert.match(fal, /Key works/);
  assert.match(fal, /aie-default-badge/);
  assert.ok(fal.includes('shot("aie-19-services-$t")'));
  assert.match(source, /@Test fun lightTheme\(\) = journey\(dark = false\)/);
  assert.match(source, /@Test fun darkTheme\(\) = journey\(dark = true\)/);
});
