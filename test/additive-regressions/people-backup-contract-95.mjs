import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const codec = readFileSync(new URL('../../app/src/main/java/nl/atlas/gallery/featurepersistence/NativePeopleNamesBackup.kt', import.meta.url), 'utf8');
const source = (start, end) => {
  const a = codec.indexOf(start);
  assert.ok(a >= 0, `Missing codec section: ${start}`);
  const b = end ? codec.indexOf(end, a + start.length) : codec.length;
  assert.ok(b > a, `Missing codec section boundary: ${end}`);
  return codec.slice(a, b);
};
const schema = source('data class NativePeopleNamesSnapshot(', '\n)');
const encode = source('fun encode(snapshot:', 'fun decode(document:');
const decode = source('fun decode(document:', 'private fun validate(');
const validate = source('private fun validate(');

// Inventory only: these are invented opaque tokens, not owner People data.
const syntheticFace = 'a'.repeat(32);
const syntheticPerson = 'person_test_01';
assert.match(syntheticFace, /^[0-9a-f]{32}$/);
assert.match(syntheticPerson, /^[a-zA-Z0-9_-]{1,128}$/);

test('baseline v1 header and legacy decoder remain explicitly supported', () => {
  assert.match(codec, /private const val VERSION\s*=\s*1\b/, 'v1 declaration changed: inventory the version migration');
  assert.match(codec, /ATLAS-NATIVE-PEOPLE-NAMES\\n/, 'v1 magic header changed');
  assert.match(encode, /writer\.writeInt\(VERSION\)/, 'encoder must write declared version');
  assert.match(decode, /reader\.readInt\(\) == VERSION/, 'v1 decoder/version rejection missing');
  assert.match(decode, /Unsupported names backup version/, 'unsupported version needs an actionable error');
  assert.match(decode, /NativePeopleNamesSnapshot\(map\(\), set\(\), map\(\), pairs\(\), pairs\(\),\s*pairs\(\), map\(\)\)/, 'seven legacy sections must decode in order');
});

test('baseline checksum is verified before parsing and import preview exposes it', () => {
  assert.match(codec, /fun checksum\(document: ByteArray\)/);
  assert.match(codec, /MessageDigest\.getInstance\("SHA-256"\)/);
  assert.match(encode, /digest\(body\)/, 'encoder must append body digest');
  assert.match(decode, /MessageDigest\.isEqual\(actual, document\.copyOfRange\(end, document\.size\)\)/, 'constant-time digest comparison missing');
  assert.match(decode, /Names backup checksum mismatch/);
  assert.match(codec, /fun preview\(document: ByteArray\)[\s\S]*?val decoded = decode\(document\)/);
});

test('baseline size and cumulative seven-section entry bounds remain enforced', () => {
  assert.match(codec, /MAX_BYTES\s*=\s*32 \* 1024 \* 1024/);
  assert.match(codec, /MAX_ENTRIES\s*=\s*250_000/);
  assert.match(encode, /body\.size <= MAX_BYTES - DIGEST/);
  assert.match(decode, /document\.size in \(magic\.size \+ 7 \* 4 \+ 4 \+ DIGEST\)\.\.MAX_BYTES/);
  assert.match(decode, /n in 0\.\.MAX_ENTRIES && total <= MAX_ENTRIES - n/, 'cumulative section count guard missing');
  assert.match(validate, /s\.names\.size \+ s\.hidden\.size \+ decisionCount\(s\) <= MAX_ENTRIES/);
});

test('baseline rejects duplicate, malformed, identifying and trailing rows', () => {
  for (const pattern of [
    /Duplicate names backup key/, /Duplicate hidden key/, /Duplicate review pair/,
    /Trailing names backup payload/, /Invalid names backup document/,
  ]) assert.match(decode, pattern);
  for (const pattern of [
    /person\.matches\(key\)/, /face\.matches\(key\)/,
    /PeopleReviewRecords\.validName\(name\)/, /Hidden person has no name/,
    /Unordered cannot-link pair/, /Unordered same-person pair/,
    /Invalid native review decision/,
  ]) assert.match(validate, pattern);
  assert.match(codec, /Regex\("\[0-9a-f\]\{32\}"\)/);
  assert.match(codec, /Regex\("\[a-zA-Z0-9_-\]\{1,128\}"\)/);
});

test('baseline serialized schema is human metadata, not media or biometric payload', () => {
  for (const field of ['names', 'hidden', 'confirmed', 'cannotLink', 'rejected', 'sameUnknown', 'faceDecisions']) {
    assert.match(schema, new RegExp('val ' + field + ':'));
    assert.match(encode, new RegExp('snapshot\\.' + field + '\\b'));
  }
  assert.doesNotMatch(schema + encode, /\b(?:uri|path|image|embedding|vector|mediaKey|fileName|photoBytes|crop)\b/i,
    'backup schema/serialization unexpectedly includes media or biometric fields');
  assert.match(codec, /private val person = Regex/);
  assert.match(codec, /private val face = Regex/);
});

test('future HUB-1A MixedGroup/v2 contract is pending #210, not silently claimed shipped', () => {
  // #210 owns migration and behavior. This test must not pretend to verify it.
  const mixedGroupV2Shipped = false;
  assert.equal(mixedGroupV2Shipped, false, 'Do not claim MixedGroup/v2 shipped before #210 verification');
  assert.match(codec, /private const val VERSION\s*=\s*1\b/,
    'Codec version changed: #210 must supply migration tests and update this pending inventory');
  assert.doesNotMatch(schema, /\bMixedGroup\b|\bmixedGroup\b/,
    'MixedGroup appeared in the backup schema: replace pending #210 gate with behavioral proof');
});
