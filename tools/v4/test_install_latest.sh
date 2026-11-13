#!/usr/bin/env bash
set -euo pipefail
self_dir="$(cd "$(dirname "$0")" && pwd)"
root="$(mktemp -d)"
trap 'rm -rf -- "$root"' EXIT
mkdir -p "$root/storage/downloads"
first="$root/storage/downloads/monochrome_studio_v4_group_candidates_preview.py"
next="$root/storage/downloads/monochrome_studio_v4_group_candidates_preview (1).py"
other="$root/storage/downloads/monochrome_studio_v4_people_ux_preview (2).py"
printf '%s\n' '# Monochrome Studio 4' 'VERSION = 1' > "$first"
printf '%s\n' '# Monochrome Studio 4' 'VERSION = 2' > "$next"
printf '%s\n' '# Monochrome Studio 4' 'VERSION = 3' > "$other"
touch -t 202610140100 "$first"
touch -t 202610140200 "$next"
touch -t 202610140300 "$other"
HOME="$root" bash "$self_dir/install_latest_download.sh" > "$root/install.log"
grep -q 'VERSION = 3' "$root/atlas_v4_latest.py"
grep -Fq 'people_ux_preview (2).py' "$root/install.log"
touch -t 202610140400 "$next"
HOME="$root" bash "$self_dir/install_latest_download.sh" > "$root/install.log"
grep -q 'VERSION = 2' "$root/atlas_v4_latest.py"
grep -q 'VERSION = 3' "$root/atlas_v4_previous.py"
grep -Fq 'group_candidates_preview (1).py' "$root/install.log"
printf '%s\n' '# Monochrome Studio 4' 'if broken syntax' > "$other"
touch -t 202610140500 "$other"
if HOME="$root" bash "$self_dir/install_latest_download.sh" >/dev/null 2>&1; then
  echo 'FAIL: syntax-invalid preview should be rejected' >&2
  exit 1
fi
grep -q 'VERSION = 2' "$root/atlas_v4_latest.py"
rm -f "$first" "$next" "$other"
if HOME="$root" bash "$self_dir/install_latest_download.sh" >/dev/null 2>&1; then
  echo 'FAIL: missing download should not overwrite installed script' >&2
  exit 1
fi
grep -q 'VERSION = 2' "$root/atlas_v4_latest.py"
# Owner-safe pinning: use an exact artifact source even if another download
# exists, and refuse corrupt/checksum-mismatched files before backup or replace.
mkdir -p "$root/verified bundle"
pinned="$root/verified bundle/v4-reviewed.py"
printf '%s\n' '# Monochrome Studio 4' 'VERSION = 4' > "$pinned"
hash="$(sha256sum "$pinned" | cut -d ' ' -f1)"
HOME="$root" ATLAS_V4_SOURCE="$pinned" ATLAS_V4_EXPECTED_SHA256="$hash" \
  bash "$self_dir/install_latest_download.sh" > "$root/install.log"
grep -q 'VERSION = 4' "$root/atlas_v4_latest.py"
grep -q 'VERSION = 2' "$root/atlas_v4_previous.py"
grep -Fq 'v4-reviewed.py' "$root/install.log"
# Changing even one byte cannot overwrite the pinned installed version.
printf '%s\n' '# Monochrome Studio 4' 'VERSION = 5' > "$pinned"
if HOME="$root" ATLAS_V4_SOURCE="$pinned" ATLAS_V4_EXPECTED_SHA256="$hash" \
  bash "$self_dir/install_latest_download.sh" >/dev/null 2>&1; then
  echo 'FAIL: tampered pinned preview installed' >&2
  exit 1
fi
grep -q 'VERSION = 4' "$root/atlas_v4_latest.py"
grep -q 'VERSION = 2' "$root/atlas_v4_previous.py"
if HOME="$root" ATLAS_V4_SOURCE="$pinned" ATLAS_V4_EXPECTED_SHA256=123 \
  bash "$self_dir/install_latest_download.sh" >/dev/null 2>&1; then
  echo 'FAIL: malformed expected hash accepted' >&2
  exit 1
fi
if HOME="$root" ATLAS_V4_SOURCE="$root/missing preview.py" ATLAS_V4_EXPECTED_SHA256="$hash" \
  bash "$self_dir/install_latest_download.sh" >/dev/null 2>&1; then
  echo 'FAIL: nonexistent pinned preview accepted' >&2
  exit 1
fi
grep -q 'VERSION = 4' "$root/atlas_v4_latest.py"
printf '%s\n' 'PASS: newest Android-suffixed file; explicit verified source; checksum mismatch, malformed hash, missing source, invalid syntax all fail without overwrite'
