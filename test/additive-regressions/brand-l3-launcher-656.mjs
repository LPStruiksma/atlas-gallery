// BRAND-L3 #656: Lapis launcher icon and splash stay as briefed.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(fileURLToPath(new URL('.', import.meta.url)), '..', '..');
const read = (p) => readFileSync(join(root, p), 'utf8');
const res = 'app/src/main/res/';

test('manifest: only the application theme points at the launch theme; label and icons unchanged', () => {
  const manifest = read('app/src/main/AndroidManifest.xml');
  const app = manifest.match(/<application[\s\S]*?>/)[0];
  assert.match(app, /android:theme="@style\/Theme\.Atlas\.Launch"/);
  assert.match(app, /android:label="ATLAS"/);
  assert.match(app, /android:icon="@mipmap\/ic_launcher"/);
  assert.match(app, /android:roundIcon="@mipmap\/ic_launcher_round"/);
  assert.doesNotMatch(manifest.replace(app, ''), /Theme\.Atlas\.Launch/, 'no activity-level launch theme');
});

test('icon layers: solid Lapis ground, whole mark centre-scaled 0.77 with stroke 8, same mono geometry', () => {
  assert.match(read(res + 'values/colors.xml'), /name="atlas_lapis">#FF2639C9</);
  const bg = read(res + 'drawable/ic_launcher_background.xml');
  assert.match(bg, /@color\/atlas_lapis/);
  assert.doesNotMatch(bg, /gradient/);
  const strip = (s) => s.replace(/<!--[\s\S]*?-->/g, '').replace(/#FF(FFFFFF|000000)/g, 'INK');
  const fg = read(res + 'drawable/ic_launcher_foreground.xml');
  const mono = read(res + 'drawable/ic_launcher_monochrome.xml');
  assert.equal(strip(fg), strip(mono), 'monochrome layer repeats the colour geometry in one tone');
  for (const attr of ['pivotX="54"', 'pivotY="54"', 'scaleX="0.77"', 'scaleY="0.77"', 'strokeWidth="8"',
    'M30,84L54,60L78,84', 'M54,40m-15,0', 'strokeLineCap="round"', 'strokeLineJoin="round"']) {
    assert.ok(fg.includes(attr), `foreground has ${attr}`);
  }
  for (const f of ['ic_launcher.xml', 'ic_launcher_round.xml']) {
    assert.match(read(res + 'mipmap-anydpi-v26/' + f), /<monochrome android:drawable="@drawable\/ic_launcher_monochrome"/);
  }
});

test('splash: static Lapis on both API families, no branding image, no animation, post-launch window kept', () => {
  const legacy = read(res + 'values/themes.xml');
  assert.match(legacy, /parent="@android:style\/Theme\.Material\.NoActionBar"/);
  assert.match(legacy, /windowBackground">@drawable\/atlas_launch_background</);
  const layers = read(res + 'drawable/atlas_launch_background.xml');
  assert.match(layers, /@color\/atlas_lapis/);
  assert.match(layers, /gravity="center"[\s\S]*@drawable\/ic_launcher_foreground/);
  const v31 = read(res + 'values-v31/themes.xml');
  assert.match(v31, /parent="@android:style\/Theme\.Material\.NoActionBar"/);
  assert.match(v31, /windowSplashScreenBackground">@color\/atlas_lapis</);
  assert.match(v31, /windowSplashScreenAnimatedIcon">@drawable\/ic_launcher_foreground</);
  assert.doesNotMatch(v31, /<item name="android:windowBackground"|windowSplashScreenBrandingImage|windowSplashScreenAnimationDuration/);
  // #763 review: light system-bar glyphs on the dark Lapis splash in both night modes.
  for (const theme of [legacy, v31]) {
    assert.match(theme, /windowLightStatusBar">false</);
    assert.match(theme, /windowLightNavigationBar">false</);
  }
  assert.ok(!existsSync(join(root, res, 'drawable/ic_launcher_foreground_animated.xml')));
});

test('no splash-screen library dependency was added', () => {
  assert.doesNotMatch(read('app/build.gradle.kts'), /core-splashscreen/);
});
