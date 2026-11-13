#!/usr/bin/env bash
# ATLAS M1 (#402 / #490): one-paste owner-signed RELEASE APK on macOS.
#
# Lukas runs this in Terminal on his own Mac after owner_mac_m1_proof.sh has
# set up ~/ATLAS-M1 (tools) and ~/ATLAS-signing (the permanent key with a
# verified backup). It builds the release variant of a pinned commit with
# Gradle, and only after Gradle has finished asks for the key password once on
# the keyboard, signs with apksigner, refuses any signer but the M1 key, and
# copies the APK to ~/Desktop. No adb, no device, no emulator, no install.
#
# Usage: bash owner_mac_release.sh <40-hex commit> [source-repo]
#   source-repo defaults to the Git checkout this script lives in, else GitHub.
#
# Written for macOS bash 3.2: no mapfile, no ${x,,}, no declare -A, no arrays.
set -euo pipefail
set +x # Never trace: the signing step reads a password.
umask 077

die() { echo "NO-GO: $*" >&2; exit 1; }
say() { printf '\n== %s\n' "$*"; }

# BEGIN PINS (reviewed constants; tests substitute this block only)
# Same tool pins as owner_mac_m1_proof.sh (#488); keep the two in step.
JDK_VERSION_TAG='jdk-17.0.13%2B11'
JDK_DIR='jdk-17.0.13+11'
JDK_FILE_AARCH64='OpenJDK17U-jdk_aarch64_mac_hotspot_17.0.13_11.tar.gz'
JDK_FILE_X64='OpenJDK17U-jdk_x64_mac_hotspot_17.0.13_11.tar.gz'
JDK_BASE_URL="https://github.com/adoptium/temurin17-binaries/releases/download/$JDK_VERSION_TAG"
JDK_SHA256_AARCH64=''
# Lukas's Mac recorded this value in the G3 PASS run (#402 comment 6739924115).
JDK_SHA256_X64='840535070200a944a6b582d258ee84608bd25c9f2b5d1cdddb58dfadb019675a'
GRADLE_DIR='gradle-8.13'
GRADLE_FILE='gradle-8.13-bin.zip'
GRADLE_BASE_URL='https://services.gradle.org/distributions'
# Lukas's Mac recorded this value in the G3 PASS run (#402 comment 6739924115).
GRADLE_SHA256='20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78'
CLT_FILE_AARCH64='commandlinetools-mac_arm64-16111833_latest.zip'
CLT_FILE_X64='commandlinetools-mac_x86_64-16111833_latest.zip'
CLT_BASE_URL='https://dl.google.com/android/repository'
CLT_SHA256_AARCH64='58da8f215781cc0208743e88b65ae259780c55e7eb40e50373d377cf369232d6'
CLT_SHA256_X64='7e601c04e7173754b3d051a87bb4c3a8243c65a9d7bfa9356ed6499fb8953fa3'
GITHUB_REPO_URL='https://github.com/LPStruiksma/atlas-gallery.git'
# The M1 key's certificate (G3 PASS, #402 comment 6739924115). Any other
# signer is refused.
EXPECTED_SIGNER_SHA256='5f15596090e3cfa339c2fd73a2039a23ecca9d02ae4e3506e22dabe976ce170b'
# Highest versionCode already installed on the S24+ (GitHub release v0.3.261109153).
VERSION_CODE_FLOOR=261109153
# END PINS

PACKAGE='nl.atlas.gallery'
KEY_ALIAS='atlas-native-release'
M1="$HOME/ATLAS-M1"
SIGNING="$HOME/ATLAS-signing"
KEY="$SIGNING/atlas-permanent.p12"
MARKER="$SIGNING/backup-verified.txt"
DESKTOP="$HOME/Desktop"

sha256_hex() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

