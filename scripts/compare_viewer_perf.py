#!/usr/bin/env python3
"""Offline A/B screening of existing synthetic viewer perf summaries.

No adb, image reads, network, phone probing, or claims of statistical proof.
Requires independently recorded matching environment/fixture protocols.
"""
import argparse
import json
import math
from pathlib import Path
import re
from statistics import median

MAX_JSON = 1024 * 1024
HEX = re.compile(r"^[0-9a-fA-F]{64}$")
ENV_FIELDS = ("device_kind", "device_token", "android_api", "display_resolution",
              "refresh_hz", "fixture_manifest_sha256", "gesture_protocol",
              "cache_protocol", "thermal_protocol", "materialized_images")


def _positive(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value) and value > 0


def read_json(path):
    if path.is_symlink() or not path.is_file() or path.stat().st_size > MAX_JSON:
        raise ValueError("Missing, linked or oversized evidence JSON")
    return json.loads(path.read_text(encoding="utf-8"))


def check_pair(summary, run):
    if not isinstance(summary, dict) or not isinstance(run, dict):
        raise ValueError("Missing benchmark evidence")
    if type(run.get("schema_version")) is not int or run["schema_version"] != 1 or not HEX.fullmatch(str(run.get("artifact_sha256", ""))):
        raise ValueError("Invalid artifact provenance")
    env = run.get("environment")
    if not isinstance(env, dict) or set(env) != set(ENV_FIELDS):
        raise ValueError("Missing or ambiguous experimental environment")
    if env["device_kind"] not in ("isolated_emulator", "physical_test_device"):
        raise ValueError("Unrecognized device category")
    if not all(isinstance(env[k], str) and 1 <= len(env[k]) <= 100
               for k in ("device_token", "display_resolution", "gesture_protocol",
                         "cache_protocol", "thermal_protocol")):
        raise ValueError("Incomplete experiment metadata")
    if not HEX.fullmatch(str(env["fixture_manifest_sha256"])):
        raise ValueError("Invalid synthetic fixture digest")
    if type(env["android_api"]) is not int or not (29 <= env["android_api"] <= 50):
        raise ValueError("Invalid Android API level")
    if type(env["materialized_images"]) is not int or not (1 <= env["materialized_images"] <= 128):
        raise ValueError("Actual image fixture count not established")
    if not _positive(env["refresh_hz"]) or not (30 <= env["refresh_hz"] <= 240):
        raise ValueError("Invalid display mode")
    if summary.get("refresh_hz") != env["refresh_hz"] or not _positive(summary.get("frame_budget_ms")):
        raise ValueError("Refresh-mode mismatch")
    # An invented budget changes over-budget rates without changing the
    # purported device display mode. Summaries round their budget to .001ms.
    if abs(summary["frame_budget_ms"] - round(1000 / env["refresh_hz"], 3)) > .002:
        raise ValueError("Frame-duration budget inconsistent with refresh rate")
    if summary.get("repeatable_evidence") is not True or type(summary.get("minimum_frames_per_trial")) is not int or summary["minimum_frames_per_trial"] != 60:
        raise ValueError("Non-repeatable or unsupported benchmark aggregation")
    if type(summary.get("catalogue_size")) is not int or not (env["materialized_images"] <= summary["catalogue_size"] <= 150000):
        raise ValueError("Synthetic catalogue/image counts inconsistent")
    if not isinstance(summary.get("phases"), dict) or set(summary["phases"]) != {"cold", "warm"}:
        raise ValueError("Cold/warm benchmark phases incomplete")
    for phase in ("cold", "warm"):
        item = summary["phases"][phase]
        if not isinstance(item, dict):
            raise ValueError("Invalid benchmark phase")
        trials = item.get("per_trial")
        if (not isinstance(trials, list) or any(not isinstance(t, dict) for t in trials)
                or len(trials) < 3 or type(item.get("trials")) is not int
                or len(trials) != item["trials"]):
            raise ValueError("Insufficient independent captures")
        if any(type(t.get("frames")) is not int for t in trials):
            raise ValueError("Malformed per-trial frame count")
        if type(item.get("frames")) is not int or item["frames"] != sum(t["frames"] for t in trials):
            raise ValueError("Capture frame counts do not add up")
        for key in ("p50_ms", "p95_ms", "p99_ms", "max_observed_pss_kib"):
            if not _positive(item.get(key)):
                raise ValueError("Invalid aggregated metric")
        if item["p50_ms"] > item["p95_ms"] or item["p95_ms"] > item["p99_ms"]:
            raise ValueError("Impossible frame-duration percentiles")
        if isinstance(item.get("over_budget_pct"), bool) or (
            not _positive(item.get("over_budget_pct"))
            and item.get("over_budget_pct") != 0
        ):
            raise ValueError("Invalid pooled frame-budget rate")
        if not 0 <= item["over_budget_pct"] <= 100:
            raise ValueError("Invalid pooled frame-budget rate")
        for trial in trials:
            rate = trial.get("over_budget_pct")
            if (trial["frames"] < 60
                    or not _positive(trial.get("p95_ms"))
                    or not _positive(trial.get("total_pss_kib"))
                    or isinstance(rate, bool) or not isinstance(rate, (int, float))
                    or not math.isfinite(rate) or not 0 <= rate <= 100):
                raise ValueError("Invalid per-trial measurements")
        # The underlying summarizer reports the maximum *observed snapshot*
        # and a frame-weighted rate. Both must match the per-trial evidence.
        if item["max_observed_pss_kib"] != max(t["total_pss_kib"] for t in trials):
            raise ValueError("Observed PSS snapshot contradicts per-trial PSS")
        weighted = sum(t["frames"] * t["over_budget_pct"] for t in trials) / item["frames"]
        if abs(item["over_budget_pct"] - weighted) > .03:
            raise ValueError("Pooled budget exceedance contradicts frame-weighted trials")
    return env


