#!/data/data/com.termux/files/usr/bin/bash
# ATLAS G3 phone-first signer. NO key creation, APK installation, upload or deletion.
# All keys remain in Termux private storage. Sign only bytes freshly staged from
# a GitHub API-authenticated trusted-main Actions artifact (not local APK folders).
set -euo pipefail
set +x
umask 077
fail() { printf 'NO-GO: %s\n' "$*" >&2; exit 1; }
PIN='36bd476530abad6ebe794bd2d00128d83940518a'
PKG='nl.atlas.gallery'
ALIAS='atlas-native-release'
FILES=(A-app.apk A-test.apk B-app.apk B-test.apk)
[[ -t 0 && -r /dev/tty ]] || fail 'Use an interactive owner-controlled Termux terminal'
[[ "${PREFIX:-}" == /data/data/com.termux/files/usr ]] || fail 'Require native Termux, never cloud or proot'
[[ "${HOME:-}" == /data/data/com.termux/files/home ]] || fail 'Unexpected private Termux home'
[[ "${1:-}" =~ ^(check|backup|sign)$ ]] || fail 'Usage: check GENERATE_RUN_ID | backup PRIVATE_KEY NEW_ENCRYPTED_FILE | sign GENERATE_RUN_ID PRIVATE_KEY VERIFIED_BACKUP OUTPUT_DIR'
command -v sha256sum >/dev/null || fail 'Install coreutils'
command -v python3 >/dev/null || fail 'Install python'
# Only staging files created by this invocation are removed; no owner media/data.
STAGE=''
cleanup() { if [[ -n "$STAGE" && -d "$STAGE" && ! -L "$STAGE" ]]; then rm -rf -- "$STAGE"; fi; }
trap cleanup EXIT
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"

