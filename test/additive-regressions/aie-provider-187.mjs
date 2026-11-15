import test from 'node:test';
import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';

const providerDir = new URL('../../app/src/main/java/nl/atlas/gallery/featureaiedit/provider/', import.meta.url);
const testDir = new URL('../../app/src/test/java/nl/atlas/gallery/featureaiedit/provider/', import.meta.url);

const sources = readdirSync(providerDir)
  .filter(name => name.endsWith('.kt'))
  .map(name => readFileSync(new URL(name, providerDir), 'utf8')).join('\n');

test('AIE-2 provider implementation is pure Kotlin, without Android or network APIs (#187)', () => {
  assert.doesNotMatch(sources, /^import\s+(android\.|androidx\.|java\.net\.|javax\.net\.|okhttp|retrofit|io\.ktor)/m);
  assert.doesNotMatch(sources, /\b(HttpURLConnection|URL\.openConnection|openConnection\s*\(|Bearer)\b/);
});

test('AIE-2 includes a reusable adapter contract and mock-driven JVM test subclass (#187)', () => {
  const tests = readFileSync(new URL('ImageEditProviderContractTest.kt', testDir), 'utf8');
  assert.match(tests, /abstract class ImageEditProviderContract/);
  assert.match(tests, /class MockImageEditProviderContractTest\s*:\s*ImageEditProviderContract\(\)/);
  for (const behavior of ['cancellationIsIdempotentAndTerminal',
    'awaitingPastBoundReportsTimeoutAndCancelsUnderlyingWork',
    'providerRefusalIsNotTransportFailure', 'oversizedOrTooManyCandidatesNeverReachProviderEngine',
    'advancedParameterRequiresTypedSchemaAndValidRange']) {
    assert.ok(tests.includes(behavior), 'Missing JVM regression: ' + behavior);
  }
});

test('AIE-2 provider exposes explicit capabilities, failure taxonomy and typed admission (#187)', () => {
  for (const symbol of ['interface ImageEditProvider', 'interface EditJob', 'sealed interface EditOutcome',
    'data class Refused', 'data class Failed', 'enum class FailureKind',
    'object ImageEditRequestValidator', 'data class Downscale', 'sealed interface OptionSchema']) {
    assert.ok(sources.includes(symbol), 'Missing type: ' + symbol);
  }
});