# ---------------------------------------------------------------- 0. inputs
[[ $# -ge 1 && $# -le 2 ]] || die "Usage: bash $0 <40-hex commit> [source-repo]"
commit="$1"
[[ "$commit" =~ ^[0-9a-f]{40}$ ]] || die "Commit must be a full 40-character lowercase commit SHA"
[[ -t 0 && -r /dev/tty ]] || die "Run this directly in Terminal (it asks for the key password on the keyboard)"
for name in ATLAS_STABLE_SIGNING_PATH ATLAS_STABLE_SIGNING_STORE_PASSWORD ATLAS_STABLE_SIGNING_KEY_ALIAS ATLAS_STABLE_SIGNING_KEY_PASSWORD ATLAS_P0_STORE_PASSWORD ATLAS_P0_KEY_PASSWORD ATLAS_RELEASE_KEY_PASSWORD; do
  [[ -z "${!name:-}" ]] || die "Refusing inherited signing variable $name; open a new Terminal window"
done

# ---------------------------------------------------------------- 1. platform
say "1/7 Checking this Mac and the signing key"
[[ "$(uname -s)" == Darwin ]] || die "This script is for macOS only"
machine="$(uname -m)"
if [[ "$machine" == x86_64 && "$(sysctl -n hw.optional.arm64 2>/dev/null || true)" == 1 ]]; then
  machine=arm64 # Terminal under Rosetta on Apple Silicon: still use native arm64 tools.
fi
case "$machine" in
  arm64)  jdk_file="$JDK_FILE_AARCH64"; jdk_pin="$JDK_SHA256_AARCH64"
          clt_file="$CLT_FILE_AARCH64"; clt_pin="$CLT_SHA256_AARCH64" ;;
  x86_64) jdk_file="$JDK_FILE_X64"; jdk_pin="$JDK_SHA256_X64"
          clt_file="$CLT_FILE_X64"; clt_pin="$CLT_SHA256_X64" ;;
  *) die "Unsupported Mac architecture: $machine" ;;
esac
for c in curl tar unzip git python3; do command -v "$c" >/dev/null 2>&1 || die "Missing $c (run: xcode-select --install, then paste again)"; done
echo "architecture=$machine"
# The key must be the one owner_mac_m1_proof.sh created and backed up.
[[ -f "$KEY" && ! -L "$KEY" ]] || die "No key at $KEY (run the M1 paste first; this script never creates a key)"
key_sha="$(sha256_hex "$KEY")"
[[ -f "$MARKER" ]] && grep -qx "key_sha256=$key_sha" "$MARKER" \
  || die "$KEY has no verified backup record in $MARKER (run the M1 paste again to verify the backup)"
echo "key has a verified backup"
mkdir -p "$M1/downloads" "$M1/tools" "$M1/logs"
chmod 700 "$M1"

# ---------------------------------------------------------------- 2. tools
# fetch_checked <url> <dest> <expected-sha256>: reuse a verified copy, else
# download to .part, verify, then move into place. Mismatch deletes and stops.
fetch_checked() {
  local url="$1" dest="$2" want="$3" got
  [[ "$want" =~ ^[0-9a-f]{64}$ ]] || die "No valid sha256 for $(basename "$dest")"
  if [[ -f "$dest" && "$(sha256_hex "$dest")" == "$want" ]]; then
    echo "reuse $(basename "$dest")"; return 0
  fi
  rm -f "$dest" "$dest.part"
  echo "download $(basename "$dest")"
  curl -fsSL --retry 3 -o "$dest.part" "$url" || die "Download failed: $url"
  got="$(sha256_hex "$dest.part")"
  if [[ "$got" != "$want" ]]; then
    rm -f "$dest.part"
    die "sha256 mismatch for $(basename "$dest"): expected $want, got $got"
  fi
  mv "$dest.part" "$dest"
}
# vendor_sha <sidecar-url> <pin>: the vendor-published checksum; a non-empty
# pin must agree with it.
vendor_sha() {
  local text sum
  text="$(curl -fsSL --retry 3 "$1")" || die "Cannot read published checksum: $1"
  sum="$(printf '%s\n' "$text" | grep -Eo '[0-9a-fA-F]{64}' | sed -n 1p | tr 'A-F' 'a-f')"
  [[ "$sum" =~ ^[0-9a-f]{64}$ ]] || die "Published checksum unreadable: $1"
  [[ -z "$2" || "$2" == "$sum" ]] || die "sha256 pin mismatch for $1: pinned $2, published $sum"
  printf '%s\n' "$sum"
}
# checked_sha <download> <sidecar-url> <pin>: reuse the checksum recorded when
# <download> was first verified (no network); else ask the vendor.
checked_sha() {
  local cached
  if [[ -f "$1" && -f "$1.sha256" ]]; then
    cached="$(cat "$1.sha256")"
    if [[ "$cached" =~ ^[0-9a-f]{64}$ && ( -z "$3" || "$3" == "$cached" ) && "$(sha256_hex "$1")" == "$cached" ]]; then
      printf '%s\n' "$cached"; return 0
    fi
  fi
  vendor_sha "$2" "$3"
}

