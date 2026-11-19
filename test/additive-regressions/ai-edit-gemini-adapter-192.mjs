import test from 'node:test';
import assert from 'node:assert/strict';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

// #192 (parent #183): the first hosted adapter. Source-level guarantees that the JVM fake-server
// tests cannot see on their own: pure Kotlin, one fixed host, key only in a header, no replay.
const root = fileURLToPath(new URL('../../', import.meta.url));
const hostedDir = join(root, 'app/src/main/java/nl/atlas/gallery/featureaiedit/provider/hosted');
const gemini = readFileSync(join(hostedDir, 'gemini/GeminiImageEditProvider.kt'), 'utf8');

function kotlinFiles(dir) {
  return readdirSync(dir).flatMap(name => {
    const p = join(dir, name);
    return statSync(p).isDirectory() ? kotlinFiles(p) : name.endsWith('.kt') ? [p] : [];
  });
}

test('hosted adapters stay pure Kotlin and reach the network only through EditHttpTransport', () => {
  for (const file of kotlinFiles(hostedDir)) {
    const text = readFileSync(file, 'utf8');
    assert.doesNotMatch(text, /^import android\./m, `${file} must not import Android`);
    assert.doesNotMatch(text, /HttpURLConnection|openConnection|java\.net\.(URL|Socket)\b/, `${file} must use EditHttpTransport`);
    assert.doesNotMatch(text, /\bLog\.[dviwe]\(|println\(/, `${file} must not log`);
  }
});

test('Gemini adapter pins one host, sends the key as a header and never replays the POST', () => {
  assert.match(gemini, /const val API_HOST = "generativelanguage\.googleapis\.com"/);
  assert.match(gemini, /const val API_KEY_HEADER = "x-goog-api-key"/);
  assert.match(gemini, /EditHttpAuth\.CustomHeader\(API_KEY_HEADER, key\)/);
  assert.doesNotMatch(gemini, /[?&]key=/, 'key must never be a query parameter');
  assert.match(gemini, /maxAttempts = 1/);
  assert.match(gemini, /allowedHosts\.map \{ it\.lowercase\(\) \}\.toSet\(\) == setOf\(API_HOST\)/);
});

test('Gemini adapter is honest: no mask claim, refusals separate from failures, real format from bytes', () => {
  assert.match(gemini, /supportsMaskEdit = false/);
  assert.match(gemini, /EditOutcome\.Refused\(parsed\.code/);
  assert.match(gemini, /results\.finalizeFile\(target, MAX_RESULT_PIXELS\)/);
  assert.match(gemini, /downloadTo = replyFile/);
  assert.match(gemini, /"thought"\]\.bool\(\) != true/);
});

test('the Gemini adapter document records dated sources and the reply-size gap', () => {
  const doc = readFileSync(join(root, 'docs/ai-edit/adapters/gemini.md'), 'utf8');
  assert.match(doc, /# Google Gemini \(#192\), 2026-11-18/);
  assert.match(doc, /googleapis\/python-genai/);
  assert.match(doc, /Large replies \(#441\)/);
});
