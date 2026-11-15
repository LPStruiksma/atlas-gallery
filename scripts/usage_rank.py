#!/usr/bin/env python3
"""Offline aggregate ranking for ATLAS schema v1 usage exports; never echoes input paths."""
import argparse
import json
import math
import re
import sys
from pathlib import Path

PERF = {
    "A1": ("Open app to cached grid", 5, "#122, #128", 500, "p50_ms"),
    "A2": ("Scroll / fling Pictures grid", 5, "#123", 1, "jank_percent"),
    "A3": ("Open photo from grid", 4, "#124", 150, "p50_ms"),
    "A4": ("Swipe between viewer photos", 4, "#138", None, None),
    "A5": ("Background face and visual indexing", 4, "#125, #126", None, None),
    "A6": ("New photo appears after capture", 4, "#139", None, None),
    "A7": ("Return to grid position", 3, "#124", None, None),
    "A8": ("Memory and stability", 3, "#127", None, None),
    "A9": ("Open People tab", 4, "#140", None, None),
    "A10": ("Open person and scroll photos", 4, "#140", None, None),
    "A11": ("Search and suggestions", 4, "#141", None, None),
    "A12": ("Pinch grid zoom", 3, "queued", None, None),
    "A13": ("Viewer zoom tiles", 3, "queued", None, None),
    "A14": ("Play video", 4, "queued", None, None),
    "A15": ("Switch tabs / browse albums", 3, "queued", None, None),
    "A16": ("Select photos", 3, "queued", None, None),
    "A17": ("Bulk actions", 3, "queued", None, None),
    "A18": ("People review actions", 4, "queued", None, None),
    "A19": ("Stories", 3, "queued", None, None),
    "A20": ("Details and favourite", 2, "queued", None, None),
    "A21": ("Photo DNA and trash review", 3, "queued", None, None),
    "A22": ("Sort and Settings", 2, "queued", None, None),
}
DESIGN = {
    "D1": ("Pictures grid", 4, "#131"), "D2": ("Viewer", 4, "#142"),
    "D3": ("Grid / viewer transition", 4, "#23"),
    "D4": ("Bottom bar and tab switching", 3, "#137"),
    "D5": ("Albums", 3, "#143"), "D6": ("People tab", 4, "#132"),
    "D7": ("Search", 4, "#134"), "D8": ("Person page", 3, "#133"),
    "D9": ("Selection mode", 3, "#144"),
    "D10": ("Dialogs and undo", 3, "#144"),
    "D11": ("Empty / loading / error states", 3, "#136"),
    "D12": ("Motion and feedback", 3, "#137"),
    "D13": ("People review", 4, "#145"),
    "D14": ("Details sheet", 3, "queued"), "D15": ("Stories", 3, "queued"),
    "D16": ("Full-screen video", 3, "queued"), "D17": ("Settings", 3, "#135"),
    "D18": ("Trash and Photo DNA", 3, "queued"),
    "D19": ("Sort sheets", 2, "queued"),
}

def finite_number(value, *, minimum=0, maximum=None):
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError("invalid numeric metric")
    if not math.isfinite(value) or value < minimum or (maximum is not None and value > maximum):
        raise ValueError("numeric metric out of range")
    return float(value)

def parse_export(document):
    if not isinstance(document, dict) or set(document) != {"schema_version", "days"}:
        raise ValueError("unsupported export envelope")
    if type(document["schema_version"]) is not int or document["schema_version"] != 1:
        raise ValueError("unsupported schema version")
    days = document["days"]
    if not isinstance(days, list) or len(days) > 90:
        raise ValueError("invalid days")
    seen = set()
    for day in days:
        if not isinstance(day, dict) or not set(day).issubset(
            {"day", "actions", "screens_seconds", "performance", "scanner"}
        ) or "day" not in day:
            raise ValueError("invalid daily record")
        date = day["day"]
        if not isinstance(date, str) or not re.fullmatch(r"\d{4}-\d{2}-\d{2}", date):
            raise ValueError("invalid day")
        from datetime import date as Date
        try:
            if Date.fromisoformat(date).isoformat() != date:
                raise ValueError("invalid day")
        except ValueError:
            raise ValueError("invalid day") from None
        if date in seen:
            raise ValueError("duplicate day")
        seen.add(date)
        for key, permitted in (("actions", PERF), ("screens_seconds", DESIGN)):
            values = day.get(key, {})
            if not isinstance(values, dict) or set(values) - set(permitted):
                raise ValueError("unknown inventory ID")
            for value in values.values():
                finite_number(value, minimum=0)
                if key == "actions" and (type(value) is not int):
                    raise ValueError("action count must be integer")
        perf = day.get("performance", {})
        if not isinstance(perf, dict) or set(perf) - set(PERF):
            raise ValueError("unknown performance ID")
        for metrics in perf.values():
            if not isinstance(metrics, dict) or not metrics or set(metrics) - {"p50_ms", "p90_ms", "jank_percent"}:
                raise ValueError("invalid performance metrics")
            for key, value in metrics.items():
                finite_number(value, maximum=100 if key == "jank_percent" else None)
            if "p50_ms" in metrics and "p90_ms" in metrics and metrics["p50_ms"] > metrics["p90_ms"]:
                raise ValueError("inconsistent timing percentiles")
        scanner = day.get("scanner", {})
        if not isinstance(scanner, dict) or set(scanner) - {"minutes", "photos_per_minute"}:
            raise ValueError("invalid scanner metrics")
        for value in scanner.values():
            finite_number(value)
    return days