say "2/7 Java 17, Gradle 8.13 and Android build tools (reusing $M1)"
JAVA_HOME="$M1/tools/$JDK_DIR/Contents/Home"
if [[ -x "$JAVA_HOME/bin/java" ]]; then
  echo "reuse Java 17"
else
  jdk_sha="$(checked_sha "$M1/downloads/$jdk_file" "$JDK_BASE_URL/$jdk_file.sha256.txt" "$jdk_pin")"
  fetch_checked "$JDK_BASE_URL/$jdk_file" "$M1/downloads/$jdk_file" "$jdk_sha"
  printf '%s\n' "$jdk_sha" > "$M1/downloads/$jdk_file.sha256"
  rm -rf "$M1/tools/$JDK_DIR"
  tar -xzf "$M1/downloads/$jdk_file" -C "$M1/tools"
  [[ -x "$JAVA_HOME/bin/java" ]] || die "Java 17 unpack failed"
fi
if [[ -x "$M1/tools/$GRADLE_DIR/bin/gradle" ]]; then
  echo "reuse Gradle 8.13"
else
  gradle_sha="$(checked_sha "$M1/downloads/$GRADLE_FILE" "$GRADLE_BASE_URL/$GRADLE_FILE.sha256" "$GRADLE_SHA256")"
  fetch_checked "$GRADLE_BASE_URL/$GRADLE_FILE" "$M1/downloads/$GRADLE_FILE" "$gradle_sha"
  printf '%s\n' "$gradle_sha" > "$M1/downloads/$GRADLE_FILE.sha256"
  rm -rf "$M1/tools/$GRADLE_DIR"
  unzip -q "$M1/downloads/$GRADLE_FILE" -d "$M1/tools"
  [[ -x "$M1/tools/$GRADLE_DIR/bin/gradle" ]] || die "Gradle unpack failed"
fi
SDK="$M1/android-sdk"
BT="$SDK/build-tools/36.0.0"
ANDROID_HOME="$SDK"
ANDROID_SDK_ROOT="$SDK"
ANDROID_USER_HOME="$M1/android-user"
PATH="$JAVA_HOME/bin:$M1/tools/$GRADLE_DIR/bin:$SDK/cmdline-tools/latest/bin:$PATH"
export JAVA_HOME ANDROID_HOME ANDROID_SDK_ROOT ANDROID_USER_HOME PATH
sdk_ready() {
  [[ -x "$BT/apksigner" && -x "$BT/zipalign" && -x "$BT/aapt" && -f "$SDK/platforms/android-36/android.jar" ]]
}
if sdk_ready; then
  echo "reuse Android SDK build-tools 36.0.0 and android-36"
