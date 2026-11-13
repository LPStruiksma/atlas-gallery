#!/usr/bin/env python3
"""Invented A/B summaries; no Android, pictures, identifiers or real metrics."""
from copy import deepcopy
from io import StringIO
import contextlib
import json
import math
from pathlib import Path
import sys
import tempfile

sys.path.insert(0, str(Path(__file__).parent))
from compare_viewer_perf import compare, main

A = "a" * 64
B = "b" * 64
FIXTURE = "c" * 64


def make_summary(p95=12, pss=200000, over=10):
    def phase():
        return {
            "trials": 3, "frames": 240, "p50_ms": 6, "p95_ms": p95,
            "p99_ms": p95 * 1.1, "over_budget_pct": over,
            "max_observed_pss_kib": pss,
            "per_trial": [
                {"frames": 80, "p95_ms": p95, "over_budget_pct": over,
                 "total_pss_kib": pss} for _ in range(3)]
        }
    return {
        "refresh_hz": 120, "frame_budget_ms": 8.333,
        "catalogue_size": 64, "repeatable_evidence": True,
        "minimum_frames_per_trial": 60, "phases": {"cold": phase(), "warm": phase()}
    }


def make_run(artifact):
    return {"schema_version": 1, "artifact_sha256": artifact,
            "environment": {
                "device_kind": "isolated_emulator",
                "device_token": "anonymous-emulator-instance",
                "android_api": 35, "display_resolution": "1080x2400",
                "refresh_hz": 120, "fixture_manifest_sha256": FIXTURE,
                "gesture_protocol": "filmstrip-64-v1",
                "cache_protocol": "cold-then-warm-v1",
                "thermal_protocol": "stable-emulator",
                "materialized_images": 64
            }}


def reject(action):
    try:
        action()
    except ValueError:
        return
    raise AssertionError("Incomparable performance evidence was accepted")


base, better = make_summary(), make_summary(p95=10, pss=190000, over=7)
x, y = make_run(A), make_run(B)
result = compare(base, better, x, y)
assert result["environment_asserted_equal"]
assert result["screening_flags"] == []
assert result["release_go"] is False
assert result["empty_flags_prove_no_regression"] is False
assert result["statistical_significance_established"] is False
assert result["phases"]["warm"]["p95_pct_change"] < 0
assert FIXTURE not in str(result)
assert "anonymous-emulator-instance" not in str(result)

worse = make_summary(p95=18, pss=245000, over=20)
flags = compare(base, worse, x, y)["screening_flags"]
assert len(flags) == 6 and "warm_observed_pss_screening_regression" in flags

# Pooled frame time and median can hide one catastrophic trial.
outlier = make_summary(p95=11, pss=190000, over=9)
outlier["phases"]["warm"]["per_trial"][2]["p95_ms"] = 35
outlier_flags = compare(base, outlier, x, y)["screening_flags"]
assert "warm_frame_p95_screening_regression" in outlier_flags
assert "cold_frame_p95_screening_regression" not in outlier_flags


reject(lambda: compare(base, better, x, x))
m = make_run(B)
m["environment"]["fixture_manifest_sha256"] = "d" * 64
reject(lambda: compare(base, better, x, m))
m = make_run(B)
m["environment"]["refresh_hz"] = 60
reject(lambda: compare(base, better, x, m))
m = make_run(B)
m["environment"].pop("thermal_protocol")
reject(lambda: compare(base, better, x, m))
m = make_run(B)
m["environment"]["materialized_images"] = 100
reject(lambda: compare(base, better, x, m))
m = make_run(B)
m["artifact_sha256"] = "invalid"
reject(lambda: compare(base, better, x, m))
s = deepcopy(better)
s["repeatable_evidence"] = False
reject(lambda: compare(base, s, x, y))
s = deepcopy(better)
s["phases"]["warm"]["per_trial"] = s["phases"]["warm"]["per_trial"][:2]
reject(lambda: compare(base, s, x, y))
s = deepcopy(better)
s["phases"]["warm"]["per_trial"][0]["frames"] = 4
reject(lambda: compare(base, s, x, y))
s = deepcopy(better)
s["phases"]["warm"]["p95_ms"] = math.nan
reject(lambda: compare(base, s, x, y))
s = deepcopy(better)
s["catalogue_size"] = 63
reject(lambda: compare(base, s, x, y))

# Impossible aggregate metadata must not turn invented timing/thermal claims
# into an apparently comparable A/B report.
s = deepcopy(better)
s["frame_budget_ms"] = 16.667
reject(lambda: compare(base, s, x, y))
s = deepcopy(better)
s["phases"]["warm"]["p50_ms"] = 999
reject(lambda: compare(base, s, x, y))
s = deepcopy(better)
s["phases"]["warm"]["p99_ms"] = 1
reject(lambda: compare(base, s, x, y))
s = deepcopy(better)
s["phases"]["warm"]["max_observed_pss_kib"] += 1
reject(lambda: compare(base, s, x, y))
s = deepcopy(better)
s["phases"]["warm"]["over_budget_pct"] = 13
reject(lambda: compare(base, s, x, y))
s = deepcopy(better)
s["phases"]["warm"]["over_budget_pct"] = False
reject(lambda: compare(base, s, x, y))
s = deepcopy(better)
s["repeatable_evidence"] = "truthy"
reject(lambda: compare(base, s, x, y))
s = deepcopy(better)
s["catalogue_size"] = True
reject(lambda: compare(base, s, x, y))
r = make_run(B)
r["environment"]["android_api"] = True
reject(lambda: compare(base, better, x, r))
r = make_run(B)
r["environment"]["materialized_images"] = True
reject(lambda: compare(base, better, x, r))
r = make_run(B)
r["schema_version"] = True
reject(lambda: compare(base, better, x, r))
s = deepcopy(better)
s["phases"]["warm"]["per_trial"][0]["over_budget_pct"] = math.nan
reject(lambda: compare(base, s, x, y))


with tempfile.TemporaryDirectory() as tmp:
    directory = Path(tmp)
    paths = [directory/name for name in ("baseline.json", "candidate.json",
                                         "baseline-run.json", "candidate-run.json")]
    for path, data in zip(paths, (base, better, x, y)):
        path.write_text(json.dumps(data))
    original = sys.argv
    try:
        sys.argv = ["ab", *map(str, paths)]
        with contextlib.redirect_stdout(StringIO()) as output:
            assert main() == 0
        assert '"release_go": false' in output.getvalue()
        assert "anonymous-emulator-instance" not in output.getvalue()
        link = directory/"linked.json"
        link.symlink_to(paths[0])
        sys.argv = ["ab", str(link), *map(str, paths[1:])]
        with contextlib.redirect_stderr(StringIO()):
            try:
                main()
            except SystemExit as error:
                assert error.code == 2
            else:
                raise AssertionError("Linked report accepted")
    finally:
        sys.argv = original
print("PASS: provenance-matched cold/warm A/B screening, adversarial false-positive prevention and no release GO")
