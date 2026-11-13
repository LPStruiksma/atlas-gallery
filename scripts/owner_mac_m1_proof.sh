#!/usr/bin/env bash
# ATLAS M1 (#402 / #488): one-paste owner bootstrap for macOS.
#
# Lukas runs this once in Terminal on his own Mac. It downloads checked tools
# into ~/ATLAS-M1, creates or reuses the permanent offline key in
# ~/ATLAS-signing (only Lukas types its passwords), verifies a backup, starts a
# fresh disposable emulator and runs scripts/p0_owner_local_signer_emulator_proof.sh
# against a pinned candidate commit. It prints public evidence only.
#
# Usage: bash owner_mac_m1_proof.sh <40-hex candidate commit> [source-repo]
#   source-repo defaults to the Git checkout this script lives in, else GitHub.
#
# Written for macOS bash 3.2: no mapfile, no ${x,,}, no declare -A, no arrays.
set -euo pipefail
set +x # Never trace: the key step and the proof prompt for passwords.
umask 077

die() { echo "NO-GO: $*" >&2; exit 1; }
say() { printf '\n== %s\n' "$*"; }

# BEGIN PINS (reviewed constants; tests substitute this block only)
JDK_VERSION_TAG='jdk-17.0.13%2B11'
JDK_DIR='jdk-17.0.13+11'
JDK_FILE_AARCH64='OpenJDK17U-jdk_aarch64_mac_hotspot_17.0.13_11.tar.gz'
JDK_FILE_X64='OpenJDK17U-jdk_x64_mac_hotspot_17.0.13_11.tar.gz'
JDK_BASE_URL="https://github.com/adoptium/temurin17-binaries/releases/download/$JDK_VERSION_TAG"
# Empty pin = accept only the checksum Adoptium publishes next to the tarball
# (<file>.sha256.txt in the same release). A non-empty pin must equal it too.
JDK_SHA256_AARCH64=''
JDK_SHA256_X64=''
GRADLE_DIR='gradle-8.13'
GRADLE_FILE='gradle-8.13-bin.zip'
GRADLE_BASE_URL='https://services.gradle.org/distributions'
GRADLE_SHA256='' # Empty = Gradle's published <file>.sha256 must match.
CLT_FILE_AARCH64='commandlinetools-mac_arm64-16111833_latest.zip'
CLT_FILE_X64='commandlinetools-mac_x86_64-16111833_latest.zip'
CLT_BASE_URL='https://dl.google.com/android/repository'
# Computed from the downloaded zips on 2026-11-07; SHA-1 matches Google's
# repository2-3.xml (arm64 ad03dc49..., x86_64 112cf961...).
CLT_SHA256_AARCH64='58da8f215781cc0208743e88b65ae259780c55e7eb40e50373d377cf369232d6'
CLT_SHA256_X64='7e601c04e7173754b3d051a87bb4c3a8243c65a9d7bfa9356ed6499fb8953fa3'
GITHUB_REPO_URL='https://github.com/LPStruiksma/atlas-gallery.git'
# END PINS

KEY_ALIAS='atlas-native-release'
M1="$HOME/ATLAS-M1"
SIGNING="$HOME/ATLAS-signing"
KEY="$SIGNING/atlas-permanent.p12"
MARKER="$SIGNING/backup-verified.txt"

sha256_hex() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

# ---------------------------------------------------------------- 0. inputs
[[ $# -ge 1 && $# -le 2 ]] || die "Usage: bash $0 <40-hex candidate commit> [source-repo]"
candidate="$1"
[[ "$candidate" =~ ^[0-9a-f]{40}$ ]] || die "Candidate must be a full 40-character lowercase commit SHA"
[[ -t 0 && -r /dev/tty ]] || die "Run this directly in Terminal (it asks for passwords on the keyboard)"
for name in ATLAS_STABLE_SIGNING_PATH ATLAS_STABLE_SIGNING_STORE_PASSWORD ATLAS_STABLE_SIGNING_KEY_ALIAS ATLAS_STABLE_SIGNING_KEY_PASSWORD ATLAS_P0_STORE_PASSWORD ATLAS_P0_KEY_PASSWORD; do
  [[ -z "${!name:-}" ]] || die "Refusing inherited signing variable $name; open a new Terminal window"
done

# ---------------------------------------------------------------- 1. platform
say "1/8 Checking this Mac"
[[ "$(uname -s)" == Darwin ]] || die "This bootstrap is for macOS only"
machine="$(uname -m)"
if [[ "$machine" == x86_64 && "$(sysctl -n hw.optional.arm64 2>/dev/null || true)" == 1 ]]; then
  machine=arm64 # Terminal under Rosetta on Apple Silicon: still use native arm64 tools.
fi
case "$machine" in
  arm64)  jdk_file="$JDK_FILE_AARCH64"; jdk_pin="$JDK_SHA256_AARCH64"
          clt_file="$CLT_FILE_AARCH64"; clt_pin="$CLT_SHA256_AARCH64"; abi=arm64-v8a ;;
  x86_64) jdk_file="$JDK_FILE_X64"; jdk_pin="$JDK_SHA256_X64"
          clt_file="$CLT_FILE_X64"; clt_pin="$CLT_SHA256_X64"; abi=x86_64 ;;
  *) die "Unsupported Mac architecture: $machine" ;;
