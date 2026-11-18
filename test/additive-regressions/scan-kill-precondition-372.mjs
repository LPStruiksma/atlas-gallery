import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { chmodSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const script = readFileSync(resolve(root, 'scripts/ci_android_upgrade_emulator.sh'), 'utf8');

// Order: find the scan job and its WorkManager work ID, freeze, re-check
// BOTH JobScheduler and WorkManager's result for that work ID, then SIGKILL.
const sequence = [
  'run_kill_phase seed seedBeforeKill',
  'scan_job="$(scan_job_id)"',
  'scan_work="$(scan_work_id)"',
  'scan_job_pending "$scan_job" || {',
  'prekill_pid="$(app_pid)"',
  'kill -STOP "$prekill_pid"',
  'if ! scan_job_pending "$scan_job" || scan_work_finished "$scan_work"; then',
  'kill -9 "$prekill_pid"',
  'adb shell dumpsys battery set ac 1',
];
let at = 0;
for (const step of sequence) {
  const next = script.indexOf(step, at);
  assert.ok(next >= 0, `#372 requires ${step} in order`);
  at = next + step.length;
}

// After the freeze, only the precondition-miss branch may resume the process,
// and it fails explicitly instead of becoming a later restore failure.
const freeze = script.indexOf('kill -STOP "$prekill_pid"');
const kill = script.indexOf('kill -9 "$prekill_pid"', freeze);
const miss = script.slice(freeze, kill).match(
  /if ! scan_job_pending "\$scan_job" \|\| scan_work_finished "\$scan_work"; then[\s\S]*?\n  fi\n/);
assert.ok(miss, '#372 precondition-miss branch must be found');
assert.match(miss[0], /re-seed required"\n    exit 1/);
assert.doesNotMatch(script.slice(freeze, kill).replace(miss[0], ''), /kill -CONT "\$prekill_pid"/);
assert.match(script, /#43: SIGKILL worker process \$first mid-run/);
assert.match(script, /#43 no restore after the kill/);

// Behaviour: run the real helpers against a fake adb that serves log lines
// copied from CI. #372 (run 44249225139): the killed job's own work already
// SUCCEEDED. Run 44280551540: a first PeopleScanWorker run finished with 0
// photos and chained a new one (Job ID 2), which is the job #43 kills.
const helpers = ['scan_work_id', 'scan_work_finished'].map((name) => {
  const m = script.match(new RegExp(`  ${name}\\(\\) \\{[\\s\\S]*?\\n  \\}\\n`));
  assert.ok(m, `#372 ${name} helper must exist`);
  return m[0];
}).join('');
const dir = mkdtempSync(join(tmpdir(), 'atlas-372-'));
writeFileSync(join(dir, 'adb'), '#!/bin/sh\ncat "$ATLAS_FAKE_LOG"\n');
chmodSync(join(dir, 'adb'), 0o755);
const bash = (log, cmd) => {
  const logFile = join(dir, 'log.txt');
  writeFileSync(logFile, log);
  try {
    return execFileSync('bash', ['-c', `${helpers}\n${cmd}`], {
      env: { ...process.env, PATH: `${dir}:${process.env.PATH}`, ATLAS_FAKE_LOG: logFile },
    }).toString().trim() || 'ok';
  } catch { return null; }
};
const sched = (id, job) => `D/WM-SystemJobScheduler(12139): Scheduling work ID ${id} (nl.atlas.gallery.featureface.work.PeopleScanWorker), Job ID ${job}\n`;
const result = (id) => `I/WM-WorkerWrapper(12139): Worker result SUCCESS for Work [ id=${id}, class=nl.atlas.gallery.featureface.work.PeopleScanWorker, tags={ nl.atlas.gallery.featureface.work.PeopleScanWorker } ]\n`;
const A = 'ecc46b3e-809e-4477-aaa7-f887864e136f';
const B = 'fffef864-35f2-40a7-999e-124e331ad0af';
const R = '3159b5a5-0000-4000-8000-000000000000';
const chained = sched(A, 0) + 'D/WM-WorkerWrapper(12139): Starting work for nl.atlas.gallery.featureface.work.PeopleScanWorker\n' +
  'I/ATLAS_FACE(12139): scan_batch photos=0\n' + result(A) +
  'I/WM-WorkerWrapper(12139): Setting status to enqueued for ' + B + '\n' + sched(B, 2) +
  'I/WM-WorkerWrapper(12139): Worker result SUCCESS for Work [ id=d919d65e-a0ca-46b1-bb9d-7ce22f884279, class=nl.atlas.gallery.featureface.work.PeopleScanRearmWorker, tags={ nl.atlas.gallery.featureface.work.PeopleScanRearmWorker } ]\n';
assert.equal(bash(chained, 'scan_work_id'), `${B} 2`, 'the chained scan job is the one to kill');
assert.equal(bash(chained, `scan_work_finished ${B}`), null, 'chained job not finished: killable');
assert.equal(bash(chained, `scan_work_finished ${A}`), 'ok');
const r372 = sched(R, 2) + 'D/WM-WorkerWrapper( 2826): Starting work for nl.atlas.gallery.featureface.work.PeopleScanWorker\n' + result(R);
assert.equal(bash(r372, 'scan_work_id'), `${R} 2`);
assert.equal(bash(r372, `scan_work_finished ${R}`), 'ok', '#372: finished work is a precondition miss');
assert.equal(bash(sched(R, 2), `scan_work_finished ${R}`), null, 'scheduled only: killable');
assert.equal(bash(r372, 'scan_work_finished ""'), null, 'no work ID never counts as finished');
console.log('scan-kill-precondition-372: source contract and helper behaviour pass');
