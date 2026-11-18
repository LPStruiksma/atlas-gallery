import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
import test from 'node:test';

// AIE-3 (#188): unlike Samsung's pencil, AI Edit is a deliberate overflow action.
// These contracts intentionally fail before the implementation lands.
const root = 'app/src/main/java/nl/atlas/gallery/';
test('AI Edit has an explicit pure state machine with refusal and failure separate', () => {
  const path = root + 'featureaiedit/ui/AiEditStateMachine.kt';
  assert.ok(existsSync(path));
  const source = readFileSync(path, 'utf8');
  for (const state of ['Idle','Composing','Generating','Result','Refused','Failed','Accepting','Saved','Cancelled']) {
    assert.match(source, new RegExp('\\b' + state + '\\b'));
  }
  assert.match(source, /fun restore\(/);
  assert.match(source, /fun cancel\(/);
  assert.match(source, /fun selectBranch\(/);
});
test('AI Edit has viewer and one-selected grid More routes, not another pencil', () => {
  const viewer = readFileSync(root + 'GalleryViewer.kt','utf8');
  const main = readFileSync(root + 'MainActivity.kt','utf8');
  assert.match(viewer, /atlas-viewer-ai-edit/);
  assert.match(main, /atlas-selection-ai-edit/);
  assert.match(main, /AiEditScreen\(/);
  assert.match(viewer, /atlas-edit-photo/);
  assert.match(main, /PhotoEditorHandoff\.launch/);
});
test('AI Edit never submits automatically and saves only through AIE-4', () => {
  const path = root + 'featureaiedit/ui/AiEditScreen.kt';
  assert.ok(existsSync(path));
  const source = readFileSync(path, 'utf8');
  const viewModel = readFileSync(root + 'featureaiedit/ui/AiEditViewModel.kt', 'utf8');
  assert.match(viewModel, /AiEditSaver/);
  assert.match(source, /onGenerate =/);
  assert.match(source, /onAccept =/);
  assert.match(source, /atlas-ai-edit-compare/);
  assert.match(source, /atlas-ai-edit-provider/);
  assert.doesNotMatch(source, /https?:\/\//);
});

test('AI Edit safe drawing and IME keep primary actions reachable', () => {
  const screen = readFileSync(root + 'featureaiedit/ui/AiEditScreen.kt', 'utf8');
  const evidence = readFileSync('app/src/androidTest/java/nl/atlas/gallery/AiEditUiEvidenceTest.kt', 'utf8');
  assert.match(screen, /windowInsetsPadding\(WindowInsets\.safeDrawing\)/);
  assert.match(screen, /imePadding\(\)/);
  assert.match(screen, /verticalScroll\(rememberScrollState\(\)\)/);
  assert.match(evidence, /boundsInWindow/);
  assert.match(evidence, /WindowInsetsCompat\.Type\.systemBars\(\)/);
  assert.match(evidence, /performScrollTo\(\)/);
});
