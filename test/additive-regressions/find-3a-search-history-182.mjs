// FIND-3a (#182): static privacy and wiring contract for persistent search history.
// Runs with node --test; the behaviour itself is covered by SearchHistoryTest (JVM).
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
const dir = 'app/src/main/java/nl/atlas/gallery';
const core = readFileSync(`${dir}/featuresearch/SearchHistory.kt`, 'utf8');
const ui = readFileSync(`${dir}/featuresearch/SearchHistoryUi.kt`, 'utf8');
const host = readFileSync(`${dir}/MainActivity.kt`, 'utf8');
const settings = readFileSync(`${dir}/AtlasSettingsScreen.kt`, 'utf8');
const manifest = readFileSync('app/src/main/AndroidManifest.xml', 'utf8');

test('history stays on the phone: app-private file, no backup, no logging or network', () => {
  assert.match(manifest, /android:allowBackup="false"/);
  assert.match(ui, /File\(app\.filesDir, "search-history-v1\.txt"\)/);
  for (const src of [core, ui]) {
    assert.doesNotMatch(src, /android\.util\.Log|\bLog\.[diwev]\(|println\(|printStackTrace/);
    assert.doesNotMatch(src, /java\.net\.|okhttp|HttpURLConnection|SharedPreferences|rememberSaveable/);
  }
});

test('the usage log never reads search history', () => {
  const walk = (d) => readdirSync(d, { withFileTypes: true }).flatMap((e) =>
    e.isDirectory() ? walk(join(d, e.name)) : [join(d, e.name)]);
  const users = walk(dir).filter((f) => f.endsWith('.kt') &&
    /SearchHistoryStore|searchHistoryStoreFor/.test(readFileSync(f, 'utf8')));
  for (const f of users) {
    assert.doesNotMatch(f, /usage/i, `${f} must not touch search history`);
  }
});

test('50 kept and 10 shown per scope; reduced access clears; off clears', () => {
  assert.match(core, /const val SHOWN = 10/);
  assert.match(core, /const val KEPT = 50/);
  assert.match(core, /level\.rank < previous\.rank/);
  assert.match(core, /copy\(enabled = false, scopes = emptyMap\(\)\)/);
  assert.match(host, /searchHistory\.onGrant\(searchGrantLevel\(grants\.images, grants\.videos, grants\.selected\)\)/);
  assert.match(host, /withContext\(Dispatchers\.IO\) \{\s*searchHistoryStore\.load\(\)/);
});

test('owner copy in Settings and Undo snackbars', () => {
  assert.match(settings, /"Save search history"/);
  assert.match(settings, /"Recent searches stay on this phone\."/);
  assert.match(settings, /"Clear search history"/);
  for (const text of ['"Removed"', '"Recent searches cleared"', '"Search history cleared"',
    '"Search history off"']) assert.ok(ui.includes(text), text);
  assert.match(ui, /actionLabel = "Undo"/);
  assert.match(ui, /Remove “\$label” from recent searches/);
  assert.match(ui, /WindowInsets\.ime/);
});

test('every scoped search area names its own history scope', () => {
  const scoped = {
    'featurealbums/AtlasAlbumsScreen.kt': 'ALBUMS',
    'featurepeopleui/PeopleHomeScreen.kt': 'PEOPLE',
    'PersonPageRoute.kt': 'PERSON',
    'featurestories/AtlasStoriesScreen.kt': 'STORIES',
  };
  for (const [file, scope] of Object.entries(scoped)) {
    assert.match(readFileSync(`${dir}/${file}`, 'utf8'),
      new RegExp(`historyScope = SearchHistoryScope\\.${scope}`), file);
  }
  assert.match(host, /historyScope = when \{[\s\S]*?SearchHistoryScope\.PERSON[\s\S]*?SearchHistoryScope\.ALBUM[\s\S]*?SearchHistoryScope\.PHOTOS/);
});
