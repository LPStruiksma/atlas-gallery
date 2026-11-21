import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, existsSync } from 'node:fs';

const app = new URL('../../app/src/', import.meta.url);
const read = path => readFileSync(new URL(path, app), 'utf8');
const feature = 'main/java/nl/atlas/gallery/featureaiedit/';

test('AIE-20 typed cost scope fails closed and never parses the note (#431 A1)', () => {
  const provider = read(feature + 'provider/ImageEditProvider.kt');
  assert.match(provider, /enum class CostScope \{ TOTAL, OUTPUT_ONLY, PARTIAL \}/);
  assert.match(provider, /val scope = estimate\.scope \?: return Unknown/);
  assert.match(provider, /val date = estimate\.observedOn \?: return Unknown/);
  const gemini = read(feature + 'provider/hosted/gemini/GeminiImageEditProvider.kt');
  assert.match(gemini, /scope = CostScope\.OUTPUT_ONLY/);
  assert.doesNotMatch(gemini, /CostScope\.TOTAL/, 'Gemini prices are output-only, never a total');
});

test('AIE-20 delivery defaults to uncertain and transport phase to after-body (#431 A3)', () => {
  const provider = read(feature + 'provider/ImageEditProvider.kt');
  assert.match(provider, /val delivery: Delivery = Delivery\.UNCERTAIN/);
  const transport = read(feature + 'network/EditHttpTransport.kt');
  assert.match(transport, /val phase: FailurePhase = FailurePhase\.AFTER_BODY_OR_UNKNOWN/);
  assert.match(transport, /connection\.connect\(\)\s+if \(cancel\.isCancelled\) return EditHttpResult\.Cancelled\s+sent\.set\(true\)/);
});

test('AIE-20 recovery is a typed capability, never supportsAsync or a display name (#431 row 6)', () => {
  const fal = read(feature + 'provider/hosted/fal/FalImageEditProvider.kt');
  const gemini = read(feature + 'provider/hosted/gemini/GeminiImageEditProvider.kt');
  assert.match(fal, /remoteRecovery = RemoteRecovery\.QUERY_BY_PERSISTED_ID/);
  assert.match(gemini, /remoteRecovery = RemoteRecovery\.NONE/);
  for (const dir of ['ui/', 'engine/']) {
    const url = new URL(feature + dir, app);
    if (!existsSync(url)) continue;
    for (const name of readdirSync(url).filter(n => n.endsWith('.kt'))) {
      assert.doesNotMatch(read(feature + dir + name), /supportsAsync/, `${dir}${name} must not branch on supportsAsync`);
    }
  }
});

test('AIE-20 rate limit is its own state and Check again is key-bound (#431 A2, A4)', () => {
  const issue = read(feature + 'engine/AiEditIssue.kt');
  assert.match(issue, /FailureKind\.BILLING -> OUT_OF_CREDIT/);
  assert.match(issue, /FailureKind\.QUOTA -> RATE_LIMITED/);
  assert.match(issue, /FailureKind\.CANNOT_CHECK\) return UNKNOWN_CANNOT_CHECK/);
  const fingerprint = read(feature + 'engine/KeyFingerprint.kt');
  assert.match(fingerprint, /SHA-256/);
  assert.match(fingerprint, /MessageDigest\.isEqual/);
});
