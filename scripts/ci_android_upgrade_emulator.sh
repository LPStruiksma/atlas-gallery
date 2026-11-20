#!/usr/bin/env bash
# Executed as a single command by android-emulator-runner.
# Its 'script:' option invokes each newline separately through /bin/sh;
# multi-line conditionals and Bash strict mode MUST live in this file.
set -euo pipefail

BASE=upgrade-baseline/app/build/outputs/apk/debug/app-debug.apk
NEW=app/build/outputs/apk/debug/app-debug.apk
TEST=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
RUNNER=nl.atlas.gallery.test/androidx.test.runner.AndroidJUnitRunner

# #377: emulator shards resolve dependencies the build job never caches (the
# connected-test configurations), so they hit Maven Central on every run. Gradle
# already retries 429/5xx, but by default only 3 attempts within ~3 s, which a
# rate-limit window outlasts (run 44262648504 shard 0: HTTP 429 on
# kotlin-stdlib-common 1.8.20 before any test ran). Allow 6 attempts with
# 5 s doubling back-off (at most ~2.5 min of waiting) for every Gradle call here.
# Only dependency downloads are retried; test execution is never repeated.
# Retries alone did not hold: on 2026-11-18 shard 0 of run 44280986519 still
# failed every Central POM with 429 within a minute. So every Gradle call here
# also resolves Maven Central through Google's mirror (scripts/ci/central-mirror.init.gradle).
CENTRAL_MIRROR_INIT="$PWD/scripts/ci/central-mirror.init.gradle"
[[ -f "$CENTRAL_MIRROR_INIT" ]] || { echo "::error::Missing $CENTRAL_MIRROR_INIT"; exit 1; }
gradle() {
  command gradle \
    --init-script "$CENTRAL_MIRROR_INIT" \
    -Dorg.gradle.internal.network.retry.max.attempts=6 \
    -Dorg.gradle.internal.network.retry.initial.backOff=5000 \
    "$@"
}

# #143: the same synthetic test APK runs on the installed baseline and the
# candidate at 1080x2340. Save a render for each system theme; the baseline
# intentionally does not assert candidate-only grid geometry.
capture_albums_grid() {
  local revision="$1" mode="$2" original capture night
  [[ "$revision" == "before" || "$revision" == "after" ]] || return 2
  [[ "$mode" == "light" || "$mode" == "dark" ]] || return 2
  original="$(adb shell cmd uimode night | tr -d '\r' | sed -n 's/^Night mode: //p')"
  night=no
  [[ "$mode" == "dark" ]] && night=yes
  adb shell cmd uimode night "$night" >/dev/null
  capture="/tmp/atlas-albums-$revision-$mode.txt"
  adb shell am instrument -w -r -e atlasAlbumsRevision "$revision" \
    -e atlasAlbumsTheme "$mode" \
    -e class "nl.atlas.gallery.AlbumsGridEvidenceTest#threeColumnsAndPinnedAlbumsScreenshot" \
    "$RUNNER" > "$capture" 2>&1 || true
  cat "$capture"
  if [[ "$original" == "yes" || "$original" == "no" || "$original" == "auto" ]]; then
    adb shell cmd uimode night "$original" >/dev/null
  fi
  if ! grep -Fq 'OK (1 test)' "$capture"; then
    echo "::error::#143 Albums $revision $mode render failed"
    timeout 30 adb logcat -d -b crash -t 300 || true
    return 1
  fi
  echo "PASS: #143 $revision $mode screenshot via real installed APK"
}


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

# Capture the old real grid *before* upgrading the app package. These shots
# are retained outside app data, so later pm clear/install and shard joins
# cannot erase the comparison fixtures.
if [[ "${ATLAS_SYNTHETIC_MISMATCH:-0}" != "1" ]]; then
  capture_albums_grid "before" "light"
  capture_albums_grid "before" "dark"
fi
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