regular() { [[ -f "$1" && ! -L "$1" ]] || fail "Expected regular, non-symlink file: $1"; }
private_file() {
  regular "$1"
  [[ "$(realpath "$1")" == "$HOME/"* ]] || fail 'Keep private material in Termux app-private home'
  [[ "$(stat -c %a "$1")" =~ ^[46]00$ ]] || fail 'Private keystore must be chmod 600 or 400'
}
private_parent() {
  local canonical_home canonical_parent parent
  [[ "$1" == /* ]] || fail 'Output must be absolute'
  [[ "$1" == "$HOME/"* ]] || fail 'Output must stay inside Termux private home'
  [[ ! -e "$1" && ! -L "$1" ]] || fail 'Refusing to overwrite output'
  parent="$(dirname -- "$1")"
  [[ -d "$parent" && ! -L "$parent" ]] || fail 'Private parent directory required'
  # A lexical $HOME prefix and a non-symlink *immediate* parent are NOT enough:
  # $HOME/a/escape/normal/new can traverse a symlink at $HOME/a/escape.
  canonical_home="$(realpath -e -- "$HOME")" || fail 'Cannot resolve private Termux home'
  canonical_parent="$(realpath -e -- "$parent")" || fail 'Cannot resolve output parent'
  [[ "$canonical_parent" == "$canonical_home" ||
     "$canonical_parent" == "$canonical_home/"* ]] || fail 'Output parent resolves outside private Termux home'
}

verify_apk_meta() {
  local apk="$1" package="$2" version="$3" line
  line="$(aapt dump badging "$apk" | grep '^package:' | head -n 1)" || fail "Cannot inspect APK: $apk"
  [[ "$line" == *"name='$package'"* ]] || fail "Unexpected APK package: $apk"
  if [[ -n "$version" ]]; then
    [[ "$line" == *"versionCode='$version'"* ]] || fail "Unexpected APK versionCode: $apk"
  fi
}
check_artifacts() {
  local run_id="$1" in f expected got count
  [[ "$run_id" =~ ^[0-9]{6,16}$ ]] || fail 'Require numeric trusted-main generate run ID (not a local artifact directory)'
  command -v gh >/dev/null || fail 'Install and authenticate GitHub CLI for private repo Actions read access'
  STAGE="$(mktemp -d "$HOME/.atlas-g3-authenticated.XXXXXXXX")" || fail 'Private staging creation failed'
  chmod 700 "$STAGE"
  python3 "$SCRIPT_DIR/p0_termux_preview_provenance.py" "$run_id" "$STAGE" || fail 'Independent GitHub artifact authenticity check failed'
  in="$STAGE"
  regular "$in/SHA256SUMS"
  regular "$in/source-commit.txt"
  [[ "$(cat "$in/source-commit.txt")" == "$PIN" ]] || fail 'Source commit differs from pinned green RC'
  [[ "$(wc -l < "$in/SHA256SUMS")" -eq 4 ]] || fail 'Expect exactly four checksums'
  for f in "${FILES[@]}"; do
    regular "$in/$f"
    count="$(awk -v f="$f" '$2==f {n++} END {print n+0}' "$in/SHA256SUMS")"
    [[ "$count" -eq 1 ]] || fail "Missing or duplicate SHA256 entry for $f"
    expected="$(awk -v f="$f" '$2==f {print $1}' "$in/SHA256SUMS")"
    [[ "$expected" =~ ^[a-fA-F0-9]{64}$ ]] || fail "Bad SHA256 entry for $f"
    got="$(sha256sum "$in/$f")"; got="${got%% *}"
    [[ "${got,,}" == "${expected,,}" ]] || fail "Input APK hash mismatch: $f"
  done
  command -v aapt >/dev/null || fail 'Install Termux aapt'
  verify_apk_meta "$in/A-app.apk" "$PKG" 3
  verify_apk_meta "$in/B-app.apk" "$PKG" 4
  verify_apk_meta "$in/A-test.apk" "$PKG.test" ''
  verify_apk_meta "$in/B-test.apk" "$PKG.test" ''
  echo 'CHECK PASS: authenticated GitHub ZIP SHA256, repo/workflow/main SHA, four input APKs, app 3→4'
}

case "$1" in
  check)
    [[ $# == 2 ]] || fail 'check requires GENERATE_RUN_ID'
    check_artifacts "$2"
    ;;
  backup)
    [[ $# == 3 ]] || fail 'backup requires PRIVATE_KEY NEW_ENCRYPTED_FILE'
    private_file "$2"; private_parent "$3"
    command -v age >/dev/null || fail 'Install Termux age'
    # age -p prompts locally and requires a separate recovery passphrase.
    age -p -o "$3" "$2" || fail 'age encryption failed'
    regular "$3"
    # Decrypt only into a pipe, never into shared storage or a new plaintext file.
    if ! age -d "$3" | cmp -s "$2" -; then fail 'Encrypted backup failed restore comparison'; fi
    echo 'BACKUP CRYPTO PASS: encrypted file decrypts byte-identically'
    echo 'NOT an independent backup until this .age file is uploaded OFF-PHONE and retrieved and checked.'
    ;;
  sign)
    [[ $# == 5 ]] || fail 'sign requires GENERATE_RUN_ID PRIVATE_KEY VERIFIED_BACKUP OUTPUT_DIR'
    check_artifacts "$2"
    private_file "$3"; regular "$4"
    [[ "$(realpath "$4")" == "$HOME/"* ]] || fail 'Encrypted backup must be in private Termux home'
    command -v age >/dev/null || fail 'Install Termux age'
    # Check the backup is recoverable before attempting any owner-key signing.
    if ! age -d "$4" | cmp -s "$3" -; then fail 'Backup does not decrypt to the owner keystore'; fi
    private_parent "$5"
    command -v apksigner >/dev/null || fail 'Install official Termux apksigner'
    mkdir -m 700 "$5"
    fingerprint=''
    for f in "${FILES[@]}"; do
      echo "Owner-local signing: $f (password prompt stays in Termux)"
      # No password in shell arguments, environment, files or GitHub. Prompt on TTY.
      apksigner sign --ks "$3" --ks-type PKCS12 --ks-key-alias "$ALIAS" \
        --ks-pass stdin --out "$5/$f" "$STAGE/$f" </dev/tty || fail "Signing failed: $f"
      verify="$(apksigner verify --verbose --print-certs --min-sdk-version 29 "$5/$f")" || fail "APK signature invalid: $f"
      # Android minSdk 29 accepts cryptographically verified v2 or v3.
      if [[ "$verify" != *'Verified using v2 scheme (APK Signature Scheme v2): true'* &&
            "$verify" != *'Verified using v3 scheme (APK Signature Scheme v3): true'* ]]; then
        fail "modern APK signature (v2/v3) not verified: $f"
      fi
      # apksigner 37 uses V2 Signer: / V3.0 Signer: cert labels.
      # Preserve strict single certificate identity across all schemes/APKs.
      cert_rows="$(printf '%s\n' "$verify" |
        sed -nE 's/^(Signer #[0-9]+|V[23](\.[0-9]+)? Signer:) certificate SHA-256 digest: ([a-fA-F0-9]{64})[[:space:]]*$/\3/p')"
      [[ -n "$cert_rows" ]] || fail "Cannot parse signer cert: $f"
      total_cert_rows="$(printf '%s\n' "$verify" | grep -Ec 'certificate SHA-256 digest:' || true)"
      parsed_cert_rows="$(printf '%s\n' "$cert_rows" | wc -l)"
      [[ "$total_cert_rows" -eq "$parsed_cert_rows" ]] || fail "Unrecognized signer certificate output: $f"
      cert=''
      while IFS= read -r candidate; do
        [[ "$candidate" =~ ^[a-fA-F0-9]{64}$ ]] || fail "Invalid signer digest: $f"
        if [[ -n "$cert" && "${cert,,}" != "${candidate,,}" ]]; then
          fail "Multiple distinct signer certs: $f"
        fi
        cert="$candidate"
      done <<< "$cert_rows"
      [[ -n "$cert" ]] || fail "No signer certificate: $f"
      [[ -z "$fingerprint" || "${fingerprint,,}" == "${cert,,}" ]] || fail 'APK signing identities differ'
      fingerprint="$cert"
      case "$f" in
        A-app.apk) verify_apk_meta "$5/$f" "$PKG" 3;;
        B-app.apk) verify_apk_meta "$5/$f" "$PKG" 4;;
        *) verify_apk_meta "$5/$f" "$PKG.test" '';;
      esac
    done
    {
      printf 'source_commit=%s\npackage=%s\nA=3\nB=4\nsigner_certificate_sha256=%s\n' "$PIN" "$PKG" "${fingerprint,,}"
      printf 'generating_workflow_run=%s\n' "$2"
      for f in "${FILES[@]}"; do printf 'signed_%s_sha256=%s\n' "$f" "$(sha256sum "$5/$f" | cut -d' ' -f1)"; done
      echo 'phone_apk_install=NOT_EXECUTED'
      echo 'owner_cert_emulator_upgrade=NOT_EXECUTED'
      echo 'people_and_production_settings_retention=NOT_TESTED'
    } > "$5/public-evidence.txt"
    echo "SIGNING CRYPTO PASS: four APKs use one certificate and verified Android 29+ v2/v3 signatures"
    echo 'NO G3 PASS: need Lane A disposable API35 cloud A→B adb install -r + real People/preferences proof.'
    echo "Public manifest: $5/public-evidence.txt"
    ;;
esac