def level(value, cutoffs):
    # Fixed ordinal threshold bands, not per-export quantiles;
    # absent samples are not zeros.
    return 1 + sum(value >= cutoff for cutoff in cutoffs)

def leverage(metrics, spec):
    _, estimate, _, target, key = spec
    if target is None or key not in metrics:
        return estimate, False
    ratio = metrics[key] / target
    # Slowdown bands: <0.75, [0.75,1), [1,1.5), [1.5,2), >=2.
    return level(ratio, (0.75, 1.0, 1.5, 2.0)), True

def ranking(days, inventory):
    rows = PERF if inventory == "perf" else DESIGN
    # Active means a day with at least one positive action count or a positive
    # screen dwell; do not include empty logging days in the denominator.
    active = [d for d in days if any(v > 0 for v in d.get("actions", {}).values())
              or any(v > 0 for v in d.get("screens_seconds", {}).values())]
    result = []
    for id_, spec in rows.items():
        name, prior_l, unit = spec[:3]
        key = "actions" if inventory == "perf" else "screens_seconds"
        observed = any(id_ in d.get(key, {}) for d in active)
        amount = sum(d.get(key, {}).get(id_, 0) for d in active)
        rate = amount / len(active) if active else None
        # Frequency bands fixed for daily action counts, and daily screen dwell
        # seconds for design; each is a quantile-like ordinal calibration.
        cuts = (0.25, 1, 5, 20) if inventory == "perf" else (1, 10, 60, 300)
        frequency = level(rate, cuts) if observed and rate is not None else None
        if inventory == "perf":
            measured = [d["performance"][id_] for d in active
                        if d.get("actions", {}).get(id_, 0) > 0
                        and id_ in d.get("performance", {})]
            # A p50 from each day lacks its sample count; do not pretend
            # its arithmetic mean is a pooled p50. Use median daily p50.
            from statistics import median
            values = {k: median([m[k] for m in measured if k in m])
                      for k in ("p50_ms", "p90_ms", "jank_percent")
                      if any(k in m for m in measured)}
            l, has_l = leverage(values, spec)
        else:
            l, has_l = prior_l, False  # dwell is not a design gap measure.
        result.append((id_, name, rate if observed else None, frequency, l, has_l,
                       (frequency * l if frequency is not None else None), unit))
    return sorted(result, key=lambda r: (r[6] is None, -(r[6] or 0), r[0]))

def markdown(days, inventory):
    out = [
        f"### {'Performance' if inventory == 'perf' else 'Design'} usage ranking (schema v1)",
        "",
        "| ID | Action / aspect | Per active day | F measured | L | Score | Unit |",
        "|---|---|---:|---:|---:|---:|---|",
    ]
    for id_, name, rate, f, l, measured, score, unit in ranking(days, inventory):
        out.append(f"| {id_} | {name} | {format(rate, '.2f') if rate is not None else '—'} | {f if f is not None else '—'} | {l}{' measured' if measured else ' estimated'} | {score if score is not None else '—'} | {unit} |")
    out += ["", "— = not observed, not zero. L estimated is a prior, not a measurement.",
            "F bands (actions/day): <0.25=1, <1=2, <5=3, <20=4, >=20=5; "
            "design uses screen seconds/day: <1=1, <10=2, <60=3, <300=4, >=300=5.",
            "Timing L is median of available daily p50 / target: "
            "<0.75=1, <1=2, <1.5=3, <2=4, >=2=5. "
            "Calibrated targets: A1 (500 ms p50), A2 (1% jank) and "
            "A3 (150 ms p50). Timing counts only on active days with "
            "positive recorded actions for the ID; other L stays estimated. "
            "Missing hooks are not evidence of zero use."]
    return "\n".join(out)

def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("export", type=Path)
    parser.add_argument("--inventory", choices=("perf", "design"), default="perf")
    args = parser.parse_args(argv)
    try:
        # Never include input contents or filename in error output.
        days = parse_export(json.loads(args.export.read_text(encoding="utf-8")))
        print(markdown(days, args.inventory))
    except (OSError, UnicodeError, json.JSONDecodeError, ValueError) as exc:
        print(f"Usage ranking failed: {type(exc).__name__}", file=sys.stderr)
        return 2
    return 0

if __name__ == "__main__":
    sys.exit(main())
