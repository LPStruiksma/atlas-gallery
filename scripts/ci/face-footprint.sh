#!/usr/bin/env bash
# Temporary #599 synthetic diagnostic. Never merge with the opt-in marker.
set -euo pipefail
shard="$1" count="$2" new="$3" test_apk="$4" runner="$5"
fail() { echo "::error::#599 $*" >&2; return 1; }
[[ "${GITHUB_ACTIONS:-}" == true && "${RUNNER_ENVIRONMENT:-}" == github-hosted &&
   "${ATLAS_SYNTHETIC_MISMATCH:-0}" != 1 && -f scripts/ci-opt-in/face-footprint &&
   "$count" == 2 && ( "$shard" == 0 || "$shard" == 1 ) ]] || {
  fail "requires opted-in standard hosted two-shard CI"; exit 1;
}
[[ "$(timeout 20 adb devices | awk 'NR>1 && NF {print $1, $2}')" =~ ^emulator-[0-9]+[[:space:]]device$ &&
   "$(timeout 20 adb shell getprop ro.kernel.qemu | tr -d '\r')" == 1 &&
   "$(timeout 20 adb shell getprop ro.build.version.sdk | tr -d '\r')" == 35 ]] || {
  fail "requires exactly one API35 emulator"; exit 1;
}
seed=25000
[[ "$shard" == 0 ]] || seed=100000
out="$PWD/app/build/outputs/androidTest-results/face-footprint"
mkdir -p "$out"
echo "#599 DIAGNOSTIC ONLY head=$(git rev-parse HEAD) shard=$shard seed=$seed"
# Use the parent's mirror and retry wrapper; test execution itself never retries.
gradle :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --stacktrace
timeout 90 adb install -r "$new"
timeout 90 adb install -r "$test_apk"
timeout 20 adb logcat -G 16M

jvm_probe() {
  local xml
  # --rerun is the Gradle 8.13 task-level option, not a rebuild of every dependency.
  if ! ATLAS_MEM_PROBE=1 ATLAS_MEM_PROBE_FACES="$seed" gradle \
      :app:testDebugUnitTest --tests '*FaceStateFootprintProbe' --rerun \
      --no-build-cache --no-daemon --stacktrace > "$out/jvm-$seed.txt" 2>&1; then
    tail -100 "$out/jvm-$seed.txt"
    fail "JVM seed=$seed did not complete"; return 1
  fi
  xml=app/build/test-results/testDebugUnitTest/TEST-nl.atlas.gallery.featurepersistence.FaceStateFootprintProbe.xml
  cp "$xml" "$out/jvm-$seed.xml" || return 1
  python3 - "$out/jvm-$seed.xml" "$seed" <<'PY'
import sys
import xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
seed = sys.argv[2]
assert root.attrib.get("tests") == "1"
assert all(root.attrib.get(k) == "0" for k in ("failures", "errors", "skipped"))
rows = [s for s in (root.findtext("system-out") or "").splitlines() if s.startswith("PROBE ")]
assert len(rows) == 6 and rows[0].startswith(f"PROBE embeddings {seed}:")
print(f"JVM seed={seed} test_seconds={root.attrib['time']}")
print("\n".join(rows))
PY
}

android_probe() {
  local pending="$1" capture="$out/android-$seed-$1.txt" rows="$out/android-$seed-$1-scale.txt" rc=0
  # Only the already-validated synthetic target; each invocation starts afresh.
  timeout 20 adb shell am force-stop nl.atlas.gallery || return 1
  timeout 20 adb logcat -c || return 1
  timeout --kill-after=10s 7m adb shell am instrument -w -r \
    -e class 'nl.atlas.gallery.FaceIndexStoreScaleAndroidTest#faceReviewPublicationFootprint' \
    -e atlasFaceFootprint true -e atlasFaceSeed "$seed" -e atlasFacePending "$pending" \
    "$runner" > "$capture" 2>&1 || rc=$?
  # Record only the synthetic test outcome and aggregate scale fields.
  grep -E '^(OK \(1 test\)|FAILURES!!!|Tests run:|INSTRUMENTATION_CODE:|INSTRUMENTATION_FAILED:|INSTRUMENTATION_STATUS_CODE:|Time:)' "$capture" || true
  timeout 30 adb logcat -d -v raw -s ATLAS_SCALE:I |
    awk '/(^| )phase=face_review_footprint( |$)/' > "$rows" || return 1
  cat "$rows"
  if [[ "$rc" != 0 ]] || ! grep -Eq '^OK \(1 test\)\r?$' "$capture" ||
    grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|INSTRUMENTATION_STATUS_CODE: -[123]' "$capture"; then
    fail "Android seed=$seed pending=$pending instrumentation exit=$rc"; return 1
  fi
  python3 - "$rows" "$seed" "$pending" <<'PY'
import math
import sys
rows = open(sys.argv[1]).read().splitlines()
assert len(rows) == 1, f"expected one footprint row, got {len(rows)}"
pairs = [token.split("=", 1) for token in rows[0].split()]
fields = dict(pairs)
assert len(fields) == len(pairs)
assert fields["phase"] == "face_review_footprint"
assert fields["seed_faces"] == sys.argv[2] and fields["pending_faces"] == sys.argv[3]
for k in ("review_wall_ms", "publication_wall_ms", "before_review_bytes",
          "after_review_bytes", "review_retained_estimate_bytes",
          "publication_sampled_peak_bytes", "publication_peak_increment_bytes",
          "after_publication_gc_bytes", "publication_rows_read", "publication_rows_written"):
    assert math.isfinite(float(fields[k])), k
print(f"Android validated seed={sys.argv[2]} pending={sys.argv[3]}")
PY
}

failed=0
jvm_probe || { echo "::error::JVM seed=$seed NOT MEASURED"; failed=1; }
for pending in 1 1000 16384; do
  android_probe "$pending" || { echo "::error::Android seed=$seed pending=$pending NOT MEASURED"; failed=1; }
done
[[ "$failed" == 0 ]] || exit 1
echo "PASS: #599 diagnostic shard only; full emulator suite NOT RUN"
