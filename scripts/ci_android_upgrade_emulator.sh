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

# BRAND-L2 #655: test the real installed old/new APKs with identical
# instrumentation and synthetic fixtures. Captures are genuine S24+ renders.
capture_brand_l2() {
  local revision="$1" mode="$2" scale="$3" capture class_name before_scale font_scale
  [[ "$revision" == "before" || "$revision" == "after" ]] || return 2
  [[ "$mode" == "light" || "$mode" == "dark" ]] || return 2
  [[ "$scale" == "100" || "$scale" == "200" ]] || return 2
  before_scale="$(adb shell settings get system font_scale | tr -d '\r')"
  font_scale=1.0
  [[ "$scale" == "200" ]] && font_scale=2.0
  adb shell settings put system font_scale "$font_scale"
  adb shell am force-stop nl.atlas.gallery || true
  class_name="nl.atlas.gallery.SystemLightThemeJourneyTest"
  [[ "$mode" == "dark" ]] && class_name="nl.atlas.gallery.SystemDarkThemeJourneyTest"
  capture="/tmp/atlas-brand-l2-$revision-$mode-$scale.txt"
  adb shell am instrument -w -r -e atlasBrandRevision "$revision" \
    -e atlasBrandScale "$scale" \
    -e class "$class_name#brandL2PairedEvidence" "$RUNNER" > "$capture" 2>&1 || true
  cat "$capture"
  if [[ "$before_scale" =~ ^[0-9]+([.][0-9]+)?$ ]]; then
    adb shell settings put system font_scale "$before_scale"
  else
    adb shell settings delete system font_scale
  fi
  adb shell am force-stop nl.atlas.gallery || true
  if ! grep -Fq 'OK (1 test)' "$capture"; then
    echo "::error::BRAND-L2 $revision $mode $scale controlled capture failed"
    timeout 30 adb logcat -d -b crash -t 300 || true
    return 1
  fi
  echo "PASS: BRAND-L2 $revision $mode $scale S24+ real synthetic capture"
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
  for mode in light dark; do
    for scale in 100 200; do capture_brand_l2 before "$mode" "$scale"; done
  done
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
      timeout 30 adb logcat -d -v time -s ATLAS_TESTKIT:V ATLAS_PERF602:V ATLAS_PINCH:V ATLAS_GROUP_IME:V ATLAS_EDIT2:V \
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
echo "::group::ATLAS_PERF602 cold-start checkpoint vs full rescan, 2,000 synthetic images (#602)"
# ColdStartCheckpointBenchmarkTest: synthetic-only timings; printed even on failure.
timeout 30 adb logcat -d -s ATLAS_PERF602:I || echo "ATLAS_PERF602 logcat unavailable (device gone or timed out)"
echo "::endgroup::"
echo "::group::ATLAS_EDIT2 editor renderer agreement and EMULATOR timings (#632)"
# EditorRendererGpuTest/EditorRendererBudgetTest: diff maxima and BUDGETS.md records
# on synthetic images only; printed even when the suite failed.
timeout 30 adb logcat -d -s ATLAS_EDIT2:I || echo "ATLAS_EDIT2 logcat unavailable (device gone or timed out)"
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

# BRAND-L3 / U2 #986: the platform starting window exists outside the app process, so its
# cold-start proof must also run outside instrumentation. In-process force-stop terminates the
# target package that hosts the instrumentation. Keep this independent from test quarantine.
lapis_cold_start_splash_986() {
  local pkg=nl.atlas.gallery remote=/data/local/tmp/atlas-ui-evidence
  local original density theme night launch frame start_out start_pid selected note
  local launch_failed=0 splash_failed=0

  adb install -r "$NEW" >/dev/null
  adb shell mkdir -p "$remote"
  original="$(adb shell cmd uimode night | tr -d '\r' | sed -n 's/^Night mode: //p')"
  density="$(adb shell wm density | tr -d '\r' | sed -n 's/.*Physical density: *//p' | tail -1)"
  [[ "$density" =~ ^[0-9]+$ ]] || { echo "::error::U2 splash could not read display density"; return 1; }

  # Preserve #925's lifecycle boundary: start no task until Launcher owns a stable focus and no
  # system ANR window is present. This keeps Quickstep transitions out of consecutive launches.
  await_launcher_idle_925() {
    local previous= focus= stable=0 deadline=$((SECONDS + 15))
    adb shell input keyevent KEYCODE_HOME
    while (( SECONDS < deadline )); do
      # `dumpsys window windows` carries no focus lines on API 35; plain `dumpsys window` does
      # (same source as SystemWindows.kt).
      focus="$(adb shell dumpsys window | tr -d '\r' | sed 's/^[[:space:]]*//' |
        grep -E '^(mCurrentFocus|mFocusedApp)=' | sort -u | head -2 || true)"
      if grep -Eqi "isn't responding|Application Not Responding|Application Error" <<<"$focus"; then
        echo "::error::U2 splash found a system ANR: $focus"; return 1
      fi
      if grep -Eq 'com[.]android[.](launcher3|quickstep)' <<<"$focus" && [[ "$focus" == "$previous" ]]; then
        stable=$((stable + 1))
        (( stable >= 2 )) && return 0
      else
        stable=0
      fi
      previous="$focus"
      sleep 0.5
    done
    echo "::error::U2 splash Launcher did not become idle: $focus"
    return 1
  }

  # Same evidence as the former Kotlin assertion: >=70% Lapis away from the centre, a white
  # centre mark, and at least 40 sampled near-white pixels in the 48dp navigation band.
  is_static_lapis_splash() {
    python3 - "$1" "$density" <<'PY'
import struct, sys, zlib
path, density = sys.argv[1], int(sys.argv[2]) / 160
raw = open(path, "rb").read()
if not raw.startswith(b"\x89PNG\r\n\x1a\n"):
    raise SystemExit(2)
pos, header, data = 8, None, bytearray()
while pos < len(raw):
    size = struct.unpack(">I", raw[pos:pos + 4])[0]
    kind, body = raw[pos + 4:pos + 8], raw[pos + 8:pos + 8 + size]
    pos += 12 + size
    if kind == b"IHDR": header = body
    elif kind == b"IDAT": data.extend(body)
    elif kind == b"IEND": break
w, h, depth, colour, _, _, interlace = struct.unpack(">IIBBBBB", header)
if depth != 8 or colour not in (2, 6) or interlace:
    raise SystemExit(2)
channels = 3 if colour == 2 else 4
packed, stride = zlib.decompress(data), w * channels
rows, offset, prior = [], 0, bytearray(stride)
for _ in range(h):
    mode, scan = packed[offset], bytearray(packed[offset + 1:offset + 1 + stride])
    offset += stride + 1
    for i in range(stride):
        left = scan[i - channels] if i >= channels else 0
        up = prior[i]
        ul = prior[i - channels] if i >= channels else 0
        if mode == 1: scan[i] = (scan[i] + left) & 255
        elif mode == 2: scan[i] = (scan[i] + up) & 255
        elif mode == 3: scan[i] = (scan[i] + ((left + up) >> 1)) & 255
        elif mode == 4:
            p = left + up - ul; pa, pb, pc = abs(p-left), abs(p-up), abs(p-ul)
            scan[i] = (scan[i] + (left if pa <= pb and pa <= pc else up if pb <= pc else ul)) & 255
        elif mode != 0: raise SystemExit(2)
    rows.append(scan); prior = scan
def rgb(x, y):
    i = x * channels
    return tuple(rows[y][i:i + 3])
def near(a, b): return all(abs(x-y) <= 12 for x, y in zip(a, b))
margin, lapis, samples = h // 8, 0, 0
for y in range(margin, h - margin, 60):
    for x in range(0, w, 60):
        samples += 1; lapis += near(rgb(x, y), (38, 57, 201))
cx, cy, radius, white = w // 2, h // 2, w // 6, 0
for y in range(cy - radius, cy + radius, 6):
    for x in range(cx - radius, cx + radius, 6):
        white += near(rgb(x, y), (255, 255, 255))
glyphs, band = 0, int(48 * density)
for y in range(h - band, h, 2):
    for x in range(0, w, 2): glyphs += min(rgb(x, y)) >= 190
print(f"lapis={lapis}/{samples} white={white} nav_glyphs={glyphs}")
# Blocking: the splash is present (Lapis field + white mark). Nav-bar glyph contrast is reported
# separately and is non-blocking until #997 is fixed (decision on #995, 2026-11-22).
raise SystemExit(0 if lapis * 10 >= samples * 7 and white > 20 else 1)
PY
  }

  echo "::group::U2 static Lapis cold-start splash (external emulator shell)"
  for theme in light dark; do
    night=no; [[ "$theme" == dark ]] && night=yes
    adb shell cmd uimode night "$night" >/dev/null
    await_launcher_idle_925 || splash_failed=1
    for launch in 1 2 3; do
      adb shell am force-stop "$pkg"
      start_out="/tmp/atlas-lapis-$theme-$launch-start.txt"
      adb shell am start -W -n "$pkg/.MainActivity" >"$start_out" 2>&1 &
      start_pid=$!
      mkdir -p "/tmp/atlas-lapis-$theme-$launch"
      # Capture immediately and concurrently with am start -W while the platform owns the window.
      for frame in $(seq 1 16); do
        adb exec-out screencap -p > "/tmp/atlas-lapis-$theme-$launch/$frame.png"
      done
      launch_failed=0
      wait "$start_pid" || launch_failed=1
      if [[ "$launch_failed" == 0 ]] && ! grep -Fq 'Status: ok' "$start_out"; then launch_failed=1; fi
      selected=
      frame_notes=
      if [[ "$launch_failed" == 0 ]]; then
        for frame in $(seq 1 16); do
          frame="/tmp/atlas-lapis-$theme-$launch/$frame.png"
          if note="$(is_static_lapis_splash "$frame")"; then selected="$frame"; break; fi
          frame_notes+=" ${frame##*/}:[${note:-unreadable}]"
        done
      fi
      if [[ -n "$selected" ]]; then
        echo "PASS: U2 splash $theme launch $launch/3 ($note)"
        nav_glyphs="${note##*nav_glyphs=}"
        if [[ "$nav_glyphs" =~ ^[0-9]+$ ]] && (( nav_glyphs >= 40 )); then
          echo "U2 NAV-CONTRAST $theme launch $launch/3: PASS (nav_glyphs=$nav_glyphs, non-blocking, #997)"
        else
          echo "::warning::U2 NAV-CONTRAST $theme launch $launch/3: FAIL (nav_glyphs=${nav_glyphs:-?} < 40 near-white pixels in the nav band; non-blocking, #997)"
        fi
        adb push "$selected" "$remote/IMG_20010101_000000_lapis656-splash-$theme.png" >/dev/null
      else
        echo "FAIL: U2 splash $theme launch $launch/3 (no qualifying frame; am-start status=$launch_failed)"
        echo "U2 splash $theme launch $launch frames (capture order):$frame_notes"
        cat "$start_out"
        splash_failed=1
      fi
      adb shell am force-stop "$pkg"
      await_launcher_idle_925 || splash_failed=1
    done
  done
  if [[ "$original" == yes || "$original" == no || "$original" == auto ]]; then
    adb shell cmd uimode night "$original" >/dev/null
  fi
  echo "::endgroup::"
  return "$splash_failed"
}

