import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const script = readFileSync(resolve(root, 'scripts/ci_android_upgrade_emulator.sh'), 'utf8');
const sequence = [
  'scan_job_pending "$scan_job" || {',
  'prekill_pid="$(app_pid)"',
  'kill -STOP "$prekill_pid"',
  'scan_job_pending "$scan_job" || {',
  'kill -9 "$prekill_pid"',
  'adb shell dumpsys battery set ac 1',
];
let at = 0;
for (const step of sequence) {
  const next = script.indexOf(step, at);
  assert.ok(next >= 0, `#372 requires ${step} in order`);
  at = next + step.length;
}
// After the process is frozen, do not unfreeze it on the successful path:
// completion between the last state check and SIGKILL is the original race.
const freeze = script.indexOf('kill -STOP "$prekill_pid"');
const kill = script.indexOf('kill -9 "$prekill_pid"', freeze);
assert.ok(freeze >= 0 && kill > freeze);
assert.doesNotMatch(script.slice(freeze, kill).replace(
  /scan_job_pending "\$scan_job" \|\| \{[\s\S]*?\n  \}/, ''
), /kill -CONT "\$prekill_pid"/);
assert.match(script.slice(freeze, kill), /scan finished before the process was frozen; re-seed required/);
assert.match(script, /#43: SIGKILL worker process \$first mid-run/);
assert.match(script, /#43 no restore after the kill/);
console.log('scan-kill-precondition-372: source contract passes');
