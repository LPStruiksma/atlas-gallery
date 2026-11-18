import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { chmodSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const script = readFileSync(resolve(root, 'scripts/ci_android_upgrade_emulator.sh'), 'utf8');

// Order: seed and arm inside arm_and_freeze, freeze, re-check BOTH the
// JobScheduler entry and WorkManager's own log while frozen, then SIGKILL.
const sequence = [
  'arm_and_freeze() {',
  'run_kill_phase seed seedBeforeKill || exit 1',
  'scan_job_pending "$scan_job" || {',
  'prekill_pid="$(app_pid)"',
  'kill -STOP "$prekill_pid"',
  'if ! scan_job_pending "$scan_job" || scan_finished_in_process; then',
  'return 2',
  'rc=0; arm_and_freeze || rc=$?',
  'kill -9 "$prekill_pid"',
  'adb shell dumpsys battery set ac 1',
];
let at = 0;
for (const step of sequence) {
  const next = script.indexOf(step, at);
  assert.ok(next >= 0, `#372 requires ${step} in order`);
  at = next + step.length;
}

// After the freeze, only the precondition-miss branch may resume the process.
const freeze = script.indexOf('kill -STOP "$prekill_pid"');
const kill = script.indexOf('kill -9 "$prekill_pid"', freeze);
const successPath = script.slice(freeze, kill).replace(
  /if ! scan_job_pending "\$scan_job" \|\| scan_finished_in_process; then[\s\S]*?\n    fi\n/, ''
);
assert.notEqual(successPath, script.slice(freeze, kill), '#372 precondition-miss branch must be found');
assert.doesNotMatch(successPath, /kill -CONT "\$prekill_pid"/);
// A precondition miss re-seeds at most once, then fails with its own message.
assert.match(script, /"\$freeze_attempt" -lt 2/);
assert.match(script, /scan finished before the process was frozen; re-seed required \(attempt/);
assert.match(script, /#43: SIGKILL worker process \$first mid-run/);
assert.match(script, /#43 no restore after the kill/);

// Behaviour: run the real helper against a fake adb. The #372 log (job still
// listed by JobScheduler, GreedyScheduler already finished the work) must
// count as finished; a scheduled-but-unstarted or mid-run log must not.
const helper = script.match(/  scan_finished_in_process\(\) \{[\s\S]*?\n  \}\n/);
assert.ok(helper, '#372 scan_finished_in_process helper must exist');
const dir = mkdtempSync(join(tmpdir(), 'atlas-372-'));
const fakeAdb = join(dir, 'adb');
writeFileSync(fakeAdb, '#!/bin/sh\ncat "$ATLAS_FAKE_LOG"\n');
chmodSync(fakeAdb, 0o755);
const run = (log) => {
  const logFile = join(dir, 'log.txt');
  writeFileSync(logFile, log);
  try {
    execFileSync('bash', ['-c', `${helper[0]}\nscan_finished_in_process`], {
      env: { ...process.env, PATH: `${dir}:${process.env.PATH}`, ATLAS_FAKE_LOG: logFile },
    });
    return true;
  } catch { return false; }
};
const scheduled = [
  'D/WM-SystemJobScheduler( 2826): Scheduling work ID 3159b5a5-0000-4000-8000-000000000000 (nl.atlas.gallery.featureface.work.PeopleScanWorker), Job ID 2',
  'D/WM-GreedyScheduler( 2826): Constraints met: Scheduling work ID WorkGenerationalId(workSpecId=3159b5a5-0000-4000-8000-000000000000, generation=0)',
  'D/WM-GreedyScheduler( 2826): Starting work for 3159b5a5-0000-4000-8000-000000000000',
  'I/ATLAS_FACE( 2826): publication_done mode=carried items=60',
].join('\n') + '\n';
const finished372 = scheduled +
  'I/WM-WorkerWrapper( 2826): Worker result SUCCESS for Work [ id=3159b5a5-0000-4000-8000-000000000000, tags={ nl.atlas.gallery.featureface.work.PeopleScanWorker } ]\n';
const midRun = scheduled + 'I/ATLAS_FACE( 2826): scan_batch photos=8\n';
const rateLine = midRun + 'I/ATLAS_FACE( 2826): scan_rate photos=60 batches=8 ms=900 photos_per_s=66.67 end=DONE\n';
const otherWorker = scheduled +
  'I/WM-WorkerWrapper( 2826): Worker result SUCCESS for Work [ id=aaaa, tags={ nl.atlas.gallery.featureface.work.PeopleScanRearmWorker } ]\n';
assert.equal(run(finished372), true, '#372: finished in-process scan must be a precondition miss');
assert.equal(run(rateLine), true, 'scan_rate means the worker run ended');
assert.equal(run(scheduled), false, 'scheduled/started scan is still killable');
assert.equal(run(midRun), false, 'mid-run scan is still killable');
assert.equal(run(otherWorker), false, 'the re-arm worker finishing is not the scan');
console.log('scan-kill-precondition-372: source contract and helper behaviour pass');
