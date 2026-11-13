#!/usr/bin/env bash
# Executed as a single command by android-emulator-runner.
# Its 'script:' option invokes each newline separately through /bin/sh;
# multi-line conditionals and Bash strict mode MUST live in this file.
set -euo pipefail

BASE=upgrade-baseline/app/build/outputs/apk/debug/app-debug.apk
NEW=app/build/outputs/apk/debug/app-debug.apk
TEST=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
RUNNER=nl.atlas.gallery.test/androidx.test.runner.AndroidJUnitRunner

# #588: the emulator-ui job runs as shards. Shard 0 also runs the upgrade
# phases; every shard runs its share of the instrumented suite.
# #591: synthetic signer-mismatch recovery is one indivisible, stateful
# upgrade/restore sequence; only the independent UI suite may be sharded.
SHARD_INDEX="${ATLAS_SHARD_INDEX:-0}"
SHARD_COUNT="${ATLAS_SHARD_COUNT:-1}"
RECOVERY_ONLY="${ATLAS_RECOVERY_ONLY:-0}"
[[ "$SHARD_COUNT" =~ ^[1-9]$ && "$SHARD_INDEX" =~ ^[0-9]$ && "$SHARD_INDEX" -lt "$SHARD_COUNT" ]] || {
  echo "::error::Bad shard ${SHARD_INDEX}/${SHARD_COUNT}"; exit 1;
}
echo "Emulator shard $((SHARD_INDEX + 1)) of $SHARD_COUNT"
if [[ "$SHARD_COUNT" -gt 1 && "${ATLAS_SYNTHETIC_MISMATCH:-0}" == "1" ]]; then
  echo "::error::The signer-mismatch lane must run unsharded"; exit 1
fi

# Two independently built APKs on the SAME runner use its debug signing key.
# Passing this is NOT evidence that the key matches a Galaxy S24+ installation.
if [[ "$SHARD_INDEX" == "0" ]]; then
gradle -p upgrade-baseline :app:assembleDebug --no-daemon --stacktrace
gradle :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --stacktrace

adb install -r "$BASE"
adb install -r "$TEST"

run_phase() {
  local phase="$1"
  local method="$2"
  local capture="/tmp/atlas-upgrade-${phase}.txt"
  # Instrumentation can report process crash yet return shell status zero.
  adb logcat -c || true
  local extra=()
  if [[ "$phase" == "restore" ]]; then
    # CSV came from validated synthetic SQLite; never log its Base64 contents.
    extra=(-e atlasRecoveryCsvB64 "$(base64 -w0 "$RECOVERY_CSV")")
  fi
  adb shell am instrument -w -r -e atlasUpgradePhase "$phase" \
    "${extra[@]}" -e class "nl.atlas.gallery.PeopleUpgradeTest#$method" "$RUNNER" \
    > "$capture" 2>&1 || true
  cat "$capture"
  if ! grep -Fq 'OK (1 test)' "$capture"; then
    echo "::error::ATLAS synthetic ${phase} instrumentation did not pass"
    echo "::group::Android emulator crash diagnostics (synthetic test only)"
    timeout 30 adb logcat -d -b crash -t 500 || true
    timeout 30 adb logcat -d -t 1000 |
      grep -E 'FATAL EXCEPTION|AndroidRuntime|PeopleUpgradeTest|TestRunner|am_crash|Process .*crashed|Instrumentation' |
      tail -160 || true
    echo "::endgroup::"
    return 1
  fi
}

run_phase seed seedBeforeUpgrade
adb install -r "$NEW"
run_phase verify verifyAfterUpgrade
echo "PASS: real emulator adb install -r preserved synthetic app-private People SQLite"
else
echo "Shard $SHARD_INDEX: upgrade phases run on shard 0"
fi  # SHARD_INDEX == 0

