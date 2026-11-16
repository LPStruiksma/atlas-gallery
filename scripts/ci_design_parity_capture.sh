#!/usr/bin/env bash
# DESIGN-1 evidence-only lane: run the existing instrumented screenshot contract,
# without installation upgrade phases or altered UI test logic.
set -euo pipefail

[[ "${GITHUB_ACTIONS:-}" == true ]] || {
  echo '::error::Synthetic parity capture is hosted-CI-only'; exit 1;
}
[[ "$(adb devices | awk '/^emulator-[0-9]+[[:space:]]+device$/ { n++ } END { print n+0 }')" == 1 ]] || {
  echo '::error::Exactly one emulator is required'; exit 1;
}
[[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || {
  echo '::error::Real Android devices cannot run this capture'; exit 1;
}
# Keep this lane independent of a physical device or stored signing identity.
for key in ATLAS_STABLE_SIGNING_PATH ATLAS_STABLE_SIGNING_STORE_PASSWORD ATLAS_STABLE_SIGNING_KEY_ALIAS ATLAS_STABLE_SIGNING_KEY_PASSWORD; do
  [[ -z "${!key:-}" ]] || { echo '::error::Stable signing is forbidden'; exit 1; }
done

# Fix and expose the controlled emulator characteristics for both revisions.
adb shell wm size 1080x2340
adb shell wm density 420
adb shell settings put system font_scale 1.0
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
[[ "$(adb shell wm size | tr -d '\r')" == *'1080x2340'* ]] || exit 1
[[ "$(adb shell wm density | tr -d '\r')" == *'420'* ]] || exit 1
[[ "$(adb shell settings get system font_scale | tr -d '\r')" == 1.0 ]] || exit 1

gradle :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --stacktrace

# Preserve the instrumentation/test class selection contract from the ordinary
# emulator lane, but run it once unsharded to capture every declared screen.
# AndroidJUnitRunner's -w result is not enough: explicitly require OK.
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# Set the per-app locale only after package installation. Verify the actual
# LocaleManager override instead of device defaults (which may be unrelated).
adb shell cmd locale set-app-locales nl.atlas.gallery --locales en-US
app_locales="$(adb shell cmd locale get-app-locales nl.atlas.gallery | tr -d '\r')"
[[ "$app_locales" =~ (^|[^[:alpha:]])en-US([^[:alpha:]]|$) ]] || {
  echo "::error::App locale is not en-US: $app_locales"; exit 1;
}
mkdir -p ui-evidence
output="${RUNNER_TEMP:-/tmp}/atlas-design1-instrumentation.txt"
adb shell am instrument -w -r nl.atlas.gallery.test/androidx.test.runner.AndroidJUnitRunner > "$output" 2>&1 || {
  cat "$output"; echo '::error::Android instrumentation failed'; exit 1;
}
cat "$output"
grep -Eq '^OK \([0-9]+ tests?\)' "$output" || {
  echo '::error::Missing AndroidJUnitRunner success marker'; exit 1;
}
bash scripts/ci_ui_evidence.sh pull
bash scripts/ci_ui_evidence.sh verify
