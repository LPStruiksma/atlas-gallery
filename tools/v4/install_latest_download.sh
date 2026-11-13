#!/usr/bin/env bash
# Choose the newest Monochrome Studio V4 preview, even when Android has
# appended a download collision suffix like ' (3).py'.
# Does not start/stop V4 server or touch the People SQLite database.
set -euo pipefail
# Optional pinned preview from a verified GitHub Actions artifact.
# Without these environment variables, preserve historical newest-download
# behavior for existing Termux users and Android collision suffixes.
latest="${ATLAS_V4_SOURCE:-}"
if [[ -n "$latest" ]]; then
  if [[ ! -f "$latest" ]]; then
    printf 'Pinned V4 preview file missing: %s\n' "$latest" >&2
    exit 1
  fi
else
  for file in "$HOME"/storage/downloads/monochrome_studio_v4_*preview*.py; do
    [[ -f "$file" ]] || continue
    if [[ -z "$latest" || "$file" -nt "$latest" ]]; then latest="$file"; fi
  done
fi
if [[ -z "$latest" ]]; then
  printf 'No V4 preview download found under %s/storage/downloads\n' "$HOME" >&2
  exit 1
fi
if ! grep -q 'Monochrome Studio 4' "$latest"; then
  echo 'Newest matching file does not look like a V4 script; installed copy unchanged.' >&2
  exit 1
fi
tmp="$(mktemp "$HOME/atlas_v4_install.XXXXXXXX")"
trap 'rm -f -- "$tmp"' EXIT
cp -- "$latest" "$tmp"
if [[ -n "${ATLAS_V4_EXPECTED_SHA256:-}" ]]; then
  expected="${ATLAS_V4_EXPECTED_SHA256}"
  if [[ ! "$expected" =~ ^[0-9A-Fa-f]{64}$ ]]; then
    echo 'Invalid ATLAS_V4_EXPECTED_SHA256: enter exactly 64 hexadecimal characters.' >&2
    exit 1
  fi
  actual="$(sha256sum "$tmp" | cut -d ' ' -f1)"
  if [[ "${actual,,}" != "${expected,,}" ]]; then
    echo 'V4 preview checksum mismatch; current installed script unchanged.' >&2
    exit 1
  fi
fi
python -m py_compile "$tmp"
destination="$HOME/atlas_v4_latest.py"
if [[ -f "$destination" ]]; then
  cp -- "$destination" "$HOME/atlas_v4_previous.py"
fi
mv -f -- "$tmp" "$destination"
printf 'Selected newest download: %s\n' "$latest"
printf 'Installed: %s\n' "$destination"
printf 'To launch after stopping the previous V4 server: python ~/atlas_v4_latest.py serve --port 8767\n'
printf 'To check live status: python ~/atlas_v4_latest.py status --port 8767\n'
