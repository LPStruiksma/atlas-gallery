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
  local rc="$1" name destination status attempt
  trap - EXIT
  set +e
  atlas954_phase "runner exit $rc"
  if [[ -n "${atlas954_pid:-}" ]]; then
    kill -TERM -- "-$atlas954_pid" 2>/dev/null || kill -TERM "$atlas954_pid" 2>/dev/null
    for attempt in {1..20}; do
      if ! kill -0 -- "-$atlas954_pid" 2>/dev/null && ! kill -0 "$atlas954_pid" 2>/dev/null; then break; fi
      sleep .1
    done
    kill -KILL -- "-$atlas954_pid" 2>/dev/null || kill -KILL "$atlas954_pid" 2>/dev/null
    wait "$atlas954_pid"
    status=$?
    printf 'collector wait status=%s (143 expected on teardown)\n' "$status" >> "$atlas954_stage/collector-status.txt"
  fi
  if [[ -f "$atlas954_stage/trigger.txt" && ! -f "$atlas954_stage/snapshot-complete" ]]; then
    echo 'snapshot incomplete at teardown' >> "$atlas954_stage/collector-status.txt"
  elif [[ ! -f "$atlas954_stage/trigger.txt" ]]; then
    echo 'no attributed launcher ANR observed; generic dialog records, if present, remain unattributed; not proof of absence' >> "$atlas954_stage/collector-status.txt"
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
  if timeout --foreground 6 adb -s "$serial" shell "$@" 2>&1 | head -c 262145 > "$stage/$label.txt"; then
    rc=${PIPESTATUS[0]}
  else
    rc=${PIPESTATUS[0]}
  fi
  printf '%s %s status=%s bytes=%s (124=timeout; 262145=truncated; inspect output for denial)\n' \
    "$(date -u +%FT%T.%3NZ)" "$label" "$rc" "$(wc -c < "$stage/$label.txt")" >> "$stage/snapshot-status.txt"
}

# API 35 exposes focus in plain dumpsys window, not dumpsys window windows.
# Extract focus independently of the raw prefix cap, in the same command.
atlas954_window() {
  local serial="$1" stage="$2" rc awk_rc
  local -a statuses
  : > "$stage/window.txt"
  : > "$stage/window-focus.txt"
  if timeout --foreground 6 adb -s "$serial" shell dumpsys window 2>&1 |
    LC_ALL=C awk -v raw="$stage/window.txt" -v focus="$stage/window-focus.txt" '
      {
        if (bytes < 262145) {
          s=$0 "\n"; n=262145-bytes
          if (length(s)>n) s=substr(s,1,n)
          printf "%s",s >> raw; bytes+=length(s)
        }
        if ($0 ~ /^[[:space:]]*(mCurrentFocus|mFocusedApp)=/) {
          if (count<64) print substr($0,1,1024) >> focus
          count++
        }
      }
      END { if (count>64) print "focus line cap reached" >> focus }
    '; then
    statuses=("${PIPESTATUS[@]}")
  else
    statuses=("${PIPESTATUS[@]}")
  fi
  rc=${statuses[0]}; awk_rc=${statuses[1]}
  local field
  for field in mCurrentFocus mFocusedApp; do
    if ! grep -Eq "^[[:space:]]*${field}=" "$stage/window-focus.txt"; then
      printf '%s unavailable (no field captured; command status=%s, parser status=%s)\n' "$field" "$rc" "$awk_rc" >> "$stage/window-focus.txt"
    fi
  done
  printf '%s window status=%s parser=%s bytes=%s (124=timeout; 262145=raw prefix capped; null is explicit no focus; missing is unavailable)\n' \
    "$(date -u +%FT%T.%3NZ)" "$rc" "$awk_rc" "$(wc -c < "$stage/window.txt")" >> "$stage/snapshot-status.txt"
}

atlas954_capture() {
  local serial="$1" stage="$2"
  atlas954_window "$serial" "$stage"
  atlas954_snapshot "$serial" "$stage" input dumpsys input
  atlas954_snapshot "$serial" "$stage" lastanr dumpsys activity lastanr
  atlas954_snapshot "$serial" "$stage" launcher-pid pidof com.google.android.apps.nexuslauncher com.android.launcher3 com.android.quickstep
  atlas954_snapshot "$serial" "$stage" surface-list dumpsys SurfaceFlinger --list
  atlas954_snapshot "$serial" "$stage" trace-availability ls -l /data/anr
  echo 'Trace: lastanr may include reason/stack; directory listing only, no arbitrary trace file read.' >> "$stage/snapshot-status.txt"
  touch "$stage/snapshot-complete"
}

# Classify real event structure, not co-occurrence of launcher and ANR words.
# A generic ATLAS_WINDOWS dialog names the system root, not the affected app.
atlas954_classify() {
  local line="${1,,}" pkg='(com\.android\.launcher3|com\.google\.android\.apps\.nexuslauncher|com\.android\.quickstep)(:[a-z0-9_.-]+)?'
  local event="am_anr[[:space:]]*:[[:space:]]*\[[[:space:]]*[0-9]+,[[:space:]]*[0-9]+,[[:space:]]*${pkg}[[:space:]]*,"
  local wm="application not responding:[[:space:]]*${pkg}([^a-z0-9_.:-]|$)"
  local am="anr in[[:space:]]+${pkg}([^a-z0-9_.:-]|$)"
  if [[ "$line" =~ [[:space:]][iwevd][[:space:]]+$event ]]; then
    kind=launcher-event
  elif [[ "$line" =~ [[:space:]][iwevd][[:space:]]+activitymanager[[:space:]]*: && "$line" =~ ^.*activitymanager[[:space:]]*:[[:space:]]*$am ]]; then
    kind=launcher-event
  elif [[ "$line" =~ [[:space:]][iwevd][[:space:]]+(windowmanager|inputmanager|inputdispatcher|atlas_windows)[[:space:]]*: ]] &&
       [[ "$line" =~ $wm || "$line" =~ (^|[^a-z0-9_])quickstep[[:space:]]+isn.t[[:space:]]+responding([^a-z0-9_]|$) ]]; then
    kind=launcher-dialog-window
  elif [[ "$line" =~ [[:space:]][iwevd][[:space:]]+atlas_windows[[:space:]]*:[[:space:]]*system[[:space:]]+anr[[:space:]]+dialog[[:space:]]+on[[:space:]]+screen ]]; then
    kind=unattributed-dialog
  else
    kind=
  fi
}

atlas954_collect() {
  local serial="$1" stage="$2" line count=0 fired=0 unattributed=0 kind
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
      if (( ! fired )); then
        atlas954_classify "$line"
        case "$kind" in
          unattributed-dialog)
            # Keep the earliest generic dialog separately, even after timeline
            # truncation. Correlate its device timestamp with subsequent explicit
            # window/event and snapshot evidence; never consume the launcher trigger.
            if (( unattributed < 32 )); then
              printf '%s unattributed system dialog: %s\n' "$(date -u +%FT%T.%3NZ)" "${line:0:1024}" >> "$stage/unattributed-dialogs.txt"
              unattributed=$((unattributed + 1))
            fi
            ;;
          launcher-event|launcher-dialog-window)
            fired=1
            printf '%s first observed %s: %s\n' "$(date -u +%FT%T.%3NZ)" "$kind" "${line:0:1024}" > "$stage/trigger.txt"
            atlas954_capture "$serial" "$stage" &
            ;;
        esac
      fi
    done
  local rc=${PIPESTATUS[0]}
  echo "logcat returned $rc"
}
if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  atlas954_collect "$@"
fi