else
  if [[ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]]; then
    fetch_checked "$CLT_BASE_URL/$clt_file" "$M1/downloads/$clt_file" "$clt_pin"
    rm -rf "$SDK/cmdline-tools" "$M1/tools/clt-unpack"
    mkdir -p "$SDK/cmdline-tools" "$M1/tools/clt-unpack"
    unzip -q "$M1/downloads/$clt_file" -d "$M1/tools/clt-unpack"
    mv "$M1/tools/clt-unpack/cmdline-tools" "$SDK/cmdline-tools/latest"
    rm -rf "$M1/tools/clt-unpack"
  fi
  sdk_log="$M1/logs/sdkmanager-release-$(date -u +%Y%m%dT%H%M%SZ).log"
  echo "SDK packages (log: $sdk_log)"
  (yes 2>/dev/null || true) | sdkmanager --sdk_root="$SDK" --licenses >>"$sdk_log" 2>&1 || die "Android SDK licence step failed; see $sdk_log"
  sdkmanager --sdk_root="$SDK" 'build-tools;36.0.0' 'platforms;android-36' >>"$sdk_log" 2>&1 \
    || die "Android SDK install failed; see $sdk_log"
  sdk_ready || die "Android build-tools 36.0.0 or android-36 still missing; see $sdk_log"
fi
command -v gradle >/dev/null 2>&1 || die "Gradle not on PATH"

# ---------------------------------------------------------------- 3. source
say "3/7 Source $commit"
here="$(cd "$(dirname "$0")/.." 2>/dev/null && pwd -P || true)"
source_repo="${2:-}"
if [[ -z "$source_repo" ]]; then
  if [[ -n "$here" ]] && git -C "$here" rev-parse --git-dir >/dev/null 2>&1; then source_repo="$here"
  else source_repo="$GITHUB_REPO_URL"; fi
fi
work="$M1/release-${commit:0:12}-$(date -u +%Y%m%dT%H%M%SZ)"
[[ ! -e "$work" ]] || die "$work already exists; wait a second and paste again"
mkdir -m 700 "$work"
checkout="$work/source"
git clone -q --no-checkout "$source_repo" "$checkout" || die "Cannot clone $source_repo"
git -C "$checkout" cat-file -e "$commit^{commit}" 2>/dev/null || die "Commit $commit not found in $source_repo"
git -C "$checkout" -c advice.detachedHead=false checkout -q --detach "$commit" || die "Cannot check out $commit"
[[ "$(git -C "$checkout" rev-parse HEAD)" == "$commit" ]] || die "Checkout HEAD is not the pinned commit"
[[ -z "$(git -C "$checkout" status --porcelain)" ]] || die "Checkout is not clean"
# The version stamp goes into a private copy outside Git, never the checkout.
build="$work/build"
mkdir -m 700 "$build"
git -C "$checkout" archive "$commit" | tar -xf - -C "$build"
gradle_file="$build/app/build.gradle.kts"
[[ -f "$gradle_file" ]] || die "Commit has no app/build.gradle.kts"

