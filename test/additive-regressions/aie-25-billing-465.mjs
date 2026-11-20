import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const app = new URL('../../app/src/', import.meta.url);
const read = path => readFileSync(new URL(path, app), 'utf8');

test('AIE-25 402 is distinct from 429 and nonretryable through hosted failures (#465)', () => {
  const transport = read('main/java/nl/atlas/gallery/featureaiedit/network/EditHttpTransport.kt');
  const provider = read('main/java/nl/atlas/gallery/featureaiedit/provider/ImageEditProvider.kt');
  const hosted = read('main/java/nl/atlas/gallery/featureaiedit/provider/hosted/HostedEditJob.kt');
  assert.match(transport, /402\s*->\s*FailureCode\.BILLING to false/);
  assert.match(transport, /429\s*->\s*FailureCode\.QUOTA to false/);
  assert.match(provider, /enum class FailureKind[^\n]*\bBILLING\b/);
  assert.match(hosted, /FailureCode\.BILLING\s*->\s*FailureKind\.BILLING to false/);
});

test('AIE-25 loopback regressions include billing, redaction and provider POST no-replay (#465)', () => {
  const transportTest = read('test/java/nl/atlas/gallery/featureaiedit/network/EditHttpTransportTest.kt');
  const falTest = read('test/java/nl/atlas/gallery/featureaiedit/provider/hosted/fal/FalImageEditProviderTest.kt');
  const geminiTest = read('test/java/nl/atlas/gallery/featureaiedit/provider/hosted/gemini/GeminiImageEditProviderTest.kt');
  assert.match(transportTest, /fun paymentRequiredIsBillingNonretryableAndDoesNotReplayPostsOrLeakBody\(/);
  assert.match(transportTest, /assertEquals\("no paid POST replay", 2, calls\.get\(\)\)/);
  for (const source of [falTest, geminiTest]) {
    assert.match(source, /402 to FailureKind\.BILLING/);
    assert.match(source, /assertEquals\("HTTP_BILLING", result\.message\)/);
  }
});