esac
for c in curl tar unzip git python3; do command -v "$c" >/dev/null 2>&1 || die "Missing $c (run: xcode-select --install, then paste again)"; done
echo "architecture=$machine system_image_abi=$abi"
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
# checked_sha <download> <sidecar-url> <pin>: on a re-run, reuse the checksum
# recorded when <download> was first verified (no network); else ask the vendor.
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

say "2/8 Java 17, Gradle 8.13 and Android SDK (checked downloads into $M1)"
jdk_sha="$(checked_sha "$M1/downloads/$jdk_file" "$JDK_BASE_URL/$jdk_file.sha256.txt" "$jdk_pin")"
fetch_checked "$JDK_BASE_URL/$jdk_file" "$M1/downloads/$jdk_file" "$jdk_sha"
printf '%s\n' "$jdk_sha" > "$M1/downloads/$jdk_file.sha256"
gradle_sha="$(checked_sha "$M1/downloads/$GRADLE_FILE" "$GRADLE_BASE_URL/$GRADLE_FILE.sha256" "$GRADLE_SHA256")"
fetch_checked "$GRADLE_BASE_URL/$GRADLE_FILE" "$M1/downloads/$GRADLE_FILE" "$gradle_sha"
printf '%s\n' "$gradle_sha" > "$M1/downloads/$GRADLE_FILE.sha256"
fetch_checked "$CLT_BASE_URL/$clt_file" "$M1/downloads/$clt_file" "$clt_pin"

if [[ ! -x "$M1/tools/$JDK_DIR/Contents/Home/bin/keytool" ]]; then
  rm -rf "$M1/tools/$JDK_DIR"
  tar -xzf "$M1/downloads/$jdk_file" -C "$M1/tools"
fi
if [[ ! -x "$M1/tools/$GRADLE_DIR/bin/gradle" ]]; then
  rm -rf "$M1/tools/$GRADLE_DIR"
  unzip -q "$M1/downloads/$GRADLE_FILE" -d "$M1/tools"
fi
SDK="$M1/android-sdk"
if [[ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]]; then
  rm -rf "$SDK/cmdline-tools" "$M1/tools/clt-unpack"
  mkdir -p "$SDK/cmdline-tools" "$M1/tools/clt-unpack"
  unzip -q "$M1/downloads/$clt_file" -d "$M1/tools/clt-unpack"
  mv "$M1/tools/clt-unpack/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$M1/tools/clt-unpack"
fi
JAVA_HOME="$M1/tools/$JDK_DIR/Contents/Home"
[[ -x "$JAVA_HOME/bin/keytool" ]] || die "Java 17 unpack failed"
ANDROID_HOME="$SDK"
ANDROID_SDK_ROOT="$SDK"
ANDROID_USER_HOME="$M1/android-user"
ANDROID_EMULATOR_HOME="$ANDROID_USER_HOME"
ANDROID_AVD_HOME="$ANDROID_USER_HOME/avd"
mkdir -p "$ANDROID_AVD_HOME"
PATH="$JAVA_HOME/bin:$M1/tools/$GRADLE_DIR/bin:$SDK/platform-tools:$SDK/cmdline-tools/latest/bin:$PATH"
export JAVA_HOME ANDROID_HOME ANDROID_SDK_ROOT ANDROID_USER_HOME ANDROID_EMULATOR_HOME ANDROID_AVD_HOME PATH
image="system-images;android-35;google_apis;$abi"
sdk_log="$M1/logs/sdkmanager-$(date -u +%Y%m%dT%H%M%SZ).log"
echo "SDK packages (log: $sdk_log)"
(yes 2>/dev/null || true) | sdkmanager --sdk_root="$SDK" --licenses >>"$sdk_log" 2>&1 || die "Android SDK licence step failed; see $sdk_log"
sdkmanager --sdk_root="$SDK" platform-tools 'build-tools;36.0.0' 'platforms;android-36' emulator "$image" >>"$sdk_log" 2>&1 \
  || die "Android SDK install failed; see $sdk_log"
