# USAGE-1 offline ranking

Run `python3 scripts/usage_rank.py /private/path/atlas-usage-YYYYMMDD.json --inventory perf` or `--inventory design`. The only committed input is `scripts/testdata/usage_synthetic.json`; **never commit real exports, output reports, private paths, logs or CI attachments**. Export data is read locally and only Markdown aggregates go to stdout. Input errors are generic and do not echo paths or values.

Schema v1 follows USAGE-0's provisional `docs/perf/USAGE_EXPORT.md`: `{"schema_version":1,"days":[{"day":"YYYY-MM-DD","actions":{"A1":2},"screens_seconds":{"D1":12},"performance":{"A1":{"p50_ms":450,"p90_ms":600,"jank_percent":1.2}},"scanner":{"minutes":2,"photos_per_minute":20}}]}`. Rows must have day-level dates only, known closed enum IDs A1–A22 and D1–D19, and finite nonnegative metrics. At most 90 unique days. No other fields accepted.

**Measured F** is total action count per active day (PERF) or total dwell seconds per active day (DESIGN). An active day has a positive recorded action count or screen dwell. Fixed reference quantile cutoffs for action/day are 0.25, 1, 5 and 20; for dwell seconds/day are 1, 10, 60 and 300. Values below the first cutoff map to 1; crossing each cutoff increases the ordinal by one, up to 5. Fixed boundaries avoid rank churn when another row is added. Missing ID data displays `—`, **not zero**. Explicit zero is usable only when the logger provides that ID for an active day. Mixed coverage may bias rates if action hooks were absent.

**Measured L** is produced only where a PERF target and matching timing exist: presently A1 (p50 ≤500 ms) and A3 (p50 ≤150 ms). Daily p50 summaries lack sample weights, so median daily p50 is used as an approximation, *not* claimed to be the pooled p50. Ratios measured p50/target below 0.75, 1, 1.5, 2 correspond to leverage 1, 2, 3, 4, and ≥2 maps to 5 (the implementation uses inclusive thresholds; boundary 0.75→2, 1→3, 1.5→4, 2→5). Other rows keep the prior L from the parent inventories, visibly marked `estimated`. DESIGN L remains estimated until DESIGN-0 supplies measured checklist gaps; dwell cannot measure visual mismatch. Score = F × L, omitted when F is absent. Sort by descending score, then ID.

The inventory names and initial L/units are snapshots of #119/#120; update them when parent inventory changes. These rankings are **not** comprehensive until USAGE-0 has production hooks for all covered actions; a missing row is not evidence of no usage.

Regression: `python3 -m unittest scripts/test_usage_rank.py` (synthetic data only).
