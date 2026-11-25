#!/usr/bin/env bash
# Synthetic contract; no emulator, no network. Run with bash.
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/ci/launcher_anr_diagnostic_954.sh
helper="$PWD/scripts/ci/launcher_anr_diagnostic_954.sh"
root="$(mktemp -d)"
trap 'rm -rf "$root"' EXIT
# Threadtime-shaped records include the historical root-only ATLAS_WINDOWS
# dialog and InputManager disposed launcher ANR window; synthetic IDs only.
generic="11-25 02:51:10.946 123 456 W ATLAS_WINDOWS: System ANR dialog on screen (android); pressing Wait"
window="11-25 02:51:10.958 100 200 W InputManager: Input channel object '5c023f6 Application Not Responding: com.android.launcher3 (client)' was disposed without first being removed!"
event="11-25 02:51:11.000 100 200 I am_anr  : [0,1338,com.android.launcher3,550010437,Input dispatching timed out]"
quick="11-25 02:51:11.001 100 200 I WindowManager: Window{abc u0 quickstep isn't responding}"
other="11-25 02:51:09.000 100 200 I am_anr  : [0,4321,other.app,0,reason mentions com.android.launcher3]"
chatter="11-25 02:51:09.001 100 200 I ActivityManager: launcher3 ANR monitoring enabled"
prefix="11-25 02:51:09.002 100 200 I am_anr  : [0,4321,com.android.launcher3.fake,0,reason]"
testline="11-25 02:51:09.003 100 200 I TestRunner: ANR in com.android.launcher3"
for line in "$other" "$chatter" "$prefix" "$testline"; do
  atlas954_classify "$line"; test -z "$kind"
done
atlas954_classify "$generic"; test "$kind" = unattributed-dialog
atlas954_classify "$window"; test "$kind" = launcher-dialog-window
atlas954_classify "$event"; test "$kind" = launcher-event
atlas954_classify "$quick"; test "$kind" = launcher-dialog-window
atlas954_classify "11-25 02:51:11.001 100 200 E ActivityManager: ANR in com.google.android.apps.nexuslauncher"; test "$kind" = launcher-event
export generic window event quick other chatter prefix testline

adb() {
  case "$3 $4" in
    "logcat -b")
      if [[ "$scenario" == generic ]]; then
        printf '%s\n' "$other" "$generic" "$chatter"
      else
        printf '%s\n' "$other" "$chatter" "$prefix" "$testline" "$generic" "$window" "$event" "$quick"
      fi ;;
    "shell dumpsys")
      printf '%s\n' "$*" >> "$fixture/calls"
      if [[ "$5" == window ]]; then
        test "$#" -eq 5 || return 77
        # Focus comes after the cap; null vs absent must remain distinct.
        printf '%0300000d\n' 0
        printf '%s\n' '  mCurrentFocus=null'
      elif [[ "$5" == input ]]; then
        printf '%s\n' 'permission denied'; return 124
      else
        printf '%s\n' 'synthetic output'
      fi ;;
    "shell test")
      [[ "$6" == */diag954-pinch-light.txt ]] ;;
    "exec-out cat")
      printf '%s\n' 'synthetic frame metadata' ;;
    *)
      printf 'synthetic command %s\n' "$*" ;;
  esac
}
export -f adb
# Simulate timeout result; no real device calls.
timeout() { [[ "$1" != --foreground ]] || shift; shift; "$@"; }
export -f timeout

fixture="$root/attributed"; mkdir "$fixture"; scenario=attributed; export fixture scenario
atlas954_collect emulator-5554 "$fixture"
for _ in {1..100}; do
  [[ -f "$fixture/snapshot-complete" ]] && break
  sleep .02
done
test -f "$fixture/snapshot-complete"
grep -Fq 'launcher-dialog-window' "$fixture/trigger.txt"
grep -Fq '5c023f6 Application Not Responding' "$fixture/trigger.txt"
test "$(wc -l < "$fixture/trigger.txt")" -eq 1
test "$(wc -l < "$fixture/unattributed-dialogs.txt")" -eq 1
test "$(grep -c ' status=' "$fixture/snapshot-status.txt")" -eq 6
test "$(wc -l < "$fixture/timeline.txt")" -eq 8
test "$(wc -c < "$fixture/window.txt")" -eq 262145
grep -Fq 'mCurrentFocus=null' "$fixture/window-focus.txt"
grep -Fq 'mFocusedApp unavailable' "$fixture/window-focus.txt"
grep -Fq 'input status=124' "$fixture/snapshot-status.txt"
test "$(sed -n '1p' "$fixture/calls")" = '-s emulator-5554 shell dumpsys window'
test "$(sed -n '2p' "$fixture/calls")" = '-s emulator-5554 shell dumpsys input'

fixture="$root/generic"; mkdir "$fixture"; scenario=generic
atlas954_collect emulator-5554 "$fixture"
test -s "$fixture/unattributed-dialogs.txt"
test ! -e "$fixture/trigger.txt"
test ! -e "$fixture/snapshot-status.txt"

# Real process-group teardown with mocked adb; all outputs in disposable cwd.
# EXIT must preserve original nonzero runner result and copy staged evidence.
mkdir "$root/cleanup"
if (
  cd "$root/cleanup"
  atlas954_stage="$(mktemp -d "$PWD/stage.XXXXXX")"
  printf '%s\n' "$atlas954_stage" > stage-path
  atlas954_serial=emulator-5554
  printf '%s\n' 'synthetic timeline' > "$atlas954_stage/timeline.txt"
  setsid bash -c 'exec sleep 60' &
  atlas954_pid=$!
  printf '%s\n' "$atlas954_pid" > collector-pid
  trap 'atlas954_finish "$?"' EXIT
  exit 37
); then
  echo 'unexpected successful runner exit' >&2; exit 1
else
  rc=$?
fi
test "$rc" -eq 37
test ! -e "$(cat "$root/cleanup/stage-path")"
if kill -0 "$(cat "$root/cleanup/collector-pid")" 2>/dev/null; then
  echo 'collector survived teardown' >&2; exit 1
fi
out="$root/cleanup/app/build/outputs/androidTest-results/launcher-anr-954"
grep -Fq 'synthetic timeline' "$out/timeline.txt"
grep -Fq 'runner exit 37' "$out/phases.txt"
grep -Fq 'synthetic frame metadata' "$out/pinch-light.txt"
test ! -e "$out/pinch-dark.txt"
echo 'PASS #954 realistic trigger, bounded focus, timeout, cleanup and exit-status contract'