for t in "$SDK/platform-tools/adb" "$SDK/emulator/emulator" "$SDK/build-tools/36.0.0/apksigner"; do
  [[ -x "$t" ]] || die "Missing after SDK install: $t"
done
command -v gradle >/dev/null 2>&1 || die "Gradle unpack failed"

# ---------------------------------------------------------------- 3. source
say "3/8 Candidate source $candidate"
here="$(cd "$(dirname "$0")/.." 2>/dev/null && pwd -P || true)"
source_repo="${2:-}"
if [[ -z "$source_repo" ]]; then
  if [[ -n "$here" ]] && git -C "$here" rev-parse --git-dir >/dev/null 2>&1; then source_repo="$here"
  else source_repo="$GITHUB_REPO_URL"; fi
fi
checkout="$M1/source-${candidate:0:12}-$(date -u +%Y%m%dT%H%M%SZ)"
git clone -q --no-checkout "$source_repo" "$checkout" || die "Cannot clone $source_repo"
git -C "$checkout" cat-file -e "$candidate^{commit}" 2>/dev/null || die "Candidate commit $candidate not found in $source_repo"
git -C "$checkout" -c advice.detachedHead=false checkout -q --detach "$candidate" || die "Cannot check out $candidate"
[[ "$(git -C "$checkout" rev-parse HEAD)" == "$candidate" ]] || die "Checkout HEAD is not the pinned candidate"
[[ -z "$(git -C "$checkout" status --porcelain)" ]] || die "Candidate checkout is not clean"
proof="$checkout/scripts/p0_owner_local_signer_emulator_proof.sh"
[[ -f "$proof" ]] || die "Candidate has no scripts/p0_owner_local_signer_emulator_proof.sh"

