"""AIE-9 offline-first benchmark runner and evidence accounting.

The default pipeline never contacts providers. Importing a local adapter or judge
requires an explicit CLI option. Dry-run numbers are simulation, not product evidence.
"""
from __future__ import annotations

import argparse
import importlib
import json
import math
import shutil
from collections import defaultdict
from pathlib import Path

from .corpus import generate_manifest, materialize, png_bytes, read_manifest

OUTCOMES = {"completed", "refused", "error"}
METRICS = ("adherence", "identity_similarity", "unchanged_ssim", "unchanged_lpips",
           "detail_score", "artifact_tag")
SCALED = {"adherence", "identity_similarity", "unchanged_ssim", "detail_score"}
# Smaller-is-better: LPIPS and artifact tag are not inverted into a single score.

def _number(value, *, minimum=0, maximum=None):
    return (isinstance(value, (int, float)) and not isinstance(value, bool)
            and math.isfinite(value) and value >= minimum
            and (maximum is None or value <= maximum))

def validate_record(record: dict, case_ids: set[str]) -> None:
    if not isinstance(record, dict) or record.get("case_id") not in case_ids:
        raise ValueError("unknown case_id")
    if not isinstance(record.get("provider"), str) or not record["provider"].strip():
        raise ValueError("missing provider")
    if record.get("outcome") not in OUTCOMES:
        raise ValueError("invalid outcome")
    if type(record.get("degraded", False)) is not bool:
        raise ValueError("degraded must be boolean")
    if record.get("outcome") != "completed" and record.get("degraded"):
        raise ValueError("only completed output may be degraded")
    if record.get("outcome") != "completed" and record.get("accepted"):
        raise ValueError("noncompleted result cannot be accepted")
    if type(record.get("accepted", False)) is not bool:
        raise ValueError("accepted must be boolean")
    for key in ("latency_ms", "cost_usd"):
        if record.get(key) is not None and not _number(record[key]):
            raise ValueError(f"invalid {key}")
    if record.get("unchanged_lpips") is not None and not _number(record["unchanged_lpips"]):
        raise ValueError("invalid unchanged_lpips")
    for key in SCALED:
        if record.get(key) is not None and not _number(record[key], maximum=1):
            raise ValueError(f"invalid {key}")
    if record.get("artifact_tag") not in (None, "yes", "no"):
        raise ValueError("artifact_tag must be human yes/no/unknown")
    for key in ("output_width", "output_height"):
        if record.get(key) is not None and (
            type(record[key]) is not int or record[key] <= 0
        ):
            raise ValueError(f"invalid {key}")
    if record.get("outcome") != "completed":
        if any(record.get(key) is not None for key in METRICS):
            raise ValueError("refusals/errors cannot contribute quality metrics")
    if record.get("outcome") == "completed" and record.get("output_path") is not None:
        if not isinstance(record["output_path"], str):
            raise ValueError("output_path must be a string")

def _metric(records: list[dict], key: str):
    values = [r[key] for r in records if r.get(key) is not None]
    if key == "artifact_tag":
        values = [int(v == "yes") for v in values]
    return {"mean": sum(values) / len(values) if values else None, "n": len(values)}

def scorecards(cases: list[dict], records: list[dict]) -> dict:
    """Refused and degraded attempts never enter the quality metric denominator."""
    case_map = {case["id"]: case for case in cases}
    seen = set()
    grouped = defaultdict(list)
    for r in records:
        validate_record(r, set(case_map))
        key = (r["provider"], r["case_id"])
        if key in seen:
            raise ValueError(f"duplicate provider-case result: {key}")
        seen.add(key)
        grouped[r["provider"]].append(r)
    quality, restrictions = {}, {}
    for provider, group in sorted(grouped.items()):
        clean = [r for r in group if r["outcome"] == "completed" and not r.get("degraded", False)]
        accepted = [r for r in clean if r.get("accepted", False)]
        priced = all(r.get("cost_usd") is not None for r in group)
        total_cost = sum(r["cost_usd"] for r in group) if priced else None
        latencies = [r["latency_ms"] for r in group if r.get("latency_ms") is not None]
        quality[provider] = {
            "attempted": len(group),
            "eligible_completed": len(clean),
            "human_or_mock_accepted": len(accepted),
            "metrics": {key: _metric(clean, key) for key in METRICS},
            "resolution": {
                "width": _metric(clean, "output_width"),
                "height": _metric(clean, "output_height"),
            },
            "latency_ms": {"mean": sum(latencies) / len(latencies) if latencies else None,
                           "n": len(latencies)},
            "cost_usd_total": total_cost,
            "cost_per_accepted_usd": (total_cost / len(accepted)
                                      if total_cost is not None and accepted else None),
            "unpriced_attempts": sum(r.get("cost_usd") is None for r in group),
        }
        categories = defaultdict(list)
        for r in group:
            categories[case_map[r["case_id"]]["category"]].append(r)
        restrictions[provider] = {}
        for category, subset in sorted(categories.items()):
            n = len(subset)
            refused = sum(r["outcome"] == "refused" for r in subset)
            degraded = sum(r["outcome"] == "completed" and r.get("degraded", False)
                           for r in subset)
            errored = sum(r["outcome"] == "error" for r in subset)
            restrictions[provider][category] = {
                "attempts": n, "refusals": refused, "refusal_rate": refused / n,
                "degraded": degraded, "degradation_rate": degraded / n,
                "errors": errored, "error_rate": errored / n,
            }
    return {"quality_scorecard": quality, "restriction_scorecard": restrictions}

