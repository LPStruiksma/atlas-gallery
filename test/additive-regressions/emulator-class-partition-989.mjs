import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';

const result = spawnSync('python3', ['-m', 'unittest', 'discover', '-s', 'scripts', '-p', 'test_ci_suite_partition.py', '-v'], { encoding: 'utf8' });
assert.equal(result.status, 0, result.stdout + result.stderr);
assert.match(result.stderr, /Ran [1-9][0-9]* tests?/);
const script = readFileSync('scripts/ci_android_upgrade_emulator.sh', 'utf8');
const workflow = readFileSync('.github/workflows/build-apk.yml', 'utf8');
assert.match(script, /am instrument -w -r -e log true/);
assert.match(script, /ci_suite_partition\.py select/);
assert.match(script, /android\.testInstrumentationRunnerArguments\.class=/);
assert.match(workflow, /ci_suite_partition\.py verify shards/);
assert.match(workflow, /ci_ui_evidence\.sh verify/);
console.log('PASS: whole-class partition and independent suite identity gate');
