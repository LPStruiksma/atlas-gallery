#!/usr/bin/env bash
# Temporary #603 synthetic JVM diagnostic. Never merge with the opt-in marker.
set -euo pipefail
shard="$1" count="$2"
fail() { echo "::error::#603 $*" >&2; exit 1; }
[[ "${GITHUB_ACTIONS:-}" == true && "${RUNNER_ENVIRONMENT:-}" == github-hosted &&
   "${ATLAS_SYNTHETIC_MISMATCH:-0}" == 0 &&
   -f scripts/ci-opt-in/cached-first-frame-stage &&
   "$count" == 2 && ( "$shard" == 0 || "$shard" == 1 ) ]] ||
  fail "requires opted-in standard hosted two-shard CI"
[[ "$(timeout 20 adb devices | awk 'NR>1 && NF {print $1, $2}')" =~ ^emulator-[0-9]+[[:space:]]device$ &&
   "$(timeout 20 adb shell getprop ro.kernel.qemu | tr -d '\r')" == 1 &&
   "$(timeout 20 adb shell getprop ro.build.version.sdk | tr -d '\r')" == 35 ]] ||
  fail "requires exactly one API35 emulator"
head="$(git rev-parse HEAD)"
[[ "$head" == "${GITHUB_SHA:-}" ]] || fail "checkout differs from dispatch SHA"
echo "#603 DIAGNOSTIC ONLY head=$head shard=$shard run=$GITHUB_SERVER_URL/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID job=$GITHUB_JOB"
if [[ "$shard" == 1 ]]; then
  echo "SKIP: #603 measurement is shard 0 only; full emulator suite NOT RUN"
  exit 0
fi
out="$PWD/app/build/outputs/androidTest-results/cached-first-frame-stage"
mkdir -p "$out"
xml=app/build/test-results/testDebugUnitTest/TEST-nl.atlas.gallery.CachedFirstFrameStageProbeTest.xml
# Remove stale result; only the newly successful forced invocation is evidence.
rm -f "$xml"
java -version 2>&1 | tee "$out/java-version.txt"
echo "COMMAND: gradle :app:testDebugUnitTest --tests '*CachedFirstFrameStageProbeTest' --rerun-tasks --info --no-daemon --no-build-cache"
if ! gradle :app:testDebugUnitTest --tests '*CachedFirstFrameStageProbeTest' \
    --rerun-tasks --info --no-daemon --no-build-cache > "$out/gradle.txt" 2>&1; then
  tail -100 "$out/gradle.txt"
  fail "targeted JVM test failed"
fi
[[ -f "$xml" ]] || fail "targeted JVM test XML missing"
cp "$xml" "$out/stage.xml"
python3 - "$out/stage.xml" "$out/stage-table.txt" <<'PY'
import math
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

def require(condition, message):
    if not condition:
        raise ValueError(message)

root = ET.parse(sys.argv[1]).getroot()
require(root.tag == "testsuite" and root.get("tests") == "1", "expected one test")
require(all(root.get(k) == "0" for k in ("failures", "errors", "skipped")),
        "JVM test did not pass")
cases = root.findall("testcase")
require(len(cases) == 1 and cases[0].get("name") == "stageTable" and
        cases[0].get("classname") == "nl.atlas.gallery.CachedFirstFrameStageProbeTest",
        "wrong test case")
lines = (root.findtext("system-out") or "").splitlines()
heading = "### PERF-A1B synthetic JVM stage table (#603)"
require(lines.count(heading) == 1, "missing or duplicate table")
jdk = [s for s in lines if s.startswith("JDK: ")]
require(len(jdk) == 1, "missing or duplicate measured JVM version")
header = "| Rows | Mode | Sample | Decode ms | Sort ms | Timeline ms | Decode + sort ms |"
separator = "|---:|---|---:|---:|---:|---:|---:|"
require(lines.count(header) == 1 and lines.count(separator) == 1, "bad table header")
rows = [s for s in lines if s.startswith("|") and s not in (header, separator)]
expected = {(str(size), mode, str(sample))
            for size in (150000, 250000)
            for mode, count in (("cold", 3), ("warm", 5))
            for sample in range(1, count + 1)}
seen = set()
require(len(rows) == 16, f"expected 16 rows, got {len(rows)}")
for row in rows:
    require(row.endswith("|"), f"malformed row: {row}")
    fields = [s.strip() for s in row[1:-1].split("|")]
    require(len(fields) == 7, f"wrong column count: {row}")
    key = tuple(fields[:3])
    require(key in expected and key not in seen, f"unexpected or duplicate sample: {key}")
    seen.add(key)
    require(all(re.fullmatch(r"[0-9]+\.[0-9]{3}", s) for s in fields[3:]),
            f"malformed stage timing: {row}")
    values = [float(s) for s in fields[3:]]
    require(all(math.isfinite(v) and v >= 0 for v in values), f"invalid timing: {row}")
    require(abs(values[0] + values[1] - values[3]) <= 0.002000001,
            f"decode + sort inconsistent: {row}")
require(seen == expected, f"missing samples: {expected - seen}")
note = [s for s in lines if s.startswith("Decode includes file open/read;")]
require(len(note) == 1, "missing fixture methodology")
table = "\n".join([heading, jdk[0], note[0], header, separator, *rows]) + "\n"
Path(sys.argv[2]).write_text(table, encoding="utf-8")
print(table, end="")
PY
echo "PASS: #603 16 validated JVM rows; full emulator suite NOT RUN"
