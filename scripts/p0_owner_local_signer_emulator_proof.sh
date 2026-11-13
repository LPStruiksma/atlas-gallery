#!/usr/bin/env bash
# Lane E P0: local operator-held signing, independent Android A/B builds,
# and disposable-emulator ONLY in-place upgrade. No real-device operations.
set -euo pipefail
set +x # Never permit shell xtrace of local keystore credentials.
umask 077
die() { echo "NO-GO: $*" >&2; exit 1; }
# macOS portability (#488): no GNU sha256sum or realpath on a stock Mac.
sha256_hex() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum | cut -d' ' -f1
  else shasum -a 256 | cut -d' ' -f1; fi
}
[[ $# -eq 5 ]] || die "Usage: $0 <clean-reviewed-git-repo> <owner-keystore.jks> <key-alias> <emulator-serial> <NEW-absolute-output-dir>"
repo="$(cd "$1" && pwd -P)" || die "Source checkout missing"
[[ -f "$2" && ! -L "$2" ]] || die "Use a regular, non-symlink owner-held keystore"
keystore="$(cd "$(dirname "$2")" && pwd -P)/$(basename "$2")"
keyalias="$3"
serial="$4"
out="$5"
[[ "$keyalias" =~ ^[A-Za-z0-9_.-]+$ ]] || die "Invalid key alias"
[[ "$serial" =~ ^emulator-[0-9]+$ ]] || die "Refusing non-emulator ADB serial"
[[ -t 0 && -r /dev/tty ]] || die "Run interactively on a trusted owner workstation; never CI"
[[ -n "${ANDROID_HOME:-}" ]] || die "ANDROID_HOME missing"
for c in git gradle adb python3 tar; do command -v "$c" >/dev/null || die "Missing $c"; done
command -v sha256sum >/dev/null || command -v shasum >/dev/null || die "Missing sha256sum/shasum"
apksigner="$ANDROID_HOME/build-tools/36.0.0/apksigner"
aapt="$ANDROID_HOME/build-tools/36.0.0/aapt"
[[ -x "$apksigner" && -x "$aapt" ]] || die "Android SDK build-tools/36.0.0 required"
[[ -d "$repo/.git" || -f "$repo/.git" ]] || die "Git checkout required"
[[ -z "$(git -C "$repo" status --porcelain)" ]] || die "Source checkout is not clean"
commit="$(git -C "$repo" rev-parse --verify HEAD)"
[[ "$out" = /* && ! -e "$out" && "$out" != "$repo/"* ]] || die "Use a new absolute output dir OUTSIDE Git"
[[ "$keystore" != "$repo/"* ]] || die "Keystore must stay outside repository"
[[ "$(adb -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || die "ADB target isn't an emulator"
# `pm path` exits 1 for a missing package on current Android, which would stop
# a fresh AVD. `pm list packages` exits 0 and lists prefix matches; keep exact.
installed="$(adb -s "$serial" shell pm list packages nl.atlas.gallery | tr -d '\r')" || die "Cannot verify emulator ATLAS installation state"
installed_app="$(printf '%s\n' "$installed" | grep -x 'package:nl.atlas.gallery' || true)"
installed_test="$(printf '%s\n' "$installed" | grep -x 'package:nl.atlas.gallery.test' || true)"
[[ -z "$installed_app" ]] || die "Emulator already has ATLAS; use a fresh AVD"
[[ -z "$installed_test" ]] || die "Emulator already has ATLAS tests; use a fresh AVD"

# Security boundary: NEVER give the owner signing key or passwords to Gradle
# (which evaluates third-party source/plugins). Build ephemeral debug APKs,
# then re-sign the resulting APKs using apksigner in distinct short-lived
# local subprocesses. Reject preexisting signing variables in the shell.
for name in ATLAS_STABLE_SIGNING_PATH ATLAS_STABLE_SIGNING_STORE_PASSWORD ATLAS_STABLE_SIGNING_KEY_ALIAS ATLAS_STABLE_SIGNING_KEY_PASSWORD; do
  [[ -z "${!name:-}" ]] || die "Refusing inherited Gradle signing configuration"
done

mkdir -m 700 "$out"
for p in a b; do
  mkdir -m 700 "$out/$p"
  git -C "$repo" archive "$commit" | tar -xf - -C "$out/$p"
done
# Do not build/sign an RC whose Android instrumentation still tests only a
# throwaway preference namespace. Require a fictional round-trip through the
# *production* V4 sync-digest preference name/key, plus the distinct CI-only
# sentinel. This is source-contract screening, not runtime proof; the actual
# instrumented assertion must subsequently PASS on the emulator.
python3 - "$out/a/app/src/androidTest/java/nl/atlas/gallery/PeopleUpgradeTest.kt" \
  "$out/a/app/src/main/java/nl/atlas/gallery/GalleryRepository.kt" <<'PY_PREFLIGHT'
from pathlib import Path
import sys
fixture = Path(sys.argv[1]).read_text()
prod = Path(sys.argv[2]).read_text()
try:
    seed = fixture.split("@Test fun seedBeforeUpgrade()", 1)[1].split(
        "@Test fun snapshotBeforeSyntheticSignerMismatch()", 1)[0]
    verify = fixture.split("@Test fun verifyAfterUpgrade()", 1)[1]
except IndexError:
    raise SystemExit("NO-GO: expected two-phase People/settings Android instrumentation")
if 'getSharedPreferences("atlas_v4_live_sync"' not in prod or 'getString("sha256"' not in prod:
    raise SystemExit("NO-GO: expected native production V4 link setting contract changed")
if not ('"atlas_v4_live_sync"' in fixture and '"sha256"' in fixture):
    raise SystemExit("NO-GO: synthetic test names no production persisted app setting")
if not (seed.count('getSharedPreferences(') >= 2 and verify.count('getSharedPreferences(') >= 2
        and seed.count('putString(') >= 2 and '.commit()' in seed
        and verify.count('getString(') >= 2 and 'assertEquals(' in verify):
    raise SystemExit("NO-GO: test must seed/read BOTH test-only and production V4 preference")
PY_PREFLIGHT
# No source changes except strictly increasing synthetic versionCode in B.
python3 - "$out/a/app/build.gradle.kts" "$out/b/app/build.gradle.kts" "$out/versions.txt" <<'PY'
import pathlib, re, sys
a,b,e=map(pathlib.Path,sys.argv[1:])
source=a.read_text()
pattern=re.compile(r'(?m)^(\s*versionCode\s*=\s*)(\d+)(\s*)$')
ms=list(pattern.finditer(source))
if len(ms)!=1: raise SystemExit("NO-GO: expected one literal versionCode")
v=int(ms[0].group(2))
if not 1<=v<2100000000: raise SystemExit("NO-GO: invalid versionCode")
b.write_text(pattern.sub(lambda m:m.group(1)+str(v+1)+m.group(3),source,count=1))
e.write_text(f"A={v}\nB={v+1}\n")
PY
for p in a b; do
  echo "Independent Gradle build $p at source $commit"
  (cd "$out/$p" && gradle :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --quiet)
done
# Independent BUILD and SIGN stages. Gradle has finished before any owner
# signing secret enters a process environment. No secrets in shell argv/logs.
# Prompt only AFTER all Gradle/plugin processes have finished; until this
# point the owner secret has not even been read into the parent shell.
read -r -s -p 'Local owner keystore password: ' storepass </dev/tty
printf '\n' > /dev/tty
read -r -s -p 'Local owner key password: ' keypass </dev/tty
printf '\n' > /dev/tty
[[ -n "$storepass" && -n "$keypass" ]] || die "Empty credential"
trap 'unset storepass keypass ATLAS_P0_STORE_PASSWORD ATLAS_P0_KEY_PASSWORD' EXIT
for p in a b; do
  for kind in app test; do
    if [[ "$kind" == app ]]; then
      input="$out/$p/app/build/outputs/apk/debug/app-debug.apk"
    else
      input="$out/$p/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
    fi
    [[ -s "$input" ]] || die "Unsigned/preview APK missing: $p/$kind"
    output="${input%.apk}-atlas-owner-signed.apk"
    (
      export ATLAS_P0_STORE_PASSWORD="$storepass"
      export ATLAS_P0_KEY_PASSWORD="$keypass"
      "$apksigner" sign --ks "$keystore" --ks-key-alias "$keyalias" \
        --ks-pass env:ATLAS_P0_STORE_PASSWORD \
        --key-pass env:ATLAS_P0_KEY_PASSWORD \
        --out "$output" "$input"
    ) || die "Detached apksigner failed for $p/$kind"
  done
done
unset storepass keypass
fingerprint=''
for p in a b; do
  apk="$out/$p/app/build/outputs/apk/debug/app-debug-atlas-owner-signed.apk"
  testapk="$out/$p/app/build/outputs/apk/androidTest/debug/app-debug-androidTest-atlas-owner-signed.apk"
  [[ -s "$apk" && -s "$testapk" ]] || die "Missing $p APK"
  for current in "$apk" "$testapk"; do
    result="$("$apksigner" verify --print-certs "$current")" || die "APK signature verification failed"
    [[ "$(printf '%s\n' "$result" | grep -Ec '^Signer #[0-9]+ certificate SHA-256 digest:')" == "1" ]] || die "Expected one APK signer only"
    cert="$(printf '%s\n' "$result" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')"
    [[ "$cert" =~ ^[a-fA-F0-9]{64}$ ]] || die "Invalid signer fingerprint"
    [[ -z "$fingerprint" || "$fingerprint" == "$cert" ]] || die "A/B or instrumentation signer mismatch"
    fingerprint="$cert"
  done
  expected="$(sed -n "s/^$(echo "$p" | tr 'ab' 'AB')=//p" "$out/versions.txt")"
  metadata="$("$aapt" dump badging "$apk" | sed -n '/^package:/p')"
  [[ "$metadata" == *"name='nl.atlas.gallery'"* && "$metadata" == *"versionCode='$expected'"* ]] || die "Package/version mismatch in $p"
  printf 'apk_%s_sha256=%s\n' "$p" "$(sha256_hex < "$apk")" >> "$out/public-evidence.txt"
  printf 'androidtest_apk_%s_sha256=%s\n' "$p" "$(sha256_hex < "$testapk")" >> "$out/public-evidence.txt"
done
{
  echo "source_commit=$commit"
  echo "package=nl.atlas.gallery"
  echo "signer_certificate_sha256=$fingerprint"
  cat "$out/versions.txt"
} >> "$out/public-evidence.txt"

# The instrumentation tests are responsible for both fictional People AND
# fictional SharedPreferences settings sentinel seed/verify assertions.
adb -s "$serial" install "$out/a/app/build/outputs/apk/debug/app-debug-atlas-owner-signed.apk" >/dev/null
adb -s "$serial" install "$out/a/app/build/outputs/apk/androidTest/debug/app-debug-androidTest-atlas-owner-signed.apk" >/dev/null
phase_test() {
  local phase="$1" method="$2" output
  output="$(adb -s "$serial" shell am instrument -w -r -e atlasUpgradePhase "$phase" -e class "nl.atlas.gallery.PeopleUpgradeTest#$method" nl.atlas.gallery.test/androidx.test.runner.AndroidJUnitRunner)" || die "$phase instrumentation failed"
  printf '%s\n' "$output" > "$out/instrumentation-$phase.txt"
  printf 'instrumentation_%s_sha256=%s\n' "$phase" "$(sha256_hex < "$out/instrumentation-$phase.txt")" >> "$out/public-evidence.txt"
  [[ "$output" == *"OK (1 test)"* ]] || die "$phase assertion failed; see the private instrumentation log"
}
phase_test seed seedBeforeUpgrade
# Android-valid, synthetic app-private SharedPreferences XML retention. This is
# a file-level settings persistence test, not proof of any particular UI setting.
fixture_xml='<map><string name="atlas_p0_synthetic_marker">fictional-settings-retention-1</string></map>'
printf '%s' "$fixture_xml" | adb -s "$serial" shell "run-as nl.atlas.gallery sh -c 'umask 077; mkdir -p shared_prefs; cat > shared_prefs/atlas_p0_proof.xml'"
pref_before="$(adb -s "$serial" exec-out run-as nl.atlas.gallery cat shared_prefs/atlas_p0_proof.xml | sha256_hex)"
[[ "$pref_before" == "$(printf '%s' "$fixture_xml" | sha256_hex)" ]] || die "Cannot seed private settings fixture"
adb -s "$serial" install -r "$out/b/app/build/outputs/apk/debug/app-debug-atlas-owner-signed.apk" >/dev/null
adb -s "$serial" install -r "$out/b/app/build/outputs/apk/androidTest/debug/app-debug-androidTest-atlas-owner-signed.apk" >/dev/null
phase_test verify verifyAfterUpgrade
pref_after="$(adb -s "$serial" exec-out run-as nl.atlas.gallery cat shared_prefs/atlas_p0_proof.xml | sha256_hex)"
[[ "$pref_before" == "$pref_after" ]] || die "Synthetic private settings file did not survive in-place upgrade"
echo "synthetic_sharedpreferences_file_preserved=PASS" >> "$out/public-evidence.txt"
echo "emulator_android_in_place_upgrade=PASS" >> "$out/public-evidence.txt"
echo "people_instrumentation=PASS" >> "$out/public-evidence.txt"
# Actual production V4-link preference *storage* assertion is independently
# source-contracted BEFORE builds and executed in the same seed/verify Android
# phases above. This is not a genuine SAF grant or UI link-state interaction.
echo "test_only_preference_instrumentation=PASS" >> "$out/public-evidence.txt"
echo "production_v4_sync_digest_preference_storage_instrumentation=PASS" >> "$out/public-evidence.txt"
echo "production_v4_live_link_ui_and_saf_grants=NOT_TESTED" >> "$out/public-evidence.txt"
echo "PASS: independent signed A/B APKs, emulator install -r, People and actual V4 sync-digest storage."
echo "Evidence: $out/public-evidence.txt (public hashes only)."
echo "NO S24+ approval: frozen candidate acceptance, owner key custody and separate phone authorization remain mandatory."
