import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

// AIE-21 (#433): a deliberately limited serial slice, not proof of the full rev 5 UX.
const root = 'app/src/main/java/nl/atlas/gallery/featureaiedit/ui/';
test('five fixed local suggestions are prompt-only, never implicit Generate', () => {
  const screen = readFileSync(root + 'AiEditScreen.kt', 'utf8');
  for (const label of ['Remove object', 'Clean background', 'Fix lighting', 'Change sky', 'Restore photo']) {
    assert.ok(screen.includes('"' + label + '"'));
  }
  assert.match(screen, /onPromptChanged\(suggestion\)/);
  assert.match(screen, /atlas-ai-edit-suggestions/);
  assert.match(screen, /atlas-ai-edit-recent/);
});
test('recent prompts are recorded only after an accepted successful provider result', () => {
  const model = readFileSync(root + 'AiEditViewModel.kt', 'utf8');
  const store = readFileSync(root + 'AiEditPromptHistory.kt', 'utf8');
  const screen = readFileSync(root + 'AiEditScreen.kt', 'utf8');
  assert.match(model, /if \(machine\.onResult\(token, created\)\)\s*\{/);
  assert.match(model, /AiEditPromptHistory\(File\(.*noBackupFilesDir/);
  assert.match(screen, /AiEditPromptHistory\(File\(app\.noBackupFilesDir/);
  assert.match(store, /MAX_ENTRIES = 20/);
  assert.match(store, /Base64\.getUrlEncoder/);
  assert.match(store, /fun clear\(\)/);
});
