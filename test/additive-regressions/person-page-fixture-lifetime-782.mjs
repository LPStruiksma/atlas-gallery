// #782: a PersonPage synthetic faces.db may be reopened by a Compose
// produceState(Dispatchers.IO) while the Activity is being torn down.
// The fixture must outlive the Compose rule, not just the JUnit @After hook.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const source = readFileSync(fileURLToPath(new URL(
  '../../app/src/androidTest/java/nl/atlas/gallery/PersonPageJourneyTest.kt',
  import.meta.url)), 'utf8');

test('PersonPage database fixture encloses Compose lifecycle (#782)', () => {
  const fixtureStart = source.indexOf('private val fixtureLifetime =');
  const ruleStart = source.indexOf('@get:Rule val chain:');
  assert.ok(fixtureStart >= 0 && ruleStart > fixtureStart,
    'fixture rule should own cleanup after all inner rules');
  const lifetime = source.slice(fixtureStart, ruleStart);
  assert.match(lifetime,
    /try\s*\{\s*base\.evaluate\(\)\s*\}\s*finally\s*\{\s*finishFixture\(\)\s*\}/,
    'the outer rule must run fixture cleanup after its wrapped Compose rule');
  const chain = source.slice(ruleStart, source.indexOf('private val instrumentation', ruleStart));
  assert.match(chain, /RuleChain\.outerRule\(fixtureLifetime\)[\s\S]*?\.around\(compose\)/,
    'fixture must be outside the Compose/Activity rule and the keyboard/display rules');
});

test('PersonPage @After detaches routes but does not destroy their database (#782)', () => {
  const finishStart = source.indexOf('private fun finishFixture()');
  const afterStart = source.indexOf('@After fun detachComposedRoutes()');
  const hostStart = source.indexOf('private fun host():');
  assert.ok(finishStart >= 0 && afterStart > finishStart && hostStart > afterStart);
  const finish = source.slice(finishStart, afterStart);
  assert.match(finish, /PeopleScanWorkRuntime\.clear\(\)/);
  assert.match(finish, /store\.close\(\)/);
  assert.match(finish, /dir\.deleteRecursively\(\)/);
  assert.match(finish, /inserted\.forEach/);
  assert.ok(finish.indexOf('store.close()') < finish.indexOf('dir.deleteRecursively()'),
    'close database before deleting its folder');
  const after = source.slice(afterStart, hostStart);
  assert.match(after, /composing = false[\s\S]*?compose\.waitForIdle\(\)/);
  assert.doesNotMatch(after, /PeopleScanWorkRuntime\.clear\(\)|store\.close\(\)|deleteRecursively\(\)|inserted\.forEach/,
    '@After runs before Compose rule teardown, so no database or media deletion here');
});
