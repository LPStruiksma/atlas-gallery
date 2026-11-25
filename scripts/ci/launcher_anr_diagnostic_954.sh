#!/usr/bin/env bash
# #954 temporary, read-only host-side observation; never merge this carrier.
# Sourced by the ordinary runner; executed as a separate process group for capture.
atlas954_phase() {
  [[ -n "${atlas954_stage:-}" ]] || return 0
  printf '%s %s\n' "$(date -u +%FT%T.%3NZ)" "$*" >> "$atlas954_stage/phases.txt" || true
}

atlas954_start() {
  [[ "${GITHUB_ACTIONS:-}" == true && "${RUNNER_ENVIRONMENT:-}" == github-hosted &&
     "${ATLAS_SYNTHETIC_MISMATCH:-0}" != 1 && "${ATLAS_RECOVERY_ONLY:-0}" != 1 &&
     ! -f scripts/ci-opt-in/thumbnail-fling && ! -f scripts/ci-opt-in/stress-classes ]] || return 0
  local devices serial
  devices="$(timeout 5 adb devices)" || return 1
  # Refuse if *any* other device is attached, even offline or unauthorized.
  serial="$(awk 'NR>1 && NF {print $1}' <<< "$devices")"
  [[ "$serial" =~ ^emulator-[0-9]+$ ]] || return 1
  [[ "$(timeout 5 adb -s "$serial" get-state)" == device &&
     "$(timeout 5 adb -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || return 1
  atlas954_stage="$(mktemp -d "${RUNNER_TEMP:-/tmp}/atlas954.XXXXXXXX")" || return 1
  atlas954_serial="$serial"
  trap 'atlas954_finish "$?"' EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  atlas954_phase "collector starting; prior buffered events may predate this host timestamp"
  timeout 5 adb -s "$serial" shell touch /data/local/tmp/atlas-954-hosted || return 1
  setsid bash scripts/ci/launcher_anr_diagnostic_954.sh "$serial" "$atlas954_stage" \
    > "$atlas954_stage/collector-status.txt" 2>&1 &
  atlas954_pid=$!
}

atlas954_finish() {
  local rc="$1" name destination status
  trap - EXIT
  set +e
  atlas954_phase "runner exit $rc"
  if [[ -n "${atlas954_pid:-}" ]]; then
    if kill -0 "$atlas954_pid" 2>/dev/null; then
      kill -TERM -- "-$atlas954_pid" 2>/dev/null
    fi
    wait "$atlas954_pid"
    status=$?
    printf 'collector wait status=%s (143 expected on teardown)\n' "$status" >> "$atlas954_stage/collector-status.txt"
  fi
  if [[ -f "$atlas954_stage/trigger.txt" && ! -f "$atlas954_stage/snapshot-complete" ]]; then
    echo 'snapshot incomplete at teardown' >> "$atlas954_stage/collector-status.txt"
  elif [[ ! -f "$atlas954_stage/trigger.txt" ]]; then
    echo 'no matching launcher ANR observed by collector; not proof of absence' >> "$atlas954_stage/collector-status.txt"
  fi
  # Fixed allowlist, independent of normal evidence parity and Gradle cleanup.
  for name in pinch-light-overlay.png pinch-light-grid.png pinch-light.txt \
    pinch-dark-overlay.png pinch-dark-grid.png pinch-dark.txt \
    launcher-light-candidate.png launcher-light.txt launcher-dark-candidate.png launcher-dark.txt; do
    if timeout 5 adb -s "$atlas954_serial" shell test -f "/data/local/tmp/atlas-ui-evidence/diag954-$name"; then
      timeout 10 adb -s "$atlas954_serial" exec-out cat "/data/local/tmp/atlas-ui-evidence/diag954-$name" |
        head -c 16777217 > "$atlas954_stage/$name"
      status=${PIPESTATUS[0]}
      if [[ "$status" != 0 || "$(wc -c < "$atlas954_stage/$name")" -gt 16777216 ]]; then
        echo "frame $name unavailable/truncated (status=$status)" >> "$atlas954_stage/collector-status.txt"
        rm -f "$atlas954_stage/$name"
      fi
    fi
  done
  timeout 5 adb -s "$atlas954_serial" shell rm -f /data/local/tmp/atlas-954-hosted
  destination="app/build/outputs/androidTest-results/launcher-anr-954"
  mkdir -p "$destination" && cp -a "$atlas954_stage/." "$destination/" ||
    echo "::warning::#954 artifact copy failed"
  rm -rf "$atlas954_stage"
  exit "$rc"
}

# A snapshot runs once, in parallel with continued log consumption. Each
# command is independently bounded; nonzero/timeout and truncation are visible.
atlas954_snapshot() {
  local serial="$1" stage="$2" label rc
  shift 2
  label="$1"; shift
  timeout --foreground 6 adb -s "$serial" shell "$@" 2>&1 | head -c 262145 > "$stage/$label.txt"
  rc=${PIPESTATUS[0]}
  printf '%s %s status=%s bytes=%s (124=timeout; 262145=truncated; inspect output for denial)\n' \
    "$(date -u +%FT%T.%3NZ)" "$label" "$rc" "$(wc -c < "$stage/$label.txt")" >> "$stage/snapshot-status.txt"
}
atlas954_capture() {
  local serial="$1" stage="$2"
  atlas954_snapshot "$serial" "$stage" lastanr dumpsys activity lastanr
  atlas954_snapshot "$serial" "$stage" launcher-pid pidof com.google.android.apps.nexuslauncher com.android.launcher3
  atlas954_snapshot "$serial" "$stage" window dumpsys window windows
  atlas954_snapshot "$serial" "$stage" input dumpsys input
  atlas954_snapshot "$serial" "$stage" surface-list dumpsys SurfaceFlinger --list
  atlas954_snapshot "$serial" "$stage" trace-availability ls -l /data/anr
  echo 'Trace: lastanr may include reason/stack; directory listing only, no arbitrary trace file read.' >> "$stage/snapshot-status.txt"
  touch "$stage/snapshot-complete"
}
atlas954_collect() {
  local serial="$1" stage="$2" line count=0 fired=0
  # logcat starts with available buffer history, then follows live events.
  # Filter on-device by tag; cap stored lines and continue observing triggers.
  timeout --foreground 90m adb -s "$serial" logcat -b main -b system -b events -v threadtime \
    am_anr:I ActivityManager:I WindowManager:I InputDispatcher:I InputManager:I \
    TestRunner:I ATLAS_WINDOWS:V ATLAS_954:V ImeTracker:I '*:S' |
    while IFS= read -r line; do
      if (( count < 40000 )); then
        printf '%s\n' "${line:0:1024}" >> "$stage/timeline.txt"
        count=$((count + 1))
      elif (( count == 40000 )); then
        echo 'timeline cap reached; trigger detection continues' >> "$stage/timeline.txt"
        count=$((count + 1))
      fi
      if (( ! fired )) && [[ "$line" =~ (am_anr|ANR|Anr|anr) && "$line" =~ (launcher3|nexuslauncher|Quickstep) ]]; then
        fired=1
        printf '%s first observed matching line: %s\n' "$(date -u +%FT%T.%3NZ)" "${line:0:1024}" > "$stage/trigger.txt"
        atlas954_capture "$serial" "$stage" &
      fi
    done
  local rc=${PIPESTATUS[0]}
  echo "logcat returned $rc"
}
if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  atlas954_collect "$@"
fi
