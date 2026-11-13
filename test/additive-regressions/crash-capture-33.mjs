import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const read = (path) => readFileSync(path, 'utf8');
const report = read('app/src/main/java/nl/atlas/gallery/LastCrashRecorder.kt');
const app = read('app/src/main/java/nl/atlas/gallery/AtlasApplication.kt');
const settings = read('app/src/main/java/nl/atlas/gallery/AtlasSettingsScreen.kt');
const activity = read('app/src/main/java/nl/atlas/gallery/MainActivity.kt');
assert.match(app, /LastCrashRecorder\.install\(this\)/);
assert.match(report, /Thread\.setDefaultUncaughtExceptionHandler/);
assert.match(report, /previous\?\.uncaughtException/);
assert.match(report, /File\(context\.filesDir, "crash\/last\.txt"\)/);
assert.match(settings, /atlas-settings-last-crash/);
assert.match(settings, /atlas-settings-last-crash-copy/);
assert.match(settings, /atlas-settings-last-crash-share/);
assert.match(activity, /Intent\.ACTION_SEND/);
assert.match(activity, /ClipboardManager/);
console.log('crash-capture-33 source contracts passed');