# ---------------------------------------------------------------- 4. emulator
avd="atlas-m1-$(date -u +%Y%m%d%H%M%S)"
serial=''
emu_pid=''
cleanup() {
  set +e
  if [[ -n "$serial" ]]; then adb -s "$serial" emu kill >/dev/null 2>&1; fi
  if [[ -n "$emu_pid" ]]; then
    local i=0
    while kill -0 "$emu_pid" 2>/dev/null && [[ $i -lt 30 ]]; do sleep 1; i=$((i + 1)); done
    kill -9 "$emu_pid" 2>/dev/null
  fi
  avdmanager delete avd -n "$avd" >/dev/null 2>&1
  rm -rf "$ANDROID_AVD_HOME/$avd.avd" "$ANDROID_AVD_HOME/$avd.ini"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

say "4/8 Fresh disposable emulator ($avd)"
echo no | avdmanager create avd -n "$avd" -k "$image" -d pixel_6 >>"$sdk_log" 2>&1 || die "AVD creation failed; see $sdk_log"
adb start-server >/dev/null 2>&1 || die "adb start failed"
port=5580
while [[ $port -le 5598 ]] && adb devices | grep "^emulator-${port}[[:space:]]" >/dev/null; do port=$((port + 2)); done
[[ $port -le 5598 ]] || die "No free emulator port"
serial="emulator-$port"
emu_log="$M1/logs/$avd-emulator.log"
"$SDK/emulator/emulator" -avd "$avd" -port "$port" -no-window -no-audio -no-boot-anim -no-snapshot -no-metrics >"$emu_log" 2>&1 &
emu_pid=$!
started=$SECONDS
echo "waiting for $serial to boot (up to 10 minutes)"
until [[ "$(adb -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; do
  kill -0 "$emu_pid" 2>/dev/null || die "Emulator exited early; see $emu_log"
  [[ $((SECONDS - started)) -lt 600 ]] || die "Emulator did not boot in 10 minutes; see $emu_log"
  sleep 3
done
until adb -s "$serial" shell pm path android >/dev/null 2>&1; do
  [[ $((SECONDS - started)) -lt 660 ]] || die "Package manager not ready; see $emu_log"
  sleep 2
done
echo "booted $serial"

# ---------------------------------------------------------------- 5. key
say "5/8 Permanent offline signing key ($KEY)"
mkdir -p "$SIGNING"
chmod 700 "$SIGNING"
if [[ -L "$KEY" ]]; then die "$KEY is a symlink; refusing"; fi
if [[ ! -f "$KEY" ]]; then
  echo "No key yet. keytool now creates it. Choose a strong password and keep it"
  echo "somewhere offline: every future ATLAS update needs this key and password."
  echo "keytool asks for the password twice. Nothing you type is shown or saved."
  if ! keytool -genkeypair -keystore "$KEY" -storetype PKCS12 -alias "$KEY_ALIAS" \
      -keyalg RSA -keysize 4096 -validity 12000 \
      -dname "CN=ATLAS Native local owner signing"; then
    rm -f "$KEY"
    die "Key creation did not finish; nothing was kept"
  fi
  chmod 600 "$KEY"
  [[ -s "$KEY" ]] || die "Key file missing after keytool"
else
  echo "reusing existing key"
fi
key_sha="$(sha256_hex "$KEY")"
backup_ok=no
if [[ -f "$MARKER" ]] && grep -qx "key_sha256=$key_sha" "$MARKER"; then
  backup_ok=yes
  echo "backup already verified ($(sed -n 's/^backup_file=//p' "$MARKER"))"
fi
if [[ "$backup_ok" != yes ]]; then
  echo "Now make a backup on a USB stick or other place you control (not this folder)."
  read -r -p 'Backup folder path (for example /Volumes/MYUSB): ' backup_dir </dev/tty
  backup_dir="${backup_dir%/}"
  case "$backup_dir" in "~"/*) backup_dir="$HOME/${backup_dir#\~/}" ;; esac
  [[ -n "$backup_dir" && -d "$backup_dir" ]] || die "Backup folder does not exist: $backup_dir"
  backup_dir="$(cd "$backup_dir" && pwd -P)"
  signing_real="$(cd "$SIGNING" && pwd -P)"
  m1_real="$(cd "$M1" && pwd -P)"
  case "$backup_dir/" in
    "$signing_real"/*|"$m1_real"/*) die "Backup must be outside $SIGNING and $M1" ;;
  esac
  case "$backup_dir" in
    /Volumes/*) ;;
    *) read -r -p "That is not an external drive. Type YES to use it anyway: " ok </dev/tty
       [[ "$ok" == YES ]] || die "No verified backup; stopping before any signing" ;;
  esac
  backup_file="$backup_dir/atlas-permanent.p12"
  if [[ -e "$backup_file" ]]; then
    [[ -f "$backup_file" && ! -L "$backup_file" && "$(sha256_hex "$backup_file")" == "$key_sha" ]] \
      || die "$backup_file exists and is a different file; refusing to overwrite it"
  else
    cp "$KEY" "$backup_file" || die "Copy to backup failed"
  fi
  [[ "$(sha256_hex "$backup_file")" == "$key_sha" ]] || die "Backup copy differs from the key"
  echo "Checking the backup opens: type the key password when keytool asks."
  tries=0
  until keytool -list -keystore "$backup_file" -storetype PKCS12 -alias "$KEY_ALIAS"; do
    tries=$((tries + 1))
    [[ $tries -lt 3 ]] || die "Backup could not be opened with keytool; no verified backup, stopping"
    echo "keytool could not open it; try the password again ($tries/3)."
  done
  {
    echo "key_sha256=$key_sha"
    echo "backup_file=$backup_file"
    echo "verified_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  } > "$MARKER"
  chmod 600 "$MARKER"
  echo "backup verified"
fi

# ---------------------------------------------------------------- 6. proof
evidence="$M1/evidence-$(date -u +%Y%m%dT%H%M%SZ)"
say "6/8 Build A and B, sign, upgrade A->B on the emulator"
echo "Gradle builds both versions first (can take 20+ minutes). Only after that"
echo "it asks for the keystore password and then the key password: for this key"
echo "type the SAME password both times."
bash "$proof" "$checkout" "$KEY" "$KEY_ALIAS" "$serial" "$evidence"

# ---------------------------------------------------------------- 7. cleanup
say "7/8 Removing the disposable emulator"
cleanup
serial=''
emu_pid=''
trap - EXIT

# ---------------------------------------------------------------- 8. report
say "8/8 Done"
[[ -s "$evidence/public-evidence.txt" ]] || die "Proof finished without public evidence"
cert="$(sed -n 's/^signer_certificate_sha256=//p' "$evidence/public-evidence.txt")"
echo "Copy everything between the two lines and paste it back into the ATLAS thread:"
echo "----------------------------------------------------------------------"
echo "ATLAS M1 owner-local proof (public evidence only)"
cat "$evidence/public-evidence.txt"
echo "public_certificate_sha256=$cert"
echo "bootstrap_arch=$machine"
echo "bootstrap_jdk_sha256=$jdk_sha"
echo "bootstrap_gradle_sha256=$gradle_sha"
echo "bootstrap_cmdline_tools_sha256=$clt_pin"
echo "----------------------------------------------------------------------"
