#!/usr/bin/env bash
# UI-evidence screenshots for the emulator jobs (#588, #586, #596).
#
#   bash scripts/ci_ui_evidence.sh pull      # every screenshot on the device; none fails
#   bash scripts/ci_ui_evidence.sh pull-some # every screenshot on the device; none is fine
#   bash scripts/ci_ui_evidence.sh verify    # declared set present + base64 log groups
#
# Pulling discovers the screenshots by listing the device's evidence directory,
# so no shared name list has to be edited by every UI PR. The expected set is
# declared per feature in scripts/ui-evidence.d/*.txt (one name per line, "#"
# comments): a PR that adds evidence adds its own file, so parallel PRs no
# longer conflict on one list.
#
# The emulator-ui shards each run part of the instrumented suite and pull what
# their own tests saved ("pull-some"). The aggregate emulator-ui job merges the
# shard artifacts and runs "verify", which fails unless the union is exactly
# the declared set: a declared shot that no test saved, or a saved shot that no
# file declares, both fail. An unsharded run (the synthetic-recovery workflow)
# uses "pull" then "verify".
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
declared_dir="${ATLAS_UI_EVIDENCE_DECLARED_DIR:-$script_dir/ui-evidence.d}"
# #577: tests store evidence under a camera-style name dated 2001-01-01.
remote_dir=/sdcard/Pictures/ATLAS-UI-Evidence
prefix=IMG_20010101_000000_
name_re='^[A-Za-z0-9][A-Za-z0-9_-]*$'

# Prints the evidence names (without prefix and .png) found on the device.
discover() {
  if ! adb shell test -d "$remote_dir"; then
    echo "No $remote_dir on the device" >&2
    return 0
  fi
  local listing
  # A failed listing must fail the job, even when it printed something (#596).
  if ! listing="$(adb shell ls -1 "$remote_dir")"; then
    echo "::error::Could not list $remote_dir on the device" >&2
    return 1
  fi
  local entry name
  while IFS= read -r entry; do
    entry="${entry%$'\r'}"
    [[ -n "$entry" ]] || continue
    name="${entry#"$prefix"}"
    name="${name%.png}"
    if [[ "$entry" != "$prefix$name.png" || ! "$name" =~ $name_re ]]; then
      echo "Ignoring non-evidence file on device: $entry" >&2
      continue
    fi
    echo "$name"
  done <<<"$listing" | sort -u
}

pull_discovered() {
  local require_any="$1" names shot pulled=0
  names="$(discover)"
  while IFS= read -r shot; do
    [[ -n "$shot" ]] || continue
    adb exec-out cat "$remote_dir/$prefix$shot.png" >"ui-evidence/$shot.png"
    pulled=$((pulled + 1))
  done <<<"$names"
  echo "Pulled $pulled UI-evidence screenshots from the device"
  if [[ "$require_any" == 1 && "$pulled" == 0 ]]; then
    echo "::error::No ATLAS UI screenshot evidence found on the device"
    exit 1
  fi
}

mode="${1:-}"
mkdir -p ui-evidence
case "$mode" in
  pull) pull_discovered 1 ;;
  pull-some) pull_discovered 0 ;;
  verify)
    UI_EVIDENCE_DECLARED_DIR="$declared_dir" UI_EVIDENCE_NAME_RE="$name_re" python3 -c '
import os, re, sys
from pathlib import Path
# #637: explicit checks, never assert: PYTHONOPTIMIZE would strip asserts and pass.
def fail(message):
    print(message, file=sys.stderr)
    sys.exit(1)
name_re = re.compile(os.environ["UI_EVIDENCE_NAME_RE"])
declared = {}
for f in sorted(Path(os.environ["UI_EVIDENCE_DECLARED_DIR"]).glob("*.txt")):
    for raw in f.read_text(encoding="utf-8").splitlines():
        n = raw.split("#", 1)[0].strip()
        if not n:
            continue
        if not name_re.match(n):
            fail(f"Bad UI-evidence name {n!r} in {f.name}")
        if n in declared:
            fail(f"{n} declared in both {declared.get(n)} and {f.name}")
        declared[n] = f.name
if not declared:
    fail("No UI-evidence names declared in scripts/ui-evidence.d/*.txt")
pulled = {p.stem for p in Path("ui-evidence").glob("*.png")}
missing = sorted(set(declared) - pulled)
extra = sorted(pulled - set(declared))
if missing:
    fail(f"Missing UI-evidence PNGs: {missing}")
if extra:
    fail(f"Undeclared UI-evidence PNGs (add them to scripts/ui-evidence.d/): {extra}")
for n in sorted(pulled):
    p = Path("ui-evidence", n + ".png")
    d = p.read_bytes()
    if not (d.startswith(bytes.fromhex("89504e470d0a1a0a")) and len(d) > 1024):
        fail(f"Invalid image {p}")
print(f"PASS: {len(declared)} real PNG screenshots")
'
    # Some review seats cannot download Actions artifacts; print every
    # screenshot as base64 so it can be decoded from the job log.
    for shot in ui-evidence/*.png; do
      [[ -f "$shot" ]] || continue
      echo "::group::UI evidence base64 $(basename "$shot")"
      base64 -w 76 "$shot"
      echo "::endgroup::"
    done
    ;;
  *)
    echo "usage: $0 pull|pull-some|verify" >&2
    exit 2
    ;;
esac
