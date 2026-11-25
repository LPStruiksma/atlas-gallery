#!/usr/bin/env bash
# Synthetic contract; no emulator, no network. Run with bash.
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/ci/launcher_anr_diagnostic_954.sh
stage="$(mktemp -d)"
trap 'rm -rf "$stage"' EXIT
# Inject two matching launcher ANRs and a non-launcher ANR. Assert exactly
# one snapshot and preserve the first matching event.
adb() {
  if [[ "$3" == logcat ]]; then
    printf '%s\n' 'am_anr other.app' 'am_anr com.android.launcher3 first' 'ANR Quickstep second'
  else
    printf 'synthetic command %s\n' "$*"
  fi
}
export -f adb
# timeout executes an external command, so wrap for this synthetic fixture.
timeout() { [[ "$1" != --foreground ]] || shift; shift; "$@"; }
atlas954_collect emulator-5554 "$stage"
for _ in {1..100}; do
  [[ -f "$stage/snapshot-complete" ]] && break
  sleep .02
done
test -f "$stage/snapshot-complete"
grep -q 'launcher3 first' "$stage/trigger.txt"
test "$(wc -l < "$stage/trigger.txt")" -eq 1
test "$(grep -c ' status=' "$stage/snapshot-status.txt")" -eq 6
test "$(wc -l < "$stage/timeline.txt")" -eq 3
echo 'PASS #954 synthetic first-trigger contract'