if [[ "${ATLAS_SYNTHETIC_MISMATCH:-0}" != "1" &&
  ( "$SHARD_INDEX" == "1" || "$SHARD_COUNT" == "1" ) ]]; then
  strictmode_baseline_417
  lapis_cold_start_splash_986
fi
if [[ "$connected_status" != "0" ]]; then exit "$connected_status"; fi
# PERF-A1A #546: run only on shard 0, after the full connected suite.
# The shell owns process-cold boundaries (the test runner cannot kill itself).
# Every destructive action is scoped to one GitHub-hosted emulator.
run_perf_a1_cached_frame() (
  set -euo pipefail
  local pkg=nl.atlas.gallery
  [[ "${GITHUB_ACTIONS:-}" == "true" ]] || {
    echo "::error::PERF-A1A requires a hosted emulator"; exit 1;
  }
  [[ "$(adb devices | awk '/^emulator-[0-9]+[[:space:]]+device$/ { n++ } END { print n+0 }')" == "1" ]] || {
    echo "::error::PERF-A1A requires exactly one emulator"; exit 1;
  }
  [[ "$(adb devices | awk '$2 == "device" { n++ } END { print n+0 }')" == "1" ]] || {
    echo "::error::PERF-A1A refuses any second connected device"; exit 1;
  }
  [[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" == "1" ]] || {
    echo "::error::PERF-A1A cannot run on a handset"; exit 1;
  }
  local head model api
  head="$(git rev-parse HEAD)"
  [[ "$head" =~ ^[0-9a-f]{40}$ ]] || { echo "::error::Unknown app/test head"; exit 1; }
  model="$(adb shell getprop ro.product.model | tr -cd '[:alnum:]_.-')"
  api="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
  [[ -n "$model" && "$api" =~ ^[0-9]+$ ]] || {
    echo "::error::Cannot identify hosted emulator model/API"; exit 1;
  }

  local seeded=0
  a1_phase() {
    local phase="$1" method="$2"
    local capture="/tmp/atlas-perf-a1-${phase}.txt"
    adb shell am instrument -w -r -e atlasPerfA1Phase "$phase" \
      -e class "nl.atlas.gallery.PerfA1CachedFirstFrameBenchmarkTest#$method" \
      "$RUNNER" > "$capture" 2>&1 || true
    if ! grep -Fq 'OK (1 test)' "$capture"; then
      cat "$capture"
      echo "::error::PERF-A1A $phase instrumentation did not pass"
      timeout 30 adb logcat -d -b crash -t 200 || true
      return 1
    fi
  }
  a1_finish() {
    local status="$?"
    trap - EXIT
    if [[ "$seeded" == "1" ]]; then
      a1_phase cleanup cleanupOnlyPerfA1Fixture || status=1
    fi
    # The fixture marker/cache and grant are disposable; never leave them for
    # the following #31 / #43 test lanes, even on a failing benchmark.
    adb shell pm clear "$pkg" >/dev/null || status=1
    if [[ "$status" == "0" ]]; then
      echo "PASS: PERF-A1A completed-frame witness, synthetic cached Pictures, five distinct cold PIDs, warm control, refresh and cleanup"
    fi
    exit "$status"
  }
  trap a1_finish EXIT

  adb shell pm clear "$pkg" >/dev/null
  adb shell pm grant "$pkg" android.permission.READ_MEDIA_IMAGES
  adb shell pm grant "$pkg" android.permission.READ_MEDIA_VIDEO
  seeded=1
  a1_phase seed seedCachedPicturesOutsideTimedWindow

  local -a times=() pids=()
  local i attempt pid capture line ms logged_pid ready refreshed
  # A cold launch whose first Photos draw was already the *fresh* publication
  # (the cache was never drawn) measures nothing: it is an invalid attempt, not
  # a slow sample. Allow at most two such attempts per run, each a new process,
  # each printed; they never reach the latency aggregate. A cached draw whose
  # completed frame cannot be matched stays a hard failure.
  local invalid_attempts=0 max_invalid_attempts=2
  while (( ${#times[@]} < 5 )); do
    i=$(( ${#times[@]} + 1 ))
    if (( i > 1 )); then
      # The previous verify was in another runner process; rewrite the same
      # synthetic cache before this sample's timer starts.
      a1_phase seed seedCachedPicturesOutsideTimedWindow
    fi
    adb shell am force-stop "$pkg"
    adb logcat -c || true
    adb shell am start -W -n "$pkg/.MainActivity" >/dev/null
    pid="$(adb shell pidof "$pkg" | tr -d '\r')"
    [[ "$pid" =~ ^[0-9]+$ ]] || { echo "::error::Missing cold app PID"; exit 1; }
    for prior in "${pids[@]}"; do
      [[ "$pid" != "$prior" ]] || {
        echo "::error::Reused PID across independent cold samples"; exit 1;
      }
    done
    pids+=("$pid")
    line=""
    refreshed=0
    for attempt in $(seq 1 180); do
      capture="$(timeout 30 adb logcat -d -v raw -s ATLAS_PERF_A1:I || true)"
      line="$(printf '%s\n' "$capture" | grep "^mode=cold pid=$pid " | tail -1 || true)"
      if printf '%s\n' "$capture" | grep -Fxq "event=refreshed pid=$pid grant=1"; then
        refreshed=1
      fi
      if [[ -n "$line" && "$refreshed" == "1" ]]; then break; fi
      sleep 0.5
    done
    [[ "$refreshed" == "1" ]] || {
      echo "::error::PERF-A1A fresh background publication did not follow cold render"; exit 1;
    }
    if [[ "$line" =~ ^mode=cold[[:space:]]pid=([0-9]+)[[:space:]]elapsed_ms=([0-9]+[.][0-9]+)[[:space:]]cached=1[[:space:]]draw=grid[[:space:]]frame=completed$ ]]; then
      logged_pid="${BASH_REMATCH[1]}"
      ms="${BASH_REMATCH[2]}"
    else
      # A missing completed cached frame is an unsuccessful measured sample,
      # never a fast latency. These debug-only app events distinguish an
      # absent cached draw from an unmatched completed frame.
      local draw_seen=0 match_seen=0
      if printf '%s\n' "$capture" | grep -Fxq "event=cached_draw pid=$pid"; then
        draw_seen=1
      fi
      if printf '%s\n' "$capture" | grep -Fxq "event=vsync_match pid=$pid"; then
        match_seen=1
      fi
      echo "ATLAS_PERF_A1 sample mode=cold index=$i pid=$pid outcome=no_completed_cached_frame cached_draw=$draw_seen vsync_match=$match_seen refreshed=$refreshed emulator=$model api=$api head=$head"
      if [[ "$draw_seen" == "0" && "$refreshed" == "1" ]] &&
         (( invalid_attempts < max_invalid_attempts )); then
        invalid_attempts=$(( invalid_attempts + 1 ))
        echo "ATLAS_PERF_A1 invalid_attempt index=$i pid=$pid reason=fresh_publication_before_cached_draw count=$invalid_attempts max=$max_invalid_attempts retry=new_process"
        continue
      fi
      # At most four snapshots from this exact PID; diagnostic lines cannot
      # satisfy the completed-frame requirement or enter latency aggregates.
      printf '%s\n' "$capture" | grep "^event=frame_diag pid=$pid " | tail -4 || true
      echo "::error::PERF-A1A has no attributable completed cached grid frame"; exit 1;
    fi
    [[ "$logged_pid" == "$pid" && "$(adb shell pidof "$pkg" | tr -d '\r')" == "$pid" ]] || {
      echo "::error::PERF-A1A cached draw belonged to a different process"; exit 1;
    }
    times+=("$ms")
    echo "ATLAS_PERF_A1 sample mode=cold index=$i pid=$pid ms=$ms n=1 frame=completed cached=1 emulator=$model api=$api head=$head"

    if (( i == 5 )); then
      # A separate control: return the Activity, *without* force-stopping the
      # same PID. The app's debug witness rearms on Activity restart.
      adb shell input keyevent KEYCODE_HOME
      adb shell am start -W -n "$pkg/.MainActivity" >/dev/null
      [[ "$(adb shell pidof "$pkg" | tr -d '\r')" == "$pid" ]] || {
        echo "::error::Warm return unexpectedly relaunched the process"; exit 1;
      }
      local warm="" warm_ms
      for attempt in $(seq 1 120); do
        capture="$(timeout 30 adb logcat -d -v raw -s ATLAS_PERF_A1:I || true)"
        warm="$(printf '%s\n' "$capture" | grep "^mode=warm pid=$pid " | tail -1 || true)"
        [[ -n "$warm" ]] && break
        sleep 0.5
      done
      if [[ "$warm" =~ ^mode=warm[[:space:]]pid=([0-9]+)[[:space:]]elapsed_ms=([0-9]+[.][0-9]+)[[:space:]]cached=[01][[:space:]]draw=grid[[:space:]]frame=completed$ ]]; then
        warm_ms="${BASH_REMATCH[2]}"
      else
        # Preserve the strict warm-frame gate, but retain bounded numeric
        # evidence from the same PID so Review/Strategy can distinguish an
        # absent eligible draw, missing callbacks, vsync mismatch or invalid
        # completion. These diagnostics never count as a warm sample.
        echo "ATLAS_PERF_A1 sample mode=warm pid=$pid outcome=no_completed_grid_frame emulator=$model api=$api head=$head"
        printf '%s\\n' "$capture" | grep -E "^event=(frame_diag|cached_draw|vsync_match) pid=$pid( |$)" | tail -6 || true
        # Hosted run 45040443795 (a6523d0): five valid cold samples, but a plain
        # HOME + return does not necessarily redraw the already-composed grid,
        # so no eligible warm draw exists to witness. The warm return is a
        # same-PID *control*, not the measured cold metric: report it as
        # unobserved (never as a latency) and keep the cold acceptance intact.
        echo "::warning::PERF-A1A warm control unobserved: no completed grid frame after same-PID return (control only; cold samples unaffected)"
        warm_ms=""
      fi
      if [[ -n "$warm_ms" ]]; then
        echo "ATLAS_PERF_A1 sample mode=warm pid=$pid ms=$warm_ms n=1 frame=completed emulator=$model api=$api head=$head"
      else
        echo "ATLAS_PERF_A1 sample mode=warm pid=$pid outcome=unobserved_control emulator=$model api=$api head=$head"
      fi
    fi
    a1_phase verify verifyFreshPicturesAndCacheAfterColdFrame
  done
  [[ "${#times[@]}" == "5" && "${#pids[@]}" == "$(( 5 + invalid_attempts ))" ]] || {
    echo "::error::PERF-A1A fewer than five independent process-cold samples"; exit 1;
  }
  printf '%s\n' "${times[@]}" | sort -n | awk \
    -v model="$model" -v api="$api" -v head="$head" -v inv="$invalid_attempts" '{
      v[NR]=$1
    } END {
      p50=v[int((NR*50+99)/100)]
      p95=v[int((NR*95+99)/100)]
      printf "ATLAS_PERF_A1 aggregate mode=cold n=%d p50_ms=%.3f p95_ms=%.3f frame=completed emulator=%s api=%s head=%s invalid_attempts=%d\n", NR, p50, p95, model, api, head, inv
    }'
)

if [[ "$SHARD_INDEX" == "0" && "${ATLAS_SYNTHETIC_MISMATCH:-0}" != "1" ]]; then
  # The connected test task already uninstalled both APKs.
  adb install -r "$NEW"
  adb install -r "$TEST"
  run_perf_a1_cached_frame
fi

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

  # #1021: pause the real Activity with fewer than SAVE_EVERY samples, then
  # force-stop and restore in a second instrumentation process. This is a
  # process-death surrogate for swipe-kill, not an in-process collector reset.
  run_perf_persistence_phase() {
    local phase="$1" method="$2"
    local capture="/tmp/atlas-perf-persistence-$phase.txt"
    adb shell am instrument -w -r -e atlasPerfPersistencePhase "$phase" \
      -e class "nl.atlas.gallery.PerformanceProcessRelaunchTest#$method" "$RUNNER" \
      > "$capture" 2>&1 || true
    cat "$capture"
    if ! grep -Fq 'OK (1 test)' "$capture"; then
      echo "::error::#1021 $phase performance process persistence failed"
      timeout 30 adb logcat -d -b crash -t 500 || true
      return 1
    fi
  }
  run_perf_persistence_phase seed seedBeforeForceStop
  adb shell am force-stop nl.atlas.gallery
  run_perf_persistence_phase verify verifyAfterForceStop
  # As above, clear only this disposable CI emulator's synthetic app data.
  adb shell pm clear nl.atlas.gallery
  echo "PASS: #1021 real pause and separate-process performance restoration"

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
  for mode in light dark; do
    for scale in 100 200; do capture_brand_l2 after "$mode" "$scale"; done
  done
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
