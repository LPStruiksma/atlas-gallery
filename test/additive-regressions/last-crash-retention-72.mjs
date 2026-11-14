import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const recorder = readFileSync(
  'app/src/main/java/nl/atlas/gallery/LastCrashRecorder.kt', 'utf8');
const jvmTest = readFileSync(
  'app/src/test/java/nl/atlas/gallery/LastCrashRetentionTest.kt', 'utf8');

// #72: fatal storage is the existing path; handled failures use a separate slot.
assert.match(recorder, /File\(context\.filesDir, "crash\/last\.txt"\)/);
assert.match(recorder, /File\(fatalFile\.parentFile, "handled\.txt"\)/);
assert.match(recorder, /CrashReportStore\(file\(appContext\)\)\.writeFatal\(/);
assert.match(recorder, /CrashReportStore\(file\(appContext\)\)\.writeHandled\(/);
assert.match(recorder, /CrashReportStore\(file\(context\)\)\.read\(\)/);

// The JVM regression exercises the real file read/write sequence, not mock UI text.
assert.match(jvmTest, /store\.writeFatal\(fatal\)/);
assert.match(jvmTest, /store\.writeHandled\(handled\)/);
assert.match(jvmTest, /copyable\.startsWith\(fatal\.trimEnd/);
assert.match(jvmTest, /assertFalse\(copyable, copyable\.contains\("content:\/\/"\)\)/);
console.log('last-crash-retention-72 source contracts passed');
