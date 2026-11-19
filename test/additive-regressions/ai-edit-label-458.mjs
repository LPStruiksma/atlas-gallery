// AIE-26 (#458): saved AI edits carry a portable AI label (IPTC Digital
// Source Type XMP) and show "Edited with AI" from private lineage only.
// Runtime proof: AiEditXmpTest (JVM), AiEditSaverAndroidTest and
// AiEditMarkerEvidenceTest (emulator).
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const read = (p) => readFileSync(p, 'utf8');
const save = 'app/src/main/java/nl/atlas/gallery/featureaiedit/save/';
const saver = read(save + 'AiEditSaver.kt');
const xmp = read(save + 'AiEditXmp.kt');
const provenance = read(save + 'AiEditProvenance.kt');
const details = read('app/src/main/java/nl/atlas/gallery/ViewerDetailsSheet.kt');
const dna = read('app/src/main/java/nl/atlas/gallery/featurecuration/PhotoDnaReviewScreen.kt');
const policy = read('docs/ai-edit/SAVE_POLICY.md');
const gradle = read('app/build.gradle.kts');

test('saver labels the pending copy after EXIF and before lineage/publish', () => {
  const exif = saver.indexOf('copyExif(candidate.originalUri, inserted, candidate)');
  const label = saver.indexOf('embedAiXmp(inserted, candidate.format)');
  const lineage = saver.indexOf('lineageStore.put(lineage)');
  assert.ok(exif > 0 && label > exif && lineage > label);
  assert.match(saver, /openOutputStream\(target, "wt"\)/);
  assert.doesNotMatch(saver, /openOutputStream\(candidate\.originalUri/);
});

test('XMP carries the IPTC term and nothing private', () => {
  assert.match(xmp, /digitalsourcetype\/compositeWithTrainedAlgorithmicMedia/);
  assert.match(xmp, /Iptc4xmpExt:DigitalSourceType/);
  assert.match(xmp, /http:\/\/ns\.adobe\.com\/xap\/1\.0\/\\u0000/);
  assert.match(xmp, /XML:com\.adobe\.xmp/);
  assert.match(xmp, /VP8X_XMP_FLAG = 0x04/);
  const packet = xmp.slice(xmp.indexOf('val packet'), xmp.indexOf('private val jpegNamespace'));
  assert.ok(packet.length > 100);
  assert.doesNotMatch(packet, /prompt|settingsJson|originalUri|provider|candidate\./);
  assert.doesNotMatch(gradle, /exifinterface|c2pa/i, 'no new metadata dependency');
});

test('marker comes from this URI\'s own lineage row and sits after Name', () => {
  assert.match(provenance, /val edited = own != null && own\.editedUri == uri && own\.originalUri != uri/);
  assert.match(provenance, /if \(!visible\(uri\)\) return null/);
  const name = details.indexOf('ViewerDetailRow("Name", facts.filename)\n            if (aiEdit?.editedWithAi == true) ViewerAiEditedRow()');
  assert.ok(name > 0, 'marker row directly after Name');
  assert.ok(details.indexOf('ViewerAiEditedRow()') < details.indexOf('"Edited from…"'));
  assert.match(details, /internal const val AI_EDITED_LABEL = "Edited with AI"/);
  assert.match(dna, /else AI_EDITED_LABEL/);
  assert.doesNotMatch(dna, /"AI edit"/);
});

test('policy records the decision and claims no C2PA conformance', () => {
  assert.match(policy, /## AI label on saved copies \(AIE-26, #458\)/);
  assert.match(policy, /ATLAS claims no C2PA conformance/);
  for (const where of ['APP1', 'iTXt', 'VP8X']) assert.ok(policy.includes(where), where);
});

test('lineage loading and failure fall back to no marker and are evidenced', () => {
  assert.match(details, /internal val LocalAiEditDetailsReader =/);
  assert.match(details, /catch \(_: Exception\) \{\n\s+null\n\s+\}/);
  const evidence = read('app/src/androidTest/java/nl/atlas/gallery/AiEditMarkerEvidenceTest.kt');
  assert.match(evidence, /aie-26-edit-details-loading\.png/);
  assert.match(evidence, /aie-26-edit-details-lineage-error\.png/);
  assert.match(evidence, /fun assertReadingOrder\(/);
  assert.match(evidence, /KeyEvent\.KEYCODE_BACK/);
});