# Expensive negative-signer scenario is opt-in. Routine Android PRs keep the
# proven same-signature People preservation + fast viewer regressions. The
# signer-mismatch recovery workflow invokes this with opt-in enabled.
if [[ "${ATLAS_SYNTHETIC_MISMATCH:-0}" == "1" ]]; then
# The *second* path proves recovery if the installed APK's signer is NOT
# compatible. All backup and destructive actions are restricted to the
# throwaway emulator fixture, never a connected physical Galaxy.
# Refuse to run if adb targets a non-emulator or multiple devices.
[[ "$(adb devices | awk '/^emulator-[0-9]+[[:space:]]+device$/ { n++ } END { print n+0 }')" == "1" ]] || {
  echo "::error::Expected exactly one isolated Android emulator"; exit 1;
}
[[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" == "1" ]] || {
  echo "::error::Not an emulator; synthetic signer-mismatch migration forbidden"; exit 1;
}
[[ "${GITHUB_ACTIONS:-}" == "true" ]] || {
  echo "::error::Synthetic destructive fixture requires isolated GitHub CI"; exit 1;
}

# Android's VACUUM INTO produces a consistent, standalone SQLite backup,
# including the committed WAL state. Extract *only fictional fixture data*.
run_phase snapshot snapshotBeforeSyntheticSignerMismatch
# The CSV contains fictional names/paths in CI, but the same exporter also
# serves owner-controlled real recovery. Exercise its strict 0700 directory
# contract instead of weakening permissions under a potentially public
# runner temp root. Disposable fixture only; never phone files.
recovery_dir="$(mktemp -d "$RUNNER_TEMP/atlas-recovery-XXXXXXXX")"
chmod 700 "$recovery_dir"
private_fixture="$recovery_dir/atlas-emulator-fixture.sqlite3"
RECOVERY_CSV="$recovery_dir/atlas-emulator-recovery.csv"
adb exec-out run-as nl.atlas.gallery cat files/atlas-ci-people-snapshot.sqlite3 > "$private_fixture"
python3 scripts/validate_native_people_backup.py "$private_fixture" --min-rows 1
python3 scripts/export_native_people_recovery.py "$private_fixture" "$RECOVERY_CSV" --min-rows 1

# Generate an independent, TEMPORARY synthetic signing key (never used to
# sign releases or touch the owner's phone). Delete it with the runner VM.
umask 077
fake_store="$RUNNER_TEMP/atlas-emulator-only-signer-b.jks"
fake_password="$(openssl rand -hex 18)"
keytool -genkeypair -noprompt -keystore "$fake_store" \
  -storepass "$fake_password" -keypass "$fake_password" \
  -alias atlas-emulator-test -keyalg RSA -keysize 2048 -validity 1 \
  -dname "CN=ATLAS CI synthetic signer mismatch,OU=Isolated emulator,O=Fake fixture" \
  >/dev/null 2>&1
apksigner="$ANDROID_HOME/build-tools/36.0.0/apksigner"
old_cert="$("$apksigner" verify --print-certs "$NEW" |
  sed -n 's/^Signer #1 certificate SHA-256 digest: //p')"
[[ -n "$old_cert" ]] || { echo "::error::Candidate A has no verified signer"; exit 1; }

# Keep the synthetic signer B active for *all* subsequent Gradle steps,
# including connectedDebugAndroidTest (which reinstalls APKs). Previously
# this block scoped key B to one Gradle invocation; the final UI test step
# silently reverted to the runner's key A and hit UPDATE_INCOMPATIBLE even
# after the successful test-only People recovery.
export ATLAS_STABLE_SIGNING_PATH="$fake_store"
export ATLAS_STABLE_SIGNING_STORE_PASSWORD="$fake_password"
export ATLAS_STABLE_SIGNING_KEY_ALIAS="atlas-emulator-test"
export ATLAS_STABLE_SIGNING_KEY_PASSWORD="$fake_password"
gradle :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --rerun-tasks --stacktrace
unset fake_password
new_cert="$("$apksigner" verify --print-certs "$NEW" |
  sed -n 's/^Signer #1 certificate SHA-256 digest: //p')"
[[ -n "$new_cert" && "$old_cert" != "$new_cert" ]] || {
  echo "::error::Synthetic test APKs unexpectedly have equal or unreadable certificates"; exit 1;
}

# Demonstrate actual Android enforcement: incompatible in-place APK MUST FAIL.
install_result="$RUNNER_TEMP/atlas-emulator-signing-rejection.txt"
if adb install -r "$NEW" > "$install_result" 2>&1; then
  echo "::error::Android unexpectedly accepted a mismatched synthetic signature"; exit 1
fi
grep -Eqi 'INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match' "$install_result" || {
  echo "::error::Synthetic upgrade failed for a reason OTHER THAN signing mismatch"; exit 1
}
echo "PASS: Android rejected the synthetic incompatible APK signing certificate"

