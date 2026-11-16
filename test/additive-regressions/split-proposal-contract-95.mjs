import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

// Goal #95 U2 / #306. This is a static inventory of the landed Kotlin source.
// It does NOT invoke SplitProposer or prove JVM/emulator behavior. All examples
// refer to logical face observations, never images, embeddings or user records.
const source = readFileSync(
  new URL('../../app/src/main/java/nl/atlas/gallery/featurepeople/split/SplitProposer.kt', import.meta.url),
  'utf8',
);

function invariant(name, contracts) {
  test('SplitProposer source contract: ' + name, () => {
    for (const [description, pattern] of contracts) {
      assert.match(source, pattern, name + ': missing/changed ' + description +
        '; inspect SplitProposer.kt and update this audit only after reviewing the Kotlin change');
    }
  });
}

invariant('bounded input and deterministic core sampling', [
  ['MAX_FACES = 50_000', /const val MAX_FACES\s*=\s*50_000\b/],
  ['input-size guard before core processing', /require\(input\.size\s*<=\s*MAX_FACES\)/],
  ['CORE_MAX = 1_500', /const val CORE_MAX\s*=\s*1_500\b/],
  ['core selection entry point', /val core\s*=\s*coreOf\(n,\s*BooleanArray\(n\)\s*\{\s*vetoOf\[it\]\.size\s*>\s*0\s*\}\)/],
  ['small inputs return every core index', /if\s*\(n\s*<=\s*CORE_MAX\)\s*return\s*IntArray\(n\)\s*\{\s*it\s*\}/],
  ['veto-face quota capped at a quarter', /hasVeto\[i\]\s*&&\s*taken\s*<\s*CORE_MAX\s*\/\s*4/],
  ['remaining slots computed from core cap', /val slots\s*=\s*CORE_MAX\s*-\s*taken/],
  ['stable integer-stride selection', /rest\[\(j\.toLong\(\)\s*\*\s*rest\.size\s*\/\s*slots\)\.toInt\(\)\]/],
]);

invariant('observation order independent of caller input', [
  ['sorted observation localHex', /val faces\s*=\s*input\.sortedBy\s*\{\s*it\.embedding\.observation\.localHex\s*\}/],
  ['duplicate observation rejection', /require\(faces\[i\]\.embedding\.observation\s*!=\s*faces\[i\s*-\s*1\]\.embedding\.observation\)/],
  ['stable output group order', /built\.sortWith\(compareByDescending<Pair<Int, ProposedGroup>>\s*\{\s*it\.second\.members\.size\s*\}\s*\.thenBy\s*\{\s*it\.second\.members\.first\(\)\.localHex\s*\}\)/],
  ['stable Unsure observation order', /\.sortedBy\s*\{\s*it\.observation\.localHex\s*\}/],
  ['deterministic Louvain community candidate order', /for\s*\(c\s+in\s+links\.keys\.sorted\(\)\)/],
  ['stable face placement ties', /compareByDescending<Int>\s*\{\s*margin\[it\]\s*\}\.thenBy\s*\{\s*it\s*\}/],
]);