# Match release-signed.yml: UTC yyMMddHH * 10 + (minute / 6).
# The checkout can be stale; fetch tags from origin and fail closed if unreadable.
git -C "$checkout" fetch -q --force --tags origin || die "Cannot fetch release tags; refusing to guess a versionCode"
tags="$(git -C "$checkout" tag --list 'v0.3.*')" || die "Cannot read release tags; refusing to guess a versionCode"
prev_code=0
while IFS= read -r tag; do
  [[ -n "$tag" ]] || continue
  if [[ "$tag" =~ ^v0[.]3[.]([0-9]{1,10})$ ]]; then
    code_text="${BASH_REMATCH[1]}"
    [[ "$code_text" != 0[0-9]* ]] || die "Malformed release tag $tag"
    tag_code=$((10#$code_text))
    if (( tag_code > prev_code )); then prev_code=$tag_code; fi
  fi
done <<< "$tags"
stamp="$(date -u +%y%m%d%H)"
minute="$(date -u +%M)"
[[ "$stamp" =~ ^[0-9]{2}(0[1-9]|1[0-2])(0[1-9]|[12][0-9]|3[01])([01][0-9]|2[0-3])$ && "$minute" =~ ^[0-5][0-9]$ ]] \
  || die "Unexpected UTC date stamp '$stamp:$minute'; check this Mac's clock"
version_code=$((10#$stamp * 10 + 10#$minute / 6))
(( version_code > prev_code )) || version_code=$((prev_code + 1))
version_name="0.3.$version_code"
(( version_code > VERSION_CODE_FLOOR && version_code < 2100000000 )) \
  || die "versionCode $version_code is outside the safe release range (floor $VERSION_CODE_FLOOR); check this Mac's clock and release tags"
python3 - "$gradle_file" "$version_code" "$version_name" <<'PY'
import pathlib, re, sys
path, code, name = pathlib.Path(sys.argv[1]), int(sys.argv[2]), sys.argv[3]
text = path.read_text()
vc = re.compile(r'(?m)^(\s*versionCode\s*=\s*)(\d+)(\s*)$')
vn = re.compile(r'(?m)^(\s*versionName\s*=\s*)"[^"\n]*"(\s*)$')
if len(vc.findall(text)) != 1 or len(vn.findall(text)) != 1:
    raise SystemExit("NO-GO: expected exactly one literal versionCode and one versionName in app/build.gradle.kts")
old = int(vc.search(text).group(2))
if code <= old or code >= 2100000000:
    raise SystemExit(f"NO-GO: versionCode {code} is not above the source's own versionCode {old}")
text = vc.sub(lambda m: m.group(1) + str(code) + m.group(3), text, count=1)
text = vn.sub(lambda m: m.group(1) + '"' + name + '"' + m.group(2), text, count=1)
path.write_text(text)
PY
echo "versionCode=$version_code versionName=$version_name (private build copy only)"

# ---------------------------------------------------------------- 4. build
# Security boundary: Gradle runs third-party build code, so it never sees the
# key or its password. It builds an UNSIGNED release APK and has exited before
# the password is even asked for.
say "4/7 Gradle release build (can take 10+ minutes)"
gradle_log="$work/gradle-release.log"
echo "full log: $gradle_log"
if ! (cd "$build" && gradle :app:assembleRelease --no-daemon --stacktrace) >"$gradle_log" 2>&1; then
  tail -n 60 "$gradle_log" >&2 || true
  die "Gradle release build failed (last 60 lines above; full log $gradle_log)"
fi
unsigned="$build/app/build/outputs/apk/release/app-release-unsigned.apk"
[[ -s "$unsigned" ]] || die "Gradle finished but $unsigned is missing"
aligned="$work/ATLAS-$version_name-aligned-unsigned.apk"
"$BT/zipalign" -f -p 4 "$unsigned" "$aligned" >"$work/zipalign.log" 2>&1 || die "zipalign failed; see $work/zipalign.log"

# ---------------------------------------------------------------- 5. sign
say "5/7 Signing with your key"
echo "Type the password of $KEY (the same one you used for the M1 proof)."
echo "Nothing you type is shown, saved or logged."
signed="$work/ATLAS-$version_name.apk"
trap 'unset keypass ATLAS_RELEASE_KEY_PASSWORD' EXIT
tries=0
while :; do
  keypass=''
  read -r -s -p 'ATLAS key password: ' keypass </dev/tty
  printf '\n' >/dev/tty
  if [[ -n "$keypass" ]]; then
    rm -f "$signed"
    # PKCS12: one password unlocks both the keystore and the key. The value
    # reaches apksigner only through its environment, in a subshell.
    if (
      export ATLAS_RELEASE_KEY_PASSWORD="$keypass"
      "$BT/apksigner" sign --ks "$KEY" --ks-type PKCS12 --ks-key-alias "$KEY_ALIAS" \
        --ks-pass env:ATLAS_RELEASE_KEY_PASSWORD \
        --key-pass env:ATLAS_RELEASE_KEY_PASSWORD \
        --out "$signed" "$aligned"
    ) >"$work/apksigner-sign.log" 2>&1; then
      break
    fi
  fi
  tries=$((tries + 1))
  [[ $tries -lt 3 ]] || { unset keypass; die "Signing failed 3 times (wrong password?); nothing was signed. apksigner output: $work/apksigner-sign.log"; }
  echo "Signing did not work; try the password again ($tries/3)."
done
unset keypass
trap - EXIT

# ---------------------------------------------------------------- 6. verify
say "6/7 Checking the signed APK"
[[ -s "$signed" ]] || die "apksigner reported success but $signed is missing"
result="$("$BT/apksigner" verify --print-certs "$signed")" || die "APK signature verification failed"
[[ "$(printf '%s\n' "$result" | grep -Ec '^Signer #[0-9]+ certificate SHA-256 digest:')" == 1 ]] || die "Expected exactly one APK signer"
cert="$(printf '%s\n' "$result" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | tr 'A-F' 'a-f')"
[[ "$cert" =~ ^[0-9a-f]{64}$ ]] || die "Unreadable signer fingerprint"
if [[ "$cert" != "$EXPECTED_SIGNER_SHA256" ]]; then
  rm -f "$signed"
  die "Signer $cert is not the M1 key $EXPECTED_SIGNER_SHA256; deleted the APK"
fi
"$BT/zipalign" -c -p 4 "$signed" >"$work/zipalign-check.log" 2>&1 || die "Signed APK is not aligned; see $work/zipalign-check.log"
badging="$("$BT/aapt" dump badging "$signed")" || die "aapt could not read the signed APK"
pkg_line="$(printf '%s\n' "$badging" | sed -n '/^package:/p')"
[[ "$pkg_line" == *"name='$PACKAGE'"* ]] || die "Package is not $PACKAGE: $pkg_line"
[[ "$pkg_line" == *"versionCode='$version_code'"* ]] || die "versionCode is not $version_code: $pkg_line"
[[ "$pkg_line" == *"versionName='$version_name'"* ]] || die "versionName is not $version_name: $pkg_line"
if printf '%s\n' "$badging" | grep -q '^application-debuggable'; then
  rm -f "$signed"
  die "APK is debuggable; a phone build must not be. Deleted the APK"
fi
apk_sha="$(sha256_hex "$signed")"

# ---------------------------------------------------------------- 7. deliver
say "7/7 Copying to your Desktop"
mkdir -p "$DESKTOP"
dest="$DESKTOP/ATLAS-$version_name.apk"
if [[ -e "$dest" || -L "$dest" ]]; then
  [[ -f "$dest" && ! -L "$dest" && "$(sha256_hex "$dest")" == "$apk_sha" ]] \
    || die "$dest already exists and is a different file; move it away and paste again"
else
  cp "$signed" "$dest"
fi
[[ "$(sha256_hex "$dest")" == "$apk_sha" ]] || die "Desktop copy differs from the signed APK"
chmod 644 "$dest"
rm -rf "$build" # Gradle intermediates; the checkout, logs and signed APK stay in $work.
echo "Ready: ~/Desktop/ATLAS-$version_name.apk. Nothing was installed; the phone step is separate."
echo "Copy everything between the two lines and paste it back into the ATLAS thread:"
echo "----------------------------------------------------------------------"
echo "ATLAS release build (public evidence only)"
echo "source_commit=$commit"
echo "package=$PACKAGE"
echo "version_code=$version_code"
echo "version_name=$version_name"
echo "apk_file=ATLAS-$version_name.apk"
echo "apk_sha256=$apk_sha"
echo "signer_certificate_sha256=$cert"
echo "debuggable=absent"
echo "bootstrap_arch=$machine"
echo "----------------------------------------------------------------------"