def mock_record(case: dict, provider: str, output_path: str) -> dict:
    index = int(case["id"].rsplit("-", 1)[1])
    refusal = (index == 11 if not case["sensitive"] else index % 4 == 0)
    degraded = not refusal and index == 10
    return {
        "case_id": case["id"], "provider": provider,
        "outcome": "refused" if refusal else "completed",
        "degraded": degraded,
        "accepted": not refusal and not degraded,
        "adherence": 0.87 if not refusal else None,
        "identity_similarity": 0.93 if not refusal and case["category"] in
                                {"portrait_identity", "multi_person", "lawful_sensitive"} else None,
        "unchanged_ssim": 0.97 if not refusal else None,
        "unchanged_lpips": 0.03 if not refusal else None,
        "detail_score": 0.80 if not refusal else None,
        "artifact_tag": "no" if not refusal else None,  # mock human annotation
        "output_width": 512 if case["category"] == "high_resolution_detail" and not refusal else
                        96 if not refusal else None,
        "output_height": 384 if case["category"] == "high_resolution_detail" and not refusal else
                         72 if not refusal else None,
        "latency_ms": 20 + index,  # simulated, NOT wall-clock latency
        "cost_usd": 0,
        "output_path": output_path if not refusal else None,
        "evidence": "MOCK_ONLY",
    }

def _import_plugin(spec: str):
    if ":" not in spec:
        raise ValueError("plugin must be module:callable")
    module, name = spec.split(":", 1)
    fn = getattr(importlib.import_module(module), name)
    if not callable(fn):
        raise ValueError("plugin is not callable")
    return fn

def run_mock(cases: list[dict], output_dir: Path) -> list[dict]:
    """Two disjoint fictional providers create local PNGs for the pairwise UI."""
    results = []
    assets = output_dir / "mock-assets"
    assets.mkdir(parents=True, exist_ok=True)
    for case in cases:
        category, index = case["category"], int(case["id"].rsplit("-", 1)[1])
        for provider in ("mock-alpha", "mock-beta"):
            path = assets / f"{provider}-{case['id']}.png"
            rel = path.relative_to(output_dir).as_posix()
            record = mock_record(case, provider, rel)
            if record["outcome"] == "completed":
                path.write_bytes(png_bytes(category, index, edited=True))
            results.append(record)
    return results

def run_adapter(cases: list[dict], output_dir: Path, adapter, judge=None) -> list[dict]:
    """Adapter: (case, source_path, mask_path, output_dir) -> record mapping.

    Judge: (case, record, source_path, mask_path) -> metric mapping.
    Detector/face-embedding, region SSIM/LPIPS and local VLM/CLIP-direction
    are supplied by judge plugins. Missing measurements remain NULL; never fake.
    Adapters must report real latency and complete cost, including refusals.
    """
    results = []
    fixtures = output_dir / "fixtures"
    for case in cases:
        source_path, mask_path = materialize(case, fixtures)
        record = dict(adapter(case, source_path, mask_path, output_dir))
        record["case_id"] = case["id"]
        if record.get("outcome") == "completed" and judge:
            scores = judge(case, record, source_path, mask_path)
            if not isinstance(scores, dict):
                raise ValueError("judge must return metric mapping")
            for key in METRICS:
                if key in scores:
                    record[key] = scores[key]
        results.append(record)
    return results

def load_records(path: Path) -> list[dict]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, list):
        raise ValueError("results JSON must be an array")
    return data

def _fmt(value, digits=3):
    return "N/A" if value is None else f"{value:.{digits}f}"

