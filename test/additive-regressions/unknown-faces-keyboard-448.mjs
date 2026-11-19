// #448: the keyboard inset is applied once. Inside MainActivity's scaffold the
// tab pill's bottom padding is already applied, so keyboard-aware screens use
// atlasImePadding (consume what the scaffold applied, then imePadding) instead
// of stacking the full IME inset on top of it (a 350 px dead band on the S24+).
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';

const read = (path) => readFileSync(new URL('../../' + path, import.meta.url), 'utf8');
const src = 'app/src/main/java/nl/atlas/gallery/';
const insetsPath = src + 'featuredesign/AtlasImeInsets.kt';

test('atlasImePadding consumes the scaffold bottom padding before the IME inset', () => {
  assert.ok(existsSync(new URL('../../' + insetsPath, import.meta.url)), insetsPath);
  const insets = read(insetsPath);
  assert.match(insets, /val LocalAtlasScaffoldBottomPadding = compositionLocalOf \{ 0\.dp \}/);
  assert.match(insets,
    /consumeWindowInsets\(PaddingValues\(bottom = appliedBottom\)\)\.imePadding\(\)/);
});

test('MainActivity provides exactly the bottom padding its content Column applies', () => {
  const activity = read(src + 'MainActivity.kt');
  assert.match(activity,
    /val appliedBottom = if \(drawUnderBar\) 0\.dp else insets\.calculateBottomPadding\(\)/);
  assert.match(activity, /LocalAtlasScaffoldBottomPadding provides appliedBottom/);
  assert.match(activity, /bottom = appliedBottom\)\)/);
  // Search content sits in the same Column and had the same stacking.
  assert.match(activity, /Modifier\.padding\(top = 12\.dp\)\.atlasImePadding\(\)/);
  assert.doesNotMatch(activity, /\.imePadding\(\)/);
});

test('Unknown faces list uses atlasImePadding, keeps 16 dp under Create, compacts its header', () => {
  const screen = read(src + 'featurepeopleui/UnknownFacesReviewScreen.kt');
  assert.match(screen, /fillMaxSize\(\)\.atlasImePadding\(\)/);
  assert.doesNotMatch(screen, /\.imePadding\(\)/);
  assert.match(screen, /NAME_FORM_KEYBOARD_MARGIN = 16\.dp/);
  assert.match(screen, /nameFormView\.bringIntoView\(Rect\(/);
  assert.match(screen, /UnknownGroupHeader\(group, clusterFacePreview, compact = compactHeader\)/);
  assert.match(screen, /derivedStateOf \{[\s\S]*?reviewImeInsets\.getBottom\(reviewDensity\) > 0/);
});

test('AI Edit is a full-screen route whose safeDrawing already consumes the IME', () => {
  const aiEdit = read(src + 'featureaiedit/ui/AiEditScreen.kt');
  assert.match(aiEdit, /\.windowInsetsPadding\(WindowInsets\.safeDrawing\)\s*\.imePadding\(\)/);
});

test('real-Activity keyboard evidence is declared and measured', () => {
  const journey = read('app/src/androidTest/java/nl/atlas/gallery/UnknownFacesKeyboardInsetsTest.kt');
  assert.match(journey, /createAndroidComposeRule<MainActivity>\(\)/);
  assert.match(journey, /KeyboardRule\(TestKeyboard\.Fixed\)/);
  assert.match(journey, /abs\(list\.bottom - keyboardTop\) <= 2f/);
  const declared = read('scripts/ui-evidence.d/448-unknown-faces-keyboard.txt')
    .split('\n').filter((line) => line && !line.startsWith('#'));
  for (const state of ['a-closed', 'b-opening', 'c-open-empty', 'd-open-typed',
    'e-landscape', 'f-font130']) {
    for (const theme of ['light', 'dark']) {
      assert.ok(declared.includes(`unknown-ime-448-${state}-${theme}`), `${state} ${theme}`);
    }
  }
});
