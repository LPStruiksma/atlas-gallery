#!/usr/bin/env bash
# CI ONLY: signed APK bytes and public hashes, no signing keys or owner device.
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true ]] || { echo 'NO-GO: emulator proof only in isolated GitHub CI' >&2; exit 1; }
[[ "$(adb devices | awk '/^emulator-[0-9]+[[:space:]]+device$/ {n++} END {print n+0}')" == 1 ]] || { echo 'NO-GO: one emulator required' >&2; exit 1; }
[[ "$(adb -s emulator-5554 shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || { echo 'NO-GO: physical devices forbidden' >&2; exit 1; }
for pkg in nl.atlas.gallery nl.atlas.gallery.test; do
  [[ -z "$(adb -s emulator-5554 shell pm path "$pkg")" ]] || { echo 'NO-GO: emulator not clean' >&2; exit 1; }
done
RUNNER='nl.atlas.gallery.test/androidx.test.runner.AndroidJUnitRunner'
installed_version() {
  local expected="$1" dump
  dump="$(adb -s emulator-5554 shell dumpsys package nl.atlas.gallery)"
  grep -Eq "(^|[[:space:]])versionCode=${expected}([[:space:]]|$)" <<< "$dump" ||
    { echo "NO-GO: emulator installed app is not versionCode $expected" >&2; return 1; }
}
phase() {
  local stage="$1" method="$2" log="p0-evidence/instrumentation-$1.txt"
  adb -s emulator-5554 shell am instrument -w -r \
    -e atlasUpgradePhase "$stage" -e class "nl.atlas.gallery.PeopleUpgradeTest#$method" \
    "$RUNNER" > "$log" 2>&1 || { echo "NO-GO: $stage instrumentation returned error" >&2; return 1; }
  grep -Fq 'OK (1 test)' "$log" || { echo "NO-GO: $stage test did not pass" >&2; return 1; }
  if grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|INSTRUMENTATION_CODE: 0' "$log"; then
    echo "NO-GO: $stage instrumentation reported failure" >&2; return 1
  fi
  echo "PASS: Android $stage People + test-only and production V4 digest assertions"
}
adb -s emulator-5554 install p0-signed/A-app.apk >/dev/null
adb -s emulator-5554 install p0-signed/A-test.apk >/dev/null
installed_version 3
phase seed seedBeforeUpgrade
adb -s emulator-5554 install -r p0-signed/B-app.apk >/dev/null
adb -s emulator-5554 install -r p0-signed/B-test.apk >/dev/null
installed_version 4
phase verify verifyAfterUpgrade
printf 'api_level=35\nowner_signed_apk_upgrade_3_to_4=PASS\npeople_sqlite=PASS\nproduction_v4_digest_preference=PASS\ntest_only_preference=PASS\nandroid_os_permission_revocation=NOT_TESTED\nsaf_grants=NOT_TESTED\nreal_s24_install=NOT_TESTED\n' > p0-evidence/g3-verdict.txt
echo 'PASS: owner-certificate 3→4 APK upgrade on isolated API35 emulator (never a phone install)'