invariant('k=1 means no clear split, not forced two-way grouping', [
  ['fallback core group when no significant candidate remains', /if\s*\(significant\.isEmpty\(\)\)\s*significant\s*=\s*listOf\(Group\(/],
  ['quality-gain fold before choosing k', /significant\s*=\s*foldWeak\(significant,/],
  ['k derived from remaining groups', /val k\s*=\s*significant\.size/],
  ['single-group assignment path', /if\s*\(k\s*==\s*1\)\s*\{[\s\S]*?Assignment\(b,\s*b,[\s\S]*?placed\[i\]\s*=\s*b;\s*continue/],
  ['split requires at least two output groups', /val isSplit:\s*Boolean\s+get\(\)\s*=\s*groups\.size\s*>=\s*2/],
  ['zero separation for fewer than two groups', /val separation\s*=\s*if\s*\(out\.size\s*<\s*2\)\s*0f/],
]);

invariant('cannot-link facts and nonzero same-media keys are hard separators', [
  ['explicit cannot-link fact source', /vetoPairs\(faces,\s*index,\s*request\.facts\.cannotLink\)/],
  ['same-media only when media key is known', /if\s*\(f\.media\s*!=\s*0L\)\s*byMedia\.getOrPut\(f\.media\)/],
  ['all same-media pairs added as vetoes', /for\s*\(list\s+in\s+byMedia\.values\)\s*for\s*\(x\s+in\s+0\s+until\s+list\.size\)\s*for\s*\(y\s+in\s+x\s*\+\s*1\s+until\s+list\.size\)/],
  ['canonical veto pair ordering', /out\s*\+=\s*min\(list\[x\],\s*list\[y\]\)\s*to\s*max\(list\[x\],\s*list\[y\]\)/],
  ['veto graph edges skipped', /if\s*\(vetoMark\[b\]\)\s*continue/],
  ['vetoed community split', /parts\s*\+=\s*splitVetoed\(c,\s*vetoLocal,/],
  ['vetoed candidate groups not joined', /if\s*\(!alive\[b\]\s*\|\|\s*vetoM\[a\]\[b\]\)\s*continue/],
  ['veto merge barriers propagated', /if\s*\(vetoM\[bestB\]\[c\]\)\s*\{\s*vetoM\[bestA\]\[c\]\s*=\s*true;\s*vetoM\[c\]\[bestA\]\s*=\s*true\s*\}/],
  ['weak-fold veto barrier', /if\s*\(blocked\)\s*continue/],
  ['assignment veto barrier', /if\s*\(g\s*>=\s*0\)\s*blocked\[g\]\s*=\s*true/],
]);

invariant('Unsure is explicit for conflict, outlier, weak margin and rejected name', [
  ['no-group Unsure classification', /Assignment\(-1,\s*-1,\s*0f,\s*UnsureReason\.NO_GROUP\)/],
  ['all eligible groups blocked yields conflict', /if\s*\(b\s*<\s*0\)\s*\{\s*out\[i\]\s*=\s*Assignment\(-1,\s*rawBest,\s*0f,\s*UnsureReason\.CONFLICT\)/],
  ['below floor yields outlier', /if\s*\(s\[b\]\s*<\s*t\.outlierFloor\)\s*\{[\s\S]*?UnsureReason\.OUTLIER/],
  ['weak margin yields unsure', /if\s*\(m\s*<\s*t\.unsureMargin\)\s*\{\s*out\[i\]\s*=\s*Assignment\(-1,\s*b,\s*conf,\s*UnsureReason\.LOW_MARGIN\)/],
  ['rejected name redirected to Unsure', /UnsureReason\.REJECTED_FOR_NAME/],
  ['negative assignment collected in unsure list', /else\s+unsure\s*\+=\s*UnsureFace\(faces\[i\]\.embedding\.observation/],
]);

invariant('named group requires confirmed majority with no ambiguous competing claim', [
  ['name share is two thirds', /const val NAME_SHARE\s*=\s*2f\s*\/\s*3f/],
  ['confirmed faces counted by target group or unassigned', /if\s*\(g\s*>=\s*0\)\s*\{\s*counts\[g\]\+\+;\s*confirmedIn\[g\]\+\+\s*\}\s*else\s*counts\[k\]\+\+/],
  ['no named claim without confirmed faces', /if\s*\(g\s*<\s*0\s*\|\|\s*counts\[g\]\s*==\s*0\)\s*continue/],
  ['majority threshold includes unassigned confirmations', /if\s*\(counts\[g\]\s*<\s*ceil\(total\s*\*\s*NAME_SHARE\)\.toInt\(\)\)\s*continue/],
  ['confirmed people in claimed group must not be dominated by a different name', /if\s*\(counts\[g\]\s*\*\s*2\s*<\s*confirmedIn\[g\]\)\s*continue/],
  ['equal competing name counts keep no name', /if\s*\(c\.size\s*>=\s*2\s*&&\s*c\[0\]\.third\s*==\s*c\[1\]\.third\)\s*continue/],
]);