# This is an EXPLICIT DESTRUCTIVE RESET OF FICTIONAL EMULATOR DATA ONLY,
# permitted solely after prior emulator validation AND checked recovery.
[[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" == "1" ]] || exit 1
adb uninstall nl.atlas.gallery
adb uninstall nl.atlas.gallery.test || true
adb install -r "$NEW"
adb install -r "$TEST"
run_phase restore restoreAfterSyntheticSignerMismatch
run_phase verify verifyAfterUpgrade
echo "PASS: incompatible-signer emulator fixture restored every synthetic People assignment"
echo "NOTICE: test does not preserve SAF permissions or other phone settings; owner migration remains blocked"

fi  # ATLAS_SYNTHETIC_MISMATCH

# Shard 0 carries the stateful upgrade proof. After its final verify (also
# after restore in the signer-mismatch lane), discard only the fictional
# upgrade row before any People UI evidence is captured. Other shards never
# seed the upgrade database.
if [[ "$SHARD_INDEX" == "0" ]]; then
  [[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" == "1" ]] || {
    echo "::error::People upgrade fixture cleanup requires an isolated emulator"; exit 1
  }
  run_phase cleanup cleanupAfterUpgradeForPeopleEvidence
fi

# In the dedicated mismatch job the stateful recovery proof ends here.
# Independent instrumentation and UI screenshots run on separate emulator
# shards and are joined by the workflow aggregate, never silently skipped.
if [[ "$RECOVERY_ONLY" == "1" ]]; then
  [[ "${ATLAS_SYNTHETIC_MISMATCH:-0}" == "1" && "$SHARD_COUNT" == "1" ]] || {
    echo "::error::Recovery-only requires one synthetic mismatch emulator"; exit 1;
  }
  echo "PASS: dedicated synthetic signer-mismatch recovery sequence completed"
  exit 0
fi

# #555 opt-in macro benchmark (grey tiles on fast scroll). A head that commits
# scripts/ci-opt-in/thumbnail-fling runs ONLY this benchmark (every shard, so
# each shard is an independent repeat), and the marker is removed again
# before review so the final head runs the full suite below. The
# signer-mismatch job keeps its own full suite.
if [[ -f scripts/ci-opt-in/thumbnail-fling && "${ATLAS_SYNTHETIC_MISMATCH:-0}" != "1" ]]; then
  if [[ "$SHARD_INDEX" != "0" ]]; then
    gradle :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --stacktrace
  fi
  adb install -r "$NEW"
  adb install -r "$TEST"
  fling_capture=/tmp/atlas-thumbnail-fling.txt
  adb logcat -G 16M || true
  adb logcat -c || true
  adb shell am instrument -w -r -e atlas_thumbnail_fling_opt_in true \
    -e class nl.atlas.gallery.featuregrid.GridThumbnailFlingBenchmarkTest "$RUNNER" \
    > "$fling_capture" 2>&1 || true
  cat "$fling_capture"
  echo "::group::ATLAS_SCALE thumbnail fling benchmark (#555)"
  timeout 30 adb logcat -d -s ATLAS_SCALE:V || true
  echo "::endgroup::"
  echo "::group::ATLAS_SHOT mid-fling screenshots, synthetic fixtures (#555)"
  timeout 60 adb logcat -d -s ATLAS_SHOT:V || true
  echo "::endgroup::"
  if ! grep -Fq 'OK (1 test)' "$fling_capture"; then
    echo "::error::#555 thumbnail fling benchmark did not pass"
    timeout 30 adb logcat -d -b crash -t 300 || true
    exit 1
  fi
  echo "NOTICE: opt-in benchmark head; full emulator suite skipped on purpose"
  exit 0
fi

# Preserve normal viewer/filmstrip checks and their real PNG screenshots.
# If the mismatch lane replaced the fixture with signer B, Gradle must
# retain the same environment or it will attempt to install signer A again.
# D2-5p scale lines (FaceIndexStoreScaleAndroidTest) go to logcat; keep a
# buffer large enough for the whole suite and print them even on failure.
adb logcat -G 16M || true
adb logcat -c || true
connected_status=0
shard_args=()
if [[ "$SHARD_COUNT" -gt 1 ]]; then
  shard_args=(-Pandroid.testInstrumentationRunnerArguments.numShards="$SHARD_COUNT"
    -Pandroid.testInstrumentationRunnerArguments.shardIndex="$SHARD_INDEX")
fi
gradle :app:connectedDebugAndroidTest "${shard_args[@]}" --no-daemon --stacktrace || connected_status=$?
echo "::group::ATLAS_SCALE synthetic SQLite measurements"
# Bounded (run 43076916972): if the device died with the tests, plain `adb logcat` blocks on
# "- waiting for device -" until the job cap and turns a red shard into a cancel.
timeout 30 adb logcat -d -s ATLAS_SCALE:I || echo "ATLAS_SCALE logcat unavailable (device gone or timed out)"
echo "::endgroup::"
if [[ "$connected_status" != "0" ]]; then exit "$connected_status"; fi
# #31: the two real-process People/visual/grid/Albums phases cannot run in
# one instrumentation invocation: force-stop must kill the target process
# between them. The test seeds only invented images and its own app-private
# database, while MainActivity and the Android permission publication are real.
# Run after the sharded suite to avoid polluting its MediaStore fixture counts;
# the PNGs still remain for the final evidence pull.
if [[ "$SHARD_INDEX" == "0" ]]; then
  [[ "${GITHUB_ACTIONS:-}" == "true" &&
    "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" == "1" ]] || {
    echo "::error::#31 real-process fixture requires isolated GitHub emulator"; exit 1
  }
  # The connected-test task cleans up its installed APKs before returning.
  # Reinstall both targets before the separately launched process phases.
  adb install -r "$NEW"
  adb install -r "$TEST"
  adb shell pm grant nl.atlas.gallery android.permission.READ_MEDIA_IMAGES
  adb shell pm grant nl.atlas.gallery android.permission.READ_MEDIA_VIDEO
  run_relaunch_phase() {
    local phase="$1" method="$2"
    local capture="/tmp/atlas-relaunch31-$phase.txt"
    adb shell am instrument -w -r -e atlasForceStopPhase "$phase" \
      -e class "nl.atlas.gallery.PeopleForceStopRelaunchTest#$method" "$RUNNER" \
      > "$capture" 2>&1 || true
    cat "$capture"
    if ! grep -Fq 'OK (1 test)' "$capture"; then
      echo "::error::#31 $phase real-process instrumentation failed"
      timeout 30 adb logcat -d -b crash -t 500 || true
      return 1
    fi
  }
  adb logcat -c || true
  run_relaunch_phase seed seedBeforeForceStop
  adb shell am force-stop nl.atlas.gallery
  run_relaunch_phase verify verifyAfterForceStop
  if timeout 30 adb logcat -d -b crash -t 500 |
    grep -E 'FATAL EXCEPTION|Process nl[.]atlas[.]gallery.*crashed'; then
    echo "::error::#31 visual index relaunch emitted a process crash"; exit 1
  fi
  run_relaunch_phase cleanup cleanupAfterForceStop
  # Reset only the disposable app data of this verified emulator. Public
  # synthetic screenshot PNGs stay available for the evidence pull.
  adb shell pm clear nl.atlas.gallery
  echo "PASS: #31 separate-process People, visual, Settings, Photos and Albums relaunch"

  # #43: real process death mid-scan. The seed opts in through the app's own
  # host while unplugged (chain enqueued, nothing scanned). Charging lets
  # WorkManager start the app process for the worker; it is killed with
  # SIGKILL (like LMK/OOM; not force-stop, which cancels jobs) mid-run, and
  # WorkManager must start it again and carry on, with no Activity at all.
  adb shell pm grant nl.atlas.gallery android.permission.READ_MEDIA_IMAGES
  adb shell pm grant nl.atlas.gallery android.permission.READ_MEDIA_VIDEO
  run_kill_phase() {
    local phase="$1" method="$2" extra="${3:-}"
    local capture="/tmp/atlas-kill43-$phase.txt"
    # shellcheck disable=SC2086
    adb shell am instrument -w -r $extra -e atlasKillPhase "$phase" \
      -e class "nl.atlas.gallery.PeopleScanProcessKillTest#$method" "$RUNNER" \
      > "$capture" 2>&1 || true
    cat "$capture"
    if ! grep -Fq 'OK (1 test)' "$capture"; then
      echo "::error::#43 $phase real-process instrumentation failed"
      timeout 30 adb logcat -d -b crash -t 500 || true
      return 1
    fi
  }
  app_pid() { adb shell pidof nl.atlas.gallery 2>/dev/null | tr -d '\r' || true; }
  face_log() { timeout 30 adb logcat -d -v brief -s ATLAS_FACE:I || true; }
  restore_pids() {
    face_log | grep 'background_restore armed=true' |
      sed -E 's/^[^(]*\( *([0-9]+)\).*/\1/' | sort -u
  }
  force_jobs() {
    local id
    for id in $(adb shell dumpsys jobscheduler | tr -d '\r' |
        grep -oE 'JOB #u[0-9]+a[0-9]+/[0-9]+: [0-9a-f]+ nl[.]atlas[.]gallery/' |
        sed -E 's|^JOB #[^/]+/([0-9]+):.*|\1|' | sort -u); do
      adb shell cmd jobscheduler run -f nl.atlas.gallery "$id" >/dev/null 2>&1 || true
    done
  }
  kill_app() {
    local pid; pid="$(app_pid)"
    [[ -z "$pid" ]] && return 0
    adb shell run-as nl.atlas.gallery kill -9 "$pid" || true
    for _ in $(seq 1 20); do [[ -z "$(app_pid)" ]] && return 0; sleep 0.5; done
    echo "::error::#43 could not kill pid $pid"; return 1
  }
  wait_restore_in_new_pid() {
    local known="$1"
    for _ in $(seq 1 24); do
      force_jobs
      for _ in $(seq 1 5); do
        local p; for p in $(restore_pids); do
          if ! grep -qx "$p" <<<"$known"; then echo "$p"; return 0; fi
        done
        sleep 1
      done
    done
    return 1
  }
  adb shell dumpsys battery unplug
  adb logcat -c || true
  # --no-restart: a finished instrumentation otherwise force-stops the target
  # package, which cancels its jobs. The seed process stays; SIGKILL ends it.
  run_kill_phase seed seedBeforeKill --no-restart
  kill_app
  adb shell dumpsys battery set ac 1
  adb shell dumpsys battery set status 2
  kill43_diagnostics() {
    face_log | tail -50
    adb shell dumpsys battery | head -12 || true
    adb shell dumpsys jobscheduler | tr -d '\r' | grep -A6 'nl[.]atlas[.]gallery/' | head -60 || true
    timeout 30 adb logcat -d -t 300 | grep -iE 'nl[.]atlas[.]gallery|WM-' | tail -60 || true
  }
  first="$(wait_restore_in_new_pid "")" || {
    kill43_diagnostics; echo "::error::#43 WorkManager never restored the scan"; exit 1; }
  # Mid-run: the worker has finished at least one batch, not its run.
  for _ in $(seq 1 120); do
    face_log | grep -qE "\( *$first\).*scan_batch photos=[1-9]" && break
    sleep 0.5
  done
  face_log | grep -E "\( *$first\).*scan_batch photos=[1-9]" ||
    { echo "::error::#43 the first worker process scanned nothing"; exit 1; }
  if face_log | grep -E "\( *$first\).*scan_rate"; then
    echo "::error::#43 the first worker run ended before the kill; not mid-scan"; exit 1
  fi
  [[ "$(app_pid)" == "$first" ]] || { echo "::error::#43 worker pid $first gone before kill"; exit 1; }
  echo "#43: SIGKILL worker process $first mid-run"
  kill_app
  second="$(wait_restore_in_new_pid "$first")" || {
    kill43_diagnostics; echo "::error::#43 no restore after the kill"; exit 1; }
  echo "#43: WorkManager restarted the scan in process $second"
  for _ in $(seq 1 120); do
    face_log | grep -qE "\( *$second\).*scan_batch photos=[1-9]" && break
    sleep 1
  done
  face_log | grep -E "\( *$second\).*scan_batch photos=[1-9]" || {
    echo "::error::#43 the restarted worker scanned nothing"; exit 1; }
  adb shell dumpsys battery unplug
  kill_app
  if timeout 30 adb logcat -d | grep -E 'START u0 .*cmp=nl[.]atlas[.]gallery/'; then
    echo "::error::#43 an Activity was started; the resume must not need one"; exit 1
  fi
  if timeout 30 adb logcat -d -b crash -t 500 |
    grep -E 'FATAL EXCEPTION|Process nl[.]atlas[.]gallery.*crashed'; then
    echo "::error::#43 the worker process crashed"; exit 1
  fi
  run_kill_phase verify verifyAfterKill
  adb shell dumpsys battery reset
  run_kill_phase cleanup cleanupAfterKill
  adb shell pm clear nl.atlas.gallery
  echo "PASS: #43 a face scan killed mid-run (SIGKILL) resumed in a new worker process without the Activity"
fi

# UI evidence (#588, #586): discovery and checks live in scripts/ci_ui_evidence.sh;
# the expected names are declared per feature in scripts/ui-evidence.d/.
# A shard pulls only its own screenshots; the aggregate emulator-ui job
# checks that the shards together saved the whole set.
if [[ "$SHARD_COUNT" -gt 1 ]]; then
  bash scripts/ci_ui_evidence.sh pull-some
else
  bash scripts/ci_ui_evidence.sh pull
  bash scripts/ci_ui_evidence.sh verify
fi
