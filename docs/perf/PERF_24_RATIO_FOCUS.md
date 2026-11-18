# PERF-24 — bounded ratio-grid focal anchor (A2/A12)

Issue: [#308](https://github.com/LPStruiksma/atlas-gallery/issues/308) · Parent: [#119](https://github.com/LPStruiksma/atlas-gallery/issues/119)

## Current contract and result

`focalRatioAnchor` rejects nonfinite focus coordinates before reading media keys; for each valid `RatioVisibleRow`, it uses `layout.rows.getOrNull` to skip invalid row indices and enumerates only `row.mediaCount` cells. For every enumerated slot, it calls `mediaKeyAtIndex` **once**. Null/blank keys are excluded from the candidate list, while each eligible candidate is represented by one `GridVisibleCell`. `focalGridAnchor` then scans the constructed visible list, discards nonpositive geometry, computes closest-point distance and preserves first-candidate tie order. No catalogue-wide key scan is present.

For \(R\) supplied visible-row entries and \(V = \sum_\text{valid rows} \text{row.mediaCount}\), callback count is exactly \(V\), and temporary cell construction and nearest-neighbour work are \(O(V)\). Because the ratio planner caps `maxItemsPerRow` to 1–16, \(V \le 16R\). This is a bound on **supplied** visible rows, not a defence against an arbitrarily long/corrupt visible-row list; duplicate visible-row entries are processed twice by the existing contract. A 250k catalogue stored in the layout does not cause the focus bridge to iterate 250k items. The layout is constructed separately and is not included in per-gesture claims.

## Synthetic JVM regression matrix

`RatioFocusBridgeScaleTest` covers:

- 0 and 1 media, including zero key callback reads for empty layouts.
- Invalid negative / huge visible-row indices and NaN / infinite focus coordinates; no catalogue reads.
- Null and blank media keys, focus at extreme finite coordinates, clamped fractions.
- 250,000 media with a 25-row visible window around item 125,000, exact one-pass source-order index trace, chosen index/key and maximum \(R \times \text{maxItemsPerRow}\) lookups.
- Stable nearest-gap tie choice (first enumerated candidate), followed by a direct second-tile hit.

The existing `RatioFocusBridgeTest` additionally covers ordinary first/second tile selection, revoked key and a 150k catalogue. Nothing here modifies layout geometry, pinch state, media identifiers, or permissions.

## Measurement and limits

This is **regression-only**: the implementation was left unchanged because the observed source already meets the visible-slot traversal contract and no independently measured allocation improvement was established. There is no before/after runtime implementation to benchmark. New JVM allocation counts, p50/p95 latency and Android frame-time impact are **unmeasured**; callback counts are deterministic tests, not byte-allocation measurements. Hosted exact-head JVM CI is required before acceptance. No device-speed claim is made. A plain revert removes this test and document.