# Opt-in stress run (docs/testing/EMULATOR_TESTS.md, "Stress a test"). A
# head that commits scripts/ci-opt-in/stress-classes runs ONLY the tests it
# lists, each as its own instrumentation run, on every shard: each shard is
# an independent emulator, so one dispatch gives 2 x count samples in minutes
# instead of one sample per full suite. One "<count> <Class or Class#method>"
# per line. Remove the file before review: the static guard fails while the file exists.
if [[ -f scripts/ci-opt-in/stress-classes && "${ATLAS_SYNTHETIC_MISMATCH:-0}" != "1" ]]; then
  if [[ "$SHARD_INDEX" != "0" ]]; then
    gradle :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --stacktrace
  fi
  adb install -r "$NEW"
  adb install -r "$TEST"
  adb logcat -G 16M || true
  stress_fail=0
  stress_out=/tmp/atlas-stress.txt
  while read -r stress_count stress_target || [[ -n "${stress_count:-}${stress_target:-}" ]]; do
    [[ -z "${stress_count:-}" || "$stress_count" == \#* ]] && continue
    if [[ ! "$stress_count" =~ ^[0-9]{1,2}$ ||
          ! "${stress_target:-}" =~ ^[A-Za-z0-9_.]+(#[A-Za-z0-9_]+)?$ ]]; then
      echo "::error::Bad scripts/ci-opt-in/stress-classes line: $stress_count ${stress_target:-}"
      exit 1
    fi
    for ((stress_run = 1; stress_run <= stress_count; stress_run++)); do
      adb logcat -c || true
      # </dev/null: adb shell would otherwise read the rest of the list.
      adb shell am instrument -w -r -e class "$stress_target" "$RUNNER" \
        > "$stress_out" 2>&1 < /dev/null || true
      if grep -Eq '^OK \([0-9]+ tests?\)' "$stress_out"; then
        echo "STRESS $stress_target run $stress_run/$stress_count: PASS"
      else
        echo "STRESS $stress_target run $stress_run/$stress_count: FAIL"
        stress_fail=1
        grep -E 'INSTRUMENTATION_STATUS: (test|stack)=|^Error in |^Tests run|^FAILURES|AssertionError|Exception' \
          "$stress_out" | cut -c1-600 | head -60 || true
      fi
      echo "::group::STRESS $stress_target run $stress_run logcat"
      timeout 30 adb logcat -d -v time -s ATLAS_TESTKIT:V ATLAS_PINCH:V ATLAS_GROUP_IME:V \
        ATLAS_TOUCH1:V ATLAS_WINDOWS:V AtlasSystemWindows:V AtlasUiWait:E ImeTracker:I || true
      echo "::endgroup::"
    done
  done < scripts/ci-opt-in/stress-classes
  echo "NOTICE: opt-in stress head; full emulator suite skipped on purpose"
  exit "$stress_fail"
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
echo "::group::ATLAS_PERF10 synthetic MediaStore observer acceptance"
# Synthetic-only metrics/flag categories from GalleryMediaStoreObserverJourneyTest.
# Keep the bounded query even on a failed test so review can read the p50.
timeout 30 adb logcat -d -s ATLAS_PERF10:I || echo "ATLAS_PERF10 logcat unavailable (device gone or timed out)"
echo "::endgroup::"
echo "::group::ATLAS_RESUME620 recents-resume People/visual retention timeline"
# #620: aggregate counts, epoch changes and gap counts only (no IDs or names),
# from PeopleVisualResumeRetentionTest; printed even when the suite failed.
timeout 30 adb logcat -d -s ATLAS_RESUME620:I || echo "ATLAS_RESUME620 logcat unavailable (device gone or timed out)"
echo "::endgroup::"
echo "::group::ATLAS_SCALE synthetic SQLite measurements"
# Bounded (run 43076916972): if the device died with the tests, plain `adb logcat` blocks on
# "- waiting for device -" until the job cap and turns a red shard into a cancel.
timeout 30 adb logcat -d -s ATLAS_SCALE:I || echo "ATLAS_SCALE logcat unavailable (device gone or timed out)"
echo "::endgroup::"
# #417 PERF-7a: debug StrictMode baseline on shard 1 (or an unsharded run).
# Report-only: a violation never fails the shard, and a missing report is a
# warning that Review checks. It runs before the suite's own exit status is
# applied, so an unrelated red test still leaves the baseline in the log.
# The debug provider stays a no-op until this step creates its marker file, so
# the suite above ran without StrictMode.
strictmode_baseline_417() {
  local pkg=nl.atlas.gallery
  # connectedDebugAndroidTest uninstalls its APKs when it returns.
  adb install -r "$NEW" >/dev/null || { echo "::warning::#417 reinstall failed"; return 0; }
  adb install -r "$TEST" >/dev/null || { echo "::warning::#417 test APK reinstall failed"; return 0; }
  local marker=files/atlas-strictmode-baseline
  adb shell pm grant "$pkg" android.permission.READ_MEDIA_IMAGES || true
  adb shell pm grant "$pkg" android.permission.READ_MEDIA_VIDEO || true
  # A fresh install has no files/ directory until the app first runs.
  if ! adb shell run-as "$pkg" sh -c "'mkdir -p files && touch $marker'"; then
    echo "::warning::#417 could not create the debug StrictMode marker"; return 0
  fi
  echo "::group::#417 cold start, am start -W, debug StrictMode on"
  adb logcat -c || true
  local totals=() i out total
  for i in 0 1 2 3 4 5; do
    adb shell am force-stop "$pkg"
    out="$(adb shell am start -W -n "$pkg/.MainActivity" | tr -d '\r' || true)"
    total="$(sed -n 's/^TotalTime: *//p' <<< "$out")"
    echo "launch $i $(grep -E '^(LaunchState|TotalTime):' <<< "$out" | tr '\n' ' ')"
    # Launch 0 is the first after this shard's reinstall; 1-5 are the sample.
    [[ "$i" != "0" && "$total" =~ ^[0-9]+$ ]] && totals+=("$total")
    sleep 4
  done
  if (( ${#totals[@]} > 0 )); then
    printf '%s\n' "${totals[@]}" | sort -n | awk '{v[NR]=$1} END {
      p50=v[int((NR-1)*0.5+0.5)+1]; p95=v[int((NR-1)*0.95+0.5)+1];
      printf "STRICT cold-start n=%d p50_ms=%d p95_ms=%d (am start -W TotalTime, launches 1-%d)\n", NR, p50, p95, NR }'
  else
    echo "STRICT cold-start unavailable: no TotalTime from am start -W"
  fi
  echo "STRICT cold-start violations: $(timeout 30 adb logcat -d -v brief -s StrictMode:D |
    grep -c 'StrictMode policy violation' || true) logged over 6 launches"
  timeout 30 adb logcat -d -v brief -s StrictMode:D | head -n 900 || true
  echo "::endgroup::"
  adb shell am force-stop "$pkg"
  adb logcat -c || true
  local capture=/tmp/atlas-strictmode-417.txt
  adb shell am instrument -w -r -e atlas_strictmode_baseline true \
    -e class nl.atlas.gallery.StrictModeBaselineTest "$RUNNER" > "$capture" 2>&1 || true
  tail -n 40 "$capture"
  echo "::group::#417 first-open flows (ATLAS_STRICT, synthetic MediaStore PNGs)"
  timeout 30 adb logcat -d -v brief -s ATLAS_STRICT:I || echo "ATLAS_STRICT logcat unavailable"
  echo "::endgroup::"
  if ! grep -Fq 'OK (1 test)' "$capture"; then
    echo "::warning::#417 StrictMode baseline test did not pass; no first-open table this run"
    timeout 30 adb logcat -d -b crash -t 300 || true
  fi
  adb shell run-as "$pkg" rm -f "$marker" || true
  adb shell am force-stop "$pkg"
}
if [[ "${ATLAS_SYNTHETIC_MISMATCH:-0}" != "1" &&
  ( "$SHARD_INDEX" == "1" || "$SHARD_COUNT" == "1" ) ]]; then
  strictmode_baseline_417
fi
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

  # #620: a true Android photo-grant loss. Revoking a runtime permission kills
  # the app process, so seed and verify are separate instrumentation runs with
  # a real `pm revoke` between them. Invented PNGs and app-private data only.
  # Re-grant and cleanup run even when verify fails, so nothing after this
  # block inherits a revoked grant; then the phase failure fails the shard.
  adb shell pm grant nl.atlas.gallery android.permission.READ_MEDIA_IMAGES
  adb shell pm grant nl.atlas.gallery android.permission.READ_MEDIA_VIDEO
  run_grant_loss_phase() {
    local phase="$1" method="$2"
    local capture="/tmp/atlas-grantloss620-$phase.txt"
    adb shell am instrument -w -r -e atlasGrantLossPhase "$phase" \
      -e class "nl.atlas.gallery.PeopleVisualResumeRetentionTest#$method" "$RUNNER" \
      > "$capture" 2>&1 || true
    cat "$capture"
    if ! grep -Fq 'OK (1 test)' "$capture"; then
      echo "::error::#620 grant-loss $phase instrumentation failed"
      timeout 30 adb logcat -d -b crash -t 500 || true
      return 1
    fi
  }
  adb logcat -c || true
  grant_loss_status=0
  run_grant_loss_phase seed grantLossSeed || grant_loss_status=1
  if [[ "$grant_loss_status" == "0" ]]; then
    # Each revoke kills the app process, as on a phone.
    adb shell pm revoke nl.atlas.gallery android.permission.READ_MEDIA_IMAGES
    adb shell pm revoke nl.atlas.gallery android.permission.READ_MEDIA_VIDEO
    run_grant_loss_phase verify grantLossVerify || grant_loss_status=1
  fi
  adb shell pm grant nl.atlas.gallery android.permission.READ_MEDIA_IMAGES
  adb shell pm grant nl.atlas.gallery android.permission.READ_MEDIA_VIDEO
  run_grant_loss_phase cleanup grantLossCleanup || grant_loss_status=1
  echo "::group::ATLAS_RESUME620 grant-loss phases"
  timeout 30 adb logcat -d -s ATLAS_RESUME620:I || echo "ATLAS_RESUME620 logcat unavailable (device gone or timed out)"
  echo "::endgroup::"
  adb shell pm clear nl.atlas.gallery
  if [[ "$grant_loss_status" != "0" ]]; then
    echo "::error::#620 true grant-loss phases failed"; exit 1
  fi
  echo "PASS: #620 a real photo-grant revoke hides prior People, visual and gallery data"

  # #43: real process death mid-scan. The seed opts in through the app's own
  # host while unplugged (chain enqueued, nothing scanned). Charging lets
  # WorkManager start the app process for the worker; it is killed with
  # SIGKILL (like LMK/OOM; not force-stop, which cancels jobs) mid-run, and
  # WorkManager must start it again and carry on, with no Activity at all.
  adb shell pm grant nl.atlas.gallery android.permission.READ_MEDIA_IMAGES
  adb shell pm grant nl.atlas.gallery android.permission.READ_MEDIA_VIDEO
  run_kill_phase() {
    local phase="$1" method="$2"
    local capture="/tmp/atlas-kill43-$phase.txt"
    adb shell am instrument -w -r -e atlasKillPhase "$phase" \
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
    # Android 35 reports WorkManager jobs as:
    # JOB androidx.work.systemjobscheduler:u0a150/4: ... @androidx.work.systemjobscheduler@nl.atlas.gallery/...
    # Jobs registered in that namespace cannot be run via the default (null)
    # namespace: cmd jobscheduler run -f nl.atlas.gallery 4 is a no-op.
    local job namespace id result
    while IFS= read -r job; do
      namespace="${job#JOB }"
      namespace="${namespace%%:*}"
      id="${job#*/}"
      id="${id%%:*}"
      if [[ "$namespace" == androidx.work.systemjobscheduler ]]; then
        result="$(adb shell cmd jobscheduler run -f --namespace "$namespace" nl.atlas.gallery "$id" 2>&1)" || {
          echo "::warning::#43 could not run WorkManager job $namespace/$id: $result" >&2
          continue
        }
      else
        result="$(adb shell cmd jobscheduler run -f nl.atlas.gallery "$id" 2>&1)" || {
          echo "::warning::#43 could not run WorkManager job $id: $result" >&2
          continue
        }
      fi
    done < <(adb shell dumpsys jobscheduler | tr -d '\r' |
      grep -E '^ *JOB (androidx[.]work[.]systemjobscheduler:|#)[^[:space:]]*/[0-9]+: [0-9a-f]+ @?[^[:space:]]*nl[.]atlas[.]gallery/' |
      sed -E 's/^ *//')
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
  # #343: WorkManager logs which worker each JobScheduler job is for. The
  # scan worker, not the 6 h re-arm (PeopleScanRearmWorker), is what #43 needs.
  # Capture first: grep -q exiting early would SIGPIPE adb, and pipefail
  # would then report a found line as missing.
  scan_scheduled() {
    local out
    out="$(timeout 30 adb logcat -d -v brief -s WM-SystemJobScheduler:D || true)"
    grep -q 'featureface[.]work[.]PeopleScanWorker)' <<<"$out"
  }
  # #343 follow-up: the WorkManager job id of the scheduled scan worker.
  scan_job_id() {
    local out
    out="$(timeout 30 adb logcat -d -v brief -s WM-SystemJobScheduler:D || true)"
    sed -nE 's/.*featureface[.]work[.]PeopleScanWorker\), Job ID ([0-9]+).*/\1/p' <<<"$out" | tail -1
  }
  scan_job_pending() {
    local id="$1" out
    [[ -n "$id" ]] || return 1
    out="$(adb shell dumpsys jobscheduler | tr -d '\r' || true)"
    grep -qE "^ *JOB androidx[.]work[.]systemjobscheduler:u0a[0-9]+/$id: .*nl[.]atlas[.]gallery/" <<<"$out"
  }
  # #372: JobScheduler keeps listing a job after WorkManager's GreedyScheduler
  # ran the same work in-process, so a pending entry does not prove the scan is
  # unfinished. WorkManager's own result line for that work ID does. Match the
  # ID, not the class: a first PeopleScanWorker run can finish (photos=0) and
  # chain a new one, which is the job #43 kills. Read it while frozen, when no
  # new line can appear. Capture first (SIGPIPE).
  scan_work_id() {
    local out
    out="$(timeout 30 adb logcat -d -v brief -s WM-SystemJobScheduler:D || true)"
    sed -nE 's/.*Scheduling work ID ([0-9a-f-]+) \(nl[.]atlas[.]gallery[.]featureface[.]work[.]PeopleScanWorker\), Job ID ([0-9]+).*/\1 \2/p' <<<"$out" | tail -1
  }
  scan_work_finished() {
    local id="$1" out
    [[ -n "$id" ]] || return 1
    out="$(timeout 30 adb logcat -d -v brief -s WM-WorkerWrapper:I || true)"
    grep -qE "Worker result [A-Z]+ for Work \[ id=$id," <<<"$out"
  }
  # What the first process did before the kill. The log is cleared after
  # the kill, so a later failure prints this capture as well.
  prekill_log=/tmp/atlas-kill43-prekill.txt
  : > "$prekill_log"
  kill43_diagnostics() {
    if [[ -s "$prekill_log" ]]; then
      echo "--- #43 before the kill (ATLAS_FACE and WorkManager) ---"
      cat "$prekill_log"
      echo "--- #43 after the kill ---"
    fi
    face_log | tail -50
    adb shell dumpsys battery | head -12 || true
    adb shell dumpsys jobscheduler | tr -d '\r' | grep -A6 'nl[.]atlas[.]gallery/' | head -60 || true
    # #343: leave out force_jobs' own retry churn, which otherwise fills the
    # tail and hides how the launch enqueued (or rescheduled) its work.
    timeout 30 adb logcat -d -v brief | grep -iE 'ATLAS_FACE|WM-' |
      grep -vE 'before schedule|is ENQUEUED; not doing|executed on JobScheduler|executed; reschedule|onStartJob|processing WorkGenerationalId|Cancelling work ID' |
      tail -80 || true
  }
  adb shell dumpsys battery unplug
  # #372 root cause (run 44291919363 prekill dump: "status: 2" while unplugged):
  # `unplug` clears ac/usb but leaves the emulator's status at CHARGING, and
  # WorkManager's in-process BatteryChargingTracker reads that status. So the
  # charging-constrained scan ran in-process (GreedyScheduler) while
  # "unplugged", finished and chained, racing the kill. Report DISCHARGING so
  # the scan stays a pending JobScheduler job until the replug below.
  adb shell dumpsys battery set status 3
  adb logcat -c || true
  run_kill_phase seed seedBeforeKill
  adb logcat -c || true
  # A finished instrumentation force-stops its target package, which cancels
  # its jobs (and --no-restart cannot start one). So the user's next open
  # arms the scan, as on a phone: launch ATLAS once (still unplugged), wait
  # for its gallery publication to enqueue the chain, go Home, and SIGKILL
  # that process. From here on no Activity may start.
  adb shell am start -W -n nl.atlas.gallery/.MainActivity >/dev/null
  for _ in $(seq 1 60); do
    face_log | grep -q 'publication_done' && break
    sleep 1
  done
  face_log | grep 'publication_done' || {
    kill43_diagnostics; echo "::error::#43 the app never published its catalogue"; exit 1; }
  # #343: publication_done is logged just before the host arms the scan, and
  # WorkManager enqueues on its own background executor, which a loaded
  # emulator can hold for seconds. A fixed 2 s wait sometimes killed the app
  # before the scan job existed (only the 6 h re-arm was left), so wait for
  # the scan worker's job itself. Never kill without it: that fails here.
  for _ in $(seq 1 60); do
    scan_scheduled && break
    sleep 0.5
  done
  scan_scheduled || {
    kill43_diagnostics; echo "::error::#43 the app published but never scheduled the scan worker"; exit 1; }
  sleep 1 # let WorkManager finish persisting the enqueue
  adb shell input keyevent KEYCODE_HOME
  # Fail here, not after the kill, if the scan job is already gone (ran,
  # was cancelled or replaced) while the app was still alive and unplugged.
  scan_job="$(scan_job_id)"
  timeout 30 adb logcat -d -v brief | grep -iE 'ATLAS_FACE|WM-' |
    grep -vE 'before schedule|is ENQUEUED; not doing|executed on JobScheduler|executed; reschedule|onStartJob|processing WorkGenerationalId' |
    tail -80 > "$prekill_log" || true
  scan_job_pending "$scan_job" || {
    kill43_diagnostics
    echo "::error::#43 the scan worker's job ${scan_job:-?} was no longer pending before the kill"; exit 1; }
  # #372: JobScheduler may still show a pending job after GreedyScheduler
  # has already completed it. Freeze the process before the final pending
  # check so the worker cannot finish between that check and SIGKILL.
  prekill_pid="$(app_pid)"
  [[ -n "$prekill_pid" ]] || {
    kill43_diagnostics; echo "::error::#43 no live app process to freeze"; exit 1; }
  adb shell run-as nl.atlas.gallery kill -STOP "$prekill_pid" || {
    kill43_diagnostics; echo "::error::#43 could not freeze pid $prekill_pid"; exit 1; }
  [[ "$(app_pid)" == "$prekill_pid" ]] || {
    kill43_diagnostics; echo "::error::#43 app process changed during freeze"; exit 1; }
  # #372 (runs 44280551540, 44284361706): while the gallery is still pending
  # each PeopleScanWorker run ends at once (photos=0) and chains the next one
  # under a new work ID and Job ID, so the job seen above can finish before
  # the freeze. Re-read the newest scan job now that nothing can change: that
  # is the work #43 kills. It must still be pending in JobScheduler, and
  # WorkManager must not have logged a result for its work ID.
  scan_job="$(scan_job_id)"
  scan_work="$(scan_work_id)"
  [[ "${scan_work##* }" == "$scan_job" ]] || {
    adb shell run-as nl.atlas.gallery kill -CONT "$prekill_pid" || true
    kill43_diagnostics; echo "::error::#43 no WorkManager work ID for frozen scan job ${scan_job:-?}"; exit 1; }
  scan_work="${scan_work%% *}"
  # An already finished scan is a precondition miss, not a restore failure.
  # Both signals are read frozen; only this branch resumes the process.
  if ! scan_job_pending "$scan_job" || scan_work_finished "$scan_work"; then
    adb shell run-as nl.atlas.gallery kill -CONT "$prekill_pid" || true
    kill43_diagnostics
    echo "::error::#43 scan work $scan_work (job $scan_job) finished before the process was frozen; re-seed required"
    exit 1
  fi
  echo "#43: scan worker job $scan_job frozen in pid $prekill_pid; SIGKILL unplugged app"
  adb shell run-as nl.atlas.gallery kill -9 "$prekill_pid"
  for _ in $(seq 1 20); do
    [[ -z "$(app_pid)" ]] && break
    sleep 0.5
  done
  [[ -z "$(app_pid)" ]] || {
    kill43_diagnostics; echo "::error::#43 frozen app process survived SIGKILL"; exit 1; }
  adb logcat -c || true
  adb shell dumpsys battery set ac 1
  adb shell dumpsys battery set status 2
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

# The candidate UI tests above assert three-column geometry and exercise
# Albums menus. Run only the paired theme capture here with the same synthetic
# fixture as the baseline. Shard 1 owns the candidate captures; shard 0 owns
# the pre-upgrade baseline captures. In an unsharded run both happen here.
if [[ "${ATLAS_SYNTHETIC_MISMATCH:-0}" != "1" &&
  ( "$SHARD_INDEX" == "1" || "$SHARD_COUNT" == "1" ) ]]; then
  adb install -r "$NEW"
  adb install -r "$TEST"
  capture_albums_grid "after" "light"
  capture_albums_grid "after" "dark"
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