def markdown_report(report: dict, *, dry_run: bool) -> str:
    lines = ["# ATLAS AI Edit benchmark", "",
             "**SIMULATED — NOT PROVIDER EVIDENCE**" if dry_run else
             "**External results — verify provenance and sampling before model selection**",
             "",
             "Refusals, degraded generations and errors are separate from quality; "
             "only completed, non-degraded outputs enter quality means. "
             "Missing metrics stay N/A. Cost/accepted includes all priced attempts.",
             "", "## Quality scorecard", "",
             "| Provider | Clean / tried | Adherence (n) | Identity (n) | "
             "Outside SSIM (n) | LPIPS ↓ (n) | Detail (n) | Artifact rate (n) | "
             "Resolution W×H | Latency ms | $/accepted |",
             "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|"]
    for provider, row in report["quality_scorecard"].items():
        def col(name):
            x = row["metrics"][name]
            return f"{_fmt(x['mean'])} ({x['n']})"
        resolution = row["resolution"]
        size = (_fmt(resolution["width"]["mean"], 0) + "×" +
                _fmt(resolution["height"]["mean"], 0))
        lines.append("| " + " | ".join([
            provider, f"{row['eligible_completed']}/{row['attempted']}",
            col("adherence"), col("identity_similarity"), col("unchanged_ssim"),
            col("unchanged_lpips"), col("detail_score"), col("artifact_tag"),
            size, _fmt(row["latency_ms"]["mean"], 1),
            _fmt(row["cost_per_accepted_usd"], 4),
        ]) + " |")
    lines.extend(["", "## Restriction scorecard (not quality)", "",
                  "| Provider | Category | Attempts | Refused | Refusal % | Degraded | "
                  "Degradation % | Errors |",
                  "|---|---|---:|---:|---:|---:|---:|---:|"])
    for provider, categories in report["restriction_scorecard"].items():
        for category, data in categories.items():
            lines.append(f"| {provider} | {category} | {data['attempts']} | "
                         f"{data['refusals']} | {100 * data['refusal_rate']:.1f}% | "
                         f"{data['degraded']} | {100 * data['degradation_rate']:.1f}% | "
                         f"{data['errors']} |")
    lines.extend(["", "## Interpretation", "",
                  "- A missing face detector/embedding or region judge produces N/A, "
                  "not a perfect score.",
                  "- Human blinded pairwise preferences and artifact tags are required "
                  "before making a model-selection decision.",
                  "- The sensitive/unconventional set is lawful and wholly synthetic. "
                  "Provider-side refusals are recorded, never quietly normalized.",
                  "- Synthetic mock scores, times and costs have no predictive validity.",
                  ""])
    return "\n".join(lines)

def run(args) -> dict:
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    cases = read_manifest(Path(args.manifest) if args.manifest else None)
    (out / "corpus_manifest.json").write_text(json.dumps(cases, indent=2) + "\n",
                                               encoding="utf-8")
    if args.dry_run:
        if args.adapter or args.results or args.judge:
            raise ValueError("--dry-run cannot combine with external adapters/results/judges")
        records = run_mock(cases, out)
    elif args.results:
        if args.adapter:
            raise ValueError("choose --results or --adapter")
        records = load_records(Path(args.results))
    elif args.adapter:
        if not args.authorize_external:
            raise ValueError("provider adapters require --authorize-external (owner spend gate)")
        records = run_adapter(cases, out, _import_plugin(args.adapter),
                              _import_plugin(args.judge) if args.judge else None)
    else:
        raise ValueError("choose --dry-run, --results, or explicitly approved --adapter")
    report = scorecards(cases, records)
    report["mode"] = "synthetic-mock" if args.dry_run else "external"
    report["corpus_size"] = len(cases)
    (out / "results.json").write_text(json.dumps(records, indent=2) + "\n", encoding="utf-8")
    (out / "scorecards.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    (out / "report.md").write_text(markdown_report(report, dry_run=args.dry_run),
                                    encoding="utf-8")
    from .review import create_pairwise
    create_pairwise(cases, records, out, seed=args.blinding_seed)
    return report

def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dry-run", action="store_true", help="synthetic mock-only, no network")
    parser.add_argument("--manifest", help="optional existing corpus manifest JSON")
    parser.add_argument("--results", help="offline provider result records JSON")
    parser.add_argument("--adapter", help="explicit adapter module:function (may contact provider)")
    parser.add_argument("--judge", help="local judge module:function; unavailable scores stay null")
    parser.add_argument("--authorize-external", action="store_true",
                        help="confirmation only; obtain owner spend authorization separately")
    parser.add_argument("--out", default="ai-edit-bench-out")
    parser.add_argument("--blinding-seed", default="aiedit-v1")
    args = parser.parse_args(argv)
    report = run(args)
    print(f"Benchmark mode={report['mode']}; {report['corpus_size']} cases; "
          f"{len(report['quality_scorecard'])} providers; output={args.out}")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