def compare(base, candidate, base_run, candidate_run):
    e1, e2 = check_pair(base, base_run), check_pair(candidate, candidate_run)
    if e1 != e2 or base["catalogue_size"] != candidate["catalogue_size"]:
        raise ValueError("Noncomparable environment, display, fixture or test protocol")
    if base_run["artifact_sha256"].lower() == candidate_run["artifact_sha256"].lower():
        raise ValueError("Baseline and candidate must identify different APK bytes")
    phases = {}
    flags = []
    for phase in ("cold", "warm"):
        a, b = base["phases"][phase], candidate["phases"][phase]
        a_trial = median(t["p95_ms"] for t in a["per_trial"])
        b_trial = median(t["p95_ms"] for t in b["per_trial"])
        d_p95 = round(100 * (b["p95_ms"] / a["p95_ms"] - 1), 2)
        d_median = round(100 * (b_trial / a_trial - 1), 2)
        a_worst = max(t["p95_ms"] for t in a["per_trial"])
        b_worst = max(t["p95_ms"] for t in b["per_trial"])
        d_worst = round(100 * (b_worst / a_worst - 1), 2)
        d_pss = round(100 * (b["max_observed_pss_kib"] / a["max_observed_pss_kib"] - 1), 2)
        d_budget = round(b["over_budget_pct"] - a["over_budget_pct"], 2)
        if max(d_p95, d_median, d_worst) > 10:
            flags.append(phase + "_frame_p95_screening_regression")
        if d_pss > 15:
            flags.append(phase + "_observed_pss_screening_regression")
        if d_budget > 5:
            flags.append(phase + "_over_budget_screening_regression")
        phases[phase] = {
            "base_frames": a["frames"], "candidate_frames": b["frames"],
            "base_p95_ms": a["p95_ms"], "candidate_p95_ms": b["p95_ms"],
            "p95_pct_change": d_p95, "median_trial_p95_pct_change": d_median,
            "worst_trial_p95_pct_change": d_worst,
            "over_budget_percentage_point_change": d_budget,
            "max_observed_pss_pct_change": d_pss
        }
    return {
        "comparison": "descriptive_screening_only", "environment_asserted_equal": True,
        "both_repeatable_aggregates": True, "phases": phases,
        "screening_flags": flags,
        "empty_flags_prove_no_regression": False,
        "statistical_significance_established": False,
        "real_phone_data_recoverable": False, "release_go": False,
        "note": "Thresholds 10% p95, 15% snapshot PSS, 5pp frame-budget are descriptive screening triggers, not certified device release limits."
    }


def main():
    p = argparse.ArgumentParser(description="Compare offline synthetic viewer summaries with matching provenance manifests")
    p.add_argument("baseline_summary", type=Path)
    p.add_argument("candidate_summary", type=Path)
    p.add_argument("baseline_run", type=Path)
    p.add_argument("candidate_run", type=Path)
    a = p.parse_args()
    try:
        result = compare(read_json(a.baseline_summary), read_json(a.candidate_summary),
                         read_json(a.baseline_run), read_json(a.candidate_run))
    except (ValueError, OSError, KeyError, TypeError, json.JSONDecodeError):
        p.exit(2, "NO COMPARISON: missing, inconsistent or unsupported performance evidence\n")
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
